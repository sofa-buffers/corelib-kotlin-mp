/*
 * SofaBuffers Kotlin Multiplatform — encoder wire-shape tests.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncoderTest {

    @Test
    fun unsignedWireShapes() {
        // (id << 3) | 0, then the value varint. CORELIB_PLAN §4.4's worked example.
        assertEquals("0000", hex(encode { it.writeUnsigned(0, 0) }))
        assertEquals("007f", hex(encode { it.writeUnsigned(0, 127) }))
        assertEquals("008001", hex(encode { it.writeUnsigned(0, 128) }))
        assertEquals("00ac02", hex(encode { it.writeUnsigned(0, 300) }))
        assertEquals("00808001", hex(encode { it.writeUnsigned(0, 16384) }))
        // id 16 is the first two-byte header: (16 << 3) | 0 == 128.
        assertEquals("800101", hex(encode { it.writeUnsigned(16, 1) }))
        // The maximal varint: ten bytes, the tenth carrying bit 63 alone.
        assertEquals("00ffffffffffffffffff01", hex(encode { it.writeUnsigned(0, -1L) }))
        assertEquals("00ffffffffffffffff7f", hex(encode { it.writeUnsigned(0, Long.MAX_VALUE) }))
    }

    @Test
    fun unsignedTakesULongToo() {
        assertEquals(
            hex(encode { it.writeUnsigned(0, -1L) }),
            hex(encode { it.writeUnsigned(0, ULong.MAX_VALUE) }),
        )
    }

    @Test
    fun signedIsZigZag() {
        // 0 -> 0, -1 -> 1, 1 -> 2, -2 -> 3, 2 -> 4 (§4.2).
        assertEquals("0100", hex(encode { it.writeSigned(0, 0) }))
        assertEquals("0101", hex(encode { it.writeSigned(0, -1) }))
        assertEquals("0102", hex(encode { it.writeSigned(0, 1) }))
        assertEquals("0103", hex(encode { it.writeSigned(0, -2) }))
        assertEquals("0104", hex(encode { it.writeSigned(0, 2) }))
        assertEquals("01ffffffffffffffffff01", hex(encode { it.writeSigned(0, Long.MIN_VALUE) }))
    }

    @Test
    fun booleanIsAnUnsignedZeroOrOne() {
        // §4.4: a boolean has no wire type of its own.
        assertEquals("0000", hex(encode { it.writeBoolean(0, false) }))
        assertEquals("0001", hex(encode { it.writeBoolean(0, true) }))
        assertEquals(
            hex(encode { it.writeUnsigned(3, 1) }),
            hex(encode { it.writeBoolean(3, true) }),
        )
    }

    @Test
    fun fixlenScalarShapes() {
        // fixlen_word = (length << 3) | subtype; float payloads are raw LE bytes.
        assertEquals("0220d00f4940", hex(encode { it.writeFp32(0, 3.14159f) }))
        assertEquals("0241" + hex(le64(2.718281828459045.toRawBits())), hex(encode { it.writeFp64(0, 2.718281828459045) }))
        // string: no NUL terminator on the wire.
        assertEquals("0a0a41", hex(encode { it.writeString(1, "A") }))
        assertEquals("0202", hex(encode { it.writeString(0, "") }))
        assertEquals("021b010203", hex(encode { it.writeBlob(0, byteArrayOf(1, 2, 3)) }))
        assertEquals("0203", hex(encode { it.writeBlob(0, ByteArray(0)) }))
    }

    @Test
    fun stringEncodesUtf8AtEveryWidth() {
        // 1-, 2-, 3- and 4-byte sequences; the last is a surrogate pair in UTF-16.
        val text = "aä€𝄞"
        val wire = encode { it.writeString(0, text) }
        assertEquals("0252" + "61c3a4e282ac" + "f09d849e", hex(wire))
        // ...and past the ASCII bulk-copy threshold, which is a separate path.
        val long = "abcdefghijklmnopqrstuvwxyz"
        assertEquals("02d201" + hex(long.encodeToByteArray()), hex(encode { it.writeString(0, long) }))
    }

    @Test
    fun integerArrayShapes() {
        assertEquals("33040a141e28", hex(encode { it.writeArrayUnsigned(6, shortArrayOf(10, 20, 30, 40)) }))
        // An empty integer array is exactly [header][count = 0] — no fixlen_word.
        assertEquals("0300", hex(encode { it.writeArrayUnsigned(0, LongArray(0)) }))
        assertEquals("0400", hex(encode { it.writeArraySigned(0, IntArray(0)) }))
        // Every width zero-extends to the same wire bytes.
        val expect = hex(encode { it.writeArrayUnsigned(0, longArrayOf(1, 200, 300)) })
        assertEquals(expect, hex(encode { it.writeArrayUnsigned(0, intArrayOf(1, 200, 300)) }))
        assertEquals(expect, hex(encode { it.writeArrayUnsigned(0, shortArrayOf(1, 200, 300)) }))
        // u8 zero-extends: -1 as a byte is 255 on the wire.
        assertEquals("0301ff01", hex(encode { it.writeArrayUnsigned(0, byteArrayOf(-1)) }))
        // i8 zigzags: -1 -> 1.
        assertEquals("040101", hex(encode { it.writeArraySigned(0, byteArrayOf(-1)) }))
    }

    @Test
    fun fixlenArrayKeepsItsWordEvenWhenEmpty() {
        // §4.8: without it an empty fp32 array and an empty fp64 array would be
        // indistinguishable on the wire.
        assertEquals("050020", hex(encode { it.writeArrayFp32(0, FloatArray(0)) }))
        assertEquals("050041", hex(encode { it.writeArrayFp64(0, DoubleArray(0)) }))
        assertEquals(
            "050220" + hex(le32(1.0f.toRawBits())) + hex(le32(2.0f.toRawBits())),
            hex(encode { it.writeArrayFp32(0, floatArrayOf(1.0f, 2.0f)) }),
        )
    }

    @Test
    fun sequenceFramesAScope() {
        // README's worked example: a struct at id 1 holding one u32 child.
        val wire = encode {
            it.writeSequenceBeginLazy(1)
            it.writeUnsigned(0, 1)
            it.writeSequenceEnd()
        }
        assertEquals("0e000107", hex(wire))
    }

    @Test
    fun contentlessSequenceVanishes() {
        // MESSAGE_SPEC §2: a sequence-typed field equal to its default is omitted,
        // so an all-default message is the empty byte string.
        assertEquals(
            "",
            hex(
                encode {
                    it.writeSequenceBeginLazy(1)
                    it.writeSequenceEnd()
                },
            ),
        )
        // ...and endKeep forces the frame out, which is what a wrapper array's last
        // element needs (§5.1).
        assertEquals(
            "0e07",
            hex(
                encode {
                    it.writeSequenceBeginLazy(1)
                    it.writeSequenceEndKeep()
                },
            ),
        )
    }

    @Test
    fun heldBackHeadersCommitOutermostFirst() {
        val wire = encode {
            it.writeSequenceBeginLazy(1)
            it.writeSequenceBeginLazy(2)
            it.writeSequenceBeginLazy(3)
            it.writeUnsigned(4, 7)
            it.writeSequenceEnd()
            it.writeSequenceEnd()
            it.writeSequenceEnd()
        }
        // 0e 16 1e | 20 07 | 07 07 07
        assertEquals("0e161e2007070707", hex(wire))
    }

    @Test
    fun onlyTheEmptyInnerFramesVanish() {
        // The outer sequence has content (the inner one that did get a child), so it
        // is framed; its empty sibling is not.
        val wire = encode {
            it.writeSequenceBeginLazy(1)
            it.writeSequenceBeginLazy(2) // stays empty -> dropped
            it.writeSequenceEnd()
            it.writeUnsigned(3, 1)
            it.writeSequenceEnd()
        }
        assertEquals("0e180107", hex(wire))
    }

    @Test
    fun holdBackReachesMaxDepth() {
        // CORELIB_PLAN §6: a heap-capable profile holds back to the full MAX_DEPTH,
        // so the output stays canonical at every legal depth.
        val wire = encode(2048) {
            repeat(Sofab.MAX_DEPTH) { d -> it.writeSequenceBeginLazy(d and 0xF) }
            repeat(Sofab.MAX_DEPTH) { _ -> it.writeSequenceEnd() }
        }
        assertEquals(0, wire.size)
    }

    @Test
    fun depthBeyondMaxIsRejected() {
        val e = assertFailsWith<SofabException> {
            encode(4096) {
                repeat(Sofab.MAX_DEPTH + 1) { d -> it.writeSequenceBeginLazy(d and 0xF) }
            }
        }
        assertEquals(SofabError.ARGUMENT, e.error)
    }

    @Test
    fun negativeIdIsAnArgumentError() {
        for (write in listOf<(OStream) -> Unit>(
            { it.writeUnsigned(-1, 0) },
            { it.writeSigned(-1, 0) },
            { it.writeFp32(-1, 0f) },
            { it.writeFp64(-1, 0.0) },
            { it.writeString(-1, "x") },
            { it.writeBlob(-1, ByteArray(1)) },
            { it.writeArrayUnsigned(-1, LongArray(1)) },
            { it.writeArrayFp32(-1, FloatArray(1)) },
            { it.writeSequenceBeginLazy(-1) },
        )) {
            val e = assertFailsWith<SofabException> { encode { write(it) } }
            assertEquals(SofabError.ARGUMENT, e.error)
        }
    }

    @Test
    fun unpairedSurrogateIsRejectedBeforeAnyByteIsWritten() {
        // MESSAGE_SPEC §8 / CORELIB_PLAN §6.4: strict, never a silent U+FFFD.
        //
        // The surrogates are built with Char(code) rather than written as literals:
        // a lone surrogate has no UTF-8 encoding, so a source literal carrying one
        // does not survive being written out as UTF-8 — on Kotlin/JS it reaches the
        // generated code as U+FFFD, and the test would then assert nothing. Building
        // it at runtime is how the value stays what the test is about.
        val high = Char(0xD800)
        val low = Char(0xDC00)
        val buf = ByteArray(64)
        val os = OStream(buf)
        val e = assertFailsWith<SofabException> { os.writeString(0, "ok" + high + "tail") }
        assertEquals(SofabError.ARGUMENT, e.error)
        assertEquals(0, os.bytesUsed, "nothing is written before the value is judged")
        assertEquals(SofabError.ARGUMENT, assertFailsWith<SofabException> { os.writeString(0, "$low") }.error)
        // A well-formed pair of those same two chars is the valid 4-byte sequence.
        assertEquals("f0908080", hex(encode { it.writeString(0, "$high$low") }, 2))
    }

    @Test
    fun byteContainerStringEntryPointValidates() {
        // writeFixlen(STRING) is the raw-bytes door and carries the same obligation.
        val e = assertFailsWith<SofabException> {
            encode { it.writeFixlen(0, unhex("c080"), 0, 2, FixlenType.STRING) }
        }
        assertEquals(SofabError.ARGUMENT, e.error)
        // The same bytes as a blob are fine — blob is the type for opaque bytes.
        assertEquals("0213c080", hex(encode { it.writeBlob(0, unhex("c080")) }))
    }

    @Test
    fun negativeFixlenLengthIsAnArgumentError() {
        val e = assertFailsWith<SofabException> {
            encode { it.writeFixlen(0, ByteArray(4), 0, -1, FixlenType.BLOB) }
        }
        assertEquals(SofabError.ARGUMENT, e.error)
    }

    @Test
    fun aFullBufferWithoutASinkIsBufferFull() {
        val os = OStream(ByteArray(2))
        os.writeUnsigned(0, 0) // exactly fills it
        assertEquals(2, os.bytesUsed)
        val e = assertFailsWith<SofabException> { os.writeUnsigned(0, 0) }
        assertEquals(SofabError.BUFFER_FULL, e.error)
    }

    @Test
    fun aBufferSizedToTheMessageIsEnoughWithoutASink() {
        // CORELIB_PLAN §5.1: no minimum binds a sink-less buffer, so a two-byte
        // message encodes into a two-byte buffer.
        val buf = ByteArray(2)
        val os = OStream(buf)
        os.writeUnsigned(0, 0)
        assertEquals("0000", hex(buf, 0, os.bytesUsed))
    }

    @Test
    fun theStartOffsetIsNeverTouched() {
        val buf = ByteArray(16) { 0x5A }
        val os = OStream(buf, 4)
        os.writeUnsigned(0, 1)
        assertEquals(6, os.bytesUsed)
        assertEquals("5a5a5a5a", hex(buf, 0, 4))
        assertEquals("0001", hex(buf, 4, 6))
    }

    @Test
    fun scratchBytesNeverReachTheMessage() {
        // A multi-byte varint is assembled with an eight-byte store, so bytes past
        // bytesUsed may be scratch — but never part of the message.
        val buf = ByteArray(64)
        val os = OStream(buf)
        os.writeUnsigned(0, 300)
        assertEquals(3, os.bytesUsed)
        assertEquals("00ac02", hex(buf, 0, os.bytesUsed))
        os.writeUnsigned(1, 1)
        assertEquals("00ac020801", hex(buf, 0, os.bytesUsed))
    }

    @Test
    fun resetRestoresDepthAndPendingRun() {
        val buf = ByteArray(64)
        val os = OStream(buf)
        os.writeSequenceBeginLazy(1)
        os.writeSequenceBeginLazy(2)
        os.reset(buf)
        os.writeUnsigned(0, 1)
        // No stale sequence header prepended, and the cursor is back at zero.
        assertEquals("0001", hex(buf, 0, os.bytesUsed))
    }

    @Test
    fun idMaxRoundTrips() {
        val wire = encode { it.writeUnsigned(Sofab.ID_MAX, 1) }
        assertEquals(listOf("u:${Sofab.ID_MAX}:1"), decodeEvents(wire))
        assertTrue(wire.size <= 6)
    }
}

/** Little-endian bytes of a 32-bit pattern, for building expectations. */
internal fun le32(bits: Int): ByteArray = ByteArray(4).also { leSetInt(it, 0, bits) }

/** Little-endian bytes of a 64-bit pattern, for building expectations. */
internal fun le64(bits: Long): ByteArray = ByteArray(8).also { leSetLong(it, 0, bits) }
