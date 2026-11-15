package tv.purple.xp

/**
 * Pins for the optional offline translation downloads. They are assets of the separate GitHub
 * pre-releases [TAG], [TAG_2] and [TAG_3] on PurpleTV-ReVive (never the module's "latest" release; each
 * model pin names its own), and each one is
 * downloaded, checked and stored on its own by the same code as the speech packs (SttPacks):
 *  - the engine pack: ONNX Runtime's two arm64 libraries, unchanged from the official AAR;
 *  - one model pack per Opus-MT model: the Xenova ONNX conversion (quantized encoder, merged
 *    quantized decoder), the source SentencePiece model, the vocabulary and the two configs.
 *
 * Why pinned: the engine is native code inside Twitch's process and the models are fed to it, so
 * a changed or truncated file must never be used. The zip's exact size and SHA-256 are checked
 * while it downloads, only the entries listed here are unpacked (never more bytes than pinned),
 * each is checked against its own pin, and the libraries are checked again before System.load. A
 * new engine or model means a new tag and new pins, never a replaced asset.
 *
 * tools/make_translate_pack.py builds the zips (byte for byte the same on every run) and prints
 * these pins. The vendored Java API in ai.onnxruntime comes from the same ONNX Runtime version
 * ([ORT_VERSION]); they must match, because the native side looks up those classes by name.
 */
object TranslatePins {

    /**
     * One model zip: its name in the app, the asset, the zip's SHA-256 and size, its entries, and
     * the release it is published in.
     */
    class ModelPin(val name: String, val asset: String, val sha256: String, val bytes: Long,
                   val entries: Map<String, PackPins.FilePin>, val tag: String = TAG)

    /** The engine and the first models. Published once, never changed. */
    const val TAG = "translate-pack-1"
    const val RELEASE_PAGE = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG
    /**
     * The into English models per chat language (es-en, de-en, ...), added later. A new release
     * rather than new assets in translate-pack-1, so what is published there never changes.
     */
    const val TAG_2 = "translate-pack-2"
    /**
     * Every language both ways, trained further for live chat, plus Japanese, Filipino and
     * Lithuanian. The models of the first two releases stay as fallbacks for phones that have them.
     */
    const val TAG_3 = "translate-pack-3"
    const val RELEASE_PAGE_3 = "https://github.com/alienware377/PurpleTV-ReVive/releases/tag/" + TAG_3

    /** The ONNX Runtime release the engine libraries and the vendored Java API both come from. */
    const val ORT_VERSION = "1.22.0"

    /** The one ABI the engine is built for. Both supported Twitch builds ship arm64-v8a only. */
    const val ENGINE_ABI = "arm64-v8a"
    /** Loaded first: the runtime itself. */
    const val ENGINE_ORT_LIB = "lib/arm64-v8a/libonnxruntime.so"
    /** Loaded second: the JNI glue for ai.onnxruntime, which needs the runtime above. */
    const val ENGINE_JNI_LIB = "lib/arm64-v8a/libonnxruntime4j_jni.so"

    // Engine: purpletv-translate-engine-ort-1.22.0-arm64.zip
    const val ENGINE_ASSET = "purpletv-translate-engine-ort-1.22.0-arm64.zip"
    const val ENGINE_SHA256 = "cc7c4b8c42a6059c19a9a760e89ee28aeb84ddd9b89ebbe60651ddeb9bcc95c9"
    const val ENGINE_BYTES: Long = 6607202L
    val ENGINE_ENTRIES: Map<String, PackPins.FilePin> = mapOf(
        "lib/arm64-v8a/libonnxruntime.so" to PackPins.FilePin(18214224L, "999ecfdb5b5a13e4097487773b6d71ce8a075408a237daab072e8f5e817bd78e"),
        "lib/arm64-v8a/libonnxruntime4j_jni.so" to PackPins.FilePin(100600L, "5614bd018cca9031c4dbcfc2a3bece42fae52861a92ff09c4bd2491bd93ac758"),
        "LICENSE-onnxruntime.txt" to PackPins.FilePin(1073L, "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
        "ThirdPartyNotices-onnxruntime.txt" to PackPins.FilePin(326866L, "e9e90971a8e75a9a8ac0c6412e29c1202d079998389915aa485f46c816c3b4cc"),
        "README.txt" to PackPins.FilePin(500L, "ba01d5be8865d5cb029ae5f228bb8b322058da7a07090850947676f4ad512931")
    )

    const val ENGINE_CREDIT = "ONNX Runtime " + ORT_VERSION + " by Microsoft (MIT). Licences and source: " + RELEASE_PAGE
    const val MODEL_CREDIT = "Opus-MT models by Helsinki-NLP, University of Helsinki (CC-BY 4.0, " +
        "https://creativecommons.org/licenses/by/4.0/), ONNX conversions by Xenova"
    const val CHAT_MODEL_CREDIT = "Chat trained models: Opus-MT by Helsinki-NLP (CC-BY 4.0) and FuguMT by staka (CC-BY-SA 4.0), " +
        "trained further for live chat by PurpleTV ReVive. Licences, sources and training data: " + RELEASE_PAGE_3

    /** Every model pack, by its name in the app. */
    val MODELS: Map<String, ModelPin> = listOf(
        // mul-en, Xenova/opus-mt-mul-en at 72a05e47cee89c718a9db4dc70d02fef3bc39de8
        ModelPin("mul-en", "purpletv-translate-model-opus-mt-mul-en.zip", "19b22b1414d89249cc0c96b584d8416696fe56ba4ec2a144785e6c177ad4134c", 67445702L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52475294L, "5ce609a524375dbdd9c66b62b82b41abc667799022667ce424f20c245bd56925"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59785040L, "6add167a0cd3f78aa298b8f927d2e8645e33cb17df11e92967f0c5b5703c8c4d"),
            "source.spm" to PackPins.FilePin(706917L, "c4a99ea3602b29fbf901ade8b93a45efa3d7c64eab8fc5fa812383efa327a87d"),
            "vocab.json" to PackPins.FilePin(1552293L, "b1a8216e6f605e72eff0ebfbfc4956e3a906f965c18943843650595e7639bafb"),
            "config.json" to PackPins.FilePin(1390L, "115093532b9893a6e3ec64951db15bafbad200f34929304f9570c3cd7f1dff94"),
            "generation_config.json" to PackPins.FilePin(293L, "66300b2138c7a98fe085d16b590ecbc01d64d46e9db240ee3dab4eadd3a3b1b9"),
            "LICENSE.txt" to PackPins.FilePin(341L, "a6040bdd0483469de2c07685a14f0b2fbf1a99405cebec46c57d0555c22cb93b"),
            "README.txt" to PackPins.FilePin(527L, "58155625c1fa0aff0f7d791e7cf7b4426aa2f88f9660da286693bbb3cbe7c3f0")
        )),
        // en-es, Xenova/opus-mt-en-es at 4b002a4c7edd54a7ced58877258b87f7efd3f892
        ModelPin("en-es", "purpletv-translate-model-opus-mt-en-es.zip", "5f3602b7de6f601a5e3a5441cb21b1641e255fc9c41e983abb0eea4e3cb53bce", 75630635L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899742L, "03434edd1147e6c49e22066e9dc5888235e99cc35a6711d1c4ea5b06455d5da9"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212804L, "58ee5ddd6e22d1693d8722b90c1486afb93700a4376cf28fd74175052ad530ae"),
            "source.spm" to PackPins.FilePin(801636L, "4dd547c24816a335e7b0b2e63376a8f1b3cbfc671eda5ab808dd44fdadaa8791"),
            "vocab.json" to PackPins.FilePin(1720044L, "b074b4cca0036ade5a39ea97faabd534e1015482c480fc2cb02c6481983eb163"),
            "config.json" to PackPins.FilePin(1468L, "499eae1f97eeba63172fc884f92fd727ae803b7ceb79c0f653aa4b011152af2f"),
            "generation_config.json" to PackPins.FilePin(293L, "b743baabb7da4c1a2f19fe558bd6b4c0c7c3b0762fcb5ca7a48fe5a2c2219803"),
            "LICENSE.txt" to PackPins.FilePin(338L, "d7b859b57099f70da2b0cfaa95efca722f52651de142a3bcadf2a7f9dca49456"),
            "README.txt" to PackPins.FilePin(525L, "778632182768f33c567119441da6b4c0998f0fa78bc34bbf26e93ea4a4e7de3c")
        )),
        // en-de, Xenova/opus-mt-en-de at 1ca130c44c4c5441ef16d48aae521a424ab644f7
        ModelPin("en-de", "purpletv-translate-model-opus-mt-en-de.zip", "b53fd3408aeeaeb8547faeef73733dad0c043cfd78c02b8d338c954b4ce46e45", 71195579L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49366942L, "15834b45fabd2dfb8c6c029b3ca3e7289aeefd90ece798ce42bcf548d1bd3b8d"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(56652404L, "8b46a825964cdd182fe47cc780f0fb87d357eae1f47cf20aa63c1c09be5b510c"),
            "source.spm" to PackPins.FilePin(768489L, "678f2a1177d8389f67b66299762dcc4fc567e89b07e212ba91b0c56daecf47ce"),
            "vocab.json" to PackPins.FilePin(1389436L, "d5acea957b265a78554999144459c5e391e0df525864edc8287bc090290baa44"),
            "config.json" to PackPins.FilePin(1411L, "517955ebf66cd6523f9982851dd41771b74fb2d9c00b4933ba2922ac7a63336f"),
            "generation_config.json" to PackPins.FilePin(293L, "cd16a899388283889c6e87b903c689ce73430aaa18335415f9d9ea770606538e"),
            "LICENSE.txt" to PackPins.FilePin(338L, "a425eabe099756ed42783c26c63c53f9ac785ef402052948681bb8a6fa033091"),
            "README.txt" to PackPins.FilePin(525L, "99db1dfb64a93f18a6f73c038827d972c0946c818f9bc7b9fe2b047f024ce1c5")
        )),
        // en-fr, Xenova/opus-mt-en-fr at 28726206f80896b90035bd99cccd5cc1e151f916
        ModelPin("en-fr", "purpletv-translate-model-opus-mt-en-fr.zip", "80989f5a9683ca1d9ce908db4f9eaf2267c056f683fba2bc134a5eb045e70506", 69141732L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(50090398L, "0a81bdba62f53223740a8c6c6f58e716eba8ea1f92dfa569caf27ed5e3b6a0f7"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57381512L, "333b244bce16023df04541c8cf9fd60aec9b0569da393c4b831d561897b0bda8"),
            "source.spm" to PackPins.FilePin(778395L, "173e9f493a668fe396d599e28d414a201193094e6ffd7a4678e5aab0f6d3d838"),
            "vocab.json" to PackPins.FilePin(1458196L, "f2ba9c69ae20f96b8bd821239a9152be422394f980350b77907cffc183db5f2d"),
            "config.json" to PackPins.FilePin(1411L, "b522b73fcc86f77981349c1df2a2e041eed0dbcbea4acf2635019cf21d51ffc0"),
            "generation_config.json" to PackPins.FilePin(293L, "f9a4824ec78c61b4a95afc43bbb6a9545a44ccf1c01d0963a286e799b9e7b256"),
            "LICENSE.txt" to PackPins.FilePin(338L, "167c95e7567127298255af628845c47a4ea5e80742d69803beee76662d8f891f"),
            "README.txt" to PackPins.FilePin(525L, "90e6e56124b20d2feec30944cb165389d109186238f4ff9234ad8408fd699bd8")
        )),
        // en-it, Xenova/opus-mt-en-it at 075406e3c8c2c30634d4a1bd8f00c21d9e162011
        ModelPin("en-it", "purpletv-translate-model-opus-mt-en-it.zip", "e041dbb7fdc1f2cd9faaf73a8c32f1f13e553f22c57f4057e1086a39344d4bf3", 76724103L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(60597150L, "b51c5110c7ba356ef01ee400d427afe162e657de1d7a2e9a40deedd811b49e6f"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(67970348L, "f9db146ed7665ea7d437dc4df564e3af5df923366de4d52aaf2c4b4e3e4ae4f1"),
            "source.spm" to PackPins.FilePin(789468L, "2efff2c37e51842c2817a6770280979b5dd803cebbe2928e576d42a888f89537"),
            "vocab.json" to PackPins.FilePin(2511647L, "40c98ffc0013f2f09ed9127418131738ad9c2de4d8f7f52d52532332bb2bf78c"),
            "config.json" to PackPins.FilePin(1376L, "1cf40ab3aa16e8b6ea7c9766981de1da705a161564c0d5ad187c1e62e5c8ee85"),
            "generation_config.json" to PackPins.FilePin(293L, "15a4ad1f54640380c5119641a6a6247862bd32dc982b8170024d9d03eb3831a2"),
            "LICENSE.txt" to PackPins.FilePin(338L, "85ad66403196833e66bb868161a422a5864ee706de6b5c637a20477e26a1758c"),
            "README.txt" to PackPins.FilePin(525L, "dc97bc275cd0d57e7e52e3bd538d9e52d56d288bcdbe2eb78e3231569c3a3e62")
        )),
        // en-ROMANCE, Xenova/opus-mt-en-ROMANCE at 9d2ba69ac80c8e8453c3d9a1e2323a0e7b8ca3cd
        ModelPin("en-ROMANCE", "purpletv-translate-model-opus-mt-en-ROMANCE.zip", "00b675c45e1357d55f104cdf3fa533f3206a80504a8f803ba7fdb45394cf0364", 61016161L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899742L, "b4ddfd9338ba3d7c6b9c2d1410ed94fb7e06741226918cef6148031101bbd019"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212804L, "b5f6d442da7fa69f9d500df90373d2fdca4f799c43696b41888353b5c3c8eb13"),
            "source.spm" to PackPins.FilePin(779155L, "b1f89e7b05828846baf19109a61029a36838f1c4f3defc218e6aee0ee2787864"),
            "vocab.json" to PackPins.FilePin(1587364L, "d9c3b6b01d3a9c9403befa60fd17e4a50dc4929698e3d6b9c2e7f066a9d1f041"),
            "config.json" to PackPins.FilePin(1508L, "dc4f6f9ffbbf1196ff9e8329e5666fe190aef1b1c6806649d86b962b9bde292c"),
            "generation_config.json" to PackPins.FilePin(293L, "b743baabb7da4c1a2f19fe558bd6b4c0c7c3b0762fcb5ca7a48fe5a2c2219803"),
            "LICENSE.txt" to PackPins.FilePin(353L, "f2a0dcb3f5bee567f456b99e04fc0c70314fd2b89ee4d40739cc0b6ba21ec1b1"),
            "README.txt" to PackPins.FilePin(535L, "62a5fa5b391fec1eb16ada447716d80b499941642421f3fb66723d118795e8e8")
        )),
        // en-ru, Xenova/opus-mt-en-ru at e050376e960175e8ddc5cff85025dc8436cddd68
        ModelPin("en-ru", "purpletv-translate-model-opus-mt-en-ru.zip", "24856312117519f7f40d9be74d0ca6e6a6ba2bd3f76ee77c663454e42a8afcd5", 80155232L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51628446L, "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(58931576L, "7efefcd793a5663bc204224a92a123524f3dfe2ffad59e5f73d6734d1b11ad04"),
            "source.spm" to PackPins.FilePin(802781L, "16bebef1389a0b8ab452772c4e35b9e605e5713f8ac7baa71ca701394eaa086d"),
            "vocab.json" to PackPins.FilePin(2726796L, "5cf0d95d930d8d3e783c9e2f46a72f08b43a18060dab4ddefbcb66a733efedcb"),
            "config.json" to PackPins.FilePin(1376L, "db16bf0ea7bac239273214c3b9ab44577884227371a9a25e0191b7baf8d6f167"),
            "generation_config.json" to PackPins.FilePin(293L, "885345001ac4f978b5e295a04eb6bd6c346ebc7381e30d5b3dd927b178d8a5ef"),
            "LICENSE.txt" to PackPins.FilePin(338L, "64ed78227572da4417ccb6e96cfa1f32d5a70262a3e881c7cf1a21ceb9a684ff"),
            "README.txt" to PackPins.FilePin(525L, "bc0ecdcb47049c91abd832fb1845ddfc64e418da06263c8f5a1c3bf9c3d6c74c")
        )),
        // en-zh, Xenova/opus-mt-en-zh at 046f55aec303cdee3e0318604406d4df20f1e8ea
        ModelPin("en-zh", "purpletv-translate-model-opus-mt-en-zh.zip", "c6b0c0010748a7571e4624ba7801f42daaf9b1a2f96ab64de187f4bc4352c5dd", 80638966L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899742L, "d3b7912bf6a9bd27e4c074c2df91d4ff3d5b4bc5f7f6c8d7cc9c805c98fbafee"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212804L, "023be4f841f4c47cd65fffcbaa81c0d99d7f7e0138f7ba0e03fa220a4e688aff"),
            "source.spm" to PackPins.FilePin(806435L, "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8"),
            "vocab.json" to PackPins.FilePin(1747795L, "22c957348eed495ee925afc40a36da3e387c8a34a734c8486967c2dca271613e"),
            "config.json" to PackPins.FilePin(1503L, "4727d1229a04f95bf6f39abf949d8080615433d99d6ebd85f81c09edd247d5fa"),
            "generation_config.json" to PackPins.FilePin(293L, "b743baabb7da4c1a2f19fe558bd6b4c0c7c3b0762fcb5ca7a48fe5a2c2219803"),
            "LICENSE.txt" to PackPins.FilePin(338L, "44e0d9850f321cd2cf198286ab39a9202d026e28bdae901b94bf0dcebc181661"),
            "README.txt" to PackPins.FilePin(525L, "2f2108a94c88686756e3787f4060afb89eb7e0befdc38492e90f642a87c8d2b2")
        )),
        // en-ar, Xenova/opus-mt-en-ar at 034a684356c19021a187cda4d7f823298b913921
        ModelPin("en-ar", "purpletv-translate-model-opus-mt-en-ar.zip", "15af25fc0e49cdc4b15e4def4ad63df94ab607f5dcc6e0498a46676cc91665f0", 76529436L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51773854L, "53c354d87ced44b39085fe8a46a25f09ea4578d442dbce9663f3977228f8a04e"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59078120L, "c0e553b0c26611780c70417c9b4c1ca7169e9cdb18ba9e649197efb017af9f02"),
            "source.spm" to PackPins.FilePin(801074L, "81ac8f4d4ee271f4109eac67560a31f9c2d555463ba5eb95c32c6deee8ae819d"),
            "vocab.json" to PackPins.FilePin(2243581L, "8b74af9e4c1ec122b8d06362db6c38795e10ca2b0156b2110cf08d4716744b76"),
            "config.json" to PackPins.FilePin(1389L, "8135d646cd810090349e8833b7ba593ac3b72d632f8858cb34925bdbcb6a1bea"),
            "generation_config.json" to PackPins.FilePin(293L, "fb6be7759d385eea2d9f79f767fa80669e9ba74ba02de1db6ec9dbc950cf20f0"),
            "LICENSE.txt" to PackPins.FilePin(338L, "c56fb9d79dd055324b355faa9f9433df38945203871f955ae07679ee1f75ce30"),
            "README.txt" to PackPins.FilePin(525L, "b2e0ad735528261a05f2b0d17d1d4cf25b80dd2ce1121672196fce1e208940a5")
        )),
        // translate-pack-2: the into English models per chat language
        // es-en, Xenova/opus-mt-es-en at eadfd7c658a9d8929ac3b8e996b68a68e2c7d480
        ModelPin("es-en", "purpletv-translate-model-opus-mt-es-en.zip", "996115540ffc42b12089949d5771ac9b9bb9d2fbc527c8d6dc75a1e6c19e6fc5", 77792502L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899742L, "c01e70f8455efc350831aa2af7ed187b16829241f0018ba07d1ba643a391bc18"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212804L, "4cd91ab30240d295ce907b5100826031838c672005e57e524d711122d75605fa"),
            "source.spm" to PackPins.FilePin(825924L, "e236ee6d866b635c0142114f8647f39831f9d92534aa2aad75c942f6a78ad0e3"),
            "vocab.json" to PackPins.FilePin(1720044L, "b074b4cca0036ade5a39ea97faabd534e1015482c480fc2cb02c6481983eb163"),
            "config.json" to PackPins.FilePin(1433L, "fab3a7f93185bc5aa7b419f6a1e6e74d98c8f2a506c94493d3019bf46da3478d"),
            "generation_config.json" to PackPins.FilePin(293L, "b743baabb7da4c1a2f19fe558bd6b4c0c7c3b0762fcb5ca7a48fe5a2c2219803"),
            "LICENSE.txt" to PackPins.FilePin(338L, "952521ba133b0a8a4c5488a479ecd1e27f486c30462b4af823f5b88b6c035df6"),
            "README.txt" to PackPins.FilePin(525L, "3a0111f633149df316509c730bb75091b295129636c89024e699234d383e0f82")
        ), tag = TAG_2),
        // ROMANCE-en, Xenova/opus-mt-ROMANCE-en at f60feaabe8bb435a5871e7702b25f53a01e770f8
        ModelPin("ROMANCE-en", "purpletv-translate-model-opus-mt-ROMANCE-en.zip", "ed7540cd9ed2104e6275ec006c903448684e3b4c76b44d0749e41bc8d56f1882", 73588784L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899741L, "13750ba31ee80c88f6bbb28aa03320674c50394efb95f791d5c4f5c8894232bf"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212803L, "5c0d95a8072a463c747343cf25ac9b4a17e9a90e08131e7efab12691ca13c979"),
            "source.spm" to PackPins.FilePin(800087L, "baeab21ec00d0b490382b82499ef0348235c5a8e75de28aec8290adf62b007c4"),
            "vocab.json" to PackPins.FilePin(1590308L, "32c8eeec099b448425a7da4e09ec10acbe424a733780c9bcec2062846f03e706"),
            "config.json" to PackPins.FilePin(1361L, "df1876a346b1852ef4590bc74c6e180eb2ba10193cf2bded24c31876f357d83c"),
            "generation_config.json" to PackPins.FilePin(293L, "b743baabb7da4c1a2f19fe558bd6b4c0c7c3b0762fcb5ca7a48fe5a2c2219803"),
            "LICENSE.txt" to PackPins.FilePin(353L, "791857ff4a73c36e5afc537742184786d7f1b0429145fc7edb695e9f7ad52bf7"),
            "README.txt" to PackPins.FilePin(535L, "c5061f4c1a8a3b11200ff129be0c4d0d3dbb63fa512954de55a0910ddb0f1458")
        ), tag = TAG_2),
        // de-en, Xenova/opus-mt-de-en at 399dfd68706739fffd503f876093e455ae268a06
        ModelPin("de-en", "purpletv-translate-model-opus-mt-de-en.zip", "50c003d956defad2d2f49069aac0ef1ab1c19d24e602dc60062da9b5bea24887", 74734228L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49366942L, "4cedda8f8c89b72a42b3c6cd1e7a27f2de24457093e3bf80cb3e46829641fcd8"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(56652404L, "4712800584b2d77c2f9e620bf29523e2b20b62f65ade5011ee01780e2b0ea5d3"),
            "source.spm" to PackPins.FilePin(796845L, "bbd1f495eea99c8e21ae086d9146e0fa7b096c3dfdd9ba07ab8b631889df5c9b"),
            "vocab.json" to PackPins.FilePin(1389436L, "d5acea957b265a78554999144459c5e391e0df525864edc8287bc090290baa44"),
            "config.json" to PackPins.FilePin(1376L, "6754743ec4754957d8552dad869d1314f3af6626b67446ec4cebe5e2fbaa0fc4"),
            "generation_config.json" to PackPins.FilePin(293L, "cd16a899388283889c6e87b903c689ce73430aaa18335415f9d9ea770606538e"),
            "LICENSE.txt" to PackPins.FilePin(338L, "8d6fc0dbf947427e1a32307fc3ada52a0ef8106d01b5b230fad3fe2b250f7fba"),
            "README.txt" to PackPins.FilePin(525L, "a2ca47212d077efa68ae85c0880324731707d2975a1b642488771aa5242a93c4")
        ), tag = TAG_2),
        // fr-en, Xenova/opus-mt-fr-en at 6b166a182780e118c997879d0ad5be4b53671644
        ModelPin("fr-en", "purpletv-translate-model-opus-mt-fr-en.zip", "04504f41d0c962c213449225c6c044b7d3f03a2dde87bcc988dda5391809bded", 71298538L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(50090398L, "e727cb26ac6bf816394c49671af69c9ae5798868fed1e40849872415dffc1772"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57381512L, "73bc7ac8e29c42e6f212ebcc29a2991d3646a04aabb642c0036aa54b40e4e1a9"),
            "source.spm" to PackPins.FilePin(802397L, "78d0e717c77053f1c4b856d8661d9cb87c64f083a35418c087b9146300e4f585"),
            "vocab.json" to PackPins.FilePin(1458196L, "f2ba9c69ae20f96b8bd821239a9152be422394f980350b77907cffc183db5f2d"),
            "config.json" to PackPins.FilePin(1411L, "6c2851f154d7b88c7767e4fdcf8a5694a36a456bfa6cbcde1b13fdbade3b56f6"),
            "generation_config.json" to PackPins.FilePin(293L, "f9a4824ec78c61b4a95afc43bbb6a9545a44ccf1c01d0963a286e799b9e7b256"),
            "LICENSE.txt" to PackPins.FilePin(338L, "c3d933b86c6ffc3e336731e10424ee0455200ebc588553dd8929fa1555ad09d7"),
            "README.txt" to PackPins.FilePin(525L, "8febb86d7fcf8d4c9c6eabfa3356c4c4688b627fba9445bd57ec82d385069b95")
        ), tag = TAG_2),
        // it-en, Xenova/opus-mt-it-en at fd0b89b9c052adc1f2f64152f555aa17353728be
        ModelPin("it-en", "purpletv-translate-model-opus-mt-it-en.zip", "f62d100c610265404f888d4886877b93e5e94cdde648b0989c84066a688a8fa2", 73629008L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(60773278L, "a025d2d85c90da23880116b67175e43e71921d8d2bf446b9f1eacdc86015aa4c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(68147852L, "aebc2789cd2ef8062fbda020bac90b61e68458ba98a4c43cea63c1d2310ba2b8"),
            "source.spm" to PackPins.FilePin(813709L, "1bd307e2756991d470e4b40172147f7889b46a323b1a9a2150e66274825b1f21"),
            "vocab.json" to PackPins.FilePin(2530593L, "08e5b03ab20b2b9b85d41aed1b36dce2650afcb6e96274a06b7869b2e06884d5"),
            "config.json" to PackPins.FilePin(1376L, "4b82a23b7a91b213a934db70d0a33712a739fdd7c5686b02d89642d7dcfc7ac4"),
            "generation_config.json" to PackPins.FilePin(293L, "bab9fcae043547da07e13c701f77cf55b6b1517d6f73848c5c4476e7255da512"),
            "LICENSE.txt" to PackPins.FilePin(338L, "6303d04d2438aafff517495a49680ea2d295ada829cd5f1680e19c54bbb0d8e5"),
            "README.txt" to PackPins.FilePin(525L, "fe510e32f2e6ce3afb525f12fd4476f377a700ee06faf6f5e418ddce5adb111b")
        ), tag = TAG_2),
        // ru-en, Xenova/opus-mt-ru-en at afe8c6c738ec81b6d033fd8f44f9678a639a7c67
        ModelPin("ru-en", "purpletv-translate-model-opus-mt-ru-en.zip", "1aa8aebe2fe9c6cc41c959915265ca64297b418089c85fc910253e445650a30f", 74246980L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51628446L, "c10df0466540534bb70c4402d539c349b8bfa9968184cad89f51408cf13e7eb7"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(58931576L, "25e7ff97b201bce64ab9c39749761d657b70b8602bcbc1ce01c67f713e90ffdf"),
            "source.spm" to PackPins.FilePin(1080169L, "745998e51ba5b058e38b7ac7765c25c43ed5c1c39cc92b27163b9b2e323c9d7c"),
            "vocab.json" to PackPins.FilePin(2726796L, "5cf0d95d930d8d3e783c9e2f46a72f08b43a18060dab4ddefbcb66a733efedcb"),
            "config.json" to PackPins.FilePin(1376L, "60dc7d0906dbe42a1d740cdd7e84ce1534685b81fbe16005a0301adac69086ce"),
            "generation_config.json" to PackPins.FilePin(293L, "9bcb507fa6117b07870df6627294b878b0710a55bc3abc9cf0db980e0c48fe2c"),
            "LICENSE.txt" to PackPins.FilePin(338L, "b1828f4e4f1db40223cbf64eaa36c3e8748145998afcc675bf7583b407acf3ae"),
            "README.txt" to PackPins.FilePin(525L, "2b65a788eace7f9891436f40eee2572cc236759f306f35c4ccf53df4c3864185")
        ), tag = TAG_2),
        // zh-en, Xenova/opus-mt-zh-en at 39d480d52a9ea3065a1f117adfe4dbc55de10e6f
        ModelPin("zh-en", "purpletv-translate-model-opus-mt-zh-en.zip", "63e7ad5d25cda50546a234c46b3b52350bea3ce6347b48274ddd91de60202d6e", 80853487L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52899742L, "84d5e171b626bc8b6b220d022ac58696e9528c25deeacca62b5cbf4364547a99"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60212804L, "c6b7f04ff1ba0fbd1bf6852599b4c0cad6fe512d57cd887f44ef36cf705424cb"),
            "source.spm" to PackPins.FilePin(804677L, "e27a3a1b539f4959ec72ea60e453f49156289f95d4e6000b29332efc45616203"),
            "vocab.json" to PackPins.FilePin(1747906L, "08a119a1defd522fa047cb5e3bfe3e89633e96caa38ced0dc9cee7ef1021a011"),
            "config.json" to PackPins.FilePin(1389L, "293d318fce41dbf04114eac45037bb88a32d7c4ee21011a75e24a8b98ca45ad1"),
            "generation_config.json" to PackPins.FilePin(293L, "8dc29fef0fe82109f94ef3c2e6ea6bded3215d357b226c34cf7b4630726766c9"),
            "LICENSE.txt" to PackPins.FilePin(338L, "30e0b6686c0cbc6ab9624cd1ce0393a8b72a56f6eff5976edd2b7017dae963cc"),
            "README.txt" to PackPins.FilePin(525L, "ac5f5e8f5e2b98b3495b491ef69ea2193bc8fd8804fde6e2c57357f6a41a5f49")
        ), tag = TAG_2),
        // ar-en, Xenova/opus-mt-ar-en at 09c7556866400dcc53562ba8d7035119e7d9a2c1
        ModelPin("ar-en", "purpletv-translate-model-opus-mt-ar-en.zip", "56ea6da21a0b1caaccad422353ff820be08e8122a64e4c88d6a669ab7d40e58b", 76131251L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51790238L, "d9b0d612acff86cf87634b49bda566d1ef39b312837de549786ea18fecefc708"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59094632L, "cdb25ae736afe9d48035b0ea311ab4b25f315810fab7f3c6f6cb0d8d87af35fa"),
            "source.spm" to PackPins.FilePin(917407L, "484f7210e5f4466f7e5b99b660717c6ef2a6f90746196daba507795b69dc02a2"),
            "vocab.json" to PackPins.FilePin(2257246L, "87b78c097c331672ca22a522c3ba24c5dc2cdff90495dcb52cf742635439a3a0"),
            "config.json" to PackPins.FilePin(1376L, "451ee0da95b01fe58d78f05c47e9ad89f0a17a4c03dcecb389136a12248ef9aa"),
            "generation_config.json" to PackPins.FilePin(293L, "6e240dc18d20cceb870d47706c90126a801f246db6009f9e02171795e9f93772"),
            "LICENSE.txt" to PackPins.FilePin(338L, "5927fde05a906a93da89a230426281f91277737f1be1c14f777590af649b436d"),
            "README.txt" to PackPins.FilePin(525L, "b1535a7e3d14cd354b0a8c011b61aaf75f23abc46fdc81d2d86d0791bad60c41")
        ), tag = TAG_2),
        // translate-pack-3
        // es-en-chat, from es-en-chat
        ModelPin("es-en-chat", "purpletv-translate-model-es-en-chat.zip", "457e23c7b70c01568b91e88d6ff56c85d0a5c69c1250137b72f96beddd9bd185", 91368591L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939355L, "e6f95d52dc871e6f70916ebd2c581694f86c0bdcb5515e2be6ae6cea1a004fa9"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60659566L, "be9c9d9892015a109d757910c093120b6d01e7a3d4b05753f93770fe8903b426"),
            "source.spm" to PackPins.FilePin(825924L, "e236ee6d866b635c0142114f8647f39831f9d92534aa2aad75c942f6a78ad0e3"),
            "vocab.json" to PackPins.FilePin(1785046L, "fd930041713bd413ccd23c628b62f927323cf1c5573bfed24035a95f12c5b7b6"),
            "config.json" to PackPins.FilePin(1467L, "de1b0ec5d604f55904aedfebcc154e0940955e79ffe91975b5c23be512995dfa"),
            "generation_config.json" to PackPins.FilePin(304L, "31d2f00381bcdc983c76c27241ee1fd5f9aeba40a90fba61539de2ac1bc5e877"),
            "LICENSE.txt" to PackPins.FilePin(589L, "bcec68001e08362a34f3ce79e395946c08d26998c0db343e4b89209bb4b0d8da"),
            "README.txt" to PackPins.FilePin(862L, "4a52a55984d6f2a80f645c2fe016c17bdb283ed81deeb5f3679d4c138ed78be2")
        ), tag = TAG_3),
        // en-es-chat, from en-es-chat
        ModelPin("en-es-chat", "purpletv-translate-model-en-es-chat.zip", "5eccc9205ba881b061878f82db27136eddba3c3af4a453105aef744ec1128fd6", 88853442L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939214L, "c715e53042834fff8bc259d36f11e38689fd889ea88b50d8ad7c40f484fadb36"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60662294L, "cd0a69c38269253002ce667d35f480d5f8dd18f648a2323fe61b138287a75373"),
            "source.spm" to PackPins.FilePin(801636L, "4dd547c24816a335e7b0b2e63376a8f1b3cbfc671eda5ab808dd44fdadaa8791"),
            "vocab.json" to PackPins.FilePin(1785046L, "fd930041713bd413ccd23c628b62f927323cf1c5573bfed24035a95f12c5b7b6"),
            "config.json" to PackPins.FilePin(1503L, "34f74d2181856681ad3485d18c7e224fa265637227b91a64f931e7ec72bf47dc"),
            "generation_config.json" to PackPins.FilePin(304L, "31d2f00381bcdc983c76c27241ee1fd5f9aeba40a90fba61539de2ac1bc5e877"),
            "LICENSE.txt" to PackPins.FilePin(589L, "aff2badf44f3248e1a90e65089a61c7e5ca9cc9f1889eb49313a4107983b5305"),
            "README.txt" to PackPins.FilePin(862L, "c04ef4beea1976d1b9461d0cc7d099cc95542debf98b3859c5ed9a159b14f554")
        ), tag = TAG_3),
        // de-en-chat, from de-en-chat
        ModelPin("de-en-chat", "purpletv-translate-model-de-en-chat.zip", "c039bac4d57b9c71a8fdce6e680060a8eb323c80844ae52b82c1239858efb53d", 86383125L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49406821L, "c6d6cc9925c61078b0731455419bcdc678a9aa7d3dbd65abf65b51f088aa49b3"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57077239L, "3754553617f2c6cbf435ea1e811b75ff94af62fc3a040496295d4b45d13173fd"),
            "source.spm" to PackPins.FilePin(796845L, "bbd1f495eea99c8e21ae086d9146e0fa7b096c3dfdd9ba07ab8b631889df5c9b"),
            "vocab.json" to PackPins.FilePin(1447538L, "ea442816fd11e6e95c1f4a7d76b2e5856f654915e0964bb537d4b1bdbb2c0e42"),
            "config.json" to PackPins.FilePin(1409L, "c20be855fedc2fe343e46c149d27dfee47288e9f37860ea6a2cfeaa13b3280cd"),
            "generation_config.json" to PackPins.FilePin(304L, "e5e5bab9b5a90283be0363d844d19667c09849e32f1f409ef28a9fd3751fa950"),
            "LICENSE.txt" to PackPins.FilePin(589L, "199826f4dbaa6c7182453c7d7aa2a316cac9f9eefc2158c3e81557fbd7770194"),
            "README.txt" to PackPins.FilePin(862L, "cd24fc538064a44781d01e2e8b91e47f1c29707b7937a4d4b898da9ef189a07d")
        ), tag = TAG_3),
        // en-de-chat, from en-de-chat
        ModelPin("en-de-chat", "purpletv-translate-model-en-de-chat.zip", "5df913e16c9b382f46f58c19cc044972496a9448be5d13f44944e78c395f16f6", 83767931L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49406436L, "5056197f7f6b76326cb270a9da5b0eca0b88035100fc8e754dba8b94c460bb9c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57094713L, "435e20a5c35658d8f7e90d9fc3574c0336fea6e80682dec45ad0e14d23f9d8d8"),
            "source.spm" to PackPins.FilePin(768489L, "678f2a1177d8389f67b66299762dcc4fc567e89b07e212ba91b0c56daecf47ce"),
            "vocab.json" to PackPins.FilePin(1447538L, "ea442816fd11e6e95c1f4a7d76b2e5856f654915e0964bb537d4b1bdbb2c0e42"),
            "config.json" to PackPins.FilePin(1445L, "d69be160b1c7a9158a2c6c49f204e529d5592c3377356e9b4b3b9567aac66726"),
            "generation_config.json" to PackPins.FilePin(304L, "e5e5bab9b5a90283be0363d844d19667c09849e32f1f409ef28a9fd3751fa950"),
            "LICENSE.txt" to PackPins.FilePin(589L, "5e2681f3a677fb02427eda5839fad6f252e0459b1a3482ff0c2c49bdaa573115"),
            "README.txt" to PackPins.FilePin(862L, "563842c7c48db5db234f46beff6381afe1d827b303dfb872862e7de457c232c8")
        ), tag = TAG_3),
        // fr-en-chat, from fr-en-chat
        ModelPin("fr-en-chat", "purpletv-translate-model-fr-en-chat.zip", "be6ff7cee3f94d9daf33da89f302aa7353e0f91c4d7bac5dd705624c9a8b1dce", 82923957L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(50129927L, "845c3cbf9d853a55f65ed5d36fbe35ce5b392aadc759f081d631a5f210f44c66"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57834729L, "9219b2bf3edd7d72c12172b85a70d4a8d03243584cb97e2c22592c17bd4eceab"),
            "source.spm" to PackPins.FilePin(802397L, "78d0e717c77053f1c4b856d8661d9cb87c64f083a35418c087b9146300e4f585"),
            "vocab.json" to PackPins.FilePin(1517711L, "a69f5dfc7e08449976df59e51e233ff1c40b0b840a45318d6dd5f59a7bdfd6e0"),
            "config.json" to PackPins.FilePin(1445L, "e0556f304c98883611274acdc8f2084c9bbcd7dcc8e167c94c0c99c7fdd651c5"),
            "generation_config.json" to PackPins.FilePin(304L, "f02f472acc8d651799b62d3b6d9d51ace663ad57f1c2ce72f5c58d6ead0cfd5b"),
            "LICENSE.txt" to PackPins.FilePin(589L, "1014c9045c245ceaf546894728776ade18eaac9ea449bb033b8e019aece8eb7d"),
            "README.txt" to PackPins.FilePin(862L, "1b1b4d024c20125bb5871c5dd68595444552e7cb5a3a2342362822147368a3c4")
        ), tag = TAG_3),
        // en-fr-chat, from en-fr-chat
        ModelPin("en-fr-chat", "purpletv-translate-model-en-fr-chat.zip", "6d7ac67c9fa6e6873d8e894cfc0bc083bab73f9281c90e826e1cfe73047c1add", 82197326L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(50130138L, "7ec91e0760779164e0967540e4daf5c5bc04cf83159561865f98d75a89a56be6"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57847280L, "8e1f2b228f7c22aa456a1683ef5fb1953e0e31cf9b5f54fe1f160d52426ba435"),
            "source.spm" to PackPins.FilePin(778395L, "173e9f493a668fe396d599e28d414a201193094e6ffd7a4678e5aab0f6d3d838"),
            "vocab.json" to PackPins.FilePin(1517711L, "a69f5dfc7e08449976df59e51e233ff1c40b0b840a45318d6dd5f59a7bdfd6e0"),
            "config.json" to PackPins.FilePin(1445L, "262ee546e288b7d5fc3af3e23c816a57453ee3a03319021ff8d95b764b201382"),
            "generation_config.json" to PackPins.FilePin(304L, "f02f472acc8d651799b62d3b6d9d51ace663ad57f1c2ce72f5c58d6ead0cfd5b"),
            "LICENSE.txt" to PackPins.FilePin(589L, "9d5bd9fbeecb71d0bed555679103b79166642844c09c2cd8ad989edc0de0953a"),
            "README.txt" to PackPins.FilePin(862L, "7f2ee83a5cd0e2888576f675e3252ae58e6b00e6d8057c738cd63c78fda501ba")
        ), tag = TAG_3),
        // it-en-chat, from it-en-chat
        ModelPin("it-en-chat", "purpletv-translate-model-it-en-chat.zip", "4058ef565b2892379e27d347ea8d1a90402f667081e9c8b06e8acc42edb04df9", 92095757L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(60812672L, "4f293c16103962fc2dd1fe1894ed1b064dfa533c4292f58bd67595dfa9bc9d17"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(68731181L, "60840d5df37efc417a697ec31e251e22698560369449ae6497cbc2a74a31e120"),
            "source.spm" to PackPins.FilePin(813709L, "1bd307e2756991d470e4b40172147f7889b46a323b1a9a2150e66274825b1f21"),
            "vocab.json" to PackPins.FilePin(2610973L, "3388641d906dd83a72f4887033c6d95c5bfd60ec5a88754dd06c9916de65e8b7"),
            "config.json" to PackPins.FilePin(1409L, "2e67f4ff54892d95390caa7a0113c0c3fa2e21a7f1fad2ed63b0ed95e0bb9169"),
            "generation_config.json" to PackPins.FilePin(304L, "7f54a7e27453c702ee1202d96bfa9dc82c18cbf63b2a2f8156cb1db9d1608c85"),
            "LICENSE.txt" to PackPins.FilePin(589L, "511ccff92aec592a2a72e7bfa7cf39e5a9268e807f2a21849a4e30c3bcfcb928"),
            "README.txt" to PackPins.FilePin(862L, "b2132e0ab231a96c5bd2693786d0510476a37e5173d1c8854df79b20106bca2e")
        ), tag = TAG_3),
        // en-it-chat, from en-it-chat
        ModelPin("en-it-chat", "purpletv-translate-model-en-it-chat.zip", "68e3cd2cb52954c09586820e28695be3b249a13d65ecf49f15368b6e9aa0e656", 90197738L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(60636726L, "5c64d8915501466c635d9dfdc4707643ebdc3c8ea4e12cb8844ea9f5ecbe9029"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(68542839L, "fade127cfceabe193fd0d11be8bcb4f37e90bd8018bc404c133f75eb222e2b60"),
            "source.spm" to PackPins.FilePin(789468L, "2efff2c37e51842c2817a6770280979b5dd803cebbe2928e576d42a888f89537"),
            "vocab.json" to PackPins.FilePin(2591683L, "d19743847b467691d9d3f41abeef0f4a66983a5db08c252bddcb0632b5e2f0e6"),
            "config.json" to PackPins.FilePin(1409L, "faf2833da26e294b4fbbd2ea2434b2f152e90062bd8d5b9fea24e3776143bed6"),
            "generation_config.json" to PackPins.FilePin(304L, "7198503594c612c9e803278239eb43c3c1bf48f62569ce88ef62c7f93ca2a296"),
            "LICENSE.txt" to PackPins.FilePin(589L, "28a50c1449482947575abe7123de18ffdf66931451a875d54df7d6dc22cb1097"),
            "README.txt" to PackPins.FilePin(862L, "3bfc288fbcfc4cd827ac48ca41caba788e27115cde9d79993203e45db9f44e05")
        ), tag = TAG_3),
        // pt-en-chat, from pt-en-chat
        ModelPin("pt-en-chat", "purpletv-translate-model-pt-en-chat.zip", "93f16e7265aa22590abc91259a6beba33be20075ffb2250548b6ea7aec98f8de", 88253100L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939168L, "12b136cc1b8e0b0ef10b0bd6061628bb46009637ad568c2d546f9e6f16726a95"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60694507L, "045db52c5a279cded1424ebd04317e9e6d4cfcdacad20c82654a79c455ac963f"),
            "source.spm" to PackPins.FilePin(800087L, "baeab21ec00d0b490382b82499ef0348235c5a8e75de28aec8290adf62b007c4"),
            "vocab.json" to PackPins.FilePin(1655310L, "53dbb98b2caad34e1d70efaa25901a5a3696fba85638a9661fc483dd3f355af2"),
            "config.json" to PackPins.FilePin(1393L, "934c1310ba6297add11116fe3b1785b299e6f93f07386ee1e404643c1fec307d"),
            "generation_config.json" to PackPins.FilePin(304L, "31d2f00381bcdc983c76c27241ee1fd5f9aeba40a90fba61539de2ac1bc5e877"),
            "LICENSE.txt" to PackPins.FilePin(599L, "b04e39566c8087443fd5fbe593a8137b30ee01c6c7b249c67288e077dc57e105"),
            "README.txt" to PackPins.FilePin(862L, "d03d0acdd5ac5eda02cd2a23aeddf449831506d7b176ec674ba0cbc6ace7348e")
        ), tag = TAG_3),
        // en-pt-chat, from en-pt-chat
        ModelPin("en-pt-chat", "purpletv-translate-model-en-pt-chat.zip", "b5348177b62a5bbee3a74d86a40d2879f17aecc8433dd6d05a76d8cbb9111446", 80084919L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939035L, "24ac61c213d6a0f115f167070e38b7d63423b98c3da5ec30c21ee717eb23b7ed"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60712065L, "8f2fec596f30c35f0f1cd5a64b4eab217de5d8570a722054dc2ca4da23cc1793"),
            "source.spm" to PackPins.FilePin(779155L, "b1f89e7b05828846baf19109a61029a36838f1c4f3defc218e6aee0ee2787864"),
            "vocab.json" to PackPins.FilePin(1652366L, "9b9150ed01d08773b7403bb3d270263e4077ea047949a437eb81dd3f71fa6836"),
            "config.json" to PackPins.FilePin(1544L, "5dde7f822856de0cac3ef2ec321d954ecfacfccaf97d91dc83fc99d5f0d33730"),
            "generation_config.json" to PackPins.FilePin(304L, "31d2f00381bcdc983c76c27241ee1fd5f9aeba40a90fba61539de2ac1bc5e877"),
            "LICENSE.txt" to PackPins.FilePin(599L, "f17409f3927d522b79010fe6a762bf954f71def42b1d02e924346d5a8f1fb12e"),
            "README.txt" to PackPins.FilePin(862L, "574d7d72156dde48204c807fd1d06147702eeab716d8988a88ae11630f085150")
        ), tag = TAG_3),
        // ru-en-chat, from ru-en-chat
        ModelPin("ru-en-chat", "purpletv-translate-model-ru-en-chat.zip", "c43966d9ebf58c9cc5c8a3ca7475a25fccabe8ea954b9617739f41ad88a5057f", 88862853L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51667796L, "74f2e72da0668f21fe033a67403062831b8465609db537673b758ad878a8b863"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59414872L, "8466b5d7f299fc2962463e75e154ca53a0d9dd466d68889beb2b19685d652916"),
            "source.spm" to PackPins.FilePin(1080169L, "745998e51ba5b058e38b7ac7765c25c43ed5c1c39cc92b27163b9b2e323c9d7c"),
            "vocab.json" to PackPins.FilePin(2789315L, "5cf82d518313b98ea802177d7ea73e6a7c9c0cc079c1f74fe910f8b1faa535bc"),
            "config.json" to PackPins.FilePin(1409L, "7b06727a4079a05ba6d7806a2c6199ef551348f3d9c4afeb8f49fdad0626e021"),
            "generation_config.json" to PackPins.FilePin(304L, "a7aee6bda3e1937555e6706dbd12b17e8c95b794a84db75c6b6865399bb678ff"),
            "LICENSE.txt" to PackPins.FilePin(589L, "40aaa22730d067ddddf86377026c149df55b9fdabb2dc51f479eff9942a7badc"),
            "README.txt" to PackPins.FilePin(863L, "7d78a7f2782120cb089d2d66fd4a8143795daf0e610899cc7877435a780bb351")
        ), tag = TAG_3),
        // en-ru-chat, from en-ru-chat
        ModelPin("en-ru-chat", "purpletv-translate-model-en-ru-chat.zip", "1ad913a9166deacb3e4d7a44b057c15644b40fd736bfeefba4f3ba667b71efec", 92492339L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51667849L, "44d0dc93ec041bac28dd5b6c85635a167521763f0ff0c051ca7d0100a8e5386c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59385781L, "458124b5c96fd3b93d266875967a4c4be0da588a0e2903a3a002147cb3fb0d36"),
            "source.spm" to PackPins.FilePin(802781L, "16bebef1389a0b8ab452772c4e35b9e605e5713f8ac7baa71ca701394eaa086d"),
            "vocab.json" to PackPins.FilePin(2789315L, "5cf82d518313b98ea802177d7ea73e6a7c9c0cc079c1f74fe910f8b1faa535bc"),
            "config.json" to PackPins.FilePin(1409L, "ccb939aec666b395c525ab236f9ce1d78928450a4e451754ece19d8a7a6ae0df"),
            "generation_config.json" to PackPins.FilePin(304L, "a3ae82ec41fcf9d0611c4d59b901043257d9e7968265a75cdf39e918a3de1cae"),
            "LICENSE.txt" to PackPins.FilePin(589L, "495451137b61794f0760e32cf02c6e8535b1602cc5d47eac8cf1c7381de5c27f"),
            "README.txt" to PackPins.FilePin(862L, "da0c9c056de0c6adb63fd5ae0ea25744c2b7c4a6359f774cd2b3c89efdb7b2f0")
        ), tag = TAG_3),
        // zh-en-chat, from zh-en-chat
        ModelPin("zh-en-chat", "purpletv-translate-model-zh-en-chat.zip", "d8fbd5e26df14ed34246d6bfd2a3a0261262034398b35ba5cc14db4f9c62a344", 93596699L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939190L, "32af5ec6c1f3da339ca1f03958a676897b6a7517e99186dfbb1ce1254b286490"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60710595L, "9347d2ac6567b1ab66962c97c393dde288f1251dc17df4c46ea505385f476739"),
            "source.spm" to PackPins.FilePin(804677L, "e27a3a1b539f4959ec72ea60e453f49156289f95d4e6000b29332efc45616203"),
            "vocab.json" to PackPins.FilePin(1812908L, "b53d8f6da91f955bd8e1640ed0151742430c33811a9aa55b6c5a5cfe82985aaa"),
            "config.json" to PackPins.FilePin(1422L, "ccb42f0d96367050a8a47a92f018657eba14d6e8acbc5bdd46d714023861ca6d"),
            "generation_config.json" to PackPins.FilePin(304L, "d383c3d0bb90f652a9e5ecb8190194e74a83bab7719d66702cd4ad51a6303656"),
            "LICENSE.txt" to PackPins.FilePin(589L, "4bdc6a58752fdb710e5de9cfa340ac99a5530ada9fa244898b9acf700463e8fe"),
            "README.txt" to PackPins.FilePin(862L, "2acaf400dfb3ee3a7e684637a9a89f0a8d282ebeb25ce2f298446e747a90217f")
        ), tag = TAG_3),
        // en-zh-chat, from en-zh-chat
        ModelPin("en-zh-chat", "purpletv-translate-model-en-zh-chat.zip", "03de6bf6792a3682b5c29671b804f8fcdf744a9cda3c1fbf437597418592b58a", 93251828L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(52939230L, "b0bc2fb8161e833b1c9360d6c09a1c602ad7d88e581b2f942d29a6dcd669208c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(60680370L, "5fa0832497efc26540a3c069f21ae826acbb6cd37296a2690089bcfad25aa46d"),
            "source.spm" to PackPins.FilePin(806435L, "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8"),
            "vocab.json" to PackPins.FilePin(1812797L, "7a7aefac27f6ba9c7a9672b5b53d04a1d94c76839475146f02b54be1769d33c8"),
            "config.json" to PackPins.FilePin(1539L, "0bf5589ac8e44ad14e6c6c9d967ae6e0adcac6ed9c9c4b6971a1566a13eab9a7"),
            "generation_config.json" to PackPins.FilePin(304L, "31d2f00381bcdc983c76c27241ee1fd5f9aeba40a90fba61539de2ac1bc5e877"),
            "LICENSE.txt" to PackPins.FilePin(589L, "8b73d000c898ca17c9e3e0069c3419f9e2656af4a46a65219cb45800e164d693"),
            "README.txt" to PackPins.FilePin(862L, "f012dd253ccb358e07d2e6b371d867b60da858964eb4fb7e77d50d58b591210b")
        ), tag = TAG_3),
        // ar-en-chat, from ar-en-chat
        ModelPin("ar-en-chat", "purpletv-translate-model-ar-en-chat.zip", "80c141ed32fb19737dabc9a9d2aecde2472ea64e9abbcb33212f2f120eb608ba", 89474829L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51829664L, "cced9ba1c46b1c3b6869bc9b95f3353c14428f5d8ef41f93289c9356711644e5"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59518645L, "cebd6c9ea81bcfdfe49f1766f412cf830f058690391f27884246ee39ef71189d"),
            "source.spm" to PackPins.FilePin(917407L, "484f7210e5f4466f7e5b99b660717c6ef2a6f90746196daba507795b69dc02a2"),
            "vocab.json" to PackPins.FilePin(2320081L, "961300b0d066b68588a5157a262de1249e62b8c8686d2b7fca072060354faf4b"),
            "config.json" to PackPins.FilePin(1409L, "955add6b34d44991d3b606016b9fb08b747ba3d7d76d7871399407de63ad1f80"),
            "generation_config.json" to PackPins.FilePin(304L, "2799b4e18ce0742ac26cb787860e2b375806aa499e276349b36dcbd829f8d966"),
            "LICENSE.txt" to PackPins.FilePin(589L, "a3c3e363e3386215c8a0fe2a0f2916fd050516009be8ef30b6afbcb61d50e767"),
            "README.txt" to PackPins.FilePin(862L, "7273d149c6e3deadcfd7b42828dd4f555c094fafae97657f9be3cbf80ae993b5")
        ), tag = TAG_3),
        // en-ar-chat, from en-ar-chat
        ModelPin("en-ar-chat", "purpletv-translate-model-en-ar-chat.zip", "821474f4fa31a19a6b7d43cbbf45d36df7d580833aed6379f0dec987efbcbb60", 92373197L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(51813477L, "908be36ae067543c8bbd3d439f659c7205d9ff4c2d23b746edad7b2a5ddf183f"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(59530848L, "208d4b63e9e0459e75eb538902ee53e8718a4db80f002fc2b5a5a0a9c7101f97"),
            "source.spm" to PackPins.FilePin(801074L, "81ac8f4d4ee271f4109eac67560a31f9c2d555463ba5eb95c32c6deee8ae819d"),
            "vocab.json" to PackPins.FilePin(2306384L, "ed005d0900db7f90e9ffe0184a99b43ce34da87c7536b14fd392b9889f1e3be4"),
            "config.json" to PackPins.FilePin(1422L, "cc5b375af36b09c14c9c62826a7b6bdc17cda794725f5dd54a8e34284ca6d969"),
            "generation_config.json" to PackPins.FilePin(304L, "f0f05b9385bfa44920f60af828939bb8f8741f4313db1466dfc2c7384dfa59b7"),
            "LICENSE.txt" to PackPins.FilePin(589L, "e3cabea459d408b4c562bb649c4a9ad23a72833a7e65519a0a01cbbd80c7cae4"),
            "README.txt" to PackPins.FilePin(862L, "77bf05ce4acab6aa555caf65cf0eb7875b982ea0a6482cda7f866c62b53cd124")
        ), tag = TAG_3),
        // ja-en-chat, from ja-en
        ModelPin("ja-en-chat", "purpletv-translate-model-ja-en-chat.zip", "f51ed6519acd07ce27139a31af71aadf95d8d52cdedd812698e1930329d3e05d", 83982118L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(50745169L, "9fda01a2aa3752f38be025ac318f9d1dc504497a0e1fa4a1adac70ca81231fd0"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(58474943L, "1292ca5bbb05b49ab214be9cb19d03de9b71cb45d388041303bac06b2bdf4a5e"),
            "source.spm" to PackPins.FilePin(781853L, "d0b5c3b10b5959f056ff2c86e2f2356129242ff1fc72d3a4a34d6a8c0eee4e57"),
            "vocab.json" to PackPins.FilePin(1683492L, "5e99fa930c75d866712758bf2221263cb5164c6c387e6cbe8cea5630d9ea3faa"),
            "config.json" to PackPins.FilePin(1409L, "1e10445a4554b31f9ae0d2a7a6f973b06321df12e6ab918e28258d87cf634b48"),
            "generation_config.json" to PackPins.FilePin(304L, "2313458d7c2b92039ea134dbaa402dec9fad29be8718529122ac9f8445d1e3ae"),
            "LICENSE.txt" to PackPins.FilePin(724L, "ea007861c3743530c5d778f665275ae03aed3ee6d88a0657b1eef3ae6bd11b98"),
            "README.txt" to PackPins.FilePin(862L, "3f1ece6ba82c55e11ef32ba23fd6df54bebd4b2fe319ca3009a0f51a8626f683")
        ), tag = TAG_3),
        // en-ja-chat, from en-ja
        ModelPin("en-ja-chat", "purpletv-translate-model-en-ja-chat.zip", "388e04f5a6532d326a24f78186ef19cf358bcef2b547095edc66d9e6055a8213", 64395626L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(36042996L, "97d582191ec6c4d78586c8d5c1ce4c9663d7ee63f50f2a79fa60cc0e6382cf2c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(43453264L, "652da1fb04cbf0e3ae657d3c886b4e8d5191af348425627c75945fdc6394b08d"),
            "source.spm" to PackPins.FilePin(768297L, "92fcbf6449e47457866ec98891dab09ef625e57d9637fe3fdbbe44fc1a365b6d"),
            "vocab.json" to PackPins.FilePin(823093L, "94b0cf8945069eaf28afb0a86db0d47516eac57e7565e9b8e26f38004bf4a1d9"),
            "config.json" to PackPins.FilePin(1066L, "2d79bc10a6c515582a3554cc54b16a791ee4c43ceedf4de8410a05ca798745ab"),
            "generation_config.json" to PackPins.FilePin(305L, "80578b51946c140a438b32bc9f23d346709a560df9ccfe9f0e93edcf8ddd0113"),
            "LICENSE.txt" to PackPins.FilePin(684L, "6048c27d8b2bd041a8c9401c10d9da025aae5de348bc1805a6996df199948a3e"),
            "README.txt" to PackPins.FilePin(861L, "370d9a204cbee77c3b087a034b2d1607ea7849c6d7067fd8a305fac027eeb813")
        ), tag = TAG_3),
        // tl-en-chat, from tl-en-chat
        ModelPin("tl-en-chat", "purpletv-translate-model-tl-en-chat.zip", "8d91ba404c99d09fd86e96fbcd78616168c95c61fdb60a861177933910357dda", 91530890L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(48944068L, "b45075f2d415bafe248b55c4a34a8b3f19a6d810db1f7ff448d2099d3cc1238c"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(56624465L, "fc6988f9f00c2138d24c847b5793636b55c29b93e9f80b9f6c152f4ef66262ee"),
            "source.spm" to PackPins.FilePin(833838L, "22951af70fe5dc1802f6464a3fd6975a377a9af2f124512a76784a351c10acbc"),
            "vocab.json" to PackPins.FilePin(1490973L, "535f2562de33d3b4f4b302eeb3818f1a8493fa0dde68cca47b1cc668dbc4c04b"),
            "config.json" to PackPins.FilePin(1422L, "b674d7bbea32e4ff4a706f6035df851fe9721008700db8a9166147a72b57d562"),
            "generation_config.json" to PackPins.FilePin(304L, "6f9cd371031e64329def74de9f93d1b96ba8dc45f21873d704ab75ce13522833"),
            "LICENSE.txt" to PackPins.FilePin(589L, "6e15cfb8b05e91bcc9ece6e4ceaa5cf2b83baa460826faed797b1e048933dae4"),
            "README.txt" to PackPins.FilePin(862L, "d65b622854c1a4e2b834de083301d887e4255b2d1df67afb44cc66d8f5bc7fae")
        ), tag = TAG_3),
        // en-tl-chat, from en-tl-chat
        ModelPin("en-tl-chat", "purpletv-translate-model-en-tl-chat.zip", "58ab7d118b558c6caa88580d3f07fb39fcb752726582058efbcd59317399eef6", 86614141L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49033681L, "f3530bdfc65338d5d6a9fa246930646da5518eccc458493a618e832c328f7999"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(56682862L, "31beb1309f232ded96707d104b7047ee34a4fd13739a8331e67574179d5bdc62"),
            "source.spm" to PackPins.FilePin(826681L, "feaf3e0cf579e3daeb6bac5dc94b906efed40e89b544d269cec9bdbb4f05f8e1"),
            "vocab.json" to PackPins.FilePin(1496836L, "e6b621420437873e57d72216e02ee570f1ac0c6b71de7520bed5cfa6bdcfa614"),
            "config.json" to PackPins.FilePin(1388L, "2f1abb06d4be8576fedb2b9727a8556d49eccc6f7a616bf06f1a9207af493d50"),
            "generation_config.json" to PackPins.FilePin(304L, "d6b4a434ad262aa546ec0b9e034911b0f23314bbe50f4b078bde24817f14c185"),
            "LICENSE.txt" to PackPins.FilePin(589L, "7537de9e20a823792c53bd38ffa2c696746c87a2afbd814d68c9fde4eac58dbd"),
            "README.txt" to PackPins.FilePin(862L, "9a9b3d378ccfc242befa8f973dd075977033f134852c0f3e99732fc1a2615886")
        ), tag = TAG_3),
        // lt-en-chat, from lt-en-chat
        ModelPin("lt-en-chat", "purpletv-translate-model-lt-en-chat.zip", "3e7bd38bc879f0e0c0dd59208c8567a403171d6a2e7a43fa8c7433077737f3d2", 88705065L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49642552L, "c88f95d8b30af85bd37f1386e24c3b3ad5a4e8e54f2f9a14a5427bcec8323c64"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(57349013L, "5c5e34275410650f5c2533c9a5b0087e5e1f180c4417454dc932aa24d6809762"),
            "source.spm" to PackPins.FilePin(832504L, "f42dafbf30e755a931fbd0b73664e35b54d03ebd767a50325f64e1036ed017ba"),
            "vocab.json" to PackPins.FilePin(1556002L, "9d279c7633f2486f702510d3d8fd2deb9a5efb232e8f1ae10f764e92720e9919"),
            "config.json" to PackPins.FilePin(1076L, "45897c5fed065f4e14717054330d760bf6e68cf2e73168e370d709fde0940702"),
            "generation_config.json" to PackPins.FilePin(304L, "938144ef99c8728eee438fd23cee671214c993a591b9f45c3aa664e4ac98079c"),
            "LICENSE.txt" to PackPins.FilePin(645L, "c2ec6ec92cc83d1c495db08cf67676205bd387e6ea6e30f33d690f0a1d1c3530"),
            "README.txt" to PackPins.FilePin(862L, "7248d9c291a46cedf54b355fa8447a9cd0370d10674d131f0df41be672866347")
        ), tag = TAG_3),
        // en-lt-chat, from en-lt-chat
        ModelPin("en-lt-chat", "purpletv-translate-model-en-lt-chat.zip", "0a8ede5fb39e1a1624055df69dd615bc2e8beee65b90f8323c56e3de680db89f", 82868958L, mapOf(
            "encoder_model_quantized.onnx" to PackPins.FilePin(49117383L, "ce2d1fa762c79b180dfc4945890eae9fce0318c1d4fe81c3e276f0c63f24d6ec"),
            "decoder_model_merged_quantized.onnx" to PackPins.FilePin(56759817L, "2cf3bc0f16e537bd6b8763525aa333409c4984faabc125490815594e70d37c5b"),
            "source.spm" to PackPins.FilePin(798349L, "2e79092368c581a3a02e60932787c07eb1f07bc35886b455c50f040801e961fa"),
            "vocab.json" to PackPins.FilePin(1520662L, "b25bdc167cb9b029ef1b2679059854e27a200e70978ddce69bbb9d5700f1976c"),
            "config.json" to PackPins.FilePin(1086L, "b77bacd74f5c6bd8eaa86d965b4bbfc22b0f1da0457527b812f7e121e127a0ae"),
            "generation_config.json" to PackPins.FilePin(312L, "ff339efe0ca39b5a9f84b471102e7460e294983eaa39511cfc533e6b8bc7003b"),
            "LICENSE.txt" to PackPins.FilePin(672L, "3276725cb8eeabcf0c909d1c417a13ba900b4e41d339eb29856f7b7dbd3f722b"),
            "README.txt" to PackPins.FilePin(862L, "0d5155dfe978bbea7fca4984ad876a396d81144be4d0b8c23e3b67c91c5d7bc6")
        ), tag = TAG_3)
    ).associateBy { it.name }
}
