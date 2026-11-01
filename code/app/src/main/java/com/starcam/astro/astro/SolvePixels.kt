package com.starcam.astro.astro

/** One read-only grayscale image per solve, shared by detector, diagnostics and engine planning. */
class SolvePixels(val width: Int, val height: Int, decode: () -> FloatArray) {
    val gray: FloatArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decode().also { require(it.size.toLong() == width.toLong() * height) { "Invalid grayscale dimensions" } }
    }

    /** JNI/simplexy receives private storage; it must never mutate the shared JVM input. */
    fun nativeCopy(): FloatArray = gray.copyOf()
}
