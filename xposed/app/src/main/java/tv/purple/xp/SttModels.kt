package tv.purple.xp

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

/**
 * One speech model for speech-to-text captions, one per stream language, described as plain data:
 * where it is downloaded from, its pins, and how sherpa-onnx must be set up for it. The recognizer
 * config is built from this data ([SttModels.modelConfig]), so a model of another kind needs a new
 * entry here, not new code in Stt.
 *
 * Pins work as in [PackPins]: the zip's exact size and SHA-256, and every entry's, all checked
 * before anything is used. A changed model means a new tag and new pins, never a replaced asset.
 */
class SttModel(
    /** The stream language, a two letter code that Translator understands as a source language. */
    val lang: String,
    /** The language's name for the settings page. */
    val label: String,
    /** The GitHub release tag that holds the zip. */
    val tag: String,
    val asset: String,
    val sha256: String,
    val bytes: Long,
    val kind: Kind,
    /** OnlineModelConfig.modelType; empty lets sherpa-onnx read it from the model's own metadata. */
    val modelType: String,
    /** Short licence name for the settings page, like "CC-BY-SA" or "Apache-2.0". */
    val licence: String,
    /** The credit line the licence asks for. */
    val credit: String,
    /** Role (encoder, decoder, joiner, model, tokens) to file name inside the zip. */
    val files: Map<String, String>,
    /** Every entry of the zip by its name inside the zip. This is the unpack allow-list. */
    val entries: Map<String, PackPins.FilePin>,
    /** Folder under ptv_stt. English keeps "model", where captions-pack-1 always put it. */
    val dir: String = "model-$lang"
) {
    /** The shapes of streaming model sherpa-onnx 1.13.8 can run that are in use here. */
    enum class Kind(val roles: List<String>) {
        /** Encoder, decoder and joiner: Zipformer and Zipformer2 transducers. */
        TRANSDUCER(listOf("encoder", "decoder", "joiner", "tokens")),
        /** One model file with a CTC head: Zipformer2 CTC. */
        ZIPFORMER2_CTC(listOf("model", "tokens"))
    }

    val unpackedBytes: Long get() = entries.values.sumOf { it.bytes }
}

object SttModels {

    /**
     * The stream language for speech-to-text, stored as a number from [ALL] (its place there never
     * changes; new languages go at the end). English (0) by default, so nothing changes for anyone
     * who never opens the setting.
     */
    const val KEY = "stt_lang"
    const val DEF = 0

    /** The pre-release with the speech models for languages other than English. */
    const val TAG2 = "captions-pack-2"
    const val RELEASE_PAGE2 = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG2

    /** English: the captions-pack-1 model, read from PackPins, which never changes. */
    val EN = SttModel(
        lang = "en", label = "English", tag = PackPins.TAG,
        asset = PackPins.MODEL_ASSET,
        sha256 = PackPins.MODEL_SHA256, bytes = PackPins.MODEL_BYTES,
        kind = SttModel.Kind.TRANSDUCER, modelType = PackPins.MODEL_TYPE,
        licence = "CC-BY-SA",
        credit = PackPins.MODEL_CREDIT,
        files = PackPins.MODEL_FILES,
        entries = PackPins.MODEL_ENTRIES,
        dir = "model"
    )

    /** captions-pack-2, built by tools/make_captions_pack.py, which prints these pins. */
    val PACK2: List<SttModel> = listOf(
        // Spanish: csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06 at 20cf7a4921613397841d31168796cade5b866585
        SttModel(
            lang = "es", label = "Spanish", tag = TAG2,
            asset = "purpletv-captions-model-kroko-es-2025-08-06.zip",
            sha256 = "ed2caee0bd2c93ea7af0f7040b8e7731c9ab958a7eb7e606bb8ef367cd45d1bc", bytes = 119567719L,
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2",
            licence = "CC-BY-SA",
            credit = "Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA, converted for sherpa-onnx by csukuangfj",
            files = mapOf(
                "encoder" to "encoder.onnx",
                "decoder" to "decoder.onnx",
                "joiner" to "joiner.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "encoder.onnx" to PackPins.FilePin(154878102L, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b"),
                "decoder.onnx" to PackPins.FilePin(617488L, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee"),
                "joiner.onnx" to PackPins.FilePin(336817L, "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5"),
                "tokens.txt" to PackPins.FilePin(6385L, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f"),
                "LICENSE.txt" to PackPins.FilePin(1360L, "73a469323193050d8e7b0ced47600d705c962af4d7ab4f334600ae81bd969fe7"),
                "README.txt" to PackPins.FilePin(2112L, "ad5a8a61729e66158477ae1c9456760e466391bbb2cd5d52bd7ccc653b330d43")
            )
        ),
        // German: csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06 at 887db3d083240198c2d2b99fb66cfcfe6948ced8
        SttModel(
            lang = "de", label = "German", tag = TAG2,
            asset = "purpletv-captions-model-kroko-de-2025-08-06.zip",
            sha256 = "231aa4b03a960a30b8c3f9bf2ffd59febeed84c74fa79b10e8d0412ac0f351ba", bytes = 55183600L,
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2",
            licence = "CC-BY-SA",
            credit = "Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA, converted for sherpa-onnx by csukuangfj",
            files = mapOf(
                "encoder" to "encoder.onnx",
                "decoder" to "decoder.onnx",
                "joiner" to "joiner.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "encoder.onnx" to PackPins.FilePin(70091557L, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5"),
                "decoder.onnx" to PackPins.FilePin(617489L, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562"),
                "joiner.onnx" to PackPins.FilePin(336817L, "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a"),
                "tokens.txt" to PackPins.FilePin(5606L, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb"),
                "LICENSE.txt" to PackPins.FilePin(1360L, "505fc3535d529e99707c34f283d1b07eff809bd0f9697f43e05ec4f76e6e7295"),
                "README.txt" to PackPins.FilePin(2058L, "49ef0e9cb161d6a6eb5cc3209fc77e19b9f6c524d04e0fdba9ec6f3a0976ab4c")
            )
        ),
        // French: csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06 at 08b84b7b7cf519be9817e9c16919d96a7a8bad91
        SttModel(
            lang = "fr", label = "French", tag = TAG2,
            asset = "purpletv-captions-model-kroko-fr-2025-08-06.zip",
            sha256 = "66364ed1bdd77cf7dd3c5f1c3f8bb5b4f123cb0be7b27dc4c4797ab5962cab6b", bytes = 54785201L,
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2",
            licence = "CC-BY-SA",
            credit = "Kroko ASR by Banafo (https://huggingface.co/Banafo/Kroko-ASR), CC-BY-SA, converted for sherpa-onnx by csukuangfj",
            files = mapOf(
                "encoder" to "encoder.onnx",
                "decoder" to "decoder.onnx",
                "joiner" to "joiner.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "encoder.onnx" to PackPins.FilePin(70092599L, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b"),
                "decoder.onnx" to PackPins.FilePin(617488L, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac"),
                "joiner.onnx" to PackPins.FilePin(336817L, "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6"),
                "tokens.txt" to PackPins.FilePin(5415L, "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298"),
                "LICENSE.txt" to PackPins.FilePin(1360L, "2b7c7dae202db558c68fecb7779c47e0dff73f66d48f0af634801ad5eeb964bc"),
                "README.txt" to PackPins.FilePin(2058L, "c315d88bcfe09a184b3ea029d4b7bf04f376df3a19ab462b0ebdce59c6e4ac00")
            )
        ),
        // Russian: csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16 at 31fa603e4f31279c6e1f7600fed13dc4312663ab
        SttModel(
            lang = "ru", label = "Russian", tag = TAG2,
            asset = "purpletv-captions-model-vosk-small-ru-2025-08-16.zip",
            sha256 = "69ce81464ed019236f52976af9173726471a12351568cc2d363fd777c486d843", bytes = 22835595L,
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2",
            licence = "Apache-2.0",
            credit = "Vosk small streaming Russian model by Alpha Cephei (https://huggingface.co/alphacep/vosk-model-small-streaming-ru), Apache-2.0, converted for sherpa-onnx by csukuangfj",
            files = mapOf(
                "encoder" to "encoder.int8.onnx",
                "decoder" to "decoder.onnx",
                "joiner" to "joiner.int8.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "encoder.int8.onnx" to PackPins.FilePin(26214060L, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
                "decoder.onnx" to PackPins.FilePin(2093080L, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
                "joiner.int8.onnx" to PackPins.FilePin(259417L, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
                "tokens.txt" to PackPins.FilePin(6388L, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
                "LICENSE.txt" to PackPins.FilePin(1105L, "999005b1af77736e8dbfa8473ebed53c56905a7a6d2c9e6bd150c5d77631e8be"),
                "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
                "README.txt" to PackPins.FilePin(2212L, "894ff7910c39435eb7e6f207cd50133cecdf77f0d9a5d8ca7a35f040685bf0a6")
            )
        ),
        // Korean: k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16 at ba6078bca4daf3f0dd37f79d0ab505af71df14a6
        SttModel(
            lang = "ko", label = "Korean", tag = TAG2,
            asset = "purpletv-captions-model-zipformer-ko-2024-06-16.zip",
            sha256 = "992ccf34a4c2eea7534f85e2dafe9d63f468f3b84e314bb5f35eeb0654ed585e", bytes = 123487570L,
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer",
            licence = "Apache-2.0",
            credit = "Korean streaming Zipformer by johnBamma (https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12), Apache-2.0, converted for sherpa-onnx by k2-fsa",
            files = mapOf(
                "encoder" to "encoder-epoch-99-avg-1.int8.onnx",
                "decoder" to "decoder-epoch-99-avg-1.onnx",
                "joiner" to "joiner-epoch-99-avg-1.int8.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "encoder-epoch-99-avg-1.int8.onnx" to PackPins.FilePin(126968852L, "8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697"),
                "decoder-epoch-99-avg-1.onnx" to PackPins.FilePin(11309084L, "b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc"),
                "joiner-epoch-99-avg-1.int8.onnx" to PackPins.FilePin(2581421L, "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced"),
                "tokens.txt" to PackPins.FilePin(60246L, "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668"),
                "LICENSE.txt" to PackPins.FilePin(1209L, "ac8fc53d319e220dcc1d26a25ef032a09dcdb8c8a29054970641a6edfd110bc4"),
                "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
                "README.txt" to PackPins.FilePin(2319L, "2fb49118f1bd974a89f22da856bf8abffa321666f369a32b863bec8c9abe0b2c")
            )
        ),
        // Chinese: csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01 at a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da
        SttModel(
            lang = "zh", label = "Chinese", tag = TAG2,
            asset = "purpletv-captions-model-zipformer-ctc-zh-small-2025-04-01.zip",
            sha256 = "bc3f5bd113f6269319b91e76adb8ab7779f0877ebfcfde6a224e79fcb2bd758d", bytes = 20009466L,
            kind = SttModel.Kind.ZIPFORMER2_CTC, modelType = "",
            licence = "Apache-2.0",
            credit = "Small streaming Zipformer CTC Chinese model by k2-fsa (csukuangfj) (https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01), Apache-2.0",
            files = mapOf(
                "model" to "model.int8.onnx",
                "tokens" to "tokens.txt"
            ),
            entries = mapOf(
                "model.int8.onnx" to PackPins.FilePin(26342340L, "68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a"),
                "tokens.txt" to PackPins.FilePin(13366L, "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd"),
                "LICENSE.txt" to PackPins.FilePin(1093L, "fa249ef2b7d2e6e96df2ebac080076a27dcb6472f2c7c46cfd85f648b58c5c70"),
                "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
                "README.txt" to PackPins.FilePin(1968L, "fbb2b417506788810545deea4c1d33a2e6ab1eafa8a3d44658580681a3ae94e3")
            )
        )
    )

    /** Every language in the order the settings page lists them; the stored number is the place here. */
    val ALL: List<SttModel> = listOf(EN) + PACK2

    fun labels(): List<String> = ALL.map { it.label }

    fun byLang(lang: String): SttModel? = ALL.firstOrNull { it.lang == lang }

    /** The model for a stored setting value. Anything unknown (a newer version's choice synced over) is English. */
    fun forValue(v: Int): SttModel = ALL.getOrNull(v) ?: EN

    /** The model for the stream language picked in settings. */
    fun chosen(): SttModel = if (Settings.isReady()) forValue(Settings.getInt(KEY, DEF)) else EN

    /**
     * sherpa-onnx's model config for [m] unpacked in [dir]. Only the part that differs per model is
     * here; features, endpoints and decoding are the same for every model (see Stt.load).
     */
    fun modelConfig(m: SttModel, dir: File, threads: Int): OnlineModelConfig {
        fun path(role: String) = File(dir, m.files[role] ?: error("no $role in the ${m.lang} model pins")).absolutePath
        val base = OnlineModelConfig(
            tokens = path("tokens"),
            numThreads = threads,
            provider = "cpu",
            modelType = m.modelType
        )
        return when (m.kind) {
            SttModel.Kind.TRANSDUCER -> base.copy(transducer = OnlineTransducerModelConfig(
                encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner")))
            SttModel.Kind.ZIPFORMER2_CTC -> base.copy(zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = path("model")))
        }
    }
}
