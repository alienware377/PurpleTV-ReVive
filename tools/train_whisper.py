#!/usr/bin/env python3
"""Fine-tunes Whisper (English) on Twitch slang speech, with real speech mixed in so it keeps
its plain English.

    python tools/train_whisper.py OUT_DIR [--base openai/whisper-base.en] [--epochs 3]

Data:
  tmp/slang_speech/manifest-*.jsonl + audio/   spoken slang and chat lines, made with Kokoro voices
  tmp/librispeech/LibriSpeech/dev-clean        LibriSpeech dev-clean (CC BY 4.0), real read speech

A few hundred of each are held out. The best epoch by word error on the held-out slang is kept,
as long as plain English (LibriSpeech) does not get much worse than the base model.
"""
import argparse
import glob
import json
import os
import random
import re

import numpy as np
import soundfile as sf
import torch
from torch.utils.data import DataLoader
from transformers import WhisperForConditionalGeneration, WhisperProcessor, get_linear_schedule_with_warmup

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("--base", default="openai/whisper-base.en")
ap.add_argument("--epochs", type=int, default=3)
ap.add_argument("--lr", type=float, default=1e-5)
ap.add_argument("--batch", type=int, default=16)
a = ap.parse_args()
rng = random.Random(5)


def slang():
    out = []
    for f in sorted(glob.glob("tmp/slang_speech/manifest-*.jsonl")):
        for line in open(f, encoding="utf-8"):
            d = json.loads(line)
            out.append(("tmp/slang_speech/audio/" + d["audio"], d["text"]))
    return out


def libri():
    out = []
    for t in glob.glob("tmp/librispeech/LibriSpeech/dev-clean/*/*/*.trans.txt"):
        d = os.path.dirname(t)
        for line in open(t, encoding="utf-8"):
            k, text = line.strip().split(" ", 1)
            # LibriSpeech is all capitals with no punctuation: written as a sentence instead.
            out.append((os.path.join(d, k + ".flac"), text.lower().capitalize()))
    return out


s, l = slang(), libri()
rng.shuffle(s)
rng.shuffle(l)
s_test, s_train = s[:300], s[300:]
l_test, l_train = l[:200], l[200:]
train = s_train + l_train
print("slang %d (+%d held out), libri %d (+%d held out)" % (len(s_train), len(s_test), len(l_train), len(l_test)), flush=True)

proc = WhisperProcessor.from_pretrained(a.base)
model = WhisperForConditionalGeneration.from_pretrained(a.base).cuda()
model.config.forced_decoder_ids = None
model.generation_config.forced_decoder_ids = None


def load(path):
    x, sr = sf.read(path, dtype="float32")
    if x.ndim > 1:
        x = x.mean(axis=1)
    assert sr == 16000, path
    return x


def augment(x):
    # Louder or quieter, and a little noise now and then, as stream sound has.
    x = x * rng.uniform(0.4, 1.3)
    if rng.random() < 0.5:
        snr = rng.uniform(12, 35)
        p = (x ** 2).mean() + 1e-9
        x = x + np.random.randn(len(x)).astype(np.float32) * np.sqrt(p / (10 ** (snr / 10)))
    return np.clip(x, -1, 1)


def collate(batch):
    audio = [augment(load(p)) for p, _ in batch]
    feats = proc.feature_extractor(audio, sampling_rate=16000, return_tensors="pt").input_features
    lab = proc.tokenizer([t for _, t in batch], padding=True, return_tensors="pt")
    ids = lab.input_ids.masked_fill(lab.attention_mask.eq(0), -100)
    # The model adds the start token itself.
    if (ids[:, 0] == model.config.decoder_start_token_id).all():
        ids = ids[:, 1:]
    return feats, ids


def words(t):
    return re.sub(r"[^\w' ]", " ", t.lower()).split()


def wer(pairs):
    model.eval()
    errs = n = 0
    outs = []
    with torch.no_grad():
        for i in range(0, len(pairs), 32):
            chunk = pairs[i:i + 32]
            feats = proc.feature_extractor([load(p) for p, _ in chunk], sampling_rate=16000, return_tensors="pt").input_features.cuda()
            gen = model.generate(feats, max_new_tokens=120)
            hyp = proc.batch_decode(gen, skip_special_tokens=True)
            for (_, ref), h in zip(chunk, hyp):
                r, hw = words(ref), words(h)
                # Word edit distance.
                d = list(range(len(hw) + 1))
                for x in range(1, len(r) + 1):
                    prev, d[0] = d[0], x
                    for y in range(1, len(hw) + 1):
                        cur = min(d[y] + 1, d[y - 1] + 1, prev + (r[x - 1] != hw[y - 1]))
                        prev, d[y] = d[y], cur
                errs += d[len(hw)]
                n += len(r)
                outs.append((ref, h.strip()))
    model.train()
    return errs / max(n, 1), outs


def report(tag):
    ws, outs = wer(s_test)
    wl, _ = wer(l_test)
    print("%s: slang WER %.3f | plain English WER %.3f" % (tag, ws, wl), flush=True)
    for ref, h in outs[:8]:
        print("   ", ref, "=>", h)
    return ws, wl


base_s, base_l = report("base")
dl = DataLoader(train, batch_size=a.batch, shuffle=True, collate_fn=collate, num_workers=0)
opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.01)
steps = len(dl) * a.epochs
sched = get_linear_schedule_with_warmup(opt, int(steps * 0.05), steps)
scaler = torch.amp.GradScaler()
best = None
for ep in range(a.epochs):
    for i, (feats, ids) in enumerate(dl):
        with torch.autocast("cuda", dtype=torch.float16):
            loss = model(input_features=feats.cuda(), labels=ids.cuda()).loss
        scaler.scale(loss).backward()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(opt); scaler.update(); opt.zero_grad(); sched.step()
        if i % 200 == 0:
            print("epoch %d step %d/%d loss %.3f" % (ep, i, len(dl), loss.item()), flush=True)
    ws, wl = report("epoch %d" % ep)
    if wl <= base_l + 0.02 and (best is None or ws < best):
        best = ws
        model.save_pretrained(a.out)
        proc.save_pretrained(a.out)
        print("  kept epoch %d" % ep, flush=True)
json.dump({"base": [base_s, base_l], "best_slang_wer": best}, open(os.path.join(a.out, "train_scores.json"), "w"))
print("done, best slang WER", best, "base", base_s)
