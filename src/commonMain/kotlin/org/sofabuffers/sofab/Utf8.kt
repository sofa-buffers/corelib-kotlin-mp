/*
 * SofaBuffers Kotlin Multiplatform — UTF-8 validation and materialization.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * UTF-8 validation — and, on the decode side, materialization — of a raw byte
 * range, for both sides of a `string` field (CORELIB_PLAN §6.4).
 *
 * [OStream.writeString] rejects an invalid [String] while measuring it, but
 * wherever a `string` is handled as *raw bytes* this validator is what enforces
 * the contract: on encode for the byte-container entry point
 * [OStream.writeFixlen] with [FixlenType.STRING], and on decode for bytes
 * arriving from a peer, which must be validated *before* they are handed to the
 * consumer as a [String] — which is what [decode] does, in that order. Generated
 * code needs it on every materialized string, so it belongs here rather than
 * being emitted into every generated message class.
 *
 * Validation is on the byte range, not on a constructed [String]:
 * `ByteArray.decodeToString()` silently substitutes `U+FFFD` for malformed input,
 * so a check made after that conversion can never fail. Checking first is what
 * makes the rejection possible at all (MESSAGE_SPEC §8: no silent replacement,
 * ever).
 */
public object Utf8 {

    /**
     * Reports whether `b[from..end)` is well-formed UTF-8.
     *
     * Accepts exactly the Unicode-scalar encoding and no more. Rejected: overlong
     * forms (including the `C0 80` "Modified UTF-8" NUL and the `C1`-lead
     * two-byte forms), surrogate code points `U+D800..DFFF` encoded as three
     * bytes, anything above `U+10FFFF`, a lead byte whose continuation bytes are
     * missing or out of range, and a bare continuation byte. Embedded `U+0000` is
     * **valid** (CORELIB_PLAN §6.4) and accepted.
     *
     * @param b buffer
     * @param from first byte of the range
     * @param end one past the last byte of the range
     * @return true when the range is valid UTF-8
     */
    public fun valid(b: ByteArray, from: Int, end: Int): Boolean {
        var i = from
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                i++
                continue
            }
            val n: Int
            val lo: Int
            val hi: Int
            // The second byte's legal range depends on the lead: it is what
            // excludes the overlong forms (E0 A0.., F0 90..) and the surrogates
            // (ED 80..9F) without a separate code-point comparison.
            when {
                c < 0xC2 -> return false // bare continuation, or overlong 2-byte
                c < 0xE0 -> { n = 1; lo = 0x80; hi = 0xBF }
                c < 0xF0 -> { n = 2; lo = if (c == 0xE0) 0xA0 else 0x80; hi = if (c == 0xED) 0x9F else 0xBF }
                c < 0xF5 -> { n = 3; lo = if (c == 0xF0) 0x90 else 0x80; hi = if (c == 0xF4) 0x8F else 0xBF }
                else -> return false // > U+10FFFF
            }
            if (i + n >= end) return false // truncated sequence
            var cc = b[i + 1].toInt() and 0xFF
            if (cc < lo || cc > hi) return false
            for (k in 2..n) {
                cc = b[i + k].toInt() and 0xFF
                if (cc < 0x80 || cc > 0xBF) return false
            }
            i += n + 1
        }
        return true
    }

    /**
     * Reports whether `b` is well-formed UTF-8 in its entirety.
     *
     * @param b buffer
     * @return true when every byte of `b` is part of a well-formed sequence
     */
    public fun valid(b: ByteArray): Boolean = valid(b, 0, b.size)

    /**
     * Materialize `b[from, from + len)` as a [String], rejecting a range that is
     * not well-formed UTF-8.
     *
     * This is what a decoder does with a `string` payload once it is complete,
     * and it is two steps for a reason: [valid] first, the conversion second.
     * [ByteArray.decodeToString] substitutes `U+FFFD` for malformed input, so a
     * check made on the result can never fail — validating the bytes is what
     * makes the rejection possible at all, and MESSAGE_SPEC §8 requires the
     * rejection rather than a repaired string.
     *
     * A Kotlin [String] is a Unicode type with no room for the unvalidated
     * bytes, so there is nothing here for a "lenient" mode to select: the ports
     * whose string type is a byte container let a receiver turn strict validation
     * off, and this one has no such switch to offer.
     *
     * @param b buffer
     * @param from first byte of the payload
     * @param len payload length in bytes
     * @return the payload as a string
     * @throws SofabException [SofabError.INVALID_MSG] when the range is not valid
     *     UTF-8
     */
    public fun decode(b: ByteArray, from: Int, len: Int): String {
        if (!valid(b, from, from + len)) {
            throw SofabException(SofabError.INVALID_MSG, "string: invalid UTF-8")
        }
        return b.decodeToString(from, from + len)
    }
}
