#!/usr/bin/env python3
"""Builds the offline translation packs for the GitHub pre-releases translate-pack-1, -2 and -3.

Run it with python3 -I (no site packages are needed, only the standard library):

    python3 -I tools/make_translate_pack.py OUT_DIR [--release translate-pack-2] [--models es-en,de-en]

Each release is built on its own and never changes once published:
  - translate-pack-1 (the default): the engine, the multilingual into English model (mul-en) and
    the English into X models;
  - translate-pack-2: one into English model per chat language (es-en, de-en and so on), which
    translate much better than mul-en. It has no engine zip; the engine stays in translate-pack-1.
  - translate-pack-3: every language both ways, trained further for live chat by
    tools/train_marian.py and converted by tools/convert_marian.py into local folders; see MODELS_3
    for where each comes from. Japanese, Filipino and Lithuanian are new here.

What it does:
  - downloads the ONNX Runtime Android AAR from Maven Central and takes its two arm64 libraries
    (libonnxruntime.so and libonnxruntime4j_jni.so) plus ORT's licence texts for the engine zip;
  - downloads each Opus-MT model (the Xenova ONNX conversion on HuggingFace, at the commit pinned
    in MODELS below, so a rebuild anywhere gives the same files) and puts the quantized encoder,
    the merged quantized decoder, the source SentencePiece model, vocab.json and the two configs
    into one zip per model;
  - writes the zips with fixed timestamps and order, so the same inputs always give the same bytes;
  - prints and writes (OUT_DIR/pins.txt for translate-pack-1, OUT_DIR/pins-<release>.txt for the
    others) the Kotlin pins for TranslatePins.kt: zip size and SHA-256,
    and every entry's size and SHA-256.

It also leaves the unpacked model folders in OUT_DIR/models/<name>/ and the desktop (linux-x64) ORT
natives in OUT_DIR/desktop-ort/, which the JVM unit tests use (see TranslateE2eTest).

Downloads are cached in OUT_DIR/cache. Nothing is published: upload the zips to the release by hand.
"""
import hashlib
import io
import os
import sys
import time
import urllib.request
import zipfile

ORT_VERSION = "1.22.0"
MAVEN = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/"
ORT_AAR = MAVEN + "onnxruntime-android/%s/onnxruntime-android-%s.aar" % (ORT_VERSION, ORT_VERSION)
ORT_JAR = MAVEN + "onnxruntime/%s/onnxruntime-%s.jar" % (ORT_VERSION, ORT_VERSION)
ORT_RAW = "https://raw.githubusercontent.com/microsoft/onnxruntime/v%s/" % ORT_VERSION
HF = "https://huggingface.co/"

PACK_1 = "translate-pack-1"
PACK_2 = "translate-pack-2"
PACK_3 = "translate-pack-3"

# Model name (as used in the app) -> (HuggingFace repo, commit, release it is published in). Each
# is a MarianMT model by Helsinki-NLP. The commits are pinned so the CI and a local run fetch the
# very same files; translate-pack-1's are the ones its published zips were built from.
MODELS = {
    "mul-en": ("Xenova/opus-mt-mul-en", "72a05e47cee89c718a9db4dc70d02fef3bc39de8", PACK_1),
    "en-es": ("Xenova/opus-mt-en-es", "4b002a4c7edd54a7ced58877258b87f7efd3f892", PACK_1),
    "en-de": ("Xenova/opus-mt-en-de", "1ca130c44c4c5441ef16d48aae521a424ab644f7", PACK_1),
    "en-fr": ("Xenova/opus-mt-en-fr", "28726206f80896b90035bd99cccd5cc1e151f916", PACK_1),
    "en-it": ("Xenova/opus-mt-en-it", "075406e3c8c2c30634d4a1bd8f00c21d9e162011", PACK_1),
    "en-ROMANCE": ("Xenova/opus-mt-en-ROMANCE", "9d2ba69ac80c8e8453c3d9a1e2323a0e7b8ca3cd", PACK_1),
    "en-ru": ("Xenova/opus-mt-en-ru", "e050376e960175e8ddc5cff85025dc8436cddd68", PACK_1),
    "en-zh": ("Xenova/opus-mt-en-zh", "046f55aec303cdee3e0318604406d4df20f1e8ea", PACK_1),
    "en-ar": ("Xenova/opus-mt-en-ar", "034a684356c19021a187cda4d7f823298b913921", PACK_1),
    # One into English model per chat language. Portuguese has no model of its own on Xenova, so
    # it uses ROMANCE-en (Spanish, Portuguese, French, Italian and more into English).
    "es-en": ("Xenova/opus-mt-es-en", "eadfd7c658a9d8929ac3b8e996b68a68e2c7d480", PACK_2),
    "ROMANCE-en": ("Xenova/opus-mt-ROMANCE-en", "f60feaabe8bb435a5871e7702b25f53a01e770f8", PACK_2),
    "de-en": ("Xenova/opus-mt-de-en", "399dfd68706739fffd503f876093e455ae268a06", PACK_2),
    "fr-en": ("Xenova/opus-mt-fr-en", "6b166a182780e118c997879d0ad5be4b53671644", PACK_2),
    "it-en": ("Xenova/opus-mt-it-en", "fd0b89b9c052adc1f2f64152f555aa17353728be", PACK_2),
    "ru-en": ("Xenova/opus-mt-ru-en", "afe8c6c738ec81b6d033fd8f44f9678a639a7c67", PACK_2),
    "zh-en": ("Xenova/opus-mt-zh-en", "39d480d52a9ea3065a1f117adfe4dbc55de10e6f", PACK_2),
    "ar-en": ("Xenova/opus-mt-ar-en", "09c7556866400dcc53562ba8d7035119e7d9a2c1", PACK_2),
}


# translate-pack-3: name -> (folder under the --from directory, licence text). The folders hold the
# converted files with the same names as the Xenova ones (see tools/convert_marian.py). Every model
# here was trained further for live chat by tools/train_marian.py before it was converted.
CC_BY = "Licence: CC-BY 4.0, https://creativecommons.org/licenses/by/4.0/\n"
CC_BY_SA = "Licence: CC-BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/\n"
CONVERTED = "ONNX conversion with 8 bit weights by PurpleTV ReVive (tools/convert_marian.py).\n"
HELSINKI = "by the Language Technology Research Group at the University of Helsinki\n(Helsinki-NLP)"
TATOEBA = "Tatoeba sentence pairs (https://tatoeba.org, CC BY 2.0 FR)"
JESC = ("JESC, the Japanese-English Subtitle Corpus by Reid Pryzant et al.\n"
        "(https://nlp.stanford.edu/projects/jesc/, CC BY-SA 4.0)")


def trained(origin, repo, commit, licence, data=TATOEBA, note=""):
    return ("Trained from %s,\nhttps://huggingface.co/%s\n(commit %s)%s\n\n" % (origin, repo, commit, note)
            + "Changes: trained further for Twitch chat and stream speech by PurpleTV ReVive\n"
            "(tools/train_marian.py), on chat style sentence pairs written for PurpleTV ReVive and on\n"
            + data + ".\n" + licence + CONVERTED)


def opus(pair, commit, licence=CC_BY, data=TATOEBA):
    return trained("Opus-MT %s %s" % (pair, HELSINKI), "Helsinki-NLP/opus-mt-" + pair, commit, licence, data)


MODELS_3 = {
    "es-en-chat": ("es-en-chat", opus("es-en", "c96e2c5399ebfae4fc43d9669556b9afa74bb69d")),
    "en-es-chat": ("en-es-chat", opus("en-es", "5bc4493d463cf000c1f0b50f8d56886a392ed4ab")),
    "de-en-chat": ("de-en-chat", opus("de-en", "1a922f3b32a8e809e17a47d4b32142d8105924e5")),
    "en-de-chat": ("en-de-chat", opus("en-de", "6183067f769a302e3861815543b9f312c71b0ca4")),
    "fr-en-chat": ("fr-en-chat", opus("fr-en", "c4aed37b318c763fd177aa449b44e3b783cc6c02")),
    "en-fr-chat": ("en-fr-chat", opus("en-fr", "dd7f6540a7a48a7f4db59e5c0b9c42c8eea67f18")),
    "it-en-chat": ("it-en-chat", opus("it-en", "42556a0848fc726f4d27399f20b19ff6f01afe11")),
    "en-it-chat": ("en-it-chat", opus("en-it", "be5a254d936f1a8c8da080406ca582a6615e6658")),
    # Portuguese: the Romance language models, trained further on Portuguese (Brazilian) only.
    "pt-en-chat": ("pt-en-chat", opus("ROMANCE-en", "e9ca9975e3972afd80732f08ce01d3a1339f47f8")),
    "en-pt-chat": ("en-pt-chat", opus("en-ROMANCE", "f8f3a28e8b6272d0ccc0290b832f699e154ae431")),
    "ru-en-chat": ("ru-en-chat", opus("ru-en", "fbd6dc73284f95536648512cc21d57f19191961a")),
    "en-ru-chat": ("en-ru-chat", opus("en-ru", "bb09c99d180016eac6819df3dae68edb1690fdee")),
    "zh-en-chat": ("zh-en-chat", opus("zh-en", "cf109095479db38d6df799875e34039d4938aaa6")),
    "en-zh-chat": ("en-zh-chat", opus("en-zh", "408d9bc410a388e1d9aef112a2daba955b945255")),
    "ar-en-chat": ("ar-en-chat", opus("ar-en", "c5b2a50db78d6be98ae207223f8d4f63bc7a0ff1")),
    "en-ar-chat": ("en-ar-chat", opus("en-ar", "03087980e8ce753d64b3248ed0a912444545b840")),
    # Japanese also learned from JESC (CC BY-SA 4.0); both ways are shared under CC-BY-SA 4.0.
    "ja-en-chat": ("ja-en", opus("ja-en", "0770961a39ba6bd66305b149c3f4110bcafca2e6", CC_BY_SA, TATOEBA + " and on\n" + JESC)),
    "en-ja-chat": ("en-ja", trained("FuguMT en-ja by Satoshi Takahashi (staka)", "staka/fugumt-en-ja",
                                    "fbb91252110cf97d90291eb808629ab0b10f7928", CC_BY_SA, TATOEBA + " and on\n" + JESC,
                                    ", itself under CC-BY-SA 4.0")),
    "tl-en-chat": ("tl-en-chat", opus("tl-en", "8d2dee91f8b3fa48dda8ec06ee2af8f24970dcbc")),
    "en-tl-chat": ("en-tl-chat", opus("en-tl", "e46e1761492cb6a6fb9515a72bb55ca654815ca5")),
    "lt-en-chat": ("lt-en-chat", trained("the Tatoeba Challenge lit-eng model %s" % HELSINKI, "Neurora/opus-tatoeba-lit-eng",
                                         "28d29e069eaff3db3fd14fc7da7ca7d2904c4204", CC_BY,
                                         note="; that copy is marked Apache-2.0")),
    "en-lt-chat": ("en-lt-chat", trained("the Tatoeba Challenge eng-lit model (with back-translation)\n%s" % HELSINKI,
                                         "Neurora/opus-tatoeba-eng-lit-bt", "d7b351a8e28921e8a27f0aa116d127409f00ce89", CC_BY,
                                         note="; that copy is marked Apache-2.0")),
}

# Files taken from each model repo, and the name each gets inside the zip.
MODEL_FILES = [
    ("onnx/encoder_model_quantized.onnx", "encoder_model_quantized.onnx"),
    ("onnx/decoder_model_merged_quantized.onnx", "decoder_model_merged_quantized.onnx"),
    ("source.spm", "source.spm"),
    ("vocab.json", "vocab.json"),
    ("config.json", "config.json"),
    ("generation_config.json", "generation_config.json"),
]

# 1980-01-01, the earliest time a zip can hold: the same bytes on every rebuild.
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


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


def kotlin_entries(entries, indent):
    lines = []
    for name, data in entries:
        lines.append('%s"%s" to PackPins.FilePin(%dL, "%s")' % (indent, name, len(data), sha256_bytes(data)))
    return ",\n".join(lines)


def build_engine(out, cache):
    aar = fetch(ORT_AAR, os.path.join(cache, "onnxruntime-android-%s.aar" % ORT_VERSION))
    lic = fetch(ORT_RAW + "LICENSE", os.path.join(cache, "ort-LICENSE.txt"))
    tpn = fetch(ORT_RAW + "ThirdPartyNotices.txt", os.path.join(cache, "ort-ThirdPartyNotices.txt"))
    with zipfile.ZipFile(aar) as z:
        ort = z.read("jni/arm64-v8a/libonnxruntime.so")
        jni = z.read("jni/arm64-v8a/libonnxruntime4j_jni.so")
    readme = (
        "PurpleTV ReVive offline translation engine\n\n"
        "ONNX Runtime %s for Android (arm64-v8a), taken unchanged from the official AAR\n"
        "com.microsoft.onnxruntime:onnxruntime-android:%s on Maven Central:\n  %s\n\n"
        "ONNX Runtime is MIT licensed (LICENSE-onnxruntime.txt); its third party notices are in\n"
        "ThirdPartyNotices-onnxruntime.txt. Source: https://github.com/microsoft/onnxruntime/tree/v%s\n"
        % (ORT_VERSION, ORT_VERSION, ORT_AAR, ORT_VERSION)
    ).encode()
    entries = [
        ("lib/arm64-v8a/libonnxruntime.so", ort),
        ("lib/arm64-v8a/libonnxruntime4j_jni.so", jni),
        ("LICENSE-onnxruntime.txt", open(lic, "rb").read()),
        ("ThirdPartyNotices-onnxruntime.txt", open(tpn, "rb").read()),
        ("README.txt", readme),
    ]
    name = "purpletv-translate-engine-ort-%s-arm64.zip" % ORT_VERSION
    size, sha = write_zip(os.path.join(out, name), entries)
    return name, size, sha, entries


def desktop_natives(out, cache):
    """linux-x64 ORT natives of the same version, for the JVM unit tests only. Never packed."""
    jar = fetch(ORT_JAR, os.path.join(cache, "onnxruntime-%s.jar" % ORT_VERSION))
    dst = os.path.join(out, "desktop-ort")
    os.makedirs(dst, exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        for lib in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
            with open(os.path.join(dst, lib), "wb") as f:
                f.write(z.read("ai/onnxruntime/native/linux-x64/" + lib))


def build_model(out, cache, name, repo, commit):
    entries = []
    unpacked = os.path.join(out, "models", name)
    os.makedirs(unpacked, exist_ok=True)
    for src, dst in MODEL_FILES:
        p = fetch(HF + repo + "/resolve/" + commit + "/" + src,
                  os.path.join(cache, "hf", repo.replace("/", "__"), commit, src))
        data = open(p, "rb").read()
        entries.append((dst, data))
        with open(os.path.join(unpacked, dst), "wb") as f:
            f.write(data)
    base = repo.split("/")[1].replace("opus-mt-", "")
    licence = (
        "Opus-MT %s by the Language Technology Research Group at the University of Helsinki\n"
        "(Helsinki-NLP), https://huggingface.co/Helsinki-NLP/opus-mt-%s\n"
        "Licence: CC-BY 4.0, https://creativecommons.org/licenses/by/4.0/\n"
        "ONNX conversion by Xenova: https://huggingface.co/%s (commit %s)\n"
        % (base, base, repo, commit)
    ).encode()
    readme = (
        "PurpleTV ReVive offline translation model %s\n\n"
        "The files are taken unchanged from https://huggingface.co/%s at commit %s:\n%s\n"
        "See LICENSE.txt for the licence and the credit it asks for.\n"
        % (name, repo, commit, "".join("  %s -> %s\n" % (s, d) for s, d in MODEL_FILES))
    ).encode()
    entries.append(("LICENSE.txt", licence))
    entries.append(("README.txt", readme))
    zname = "purpletv-translate-model-opus-mt-%s.zip" % name
    size, sha = write_zip(os.path.join(out, zname), entries)
    return zname, size, sha, entries, commit


def build_local_model(out, src, name, folder, licence):
    """A translate-pack-3 model from a local folder of converted files."""
    d = os.path.join(src, folder)
    entries = []
    unpacked = os.path.join(out, "models", name)
    os.makedirs(unpacked, exist_ok=True)
    for _, dst in MODEL_FILES:
        data = open(os.path.join(d, dst), "rb").read()
        entries.append((dst, data))
        with open(os.path.join(unpacked, dst), "wb") as f:
            f.write(data)
    readme = (
        "PurpleTV ReVive offline translation model %s\n\n"
        "A MarianMT model converted to ONNX for PurpleTV ReVive's offline translator. Where it comes\n"
        "from and its licence are in LICENSE.txt. Files:\n%s"
        % (name, "".join("  %s   %d bytes, SHA-256 %s\n" % (n, len(b), sha256_bytes(b)) for n, b in entries))
    ).encode()
    entries.append(("LICENSE.txt", licence.encode()))
    entries.append(("README.txt", readme))
    zname = "purpletv-translate-model-%s.zip" % name
    size, sha = write_zip(os.path.join(out, zname), entries)
    return zname, size, sha, entries


def main():
    args = sys.argv[1:]
    if not args or args[0].startswith("-"):
        raise SystemExit(__doc__)
    out = os.path.abspath(args[0])
    only = None
    release = PACK_1
    src = None
    i = 1
    while i < len(args):
        if args[i] == "--models" and i + 1 < len(args):
            only = args[i + 1].split(",")
        elif args[i] == "--release" and i + 1 < len(args):
            release = args[i + 1]
        elif args[i] == "--from" and i + 1 < len(args):
            src = args[i + 1]
        else:
            raise SystemExit(__doc__)
        i += 2
    if release not in (PACK_1, PACK_2, PACK_3):
        raise SystemExit("unknown release " + release)
    if release == PACK_3:
        if not src:
            raise SystemExit("translate-pack-3 needs --from FOLDER with the converted models")
        models = []
        for m, (folder, licence) in MODELS_3.items():
            if only and m not in only:
                continue
            zname, size, sha, entries = build_local_model(out, src, m, folder, licence)
            print("%s %d %s" % (zname, size, sha))
            models.append("        // %s, from %s\n        ModelPin(\"%s\", \"%s\", \"%s\", %dL, mapOf(\n%s\n        ), tag = TAG_3)"
                          % (m, folder, m, zname, sha, size, kotlin_entries(entries, "            ")))
        text = "        // " + release + "\n" + ",\n".join(models) + "\n"
        with open(os.path.join(out, "pins-%s.txt" % release), "w") as f:
            f.write(text)
        print(text)
        return
    cache = os.path.join(out, "cache")
    os.makedirs(cache, exist_ok=True)
    pins = []
    models = []
    if release == PACK_1:
        name, size, sha, entries = build_engine(out, cache)
        print("%s %d %s" % (name, size, sha))
        pins.append("    // Engine: %s\n    const val ENGINE_ASSET = \"%s\"\n    const val ENGINE_SHA256 = \"%s\"\n"
                    "    const val ENGINE_BYTES: Long = %dL\n    val ENGINE_ENTRIES: Map<String, PackPins.FilePin> = mapOf(\n%s\n    )\n"
                    % (name, name, sha, size, kotlin_entries(entries, "        ")))
    desktop_natives(out, cache)
    for m, (repo, commit, rel) in MODELS.items():
        if rel != release or (only and m not in only):
            continue
        zname, size, sha, entries, commit = build_model(out, cache, m, repo, commit)
        print("%s %d %s" % (zname, size, sha))
        # translate-pack-1's pins take the default tag; later releases name theirs.
        tag = "" if rel == PACK_1 else ", tag = TAG_2"
        models.append("        // %s, %s at %s\n        ModelPin(\"%s\", \"%s\", \"%s\", %dL, mapOf(\n%s\n        )%s)"
                      % (m, repo, commit, m, zname, sha, size, kotlin_entries(entries, "            "), tag))
    # The engine constants (translate-pack-1 only), then the model pins for TranslatePins.MODELS.
    text = "\n".join(pins) + "\n        // " + release + "\n" + ",\n".join(models) + "\n"
    pins_name = "pins.txt" if release == PACK_1 else "pins-%s.txt" % release
    with open(os.path.join(out, pins_name), "w") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    main()
