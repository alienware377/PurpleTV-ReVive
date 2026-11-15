#!/usr/bin/env python3
"""Fine-tunes a MarianMT model for Twitch chat and streamer speech.

    python tools/train_marian.py BASE_REPO BASE_COMMIT xx-en|en-xx DATA_DIR OUT_DIR [--slang DIR] [--epochs 3]

xx is the two letter code of the other language (ja, ru, es, ...). Data (DATA_DIR):
  slang/*.jsonl              {"xx": ..., "en": ...} pairs of live chat and stream speech (or --slang)
  tatoeba-dev.eng-XXX.tsv    Tatoeba (CC BY 2.0 FR) pairs, everyday sentences, keeps the model general;
  tatoeba-test.eng-XXX.tsv   XXX is Tatoeba's three letter code; columns: eng, XXX, English, other
  jesc.parquet               Japanese only: JESC film and TV subtitles (CC BY-SA 4.0), casual speech

The chat pairs are repeated so they weigh about as much as the general data, and a tenth of
them (by a fixed shuffle) are held out to measure the result. The best epoch by chrF on the
held-out chat pairs is kept, as long as it does not get much worse on the Tatoeba test set.
"""
import argparse
import glob
import json
import os
import random

import pandas as pd
import sacrebleu
import torch
from torch.utils.data import DataLoader
from transformers import MarianMTModel, MarianTokenizer, get_linear_schedule_with_warmup

ap = argparse.ArgumentParser()
ap.add_argument("repo")
ap.add_argument("commit")
ap.add_argument("direction")
ap.add_argument("data")
ap.add_argument("out")
ap.add_argument("--epochs", type=int, default=3)
ap.add_argument("--lr", type=float, default=3e-5)
ap.add_argument("--batch", type=int, default=32)
ap.add_argument("--general", type=int, default=40000, help="Tatoeba pairs and JESC pairs, each")
ap.add_argument("--slang-repeat", type=int, default=8)
ap.add_argument("--slang", help="folder of the chat pairs (default DATA_DIR/slang)")
ap.add_argument("--prefix", default="", help="target language token put before every source line, like >>pt_br<<")
a = ap.parse_args()

rng = random.Random(1234)
src_key, tgt_key = a.direction.split("-")
lang = src_key if tgt_key == "en" else tgt_key
assert "en" in (src_key, tgt_key) and len(lang) == 2, a.direction
L3 = {"ja": "jpn", "ru": "rus", "es": "spa", "pt": "por", "de": "deu", "fr": "fra", "it": "ita",
      "zh": "cmn", "ar": "ara", "tl": "tgl", "lt": "lit", "ko": "kor"}[lang]


def pairs_slang():
    out = []
    for f in sorted(glob.glob(os.path.join(a.slang or os.path.join(a.data, "slang"), "*.jsonl"))):
        for line in open(f, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            xx, en = (d.get(lang) or "").strip(), (d.get("en") or "").strip()
            if xx and en:
                out.append({lang: xx, "en": en})
    seen, uniq = set(), []
    for p in out:
        if p[lang] not in seen:
            seen.add(p[lang]); uniq.append(p)
    return uniq


def pairs_tatoeba(name):
    out = []
    for line in open(os.path.join(a.data, name), encoding="utf-8"):
        c = line.rstrip("\n").split("\t")
        if len(c) >= 4 and c[0].startswith("eng") and c[1].startswith(L3):
            out.append({"en": c[2], lang: c[3]})
    return out


def pairs_jesc():
    df = pd.read_parquet(os.path.join(a.data, "jesc.parquet"))
    cols = list(df.columns)
    out = []
    if "translation" in cols:
        for t in df["translation"]:
            out.append({"en": t["en"], "ja": t["ja"]})
    else:
        en = next(c for c in cols if c.lower() in ("en", "english"))
        ja = next(c for c in cols if c.lower() in ("ja", "japanese"))
        out = [{"en": e, "ja": j} for e, j in zip(df[en], df[ja])]
    # JESC Japanese is split into words with spaces; real chat has none.
    for p in out:
        p["ja"] = p["ja"].replace(" ", "")
    return out


slang = pairs_slang()
rng.shuffle(slang)
held = slang[: max(50, len(slang) // 10)]
slang_train = slang[len(held):]
tat = pairs_tatoeba("tatoeba-dev.eng-%s.tsv" % L3)
rng.shuffle(tat)
jesc = pairs_jesc() if lang == "ja" else []
rng.shuffle(jesc)
tat_test = pairs_tatoeba("tatoeba-test.eng-%s.tsv" % L3)
rng.shuffle(tat_test)
tat_test = tat_test[:500]
train = slang_train * a.slang_repeat + tat[: a.general] + jesc[: a.general]
rng.shuffle(train)
print("slang %d (held out %d), tatoeba %d, jesc %d, train %d" % (len(slang), len(held), min(len(tat), a.general),
                                                                  min(len(jesc), a.general), len(train)))

dev = torch.device("cuda")
tok = MarianTokenizer.from_pretrained(a.repo, revision=a.commit)
model = MarianMTModel.from_pretrained(a.repo, revision=a.commit).to(dev)


def pre(t):
    return (a.prefix + " " + t) if a.prefix else t


def collate(batch):
    x = tok([pre(b[src_key]) for b in batch], text_target=[b[tgt_key] for b in batch], max_length=128,
            truncation=True, padding=True, return_tensors="pt")
    x["labels"][x["labels"] == tok.pad_token_id] = -100
    return x


def translate(lines, bs=64):
    model.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(lines), bs):
            x = tok([pre(t) for t in lines[i:i + bs]], max_length=128, truncation=True, padding=True, return_tensors="pt").to(dev)
            y = model.generate(**x, num_beams=1, do_sample=False, max_new_tokens=128)
            out += tok.batch_decode(y, skip_special_tokens=True)
    model.train()
    return out


def score(pairs):
    hyp = translate([p[src_key] for p in pairs])
    ref = [p[tgt_key] for p in pairs]
    try:
        chrf = sacrebleu.corpus_chrf(hyp, [ref]).score
    except Exception:  # noqa: BLE001
        chrf = 0.0
    bleu = sacrebleu.corpus_bleu(hyp, [ref], tokenize="char" if tgt_key in ("ja", "zh") else "13a").score
    return chrf, bleu, hyp


def report(tag):
    c1, b1, hyp = score(held)
    c2, b2, _ = score(tat_test)
    print("%s: chat chrF %.1f BLEU %.1f | tatoeba chrF %.1f BLEU %.1f" % (tag, c1, b1, c2, b2), flush=True)
    for p, h in list(zip(held, hyp))[:8]:
        print("   ", p[src_key], "=>", h, "| ref:", p[tgt_key])
    return c1, c2


base_chat, base_tat = report("base")
dl = DataLoader(train, batch_size=a.batch, shuffle=True, collate_fn=collate)
opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.01)
steps = len(dl) * a.epochs
sched = get_linear_schedule_with_warmup(opt, int(steps * 0.05), steps)
scaler = torch.amp.GradScaler()
best = None
for ep in range(a.epochs):
    for i, x in enumerate(dl):
        x = {k: v.to(dev) for k, v in x.items()}
        with torch.autocast("cuda", dtype=torch.float16):
            loss = model(**x).loss
        scaler.scale(loss).backward()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(opt); scaler.update(); opt.zero_grad(); sched.step()
        if i % 500 == 0:
            print("epoch %d step %d/%d loss %.3f" % (ep, i, len(dl), loss.item()), flush=True)
    chat, tat_c = report("epoch %d" % ep)
    # Better on chat, and not more than 2 chrF worse on everyday sentences than the base.
    if tat_c >= base_tat - 2 and (best is None or chat > best):
        best = chat
        model.save_pretrained(a.out)
        tok.save_pretrained(a.out)
        print("  kept epoch %d" % ep, flush=True)
json.dump({"base": [base_chat, base_tat], "best_chat": best}, open(os.path.join(a.out, "train_scores.json"), "w"))
print("done, best chat chrF", best, "base", base_chat)
