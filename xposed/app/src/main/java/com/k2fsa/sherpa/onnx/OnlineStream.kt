// Vendored from sherpa-onnx v1.13.8 by the k2-fsa/sherpa-onnx authors, Apache License 2.0:
// https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OnlineStream.kt
// It must match the libsherpa-onnx-jni.so in the captions engine pack exactly, because the native
// side finds these classes and fields by name.
// Only change: the companion init that called System.loadLibrary("sherpa-onnx-jni") is removed.
// PurpleTV loads the verified library itself with System.load, so touching this class before that
// gives a catchable UnsatisfiedLinkError at the call instead of a class broken for the whole process.

package com.k2fsa.sherpa.onnx

class OnlineStream(var ptr: Long = 0) {
    init {
        require(ptr != 0L) { "Failed to create native OnlineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)


    companion object {
        // init { System.loadLibrary("sherpa-onnx-jni") } removed for PurpleTV, see the note at the top.
    }
}
