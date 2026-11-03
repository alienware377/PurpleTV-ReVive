#!/usr/bin/env python3
"""Builds the offline translation packs for the GitHub pre-releases translate-pack-1 and -2.

Run it with python3 -I (no site packages are needed, only the standard library):

    python3 -I tools/make_translate_pack.py OUT_DIR [--release translate-pack-2] [--models es-en,de-en]

Each release is built on its own and never changes once published:
  - translate-pack-1 (the default): the engine, the multilingual into English model (mul-en) and
    the English into X models;
  - translate-pack-2: one into English model per chat language (es-en, de-en and so on), which
    translate much better than mul-en. It has no engine zip; the engine stays in translate-pack-1.

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


def main():
    args = sys.argv[1:]
    if not args or args[0].startswith("-"):
        raise SystemExit(__doc__)
    out = os.path.abspath(args[0])
    only = None
    release = PACK_1
    i = 1
    while i < len(args):
        if args[i] == "--models" and i + 1 < len(args):
            only = args[i + 1].split(",")
        elif args[i] == "--release" and i + 1 < len(args):
            release = args[i + 1]
        else:
            raise SystemExit(__doc__)
        i += 2
    if release not in (PACK_1, PACK_2):
        raise SystemExit("unknown release " + release)
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
