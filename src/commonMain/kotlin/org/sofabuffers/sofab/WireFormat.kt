/*
 * SofaBuffers Kotlin Multiplatform — shared wire constants and codecs.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

// --- field-header 3-bit type tags (low 3 bits of the id header varint) -------

internal const val T_VARINT_UNSIGNED: Int = 0x0
internal const val T_VARINT_SIGNED: Int = 0x1
internal const val T_FIXLEN: Int = 0x2
internal const val T_VARINTARRAY_UNSIGNED: Int = 0x3
internal const val T_VARINTARRAY_SIGNED: Int = 0x4
internal const val T_FIXLENARRAY: Int = 0x5
internal const val T_SEQUENCE_START: Int = 0x6
internal const val T_SEQUENCE_END: Int = 0x7

// --- fixlen subtype tags (the low 3 bits of a fixlen_word) -------------------

internal const val F_FP32: Int = 0x0
internal const val F_FP64: Int = 0x1
internal const val F_STRING: Int = 0x2
internal const val F_BLOB: Int = 0x3

/** Largest valid field id (`INT32_MAX`), matching `SOFAB_ID_MAX`. */
internal const val ID_MAX: Long = Int.MAX_VALUE.toLong()

/**
 * Largest array element count / fixlen byte length (`INT32_MAX`), matching
 * `SOFAB_ARRAY_MAX` / `SOFAB_FIXLEN_MAX`.
 */
internal const val ARRAY_MAX: Long = Int.MAX_VALUE.toLong()

/** Number of value bits; bounds the maximum varint length (64-bit value type). */
internal const val VALUE_BITS: Int = 64

/** One per byte lane: the continuation bit of each of eight varint bytes. */
internal const val CONT_BITS: Long = -0x7f7f7f7f7f7f7f80L // 0x8080_8080_8080_8080

/** ZigZag-encode a signed value to its unsigned varint representation. */
internal fun zigzagEncode(v: Long): Long = (v shl 1) xor (v shr 63)

/** ZigZag-decode an unsigned varint back to a signed value. */
internal fun zigzagDecode(u: Long): Long = (u ushr 1) xor -(u and 1L)

/**
 * Spread the low 56 bits of [v] into eight byte lanes, seven payload bits each —
 * the inverse of the decoder's lane gather. Group `i` sits at bits `[7i, 7i+7)`
 * and belongs at `[8i, 8i+7)`.
 *
 * Done by repeated halving rather than one term per group: split 56 bits into two
 * 28-bit halves and open a 4-bit gap, then each half into 14-bit quarters with a
 * 2-bit gap, then each quarter into 7-bit eighths with a 1-bit gap. Three
 * mask/shift/or stages replace eight, which matters because this is the whole
 * cost of encoding a multi-byte varint.
 */
internal fun scatter7(v: Long): Long {
    var x = (v and 0x0FFF_FFFFL) or ((v and 0x00FF_FFFF_F000_0000L) shl 4)
    x = (x and 0x0000_3FFF_0000_3FFFL) or ((x and 0x0FFF_C000_0FFF_C000L) shl 2)
    return (x and 0x007F_007F_007F_007FL) or ((x and 0x3F80_3F80_3F80_3F80L) shl 1)
}

/**
 * Gather the 7-bit payloads of eight varint bytes packed one per lane of [x] into
 * a contiguous 56-bit value — the exact inverse of [scatter7], and three
 * mask/shift/or stages instead of one term per lane.
 */
internal fun gather7(x: Long): Long {
    var y = (x and 0x007F_007F_007F_007FL) or ((x ushr 1) and 0x3F80_3F80_3F80_3F80L)
    y = (y and 0x0000_3FFF_0000_3FFFL) or ((y ushr 2) and 0x0FFF_C000_0FFF_C000L)
    return (y and 0x0FFF_FFFFL) or ((y ushr 4) and 0x00FF_FFFF_F000_0000L)
}
