package tv.purple.xp

/**
 * Pins for the optional offline translation downloads. They are assets of the separate GitHub
 * pre-releases [TAG] and [TAG_2] on PurpleTV-ReVive (never the module's "latest" release; each
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
        ), tag = TAG_2)
    ).associateBy { it.name }
}
