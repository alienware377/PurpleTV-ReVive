/*
 * Part of PurpleTV ReVive, added to the vendored ONNX Runtime 1.22.0 Java API (MIT licensed, see
 * OnnxRuntime.java). Not in upstream ONNX Runtime.
 */
package ai.onnxruntime;

import java.io.File;

/**
 * Absolute paths of the two ONNX Runtime libraries, set before the first use of any ORT class
 * (OrtEnvironment.getEnvironment() and friends load them). PurpleTV ReVive runs inside the Twitch
 * app, so the libraries come from a checked download in Twitch's private storage, and the patched
 * OnnxRuntime loader System.load()s them from here instead of System.loadLibrary.
 */
public final class NativeLibs {
  private static volatile String ort;
  private static volatile String jni;

  private NativeLibs() {}

  /**
   * Sets both paths. Has no effect once the libraries are loaded.
   *
   * @param onnxruntime libonnxruntime.so
   * @param onnxruntimeJni libonnxruntime4j_jni.so
   */
  public static synchronized void set(File onnxruntime, File onnxruntimeJni) {
    ort = onnxruntime.getAbsolutePath();
    jni = onnxruntimeJni.getAbsolutePath();
  }

  /** True once the native side is loaded and initialised. */
  public static boolean loaded() {
    return OnnxRuntime.loaded;
  }

  static String path(String library) {
    if (OnnxRuntime.ONNXRUNTIME_LIBRARY_NAME.equals(library)) return ort;
    if (OnnxRuntime.ONNXRUNTIME_JNI_LIBRARY_NAME.equals(library)) return jni;
    return null;
  }
}
