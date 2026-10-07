/*
 * SofaBuffers Kotlin Multiplatform — the raw-bits view of an fp32 array.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * An [IntArray] over the SAME storage as [a], one IEEE-754 binary32 bit pattern
 * per element, or `null` where a target cannot alias the two.
 *
 * CORELIB_PLAN §6.5: an `fp32` payload must survive decode -> re-encode bit for
 * bit, a signaling NaN included. A [FloatArray] can hold any 32-bit pattern, but
 * on Kotlin/JS every float VALUE is a double, and moving an element through one
 * sets a signaling NaN's quiet bit. There a [FloatArray] is a `Float32Array`, so
 * an `Int32Array` over its buffer reads and writes the patterns without ever
 * forming a value: that is this view. Kotlin/JVM and Kotlin/Native hold a
 * signaling NaN in a `Float` unchanged, so they need no view and answer `null`.
 */
internal expect fun fp32BitsView(a: FloatArray): IntArray?
