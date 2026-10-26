package com.localllm.server.data.inference

/** Callback invoked from native code for every streamed delta (UTF-8 JSON). Return false to abort. */
fun interface NativeDeltaCallback {
    fun onDelta(json: ByteArray): Boolean
}

/** Raw JNI declarations, implemented in `src/main/cpp/llama_jni.cpp`. */
object LlamaNative {
    init {
        System.loadLibrary("localllm")
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeSystemInfo(): String
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeFreeModel(handle: Long)
    external fun nativeModelInfo(handle: Long): String
    external fun nativeChat(handle: Long, requestJson: ByteArray, callback: NativeDeltaCallback): ByteArray
}
