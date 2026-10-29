// Vendored from sherpa-onnx v1.13.8 by the k2-fsa/sherpa-onnx authors, Apache License 2.0:
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt
// It must match the libsherpa-onnx-jni.so in the captions engine pack exactly, because the native
// side finds these classes and fields by name.
// No changes apart from this header.

package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
