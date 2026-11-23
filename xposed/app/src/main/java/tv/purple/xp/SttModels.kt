package tv.purple.xp

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
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
    /**
     * The stream language, a two letter code that Translator understands as a source language,
     * or [SttModels.AUTO] for a model that hears any language. A second model for a language
     * that already has one adds a suffix ("en-slang"); the translator only sees the code.
     */
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
    val dir: String = "model-$lang",
    /** What the download is called in the menu; languages that share one model share the name. */
    val what: String = "$label speech model",
    /** Which writing systems of its text are kept (see SpeechScript). */
    val script: SpeechScript.Mode = SpeechScript.Mode.ANY,
    /**
     * Whisper only: the language it is told to hear (see [SttModels.forced]). Empty lets it find
     * the language of each sentence itself, which it often gets wrong on stream sound.
     */
    val whisperLang: String = "",
    /**
     * The model writes capital letters. A model that writes none (the Russian one, the
     * many-language one) gets a capital at the start of each caption line.
     */
    val cased: Boolean = true,
    /**
     * The model's tokens are byte-level BPE: each byte of the text's UTF-8 is written as one of
     * 256 printable characters, so its token list does not read as text ("ƋŞĮ" for "不"). Stt reads
     * them back ([SpeechLines.fromByteBpe]) before the lines are cut.
     */
    val byteBpe: Boolean = false,
    /** Mel bins the model was trained on. Nearly all use 80; GigaAM uses 64. */
    val featDim: Int = 80,
    /** How a streaming model searches for its words. Whole sentence models stay on greedy. */
    val decoding: Decoding = Decoding.GREEDY
) {
    /**
     * Greedy takes the likeliest token each step. Modified beam search keeps [maxPaths] guesses
     * going, which is more accurate and slower, and can rewrite the last few tokens as it goes
     * (SpeechLines.Splitter copes with that). Greedy never reads [maxPaths], so it keeps
     * sherpa-onnx's default.
     */
    enum class Decoding(val method: String, val maxPaths: Int) {
        GREEDY("greedy_search", 4),
        BEAM4("modified_beam_search", 4)
    }

    /**
     * The shapes of model sherpa-onnx 1.13.8 can run that are in use here. Streaming models hear
     * the sound as it comes; whole sentence ones are given one sentence at a time, found with the
     * Silero VAD file ("vad") that comes in the same zip.
     */
    enum class Kind(val roles: List<String>, val offline: Boolean = false) {
        /** Encoder, decoder and joiner: Zipformer and Zipformer2 transducers. */
        TRANSDUCER(listOf("encoder", "decoder", "joiner", "tokens")),
        /** One model file with a CTC head: Zipformer2 CTC. */
        ZIPFORMER2_CTC(listOf("model", "tokens")),
        /** A whole sentence Zipformer transducer. */
        OFFLINE_TRANSDUCER(listOf("encoder", "decoder", "joiner", "tokens", "vad"), offline = true),
        /** Meta's Omnilingual ASR CTC model, one model for every language it knows. */
        OMNILINGUAL_CTC(listOf("model", "tokens", "vad"), offline = true),
        /** OpenAI's Whisper: about a hundred languages, and it names the one it heard. */
        WHISPER(listOf("encoder", "decoder", "tokens", "vad"), offline = true)
    }

    val unpackedBytes: Long get() = entries.values.sumOf { it.bytes }

    /** The language the translator starts from: null lets it guess from each line. */
    val sourceLang: String? get() = if (lang == SttModels.AUTO) whisperLang.ifEmpty { null } else lang.substringBefore('-')

    /**
     * Tells loaded models apart: the language, and for Whisper the language it was told. Whisper
     * holds that language in its recognizer, so another one needs a new recognizer.
     */
    val key: String get() = if (whisperLang.isEmpty()) lang else "$lang:$whisperLang"
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

    /** The pre-release with the whole sentence models: Japanese, and one model for many languages. */
    const val TAG3 = "captions-pack-3"
    const val RELEASE_PAGE3 = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG3

    /** The pre-release with Whisper base, the all-languages model behind Auto. */
    const val TAG4 = "captions-pack-4"
    const val RELEASE_PAGE4 = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG4

    /** The pre-release with Whisper base.en trained further on Twitch chat speech. */
    const val TAG5 = "captions-pack-5"
    const val RELEASE_PAGE5 = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG5

    /** The pre-release with GigaAM, the better Russian model. */
    const val TAG6 = "captions-pack-6"

    /** The pre-release with X-ASR, the better English model. */
    const val TAG7 = "captions-pack-7"

    /** How the English models search for their words: the one place to change it. */
    val EN_DECODING = SttModel.Decoding.BEAM4
    val EN_XASR_DECODING = SttModel.Decoding.BEAM4

    /**
     * Off: X-ASR never takes over English, even with its pack on the phone. On one recorded English
     * stream it wrote Chinese, which is worse than the words it gets right elsewhere.
     */
    const val EN_XASR_ON = false

    /**
     * The stream language that follows each stream: its language is found (see StreamLang) and that
     * language's own model is used. Its entry in [ALL] is Whisper base, the all-languages model,
     * which hears the start of a channel to check the language and covers languages without a model.
     */
    const val AUTO = "auto"

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
        dir = "model",
        decoding = EN_DECODING
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
            kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2", cased = false,
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
            kind = SttModel.Kind.ZIPFORMER2_CTC, modelType = "", byteBpe = true,
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

    // captions-pack-3, built by tools/make_captions_pack.py, which prints these pins.

    // Japanese: reazon-research/reazonspeech-k2-v2 at 291488c8151be24d7da4bf7af26e533fad96e407
    val JA = SttModel(
        lang = "ja", label = "Japanese", tag = TAG3,
        asset = "purpletv-captions-model-reazonspeech-k2-v2-ja.zip",
        sha256 = "7655db3b03d7685b887758a3173bdfb07aa8c2e45291e018c889e1e3d02bf26c", bytes = 139267071L,
        kind = SttModel.Kind.OFFLINE_TRANSDUCER, modelType = "",
        licence = "Apache-2.0",
        credit = "ReazonSpeech k2 v2 by Reazon Human Interaction Lab (https://huggingface.co/reazon-research/reazonspeech-k2-v2), Apache-2.0",
        files = mapOf(
            "encoder" to "encoder-epoch-99-avg-1.int8.onnx",
            "decoder" to "decoder-epoch-99-avg-1.onnx",
            "joiner" to "joiner-epoch-99-avg-1.int8.onnx",
            "tokens" to "tokens.txt",
            "vad" to "silero_vad.onnx"
        ),
        entries = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to PackPins.FilePin(154670139L, "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
            "decoder-epoch-99-avg-1.onnx" to PackPins.FilePin(11767836L, "58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124"),
            "joiner-epoch-99-avg-1.int8.onnx" to PackPins.FilePin(2696970L, "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"),
            "tokens.txt" to PackPins.FilePin(45754L, "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
            "silero_vad.onnx" to PackPins.FilePin(1807522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"),
            "LICENSE.txt" to PackPins.FilePin(1468L, "82763e41ad08d680e836979ae26153719d3e1f3aba05fd1b9f3cd69b06c46fb2"),
            "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
            "LICENSE-MIT-silero-vad.txt" to PackPins.FilePin(1075L, "2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b"),
            "README.txt" to PackPins.FilePin(2459L, "9ca6a8130352a7e4cd2a9a0bf71e74d69d482b788e4c3a3b546b49643d445ee4")
        )
    )

    private val MANY_ENTRIES = mapOf(
        "model.int8.onnx" to PackPins.FilePin(365352120L, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"),
        "tokens.txt" to PackPins.FilePin(86423L, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
        "silero_vad.onnx" to PackPins.FilePin(1807522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"),
        "LICENSE.txt" to PackPins.FilePin(1454L, "4932c4b7c43729dff4b96a60a722681393e4a2abc223719837f5d36477a3e91c"),
        "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        "LICENSE-omnilingual-asr.txt" to PackPins.FilePin(581L, "a70a523bafbb595c2844104feb313d204904dac91c3d186c05f22a10a71c7a94"),
        "LICENSE-MIT-silero-vad.txt" to PackPins.FilePin(1075L, "2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b"),
        "README.txt" to PackPins.FilePin(2333L, "3166c27dc117cd227b3b53027b7f38db7753a154ed8b77218eabe85d4cd83716")
    )

    /**
     * Many languages: csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12
     * at 6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094. The model is told no language, so Filipino,
     * Lithuanian and Auto share one zip and one folder; they differ in the scripts kept and the
     * language the translator starts from.
     */
    private fun many(lang: String, label: String, script: SpeechScript.Mode) = SttModel(
        lang = lang, label = label, tag = TAG3,
        asset = "purpletv-captions-model-omnilingual-asr-300m-ctc-int8-2025-11-12.zip",
        sha256 = "6cdbdb5b41672ef32b56a2bdf5cdd32201978ddedd4eea20d136ab0df5190b1b", bytes = 290520222L,
        kind = SttModel.Kind.OMNILINGUAL_CTC, modelType = "",
        licence = "Apache-2.0",
        credit = "Omnilingual ASR (omniASR_CTC_300M) by Meta (https://github.com/facebookresearch/omnilingual-asr), Apache-2.0, converted for sherpa-onnx by csukuangfj",
        files = mapOf(
            "model" to "model.int8.onnx",
            "tokens" to "tokens.txt",
            "vad" to "silero_vad.onnx"
        ),
        entries = MANY_ENTRIES,
        dir = "model-mul",
        what = "many-language speech model (Filipino and Lithuanian)",
        script = script,
        cased = false
    )

    val PACK3: List<SttModel> = listOf(
        JA,
        many("tl", "Filipino", SpeechScript.Mode.LATIN),
        many("lt", "Lithuanian", SpeechScript.Mode.LATIN),
        // Auto, the all-languages model: csukuangfj/sherpa-onnx-whisper-base at bb53ee204431c90d314c1cc08d28d23e5b7927cc
        SttModel(
            lang = AUTO, label = "Auto (any language)", tag = TAG4,
            asset = "purpletv-captions-model-whisper-base.zip",
            sha256 = "130a675acdd05aa54d51f9cad2f3a8a1c44982170bf2bf950538197969d09315", bytes = 95984094L,
            kind = SttModel.Kind.WHISPER, modelType = "",
            licence = "MIT",
            credit = "Whisper base by OpenAI (https://github.com/openai/whisper), MIT, converted for sherpa-onnx by csukuangfj",
            files = mapOf(
                "encoder" to "base-encoder.int8.onnx",
                "decoder" to "base-decoder.int8.onnx",
                "tokens" to "base-tokens.txt",
                "vad" to "silero_vad.onnx"
            ),
            entries = mapOf(
                "base-encoder.int8.onnx" to PackPins.FilePin(29120534L, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
                "base-decoder.int8.onnx" to PackPins.FilePin(130672026L, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
                "base-tokens.txt" to PackPins.FilePin(816730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
                "silero_vad.onnx" to PackPins.FilePin(1807522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"),
                "LICENSE.txt" to PackPins.FilePin(1324L, "cd4c085db78bbbb4d9cfbb37bf500c083da55b7e6caa9c910b857cd8f50ec781"),
                "LICENSE-MIT-whisper.txt" to PackPins.FilePin(1063L, "b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d"),
                "LICENSE-MIT-silero-vad.txt" to PackPins.FilePin(1075L, "2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b"),
                "README.txt" to PackPins.FilePin(2187L, "310b0397083cdb992da65dc1ae63b75b3435bcebe2404e3c290261eaa197e35d")
            ),
            what = "all-languages speech model (Whisper base)"
        )
    )

    /** captions-pack-5, built by tools/make_captions_pack.py from tools/train_whisper.py's model. */
    val PACK5: List<SttModel> = listOf(
        // English (Twitch slang): openai/whisper-base.en at 911407f4214e0e1d82085af863093ec0b66f9cd6
        SttModel(
            lang = "en-slang", label = "English (Twitch slang)", tag = TAG5,
            asset = "purpletv-captions-model-whisper-base-en-twitch.zip",
            sha256 = "d4319520c53b4db5ddf945424a737453e2db89292c965f18ece63312fc40d117", bytes = 136057903L,
            kind = SttModel.Kind.WHISPER, modelType = "",
            licence = "MIT",
            credit = "Whisper base.en by OpenAI (https://github.com/openai/whisper), MIT, trained further on Twitch chat speech for PurpleTV ReVive",
            files = mapOf(
                "encoder" to "base.en-encoder.int8.onnx",
                "decoder" to "base.en-decoder.int8.onnx",
                "tokens" to "base.en-tokens.txt",
                "vad" to "silero_vad.onnx"
            ),
            entries = mapOf(
                "base.en-encoder.int8.onnx" to PackPins.FilePin(29104806L, "c71b7277e8ca6b0d6126b937d6561c7cbde491faaa3d6f6c5973271fae2671b2"),
                "base.en-decoder.int8.onnx" to PackPins.FilePin(130656976L, "43e5e067a047c568f981d0d22096f2b06df0a971516f952e872f340df5fa2f66"),
                "base.en-tokens.txt" to PackPins.FilePin(885810L, "ccf876776dd2a896257e3427ff8627bd1d3538023617d507ebba654d57d36557"),
                "silero_vad.onnx" to PackPins.FilePin(1807522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"),
                "LICENSE.txt" to PackPins.FilePin(1830L, "5d182237b24879494bda853253cab2b328fdd04df256392e89325296e773435e"),
                "LICENSE-MIT-whisper.txt" to PackPins.FilePin(1063L, "b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d"),
                "LICENSE-MIT-silero-vad.txt" to PackPins.FilePin(1075L, "2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b"),
                "README.txt" to PackPins.FilePin(2519L, "fdcecc9a15a767dcf3f669e30256433137c28541b53d5a976b648efc22f4aed1")
            )
        )
    )

    /** Every language in the order the settings page lists them; the stored number is the place here. */
    val ALL: List<SttModel> = listOf(EN) + PACK2 + PACK3 + PACK5

    fun labels(): List<String> = ALL.map { it.label }

    fun byLang(lang: String): SttModel? = ALL.firstOrNull { it.lang == lang }

    /**
     * Russian, captions-pack-6: GigaAM v3 end-to-end, csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
     * at a6039be7cee829a9044a69ac0ebaf1c191217c97. It writes punctuation and capitals, which also
     * gives the translator whole sentences to work with. A whole sentence model on 64 mel bins.
     */
    val RU_GIGAAM = SttModel(
        lang = "ru-gigaam", label = "Russian", tag = TAG6,
        asset = "purpletv-captions-model-gigaam-v3-punct-ru-2025-12-16.zip",
        sha256 = "f2c20c8e85fb3ee16ae461db5291ebd1325583ca380e8f418e70aaea8d45c49c", bytes = 171595177L,
        kind = SttModel.Kind.OFFLINE_TRANSDUCER, modelType = "nemo_transducer",
        licence = "MIT",
        credit = "GigaAM v3 by Sber (https://github.com/salute-developers/GigaAM), MIT, converted for sherpa-onnx by csukuangfj",
        files = mapOf(
            "encoder" to "encoder.int8.onnx",
            "decoder" to "decoder.onnx",
            "joiner" to "joiner.onnx",
            "tokens" to "tokens.txt",
            "vad" to "silero_vad.onnx"
        ),
        entries = mapOf(
            "encoder.int8.onnx" to PackPins.FilePin(224570820L, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
            "decoder.onnx" to PackPins.FilePin(4600132L, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
            "joiner.onnx" to PackPins.FilePin(2712896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
            "tokens.txt" to PackPins.FilePin(13354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7"),
            "silero_vad.onnx" to PackPins.FilePin(1807522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"),
            "LICENSE.txt" to PackPins.FilePin(1370L, "baa68b4d00c1fba8a1ab03c6ca648caea298cf3945df650c7627ad8a508913ff"),
            "LICENSE-MIT-gigaam.txt" to PackPins.FilePin(1070L, "f00de6715714c7a63d08639cdbfaa40224eefc407302614bd19f1a8b98c875aa"),
            "LICENSE-MIT-silero-vad.txt" to PackPins.FilePin(1075L, "2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b"),
            "README.txt" to PackPins.FilePin(2333L, "2583a7b3d038c7d1fb93ddca281eaceff1e0525b3942cb3e280fe790d6afec03")
        ),
        featDim = 64
    )

    /**
     * English, captions-pack-7: X-ASR 480 ms streaming Zipformer, from the sherpa-onnx release
     * archive of Gilgamesh-J/X-ASR at 838297cd47fed858e6cf72eaf5a52f948a3edd73. It writes
     * punctuation and capitals. It is a Chinese and English model, so its text is kept to Latin
     * letters ([SttModel.script], see SpeechLines.withoutCjk).
     */
    val EN_XASR = SttModel(
        lang = "en-xasr", label = "English", tag = TAG7,
        asset = "purpletv-captions-model-x-asr-480ms-zh-en-punct-int8-2026-06-05.zip",
        sha256 = "5af09c91d2778ac341551f32514fc60740faa770009a3a76c715021d4acbe8aa", bytes = 131321676L,
        kind = SttModel.Kind.TRANSDUCER, modelType = "zipformer2",
        licence = "Apache-2.0",
        credit = "X-ASR by Gilgamesh-J (https://github.com/Gilgamesh-J/X-ASR), Apache-2.0, packaged for sherpa-onnx by k2-fsa",
        files = mapOf(
            "encoder" to "encoder.int8.onnx",
            "decoder" to "decoder.onnx",
            "joiner" to "joiner.int8.onnx",
            "tokens" to "tokens.txt"
        ),
        entries = mapOf(
            "encoder.int8.onnx" to PackPins.FilePin(155278641L, "908596dcc137a73b95be908ca55e88caa1b3dbbe8027c171615f4b0609c5eb1e"),
            "decoder.onnx" to PackPins.FilePin(11309084L, "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257"),
            "joiner.int8.onnx" to PackPins.FilePin(2581422L, "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3"),
            "tokens.txt" to PackPins.FilePin(58806L, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"),
            "LICENSE.txt" to PackPins.FilePin(1093L, "8d03aac8ca6afe742e633311fc07854f591ecdd126a1a4e515f5d392b649b72b"),
            "LICENSE-Apache-2.0.txt" to PackPins.FilePin(11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
            "LICENSE-x-asr.txt" to PackPins.FilePin(11357L, "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
            "README.txt" to PackPins.FilePin(2303L, "0131e881b72e82d01ed8a6bf5cf034761964439c39e8cce2e361095c909da424")
        ),
        script = SpeechScript.Mode.LATIN,
        decoding = EN_XASR_DECODING
    )

    /**
     * Better models that take over from a language's model once they are on the phone. They are
     * not in [ALL]: they add no row to the language list and no download, so a phone without one
     * goes on with the model it has, and nothing offers a file that is not released.
     */
    val UPGRADES: Map<String, SttModel> = mapOf("ru" to RU_GIGAAM, "en" to EN_XASR)

    /**
     * [m], or the model that takes over from it when that one is downloaded ([ready]) and has not
     * failed to load in this run ([bad]). A failed load falls back to [m] until Twitch restarts.
     */
    fun upgraded(m: SttModel, bad: (SttModel) -> Boolean, ready: (SttModel) -> Boolean): SttModel {
        val up = takesOver(m.lang) ?: return m
        return if (!bad(up) && ready(up)) up else m
    }

    /** The model that may take over from [lang]'s now. [UPGRADES] also lists packs that are switched off. */
    private fun takesOver(lang: String): SttModel? =
        UPGRADES[lang]?.takeIf { it !== EN_XASR || EN_XASR_ON }

    /** The same for the phone as it is now. Only a language with an upgrade looks at the disk. */
    private fun upgradedNow(m: SttModel): SttModel {
        if (takesOver(m.lang) == null) return m
        val ctx = Settings.ctx() ?: return m
        return upgraded(m, { Stt.bad(it.lang) }) { SttPacks.ready(ctx, SttPacks.modelPack(it)) }
    }

    /** The model for a stored setting value. Anything unknown (a newer version's choice synced over) is English. */
    fun forValue(v: Int): SttModel = ALL.getOrNull(v) ?: EN

    /**
     * Test aid (DevBridge, do=force_lang): a stream language for this run of Twitch only. The
     * stored setting syncs to other devices, so a test must not change it.
     */
    @Volatile var devLang: String? = null

    /** The stream language picked in settings: a language's model, or the Auto entry. */
    fun picked(): SttModel = devLang?.let { byLang(it) }
        ?: if (Settings.isReady()) forValue(Settings.getInt(KEY, DEF)) else EN

    /** True when the stream language picked is Auto. */
    fun auto(): Boolean = picked().lang == AUTO

    /**
     * The model to hear the stream with now: the language picked, or with Auto the model for the
     * language of the channel being watched (see StreamLang).
     */
    fun chosen(): SttModel {
        val p = picked()
        return if (p.lang == AUTO) StreamLang.model() else upgradedNow(p)
    }

    /** Whisper base, the all-languages model: Auto's own entry. */
    val WHISPER: SttModel = byLang(AUTO)!!

    /**
     * The languages Whisper base knows, read from the model's own list (all_language_codes). Told
     * any other language, sherpa-onnx ends the whole process at its first sentence, so Whisper is
     * only ever told one of these.
     */
    val WHISPER_LANGS: Set<String> = ("af am ar as az ba be bg bn bo br bs ca cs cy da de el en es et eu fa fi fo fr gl gu " +
        "ha haw he hi hr ht hu hy id is it ja jw ka kk km kn ko la lb ln lo lt lv mg mi mk ml mn mr ms mt my ne nl nn no " +
        "oc pa pl ps pt ro ru sa sd si sk sl sn so sq sr su sv sw ta te tg th tk tl tr tt uk ur uz vi yi yo zh").split(' ').toSet()

    /** The models made for [lang] alone, best first (English before English (Twitch slang)). */
    fun dedicated(lang: String): List<SttModel> = ALL.filter { it.lang != AUTO && it.lang.substringBefore('-') == lang }

    private val forcedModels = java.util.concurrent.ConcurrentHashMap<String, SttModel>()

    /**
     * Whisper told to hear [lang] only: the same download and folder as [WHISPER]. Null when
     * Whisper does not know [lang].
     */
    fun forced(lang: String): SttModel? {
        if (lang !in WHISPER_LANGS) return null
        return forcedModels.getOrPut(lang) {
            val w = WHISPER
            SttModel(w.lang, w.label, w.tag, w.asset, w.sha256, w.bytes, w.kind, w.modelType, w.licence, w.credit,
                w.files, w.entries, w.dir, w.what, w.script, whisperLang = lang, cased = w.cased, byteBpe = w.byteBpe)
        }
    }

    /**
     * What Auto uses for a stream in [lang]. [missing] is the language's own model when it has one
     * that is not downloaded, so the caption box and the menu can say so.
     */
    class AutoPick(val model: SttModel, val lang: String?, val missing: SttModel?)

    /**
     * Auto's model for a stream in [lang] (null: not known), [ready] saying which downloads are on
     * the phone and [bad] which models failed to load in this run:
     *  - a model that takes over from the language's own (see [UPGRADES]) when it is downloaded;
     *  - the language's own model when it is downloaded (English before English (Twitch slang));
     *  - its own model is not downloaded: Whisper told the language when Whisper is, else that model
     *    (the caption box then says it needs a download);
     *  - its own model is downloaded but failed to load: Whisper told the language, and nothing says
     *    to download a model that is already there. When Whisper can not stand in either, that
     *    model all the same, and Stt loads nothing for it, as before;
     *  - no model of its own (Portuguese, Italian, Arabic, ...): Whisper told the language;
     *  - not known: Whisper finding the language of each sentence, the last resort.
     */
    fun autoPick(lang: String?, bad: (SttModel) -> Boolean = { false }, ready: (SttModel) -> Boolean): AutoPick {
        if (lang == null) return AutoPick(WHISPER, null, null)
        val own = dedicated(lang)
        val usable = own.filterNot(bad)
        own.firstNotNullOfOrNull { m -> upgraded(m, bad, ready).takeIf { it !== m } }?.let { return AutoPick(it, lang, null) }
        usable.firstOrNull(ready)?.let { return AutoPick(it, lang, null) }
        val missing = usable.firstOrNull()
        val told = forced(lang)
        return when {
            own.isEmpty() -> AutoPick(told ?: WHISPER, lang, null)
            told != null && !bad(told) && ready(WHISPER) -> AutoPick(told, lang, missing)
            else -> AutoPick(missing ?: own.first(), lang, missing)
        }
    }

    /**
     * [lang]'s name for a message: the stream language's own label when it has a model (I18n
     * translates those), otherwise the name in the language PurpleTV is shown in ([ui], see
     * I18n.shownLang).
     */
    fun langName(lang: String, ui: String = "en"): String =
        dedicated(lang).firstOrNull()?.label
            ?: runCatching { java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.forLanguageTag(ui)) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != lang }?.replaceFirstChar { it.uppercase() }
            ?: lang

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
            else -> error("${m.lang} is a whole sentence model, see offlineConfig")
        }
    }

    /** The same for the whole sentence models. */
    fun offlineConfig(m: SttModel, dir: File, threads: Int): OfflineModelConfig {
        fun path(role: String) = File(dir, m.files[role] ?: error("no $role in the ${m.lang} model pins")).absolutePath
        val base = OfflineModelConfig(
            tokens = path("tokens"),
            numThreads = threads,
            provider = "cpu",
            modelType = m.modelType
        )
        return when (m.kind) {
            SttModel.Kind.OFFLINE_TRANSDUCER -> base.copy(transducer = OfflineTransducerModelConfig(
                encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner")))
            SttModel.Kind.OMNILINGUAL_CTC -> base.copy(omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path("model")))
            // The language Auto found for the stream, or none: Whisper then finds it for every
            // sentence and says which it was. Only ever one it knows (see WHISPER_LANGS).
            SttModel.Kind.WHISPER -> base.copy(whisper = OfflineWhisperModelConfig(
                encoder = path("encoder"), decoder = path("decoder"),
                language = m.whisperLang.takeIf { it in WHISPER_LANGS }.orEmpty(), task = "transcribe"))
            else -> error("${m.lang} is a streaming model, see modelConfig")
        }
    }

    /**
     * Silero VAD for a whole sentence model. It only says whether someone is talking; Stt cuts the
     * sentences itself, so the VAD's own longest sentence is set well above Stt's.
     */
    fun vadConfig(m: SttModel, dir: File): VadModelConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = File(dir, m.files["vad"] ?: error("no vad in the ${m.lang} model pins")).absolutePath,
            threshold = 0.5f,
            minSilenceDuration = 0.4f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 30f
        ),
        sampleRate = 16000,
        numThreads = 1,
        provider = "cpu"
    )
}
