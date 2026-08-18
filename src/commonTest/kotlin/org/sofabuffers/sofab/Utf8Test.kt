/*
 * SofaBuffers Kotlin Multiplatform — UTF-8 materialization on the decode side.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Utf8Test {

    @Test
    fun aValidRangeBecomesItsString() {
        val bytes = "hé€𝄞".encodeToByteArray()
        assertEquals("hé€𝄞", Utf8.decode(bytes, 0, bytes.size))
    }

    @Test
    fun onlyTheGivenRangeIsMaterialized() {
        // The payload sits inside the decoder's input buffer, so decode reads a
        // window of it rather than the whole array.
        val framed = "xxéyy".encodeToByteArray()
        assertEquals("é", Utf8.decode(framed, 2, 2))
        assertEquals("", Utf8.decode(framed, 2, 0))
        assertEquals("", Utf8.decode(ByteArray(0), 0, 0))
    }

    @Test
    fun anEmbeddedNulIsText() {
        // CORELIB_PLAN §6.4: a string is a length-delimited byte range, not a
        // C string — U+0000 is an ordinary code point.
        val bytes = byteArrayOf(0x61, 0x00, 0x62)
        assertEquals("a\u0000b", Utf8.decode(bytes, 0, 3))
    }

    @Test
    fun malformedBytesAreRejectedRatherThanRepaired() {
        // The rejection is what distinguishes decode from a plain conversion:
        // decodeToString would hand back U+FFFD and MESSAGE_SPEC §8 forbids that.
        val cases = mapOf(
            "bare continuation" to byteArrayOf(0x80.toByte()),
            "truncated 2-byte" to byteArrayOf(0xC3.toByte()),
            "lead then non-continuation" to byteArrayOf(0xC3.toByte(), 0x28),
            "overlong NUL" to byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            "surrogate U+D800" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "above U+10FFFF" to byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            "4-byte with a bad tail" to byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x28),
            "4-byte with a lead in the tail" to byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0xC0.toByte()),
        )
        for ((what, bytes) in cases) {
            val e = assertFailsWith<SofabException>(what) { Utf8.decode(bytes, 0, bytes.size) }
            assertEquals(SofabError.INVALID_MSG, e.error, what)
            assertTrue(e.message!!.contains("UTF-8"), what)
        }
    }

    @Test
    fun aMalformedTailOutsideTheRangeIsNotRead() {
        // Only the payload is judged: bytes after it belong to the next field.
        val bytes = byteArrayOf(0x61, 0x62, 0xC3.toByte())
        assertEquals("ab", Utf8.decode(bytes, 0, 2))
        assertFailsWith<SofabException> { Utf8.decode(bytes, 0, 3) }
    }
}
