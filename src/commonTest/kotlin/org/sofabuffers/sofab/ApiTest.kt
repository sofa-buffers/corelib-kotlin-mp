/*
 * SofaBuffers Kotlin Multiplatform — the normative constants and tag values, and
 * the little-endian primitives every target has to agree on.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    @Test
    fun constantsMatchTheFormat() {
        assertEquals(1, Sofab.API_VERSION, "the wire contract is version 1 (§6.2)")
        assertEquals(2147483647, Sofab.ID_MAX)
        assertEquals(2147483647L, Sofab.ARRAY_MAX)
        assertEquals(255, Sofab.MAX_DEPTH)
        assertTrue(Sofab.MIN_OUTPUT_BUFFER in 1..20)
    }

    @Test
    fun tagValuesAreNormativeAcrossTheFamily() {
        // §4.6 fixlen subtypes.
        assertEquals(0, FixlenType.FP32.raw)
        assertEquals(1, FixlenType.FP64.raw)
        assertEquals(2, FixlenType.STRING.raw)
        assertEquals(3, FixlenType.BLOB.raw)
        // ArrayKind ordinals are normative: UNSIGNED = 0, SIGNED = 1, FP32 = 2, FP64 = 3.
        assertEquals(0, ArrayKind.UNSIGNED.ordinal)
        assertEquals(1, ArrayKind.SIGNED.ordinal)
        assertEquals(2, ArrayKind.FP32.ordinal)
        assertEquals(3, ArrayKind.FP64.ordinal)
    }

    @Test
    fun anExceptionCarriesItsCategory() {
        val e = SofabException(SofabError.BUFFER_FULL, "detail")
        assertEquals(SofabError.BUFFER_FULL, e.error)
        assertTrue(e.message!!.contains("BUFFER_FULL"))
        assertTrue(e.message!!.contains("detail"))
    }

    @Test
    fun aFreshDecoderIsComplete() {
        // The empty message is valid and denotes the all-default value
        // (MESSAGE_SPEC §2), so a decoder that has seen nothing is COMPLETE.
        val input = IStream()
        assertEquals(DecodeStatus.COMPLETE, input.status)
        input.feed(ByteArray(0), RecordingVisitor())
        assertEquals(DecodeStatus.COMPLETE, input.status)
    }

    @Test
    fun theLittleEndianPrimitivesAgreeWithThePortableForm() {
        // The JVM reads and writes multi-byte words through byte-array VarHandles;
        // every other target uses the portable shift/or form. They must produce the
        // same bytes, or two ports of this library would not share a wire format.
        val b = ByteArray(24)
        for (v in longArrayOf(0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x0102_0304_0506_0708L)) {
            leSetLong(b, 3, v)
            assertEquals(v, leGetLong(b, 3))
            assertEquals(portableGetLong(b, 3), leGetLong(b, 3))
            portableSetLong(b, 11, v)
            assertEquals(v, leGetLong(b, 11))
        }
        for (v in intArrayOf(0, 1, -1, Int.MIN_VALUE, 0x0102_0304)) {
            leSetInt(b, 5, v)
            assertEquals(v, leGetInt(b, 5))
            assertEquals(portableGetInt(b, 5), leGetInt(b, 5))
            portableSetInt(b, 13, v)
            assertEquals(v, leGetInt(b, 13))
        }
        leSetShort(b, 7, 0x1234)
        assertEquals(0x34, b[7].toInt() and 0xFF)
        assertEquals(0x12, b[8].toInt() and 0xFF)
    }

    @Test
    fun asciiCopyMatchesTheCharLoopWhereItExists() {
        // The bulk path is an optimization, not a second encoding: where a target
        // offers it, its bytes are the char loop's bytes.
        val text = "identifier-0123456789"
        val dst = ByteArray(text.length)
        if (copyAsciiInto(text, text.length, dst, 0)) {
            assertEquals(text, dst.decodeToString())
        }
        val wire = encode { it.writeString(0, text) }
        assertTrue(hex(wire).endsWith(hex(text.encodeToByteArray())), "the payload is the string's UTF-8 bytes")
    }

    @Test
    fun utf8ValidatorAcceptsAndRejectsExactlyTheScalarEncoding() {
        assertTrue(Utf8.valid(ByteArray(0)))
        assertTrue(Utf8.valid("aä€𝄞".encodeToByteArray()))
        assertTrue(Utf8.valid(byteArrayOf(0x61, 0x00, 0x62)), "embedded U+0000 is valid UTF-8 (§6.4)")
        for (bad in listOf(
            "c080", // overlong NUL ("Modified UTF-8")
            "c1bf", // overlong two-byte
            "e08080", // overlong three-byte
            "eda080", // surrogate U+D800
            "f4908080", // above U+10FFFF
            "ff", // never a lead byte
            "80", // bare continuation
            "e282", // truncated sequence
        )) {
            assertTrue(!Utf8.valid(unhex(bad)), "must reject $bad")
        }
    }
}
