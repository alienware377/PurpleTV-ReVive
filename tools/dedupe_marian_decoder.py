#!/usr/bin/env python3
"""Drops the second copy of the vocabulary from a quantized merged Marian decoder.

    python tools/dedupe_marian_decoder.py decoder_model_merged_quantized.onnx [out.onnx]

The export keeps the shared embedding twice: [vocab, d] for looking up the input token and a
transposed [d, vocab] copy for the output layer, about 30 MB each once quantized. This makes the
token lookup read the output layer's copy instead: Gather along its vocabulary axis, then
dequantize with that column's own scale and zero point (one per vocabulary entry). The [vocab, d]
copy is then unused and removed. Everything else is left as it is.
"""
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper


def subgraphs(g):
    yield g
    for n in g.node:
        for a in n.attribute:
            if a.type == onnx.AttributeProto.GRAPH:
                yield from subgraphs(a.g)
            elif a.type == onnx.AttributeProto.GRAPHS:
                for sg in a.graphs:
                    yield from subgraphs(sg)


def dedupe(path, out=None):
    m = onnx.load(path)
    init = {t.name: t for t in m.graph.initializer}
    graphs = list(subgraphs(m.graph))
    nodes = [(g, n) for g in graphs for n in g.node]
    # The output layer: MatMulInteger whose weight is a quantized [d, vocab] initializer.
    head = [n for _, n in nodes if n.op_type == "MatMulInteger" and n.input[1] in init and len(init[n.input[1]].dims) == 2
            and init[n.input[1]].dims[1] > 10000]
    if len(set(n.input[1] for n in head)) != 1:
        print("no single output layer found, left as it is")
        return False
    wq = head[0].input[1]
    wzp = head[0].input[3]
    vocab = init[wq].dims[1]
    # Its scale: the initializer named like the weight, ending in _scale.
    wscale = wq.replace("_quantized", "_scale")
    if wscale not in init or wzp not in init:
        print("output layer scale or zero point not found, left as it is")
        return False
    # The token lookup: Gather on a quantized [vocab, d] initializer, then DequantizeLinear.
    lookups = [(g, n) for g, n in nodes if n.op_type == "Gather" and n.input[0] in init
               and list(init[n.input[0]].dims)[:1] == [vocab] and len(init[n.input[0]].dims) == 2]
    if not lookups:
        print("no token lookup on a [vocab, d] table, left as it is")
        return False
    table = lookups[0][1].input[0]
    a = numpy_helper.to_array(init[table]).astype(np.int32)
    b = numpy_helper.to_array(init[wq]).astype(np.int32).T
    print("tables: lookup %s %s, output layer %s %s" % (table, a.shape, wq, b.shape))
    k = 0
    for g, gat in lookups:
        deq = [n for n in g.node if n.op_type == "DequantizeLinear" and n.input[0] == gat.output[0]]
        if len(deq) != 1:
            print("the lookup is not followed by one DequantizeLinear, left as it is")
            return False
        deq = deq[0]
        p = "dedupe%d_" % k
        k += 1
        ids = gat.input[1]
        new = [
            helper.make_node("Gather", [wq, ids], [p + "q"], axis=1),            # [d, ...ids]
            helper.make_node("Gather", [wscale, ids], [p + "s"], axis=0),        # [...ids]
            helper.make_node("Gather", [wzp, ids], [p + "z"], axis=0),
            helper.make_node("Cast", [p + "q"], [p + "qf"], to=onnx.TensorProto.FLOAT),
            helper.make_node("Cast", [p + "z"], [p + "zf"], to=onnx.TensorProto.FLOAT),
            helper.make_node("Unsqueeze", [p + "zf", p + "ax"], [p + "zu"]),     # [1, ...ids]
            helper.make_node("Unsqueeze", [p + "s", p + "ax"], [p + "su"]),
            helper.make_node("Sub", [p + "qf", p + "zu"], [p + "c"]),
            helper.make_node("Mul", [p + "c", p + "su"], [p + "f"]),              # [d, ...ids] float
            # ids are [batch, seq]: move d last.
            helper.make_node("Transpose", [p + "f"], [deq.output[0]], perm=[1, 2, 0]),
        ]
        g.initializer.append(numpy_helper.from_array(np.array([0], dtype=np.int64), p + "ax"))
        at = list(g.node).index(gat)
        g.node.remove(deq)
        g.node.remove(gat)
        for i, n in enumerate(new):
            g.node.insert(at + i, n)
    # The [vocab, d] table and its scale and zero point, if nothing else uses them any more.
    used = {i for g in graphs for n in g.node for i in n.input}
    for name in [table, table.replace("_quantized", "_scale"), table.replace("_quantized", "_zero_point")]:
        if name not in used:
            for t in list(m.graph.initializer):
                if t.name == name:
                    m.graph.initializer.remove(t)
    onnx.checker.check_model(m)
    onnx.save(m, out or path)
    print("lookup reads the output layer now (%d places)" % k)
    return True


if __name__ == "__main__":
    dedupe(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
