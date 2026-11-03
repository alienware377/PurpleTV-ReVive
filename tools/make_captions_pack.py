#!/usr/bin/env python3
"""Builds the speech model packs for the GitHub pre-release captions-pack-2.

Run it with python3 -I (no site packages are needed, only the standard library):

    python3 -I tools/make_captions_pack.py OUT_DIR [--langs es,de]

captions-pack-2 holds one speech model per stream language other than English. English and the
engine (the sherpa-onnx library) stay in captions-pack-1, which never changes.

What it does:
  - downloads each model's files from its Hugging Face repo at a fixed commit, and checks every
    file against the size and SHA-256 written below, so a changed upstream file stops the build
    instead of slipping into a pack;
  - puts the model files, a LICENSE.txt and a README.txt (credits, sources, hashes) into one zip
    per language, with fixed timestamps and order, so the same inputs always give the same bytes;
  - prints and writes (OUT_DIR/pins.txt) the Kotlin pins for SttModels.kt: zip size and SHA-256,
    and every entry's size and SHA-256.

It also leaves the unpacked model folders in OUT_DIR/models/<lang>/, handy for a desktop decode
test with the sherpa-onnx Python package. Downloads are cached in OUT_DIR/cache.
Nothing is published: CI (or a person) uploads the zips to the release.
"""
import hashlib
import io
import os
import sys
import time
import urllib.request
import zipfile

TAG = "captions-pack-2"
HF = "https://huggingface.co/"
MODEL_LIST = "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/index.html"

# The Apache License 2.0 text, put into the packs of Apache-2.0 models as the licence asks. It is
# the same file as LICENSE-sherpa-onnx.txt in the captions-pack-1 engine pack.
APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"
APACHE_BYTES = 11358
APACHE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
APACHE_FILE = "LICENSE-Apache-2.0.txt"

# A fixed time for every zip entry: the same bytes on every rebuild.
ZIP_TIME = (2026, 11, 3, 0, 0, 0)

KROKO_LICENCE = "CC-BY-SA"
KROKO_LICENCE_TEXT = """Licence: Creative Commons Attribution-ShareAlike (CC-BY-SA)

Credit: {credit}

Banafo publishes its Kroko community models under CC-BY-SA:
    https://huggingface.co/Banafo/Kroko-ASR
    https://github.com/kroko-ai/kroko-onnx
The sherpa-onnx copy points to the same licence ("See license at
https://huggingface.co/Banafo/Kroko-ASR").

Banafo does not name a version number of the licence. The current version
is Creative Commons Attribution-ShareAlike 4.0 International:
    Summary:    https://creativecommons.org/licenses/by-sa/4.0/
    Legal code: https://creativecommons.org/licenses/by-sa/4.0/legalcode
"""

APACHE_LICENCE_TEXT = """Licence: Apache License 2.0

Credit: {credit}

The original model is published under the Apache License 2.0:
    {origin}
Full licence text: LICENSE-Apache-2.0.txt in this zip, and
https://www.apache.org/licenses/LICENSE-2.0
"""

# One entry per stream language. "files" lists (role, file name in the repo and in the zip,
# bytes, SHA-256); the roles are what the app's recognizer config asks for. "kind" and
# "model_type" go into sherpa-onnx's OnlineModelConfig as they are.
MODELS = [
    dict(
        lang="es", label="Spanish",
        asset="purpletv-captions-model-kroko-es-2025-08-06.zip",
        repo="csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
        commit="20cf7a4921613397841d31168796cade5b866585",
        kind="transducer", model_type="zipformer2",
        files=[
            ("encoder", "encoder.onnx", 154878102, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b"),
            ("decoder", "decoder.onnx", 617488, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee"),
            ("joiner", "joiner.onnx", 336817, "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5"),
            ("tokens", "tokens.txt", 6385,
             "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f"),
        ],
        what="Kroko Spanish community streaming model, 2025-08-06 release, a streaming\n"
             "Zipformer2 transducer for 16 kHz audio. It is the larger Kroko size, so it\n"
             "needs about twice the work of the English one.",
        credit="Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA,\n"
               "converted for sherpa-onnx by csukuangfj",
        licence=KROKO_LICENCE, licence_text=KROKO_LICENCE_TEXT, origin="https://huggingface.co/Banafo/Kroko-ASR",
    ),
    dict(
        lang="de", label="German",
        asset="purpletv-captions-model-kroko-de-2025-08-06.zip",
        repo="csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
        commit="887db3d083240198c2d2b99fb66cfcfe6948ced8",
        kind="transducer", model_type="zipformer2",
        files=[
            ("encoder", "encoder.onnx", 70091557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5"),
            ("decoder", "decoder.onnx", 617489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562"),
            ("joiner", "joiner.onnx", 336817, "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a"),
            ("tokens", "tokens.txt", 5606,
             "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb"),
        ],
        what="Kroko German community streaming model, 2025-08-06 release, a streaming\n"
             "Zipformer2 transducer for 16 kHz audio, the same size as the English one.",
        credit="Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA,\n"
               "converted for sherpa-onnx by csukuangfj",
        licence=KROKO_LICENCE, licence_text=KROKO_LICENCE_TEXT, origin="https://huggingface.co/Banafo/Kroko-ASR",
    ),
    dict(
        lang="fr", label="French",
        asset="purpletv-captions-model-kroko-fr-2025-08-06.zip",
        repo="csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06",
        commit="08b84b7b7cf519be9817e9c16919d96a7a8bad91",
        kind="transducer", model_type="zipformer2",
        files=[
            ("encoder", "encoder.onnx", 70092599, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b"),
            ("decoder", "decoder.onnx", 617488, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac"),
            ("joiner", "joiner.onnx", 336817, "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6"),
            ("tokens", "tokens.txt", 5415,
             "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298"),
        ],
        what="Kroko French community streaming model, 2025-08-06 release, a streaming\n"
             "Zipformer2 transducer for 16 kHz audio, the same size as the English one.",
        credit="Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA,\n"
               "converted for sherpa-onnx by csukuangfj",
        licence=KROKO_LICENCE, licence_text=KROKO_LICENCE_TEXT, origin="https://huggingface.co/Banafo/Kroko-ASR",
    ),
    dict(
        lang="ru", label="Russian",
        asset="purpletv-captions-model-vosk-small-ru-2025-08-16.zip",
        repo="csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
        commit="31fa603e4f31279c6e1f7600fed13dc4312663ab",
        kind="transducer", model_type="zipformer2",
        files=[
            ("encoder", "encoder.int8.onnx", 26214060,
             "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
            ("decoder", "decoder.onnx", 2093080,
             "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
            ("joiner", "joiner.int8.onnx", 259417,
             "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
            ("tokens", "tokens.txt", 6388,
             "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
        ],
        what="Vosk small streaming Russian model (version 0.54) by Alpha Cephei, a small\n"
             "streaming Zipformer2 transducer trained with icefall, with 8 bit weights.",
        credit="Vosk small streaming Russian model by Alpha Cephei\n"
               "(https://huggingface.co/alphacep/vosk-model-small-streaming-ru), Apache-2.0,\n"
               "converted for sherpa-onnx by csukuangfj",
        licence="Apache-2.0", licence_text=APACHE_LICENCE_TEXT,
        origin="https://huggingface.co/alphacep/vosk-model-small-streaming-ru",
    ),
    dict(
        lang="ko", label="Korean",
        asset="purpletv-captions-model-zipformer-ko-2024-06-16.zip",
        repo="k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16",
        commit="ba6078bca4daf3f0dd37f79d0ab505af71df14a6",
        kind="transducer", model_type="zipformer",
        files=[
            ("encoder", "encoder-epoch-99-avg-1.int8.onnx", 126968852,
             "8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697"),
            ("decoder", "decoder-epoch-99-avg-1.onnx", 11309084,
             "b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc"),
            ("joiner", "joiner-epoch-99-avg-1.int8.onnx", 2581421,
             "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced"),
            ("tokens", "tokens.txt", 60246,
             "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668"),
        ],
        what="Streaming Zipformer (version 1) transducer for Korean, trained with icefall on\n"
             "KsponSpeech by johnBamma, with 8 bit weights for the encoder and joiner.",
        credit="Korean streaming Zipformer by johnBamma\n"
               "(https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12),\n"
               "Apache-2.0, converted for sherpa-onnx by k2-fsa",
        licence="Apache-2.0", licence_text=APACHE_LICENCE_TEXT,
        origin="https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12",
    ),
    dict(
        lang="zh", label="Chinese",
        asset="purpletv-captions-model-zipformer-ctc-zh-small-2025-04-01.zip",
        repo="csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
        commit="a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da",
        kind="zipformer2_ctc", model_type="",
        files=[
            ("model", "model.int8.onnx", 26342340,
             "68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a"),
            ("tokens", "tokens.txt", 13366,
             "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd"),
        ],
        what="Small streaming Zipformer2 CTC model for Mandarin Chinese by k2-fsa\n"
             "(icefall), with 8 bit weights.",
        credit="Small streaming Zipformer CTC Chinese model by k2-fsa (csukuangfj)\n"
               "(https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01), Apache-2.0",
        licence="Apache-2.0", licence_text=APACHE_LICENCE_TEXT,
        origin="https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01",
    ),
]


def fetch(url, dst):
    """Downloads url to dst once (kept as a cache), retrying on 429 and network errors."""
    if os.path.isfile(dst) and os.path.getsize(dst) > 0:
        return dst
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".part"
    for attempt in range(8):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as out:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    out.write(b)
            os.replace(tmp, dst)
            return dst
        except Exception as e:  # noqa: BLE001, a plain retry loop
            wait = 30 if "429" in str(e) else 5 * (attempt + 1)
            print("  retry %s in %ds: %s" % (url, wait, e), file=sys.stderr)
            time.sleep(wait)
    raise SystemExit("could not download " + url)


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def write_zip(path, entries):
    """entries: list of (name, bytes). Stored in this order, deflated, with fixed times."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data, compresslevel=9)
    data = buf.getvalue()
    with open(path, "wb") as f:
        f.write(data)
    return len(data), sha256_bytes(data)


def kt(s):
    """A Kotlin string literal."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def licence_txt(m):
    names = ", ".join(f[1] for f in m["files"])
    head = "Licence of the model files in this pack\n========================================\n\nFiles: %s\n\n" % names
    body = m["licence_text"].format(credit=m["credit"].replace("\n", "\n"), origin=m["origin"])
    tail = (
        "\nChanges: none. The model files are byte for byte the ones in the sherpa-onnx\n"
        "copy at https://huggingface.co/%s\n(commit %s). They were only put into this zip\n"
        "together with README.txt and this file.\n\n"
        "No warranty: the model is provided as is, without any warranty, as the\nlicence says.\n\n"
        "This licence covers only the model files. The PurpleTV ReVive module and\n"
        "the separate engine pack have their own licences.\n" % (m["repo"], m["commit"])
    )
    return (head + body + tail).encode()


def readme_txt(m, model_entries):
    title = "PurpleTV ReVive captions model pack 2 (%s speech model)" % m["label"]
    lines = [
        title, "=" * len(title), "",
        "What this is", "------------",
        "The %s speech model that PurpleTV ReVive's captions use when you set the" % m["label"],
        "stream language to %s, together with the engine pack of captions-pack-1" % m["label"],
        "(the sherpa-onnx library). PurpleTV downloads it by itself, checks each",
        "file's SHA-256 and keeps it in Twitch's private storage on the phone. You",
        "do not need to unpack or install anything by hand.", "",
        "Everything runs on the phone. No audio and no text is sent anywhere.", "",
        "Credits", "-------", m["credit"], "",
        "Model: " + m["what"],
        "    Original:         " + m["origin"],
        "    sherpa-onnx copy: https://huggingface.co/" + m["repo"],
        "                      (commit %s)" % m["commit"],
        "    Model list:       " + MODEL_LIST, "",
        "What is inside", "--------------",
        "The model files are byte for byte the ones published in the sherpa-onnx",
        "copy above. Nothing was changed, removed from them or retrained. Only the",
        "README.txt and LICENSE.txt are new.", "",
    ]
    for name, data in model_entries:
        lines.append("%s   %d bytes" % (name, len(data)))
        lines.append("    SHA-256 " + sha256_bytes(data))
    lines += [
        "LICENSE.txt    the licence of the model files",
    ] + (["%s   the full licence text" % APACHE_FILE] if m["licence"] == "Apache-2.0" else []) + [
        "README.txt     this file", "",
        "Licence", "-------",
        "The model files are under the %s licence. See LICENSE.txt." % m["licence"],
        "If you share these files, keep the credit line above and the same licence.", "",
    ]
    return "\n".join(lines).encode()


def build_model(out, cache, m):
    model_entries = []
    unpacked = os.path.join(out, "models", m["lang"])
    os.makedirs(unpacked, exist_ok=True)
    for role, name, size, sha in m["files"]:
        p = fetch(HF + m["repo"] + "/resolve/" + m["commit"] + "/" + name,
                  os.path.join(cache, m["repo"].replace("/", "__"), m["commit"], name))
        data = open(p, "rb").read()
        got = sha256_bytes(data)
        if len(data) != size or (sha is not None and got != sha):
            raise SystemExit("%s/%s: %d bytes, sha %s; expected %d bytes, sha %s"
                             % (m["repo"], name, len(data), got, size, sha))
        model_entries.append((name, data))
        with open(os.path.join(unpacked, name), "wb") as f:
            f.write(data)
    extra = []
    if m["licence"] == "Apache-2.0":
        p = fetch(APACHE_URL, os.path.join(cache, APACHE_FILE))
        data = open(p, "rb").read()
        if len(data) != APACHE_BYTES or sha256_bytes(data) != APACHE_SHA256:
            raise SystemExit("%s does not match its pin" % APACHE_URL)
        extra.append((APACHE_FILE, data))
    entries = model_entries + [("LICENSE.txt", licence_txt(m))] + extra + [("README.txt", readme_txt(m, model_entries))]
    size, sha = write_zip(os.path.join(out, m["asset"]), entries)
    return size, sha, entries


def kotlin_pin(m, size, sha, entries):
    roles = ",\n".join('            %s to %s' % (kt(r), kt(n)) for r, n, _, _ in m["files"])
    ents = ",\n".join('            %s to PackPins.FilePin(%dL, "%s")' % (kt(n), len(d), sha256_bytes(d))
                      for n, d in entries)
    credit = m["credit"].replace("\n", " ")
    return (
        "        // %s: %s at %s\n"
        "        SttModel(\n"
        "            lang = %s, label = %s, tag = TAG2,\n"
        "            asset = %s,\n"
        "            sha256 = \"%s\", bytes = %dL,\n"
        "            kind = %s, modelType = %s,\n"
        "            licence = %s,\n"
        "            credit = %s,\n"
        "            files = mapOf(\n%s\n            ),\n"
        "            entries = mapOf(\n%s\n            )\n"
        "        )"
        % (m["label"], m["repo"], m["commit"], kt(m["lang"]), kt(m["label"]), kt(m["asset"]), sha, size,
           "SttModel.Kind." + ("TRANSDUCER" if m["kind"] == "transducer" else "ZIPFORMER2_CTC"),
           kt(m["model_type"]), kt(m["licence"]), kt(credit),
           roles.replace("            ", "                "), ents.replace("            ", "                "))
    )


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out = os.path.abspath(sys.argv[1])
    only = None
    if len(sys.argv) >= 4 and sys.argv[2] == "--langs":
        only = sys.argv[3].split(",")
    cache = os.path.join(out, "cache")
    os.makedirs(cache, exist_ok=True)
    pins = []
    for m in MODELS:
        if only and m["lang"] not in only:
            continue
        size, sha, entries = build_model(out, cache, m)
        print("%s %d %s" % (m["asset"], size, sha))
        pins.append(kotlin_pin(m, size, sha, entries))
    text = ",\n".join(pins) + "\n"
    with open(os.path.join(out, "pins.txt"), "w") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    main()
