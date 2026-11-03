#!/usr/bin/env python3
"""Writes the tokenizer reference ids used by SentencePieceTest (src/test/resources/translate/tokref.json).

Needs transformers and sentencepiece (pip install transformers sentencepiece in a virtualenv; no
PyTorch needed). MODEL_ROOT holds one folder per model with source.spm, target.spm, vocab.json and
tokenizer_config.json from the Xenova/opus-mt-* repos on HuggingFace.

    python -I tools/make_tokenizer_refs.py MODEL_ROOT OUT.json
"""
import json, sys
from transformers import MarianTokenizer
root = sys.argv[1]
texts = [
    "Hello, how are you?",
    "  leading and   multiple   spaces  ",
    "Don't stop me now!!! I'm having such a good time",
    "Ｆｕｌｌｗｉｄｔｈ ＡＢＣ １２３ and ﬁ ligature",
    "Café, naïve, résumé, Straße, São Paulo",
    "emoji 😀🔥 test ✨",
    "GG WP EZ Clap",
    "tab\tand\nnewline",
    "Привет, как дела?",
    "这是一个测试。",
    "Ich habe heute keine Zeit, aber morgen vielleicht.",
    "¿Qué tal? ¡Muy bien!",
    "€100 or $50 … 3.14% @user #tag https://twitch.tv/x",
    "a",
    "",
    "   ",
    " no-break space​ zero width",
    "ʻokina ǂ ⅷ ① ㍿",
]
out = {}
for m, prefix in [("en-es", None), ("mul-en", None), ("en-zh", ">>cmn_Hans<<"), ("en-ROMANCE", ">>pt_br<<"), ("en-ru", None)]:
    tok = MarianTokenizer.from_pretrained(root + "/" + m)
    rows = []
    for t in texts:
        src = (prefix + " " + t) if prefix else t
        ids = tok(src)["input_ids"]
        pieces = tok.spm_source.encode(t, out_type=str)
        rows.append({"text": t, "prefix": prefix, "pieces": pieces, "ids": ids})
    out[m] = rows
json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
