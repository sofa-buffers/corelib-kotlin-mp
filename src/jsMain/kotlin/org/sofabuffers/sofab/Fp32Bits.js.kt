/*
 * SofaBuffers Kotlin/JS — the raw-bits view of an fp32 array.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * A `FloatArray` is a `Float32Array` on this target and an `IntArray` an
 * `Int32Array`, so the view is an `Int32Array` over the same buffer: writing a
 * pattern through it never forms a double, so a signaling NaN keeps its quiet
 * bit clear.
 */
internal actual fun fp32BitsView(a: FloatArray): IntArray? {
    val f = a.unsafeCast<Float32Array>()
    return Int32Array(f.buffer, f.byteOffset, f.length).unsafeCast<IntArray>()
}
