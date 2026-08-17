/*
 * SofaBuffers Kotlin/Native — little-endian access.
 *
 * The portable shift/or form: it needs no pinning, no pointer reinterpretation
 * and no assumption that the target tolerates unaligned loads, and the Kotlin
 * compiler lowers it to eight indexed loads with the bounds checks hoisted.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

internal actual fun leGetLong(b: ByteArray, p: Int): Long = portableGetLong(b, p)

internal actual fun leGetInt(b: ByteArray, p: Int): Int = portableGetInt(b, p)

internal actual fun leSetLong(b: ByteArray, p: Int, v: Long) = portableSetLong(b, p, v)

internal actual fun leSetInt(b: ByteArray, p: Int, v: Int) = portableSetInt(b, p, v)

internal actual fun leSetShort(b: ByteArray, p: Int, v: Int) = portableSetShort(b, p, v)

/**
 * No bulk path: this target cannot reach a `String`'s backing storage as bytes,
 * so the caller's own char loop — one store per char, which is what a bulk copy
 * would lower to here anyway — writes the payload.
 */
internal actual fun copyAsciiInto(s: String, len: Int, dst: ByteArray, at: Int): Boolean = false
