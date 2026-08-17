/*
 * SofaBuffers Kotlin Multiplatform — little-endian multi-byte access.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * Read eight little-endian bytes at [p]. The caller guarantees `p + 8 <= b.size`.
 */
internal expect fun leGetLong(b: ByteArray, p: Int): Long

/** Read four little-endian bytes at [p]. The caller guarantees `p + 4 <= b.size`. */
internal expect fun leGetInt(b: ByteArray, p: Int): Int

/** Write eight little-endian bytes at [p]. The caller guarantees `p + 8 <= b.size`. */
internal expect fun leSetLong(b: ByteArray, p: Int, v: Long)

/** Write four little-endian bytes at [p]. The caller guarantees `p + 4 <= b.size`. */
internal expect fun leSetInt(b: ByteArray, p: Int, v: Int)

/** Write two little-endian bytes at [p]. The caller guarantees `p + 2 <= b.size`. */
internal expect fun leSetShort(b: ByteArray, p: Int, v: Int)

/**
 * Copy the first [len] chars of [s] — all of which the caller has proven to be
 * below `U+0080` — into [dst] at [at] as one byte each, and report whether the
 * target could do it in bulk.
 *
 * An all-ASCII string is the common case for a schema's identifiers and codes,
 * and its UTF-8 payload *is* its own storage, so a target that can reach that
 * storage copies it wholesale instead of walking chars. A target that cannot
 * returns `false` and the caller falls back to its own loop; nothing is written
 * in that case.
 */
internal expect fun copyAsciiInto(s: String, len: Int, dst: ByteArray, at: Int): Boolean

// --- portable fallbacks, shared by every target without an intrinsified view ---

internal fun portableGetLong(b: ByteArray, p: Int): Long =
    (b[p].toLong() and 0xFF) or
        ((b[p + 1].toLong() and 0xFF) shl 8) or
        ((b[p + 2].toLong() and 0xFF) shl 16) or
        ((b[p + 3].toLong() and 0xFF) shl 24) or
        ((b[p + 4].toLong() and 0xFF) shl 32) or
        ((b[p + 5].toLong() and 0xFF) shl 40) or
        ((b[p + 6].toLong() and 0xFF) shl 48) or
        ((b[p + 7].toLong() and 0xFF) shl 56)

internal fun portableGetInt(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xFF) or
        ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or
        ((b[p + 3].toInt() and 0xFF) shl 24)

internal fun portableSetLong(b: ByteArray, p: Int, v: Long) {
    b[p] = v.toByte()
    b[p + 1] = (v ushr 8).toByte()
    b[p + 2] = (v ushr 16).toByte()
    b[p + 3] = (v ushr 24).toByte()
    b[p + 4] = (v ushr 32).toByte()
    b[p + 5] = (v ushr 40).toByte()
    b[p + 6] = (v ushr 48).toByte()
    b[p + 7] = (v ushr 56).toByte()
}

internal fun portableSetInt(b: ByteArray, p: Int, v: Int) {
    b[p] = v.toByte()
    b[p + 1] = (v ushr 8).toByte()
    b[p + 2] = (v ushr 16).toByte()
    b[p + 3] = (v ushr 24).toByte()
}

internal fun portableSetShort(b: ByteArray, p: Int, v: Int) {
    b[p] = v.toByte()
    b[p + 1] = (v ushr 8).toByte()
}
