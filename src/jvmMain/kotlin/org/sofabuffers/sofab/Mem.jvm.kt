/*
 * SofaBuffers Kotlin/JVM — little-endian access through byte-array VarHandles.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Little-endian views over a `ByteArray`. A multi-byte wire word — a float
 * payload, or the eight-byte window a varint is assembled in — becomes one
 * intrinsified unaligned load/store instead of a byte at a time, paying a single
 * bounds check rather than one per byte.
 */
private val LE_LONG: VarHandle =
    MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
private val LE_INT: VarHandle =
    MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
private val LE_SHORT: VarHandle =
    MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)

internal actual fun leGetLong(b: ByteArray, p: Int): Long = LE_LONG.get(b, p) as Long

internal actual fun leGetInt(b: ByteArray, p: Int): Int = LE_INT.get(b, p) as Int

internal actual fun leSetLong(b: ByteArray, p: Int, v: Long) {
    LE_LONG.set(b, p, v)
}

internal actual fun leSetInt(b: ByteArray, p: Int, v: Int) {
    LE_INT.set(b, p, v)
}

internal actual fun leSetShort(b: ByteArray, p: Int, v: Int) {
    LE_SHORT.set(b, p, v.toShort())
}

/**
 * `String.getBytes(int, int, byte[], int)` copies *low bytes*, not an encoding,
 * which is exactly what an already-proven-ASCII range needs and is a bulk copy
 * out of the string's own storage — a `System.arraycopy` for the Latin-1-coded
 * `String` that an all-ASCII one always is. It is the only way to reach that
 * storage without allocating an intermediate `byte[]` first, which is what
 * encoding straight into the output buffer exists to avoid. Deprecated since
 * 1.1 for the reason that makes it right here (it is wrong for anything not
 * known to be single-byte); never marked for removal.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "DEPRECATION")
internal actual fun copyAsciiInto(s: String, len: Int, dst: ByteArray, at: Int): Boolean {
    (s as java.lang.String).getBytes(0, len, dst, at)
    return true
}
