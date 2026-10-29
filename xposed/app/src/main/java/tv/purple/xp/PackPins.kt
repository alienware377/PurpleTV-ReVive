package tv.purple.xp

/**
 * Pins for the two optional live caption downloads (speech to text). Both are assets of the
 * separate GitHub pre-release [TAG] on PurpleTV-ReVive, so they never show up as the module's
 * "latest" release, and each one is downloaded, checked and stored on its own:
 *  - the engine pack: the sherpa-onnx JNI library (native code, arm64 only);
 *  - the model pack: the Kroko English streaming speech model.
 *
 * Why everything is pinned: the engine is native code that runs inside Twitch's process, so a
 * changed or truncated file must never be loaded. The app checks the zip's exact size and SHA-256
 * while downloading, unpacks only the entries listed here (and never more bytes than pinned for
 * each), checks every unpacked file's SHA-256, and checks the library again right before
 * System.load. A new engine or model means a new tag and new pins, never a replaced asset.
 *
 * The vendored Kotlin API in com.k2fsa.sherpa.onnx comes from the same sherpa-onnx tag as the
 * library ([SHERPA_VERSION]). They must match, because the native side looks up those Kotlin
 * classes and fields by name.
 */
object PackPins {

    /** Size and SHA-256 (lower case hex) of one file inside a pack zip. */
    class FilePin(val bytes: Long, val sha256: String)

    /** The GitHub release tag that holds both packs. */
    const val TAG = "captions-pack-1"
    /** Asset download address: BASE_URL + asset name. */
    const val BASE_URL = "https://github.com/alienware377/PurpleTV-ReVive/releases/download/" + TAG + "/"
    /**
     * The release page itself. It carries the full licence texts and the source links. The copies
     * inside the packs land in Twitch's private storage, where the user can not open them.
     */
    const val RELEASE_PAGE = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG
    /** The model's licence. Banafo names no version; the release notes point to the current one. */
    const val MODEL_LICENCE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

    /** The sherpa-onnx release the engine library and the vendored Kotlin API both come from. */
    const val SHERPA_VERSION = "1.13.8"

    // Engine pack

    const val ENGINE_ASSET = "purpletv-captions-engine-1.13.8-arm64.zip"
    const val ENGINE_URL = BASE_URL + ENGINE_ASSET
    const val ENGINE_SHA256 = "901da4111c81c75b1ef326642fb90d0c019610082ce8bf5c08144577a0ec8cbf"
    const val ENGINE_BYTES: Long = 8963964L
    /** Space the engine needs once unpacked. */
    const val ENGINE_UNPACKED_BYTES: Long = 24552894L
    /** The only ABI the engine is built for. Both supported Twitch builds ship arm64-v8a only. */
    const val ENGINE_ABI = "arm64-v8a"
    /**
     * The one library to System.load. It is sherpa-onnx's "static link onnxruntime" build, so ONNX
     * Runtime is inside it and there is no libonnxruntime.so to load first. Its only needs are
     * Android system libraries (libc, libm, libdl, liblog, libandroid).
     */
    const val ENGINE_LIB = "lib/arm64-v8a/libsherpa-onnx-jni.so"
    /**
     * Every entry of the engine zip, by its name inside the zip. This is the unpack allow-list.
     * Note that the library's entry name has folders (lib/arm64-v8a/); the others sit at the top.
     */
    val ENGINE_ENTRIES: Map<String, FilePin> = mapOf(
        "lib/arm64-v8a/libsherpa-onnx-jni.so" to FilePin(24169352L, "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8"),
        "LICENSE-sherpa-onnx.txt" to FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        "LICENSE-onnxruntime.txt" to FilePin(1073L, "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
        "ThirdPartyNotices-onnxruntime.txt" to FilePin(325054L, "0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2"),
        "LICENSE-espeak-ng.txt" to FilePin(35147L, "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903"),
        "THIRD-PARTY.txt" to FilePin(8680L, "c50676d073fbd2a129399ced5d291bb0d6d5440f51debfc9aeb0bb3f27102257"),
        "README.txt" to FilePin(2230L, "66a90a754bc746f9d1a52ffed1c3694a4bb941177cb8e23da1fc615c256db288")
    )
    /**
     * Credit line for the settings page. The library also links in espeak-ng (GPL-3.0), which
     * sherpa-onnx uses for its own text to speech side, so that is named too.
     */
    const val ENGINE_CREDIT = "sherpa-onnx 1.13.8 by k2-fsa (Apache-2.0) with ONNX Runtime 1.28.2 (MIT); " +
        "includes espeak-ng (GPL-3.0). Licences and source: " + RELEASE_PAGE

    // Model pack

    const val MODEL_ASSET = "purpletv-captions-model-kroko-en-2025-08-06.zip"
    const val MODEL_URL = BASE_URL + MODEL_ASSET
    const val MODEL_SHA256 = "be9d7cb6e6e34703b8b15997c435a968b1d753e70770a9ca1eb5c69b8f52ad21"
    const val MODEL_BYTES: Long = 55728993L
    /** Space the model needs once unpacked. */
    const val MODEL_UNPACKED_BYTES: Long = 71056836L
    /** Role to file name inside the model zip, for OnlineTransducerModelConfig and tokens. */
    val MODEL_FILES: Map<String, String> = mapOf(
        "encoder" to "encoder.onnx",
        "decoder" to "decoder.onnx",
        "joiner" to "joiner.onnx",
        "tokens" to "tokens.txt"
    )
    /** Every entry of the model zip, by its name inside the zip. This is the unpack allow-list. */
    val MODEL_ENTRIES: Map<String, FilePin> = mapOf(
        "encoder.onnx" to FilePin(70092599L, "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4"),
        "decoder.onnx" to FilePin(617488L, "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1"),
        "joiner.onnx" to FilePin(336817L, "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52"),
        "tokens.txt" to FilePin(6310L, "396dbeb5f4858875690716084f54e90d339679d0ba3e6b5b584f3d7589254d2d"),
        "LICENSE.txt" to FilePin(1378L, "3094cc9b2f519df2996064929194ae37b1aba4476b11ed04d5b8c4a64b5870a6"),
        "README.txt" to FilePin(2244L, "5aa436b2fea82f29d3102927c5d78c0e6406bb48298652337817f65bf363a4e6")
    )
    /**
     * OnlineModelConfig.modelType for this model. The encoder's own metadata says the same, and
     * sherpa-onnx's own example config for this model sets it too, so auto detection is not needed.
     * sherpa-onnx's examples run it with FeatureConfig's defaults (16000 Hz, 80 bins), fed mono.
     */
    const val MODEL_TYPE = "zipformer2"
    /** Credit line the licence asks for (CC-BY-SA); show it wherever the model is offered. */
    const val MODEL_CREDIT = "Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA " +
        "(" + MODEL_LICENCE_URL + "), converted for sherpa-onnx by csukuangfj"
}
