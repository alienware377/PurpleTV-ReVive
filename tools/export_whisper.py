#!/usr/bin/env python3
"""Exports a fine-tuned Whisper (Hugging Face layout) to the ONNX files sherpa-onnx reads.

    python tools/export_whisper.py HF_DIR OUT_DIR --script export-onnx.py [--name base.en]

--script is sherpa-onnx's own scripts/whisper/export-onnx.py (Apache 2.0, from the sherpa-onnx
release the app ships, v1.13.8), which writes <name>-encoder/decoder(.int8).onnx and
<name>-tokens.txt. It loads models with openai-whisper, so the Hugging Face weights are renamed
to openai-whisper's names first and handed to it in place of a download.

Needs torch, openai-whisper, onnx and onnxruntime.
"""
import argparse
import importlib.util
import json
import os
import sys

import torch
from safetensors.torch import load_file
from whisper.model import ModelDimensions, Whisper

ap = argparse.ArgumentParser()
ap.add_argument("hf_dir")
ap.add_argument("out")
ap.add_argument("--script", required=True)
ap.add_argument("--name", default="base.en")
a = ap.parse_args()

# Hugging Face name -> openai-whisper name, in this order.
RENAMES = [
    ("encoder.embed_positions.weight", "encoder.positional_embedding"),
    ("decoder.embed_positions.weight", "decoder.positional_embedding"),
    ("decoder.embed_tokens.", "decoder.token_embedding."),
    ("encoder.layer_norm.", "encoder.ln_post."),
    ("decoder.layer_norm.", "decoder.ln."),
    (".layers.", ".blocks."),
    (".self_attn_layer_norm.", ".attn_ln."),
    (".encoder_attn_layer_norm.", ".cross_attn_ln."),
    (".final_layer_norm.", ".mlp_ln."),
    (".self_attn.q_proj.", ".attn.query."),
    (".self_attn.k_proj.", ".attn.key."),
    (".self_attn.v_proj.", ".attn.value."),
    (".self_attn.out_proj.", ".attn.out."),
    (".encoder_attn.q_proj.", ".cross_attn.query."),
    (".encoder_attn.k_proj.", ".cross_attn.key."),
    (".encoder_attn.v_proj.", ".cross_attn.value."),
    (".encoder_attn.out_proj.", ".cross_attn.out."),
    (".fc1.", ".mlp.0."),
    (".fc2.", ".mlp.2."),
]


def openai_model(hf_dir):
    cfg = json.load(open(os.path.join(hf_dir, "config.json"), encoding="utf-8"))
    dims = ModelDimensions(
        n_mels=cfg["num_mel_bins"], n_vocab=cfg["vocab_size"],
        n_audio_ctx=cfg["max_source_positions"], n_audio_state=cfg["d_model"],
        n_audio_head=cfg["encoder_attention_heads"], n_audio_layer=cfg["encoder_layers"],
        n_text_ctx=cfg["max_target_positions"], n_text_state=cfg["d_model"],
        n_text_head=cfg["decoder_attention_heads"], n_text_layer=cfg["decoder_layers"],
    )
    state = {}
    for k, v in load_file(os.path.join(hf_dir, "model.safetensors")).items():
        if k == "proj_out.weight":
            continue  # tied to the token embedding
        k = k.removeprefix("model.")
        for old, new in RENAMES:
            k = k.replace(old, new)
        state[k] = v
    m = Whisper(dims)
    m.load_state_dict(state, strict=True)
    return m


model = openai_model(a.hf_dir)
spec = importlib.util.spec_from_file_location("sherpa_whisper_export", os.path.abspath(a.script))
exp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exp)
exp.load_model = lambda name: model

os.makedirs(a.out, exist_ok=True)
os.chdir(a.out)
sys.argv = ["export-onnx.py", "--model", a.name]
torch.set_num_threads(4)
from whisper.model import disable_sdpa  # noqa: E402

with disable_sdpa():
    exp.main()
