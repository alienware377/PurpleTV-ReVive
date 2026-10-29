// Vendored from sherpa-onnx v1.13.8 by the k2-fsa/sherpa-onnx authors, Apache License 2.0:
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/VersionInfo.kt
// It must match the libsherpa-onnx-jni.so in the captions engine pack exactly, because the native
// side finds these classes and fields by name.
// Only change: the companion init that called System.loadLibrary("sherpa-onnx-jni") is removed.
// PurpleTV loads the verified library itself with System.load, so touching this class before that
// gives a catchable UnsatisfiedLinkError at the call instead of a class broken for the whole process.

package com.k2fsa.sherpa.onnx

class VersionInfo {
    companion object {
        // init { System.loadLibrary("sherpa-onnx-jni") } removed for PurpleTV, see the note at the top.

        val version: String
            get() = getVersionStr2()

        val gitSha1: String
            get() = getGitSha12()

        val gitDate: String
            get() = getGitDate2()

        val onnxruntimeVersion: String
            get() = getOnnxruntimeVersionStr2()

        external fun getVersionStr2(): String
        external fun getGitSha12(): String
        external fun getGitDate2(): String
        external fun getOnnxruntimeVersionStr2(): String
    }
}
