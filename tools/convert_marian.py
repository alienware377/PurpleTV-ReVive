#!/usr/bin/env python3
"""Converts a MarianMT model on Hugging Face into the ONNX layout PurpleTV's translator reads.

    python tools/convert_marian.py REPO_OR_FOLDER COMMIT OUT_DIR

The layout is the one of the Xenova conversions the other translation packs use:
  encoder_model_quantized.onnx         the encoder, 8 bit weights
  decoder_model_merged_quantized.onnx  the decoder with and without its cache in one graph, 8 bit
  source.spm                           the source SentencePiece model
  vocab.json                           the one vocabulary shared by source and target
  config.json, generation_config.json

Needs torch, transformers (4.x), optimum[onnxruntime] and onnxruntime. Models with a separate
target vocabulary are refused: the app reads one vocab.json for both sides.
"""
import json
import os
import shutil
import sys
import tempfile

from huggingface_hub import snapshot_download
from onnxruntime.quantization import QuantType, quantize_dynamic
from optimum.exporters.onnx import main_export


# Per channel weights keep a little more of the model than one scale per tensor (checked on tl-en).
PER_CHANNEL = os.environ.get("PER_CHANNEL", "1") == "1"


def convert(repo, commit, out):
    # A local folder (a fine-tuned model) is used as it is.
    src = repo if os.path.isdir(repo) else snapshot_download(repo, revision=commit)
    cfg = json.load(open(os.path.join(src, "config.json"), encoding="utf-8"))
    if cfg.get("separate_vocabs") or (cfg.get("decoder_vocab_size") not in (None, cfg.get("vocab_size"))):
        raise SystemExit("%s has a separate target vocabulary, which the app cannot read" % repo)
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        main_export(src, output=tmp, task="text2text-generation-with-past", opset=14, no_post_process=False)
        names = sorted(os.listdir(tmp))
        if "decoder_model_merged.onnx" not in names:
            raise SystemExit("no merged decoder in the export: %s" % names)
        for name in ("encoder_model", "decoder_model_merged"):
            quantize_dynamic(os.path.join(tmp, name + ".onnx"), os.path.join(out, name + "_quantized.onnx"),
                             weight_type=QuantType.QUInt8, per_channel=PER_CHANNEL, reduce_range=False,
                             extra_options={"EnableSubgraph": True})
    # One copy of the vocabulary instead of two (see dedupe_marian_decoder.py): about 30 MB less.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from dedupe_marian_decoder import dedupe
    dedupe(os.path.join(out, "decoder_model_merged_quantized.onnx"))
    for f in ("source.spm", "vocab.json", "config.json", "generation_config.json"):
        shutil.copy(os.path.join(src, f), os.path.join(out, f))
    print("converted", repo, "->", out)
    for f in sorted(os.listdir(out)):
        print("  %-40s %d" % (f, os.path.getsize(os.path.join(out, f))))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    convert(sys.argv[1], sys.argv[2], sys.argv[3])
