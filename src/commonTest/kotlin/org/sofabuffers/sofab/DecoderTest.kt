/*
 * SofaBuffers Kotlin Multiplatform — decoder outcome tests.
 *
 * The three-valued outcome of CORELIB_PLAN §5.2 is what this file holds the
 * decoder to: malformed input is INVALID and terminal, truncated input is
 * INCOMPLETE and resumable, and non-canonical-but-well-formed input decodes
 * rather than being rejected (§7.2 test kinds 5, 5b and 6).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoderTest {

    private fun invalid(wire: ByteArray, why: String) {
        val input = IStream()
        val e = assertFailsWith<SofabException>(why) { input.feed(wire, RecordingVisitor()) }
        assertEquals(SofabError.INVALID_MSG, e.error, why)
        assertEquals(DecodeStatus.INVALID, input.status, "$why: the verdict must latch")
    }

    private fun incomplete(wire: ByteArray, why: String) {
        val input = IStream()
        input.feed(wire, RecordingVisitor())
        assertEquals(DecodeStatus.INCOMPLETE, input.status, why)
    }

    // --- malformed input (§7.2 kind 5) --------------------------------------

    @Test
    fun overlongVarintIsInvalid() {
        invalid(unhex("00" + "80".repeat(10) + "00"), "an eleventh varint byte")
        invalid(unhex("00" + "80".repeat(9) + "02"), "a tenth byte with payload above 1")
        // Same two shapes with enough trailing room that the eight-byte fast path,
        // not the bounded reader, is what judges them.
        invalid(unhex("00" + "80".repeat(10) + "00" + "0001"), "overlong varint, fast path")
        invalid(unhex("00" + "80".repeat(9) + "02" + "0001"), "tenth-byte overflow, fast path")
    }

    @Test
    fun reservedFixlenSubtypeIsInvalid() {
        for (sub in 4..7) {
            invalid(byteArrayOf(0x02, ((0 shl 3) or sub).toByte()), "reserved fixlen subtype $sub")
        }
    }

    @Test
    fun wrongWidthFloatIsInvalid() {
        invalid(unhex("0228" + "0000000000"), "fp32 declaring 5 bytes")
        invalid(unhex("0221" + "0000"), "fp64 declaring 4 bytes")
        // CORELIB_PLAN §5.2's worked example: malformed *and* truncated is INVALID.
        invalid(unhex("560a59"), "a nested fp64 whose fixlen_word declares 11")
    }

    @Test
    fun danglingSequenceEndIsInvalid() {
        invalid(unhex("07"), "a sequence end with nothing open")
        invalid(unhex("0e0707"), "one end too many")
    }

    @Test
    fun nestingPastMaxDepthIsInvalid() {
        invalid(ByteArray(Sofab.MAX_DEPTH + 1) { 0x0e }, "256 nested sequences")
        // Exactly MAX_DEPTH is legal.
        val ok = IStream()
        ok.feed(ByteArray(Sofab.MAX_DEPTH) { 0x0e }, RecordingVisitor())
        assertEquals(DecodeStatus.INCOMPLETE, ok.status)
    }

    @Test
    fun idAboveIdMaxIsInvalid() {
        // The ceiling binds every header (§6.2) — including a sequence end, whose id
        // the decoder otherwise discards.
        val b = ByteArray(16)
        for (type in intArrayOf(T_VARINT_UNSIGNED, T_SEQUENCE_END)) {
            val n = putVarint(b, 0, (2147483648L shl 3) or type.toLong())
            invalid(b.copyOf(n), "id 2^31 on wire type $type")
        }
    }

    @Test
    fun oversizedCountOrLengthIsInvalid() {
        val b = ByteArray(24)
        var n = putVarint(b, 1, 2147483648L)
        b[0] = 0x03 // unsigned array header, id 0
        invalid(b.copyOf(n), "an array count above ARRAY_MAX")

        n = putVarint(b, 1, (2147483648L shl 3) or F_BLOB.toLong())
        b[0] = 0x02 // fixlen header, id 0
        invalid(b.copyOf(n), "a fixlen length above FIXLEN_MAX")
    }

    @Test
    fun dynamicSubtypeInAFixlenArrayIsInvalid() {
        // §4.8: string/blob are not valid fixlen-array elements. A format violation,
        // judged before the field is ever offered to the visitor.
        invalid(unhex("05010a41"), "a string element in a fixlen array")
        invalid(unhex("05010b41"), "a blob element in a fixlen array")
        // ...on the byte-at-a-time path too.
        val input = IStream()
        val wire = unhex("05010a41")
        val e = assertFailsWith<SofabException> {
            for (i in wire.indices) input.feed(wire, i, 1, RecordingVisitor())
        }
        assertEquals(SofabError.INVALID_MSG, e.error)
    }

    // --- truncation (§7.2 kind 6) -------------------------------------------

    @Test
    fun truncationIsIncompleteNotInvalid() {
        incomplete(unhex("80"), "a lone dangling varint byte")
        incomplete(unhex("00"), "a header with no value")
        incomplete(unhex("020a"), "a string payload shorter than declared")
        incomplete(unhex("0e"), "an unclosed sequence")
        incomplete(unhex("0e0001"), "a closed field inside an unclosed sequence")
        incomplete(unhex("0302"), "an array with elements still pending")
        incomplete(unhex("0501"), "a fixlen array cut between count and fixlen_word")
        incomplete(unhex("0220" + "0000"), "an fp32 payload cut in half")
    }

    @Test
    fun aFixlenWordCutAfterItsFirstByteIsIncomplete() {
        // §4.1: a decoder MUST NOT evaluate part of an incomplete varint, even when
        // the settled low three bits already name a reserved subtype (0x4..0x7).
        // Nothing else in the suite exercises the no-partial-evaluation rule.
        for (sub in 4..7) {
            incomplete(byteArrayOf(0x02, (0x80 or sub).toByte()), "fixlen_word cut, subtype $sub settled")
        }
        // And the same word, completed, is INVALID — so the rule is about timing.
        invalid(byteArrayOf(0x02, (0x80 or 4).toByte(), 0x00), "the completed reserved word")
    }

    @Test
    fun feedingTheMissingBytesCompletesIt() {
        val input = IStream()
        val v = RecordingVisitor()
        input.feed(unhex("020a"), v)
        assertEquals(DecodeStatus.INCOMPLETE, input.status)
        input.feed(unhex("41"), v)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(listOf("str:0:A"), v.events)
    }

    // --- tolerance (§7.2 kind 5b) -------------------------------------------

    @Test
    fun nonMinimalVarintsDecodeAndReEncodeCanonically() {
        // §4.1: minimal on encode, tolerant on decode. A non-minimal encoding is
        // normalized away, not rejected.
        assertEquals(listOf("u:0:1"), decodeEvents(unhex("800001"))) // header 0x00 as two bytes
        assertEquals(listOf("u:0:1"), decodeEvents(unhex("008100"))) // value 1 as two bytes
        assertEquals(listOf("str:0:A"), decodeEvents(unhex("028a0041"))) // fixlen_word padded
        assertEquals(listOf("arr:0:UNSIGNED:1", "u:0:1"), decodeEvents(unhex("03810001"))) // count padded
        // Padded to eleven bytes so the same words go through the fast path as well.
        assertEquals(listOf("u:0:1", "u:1:1"), decodeEvents(unhex("800001" + "0801")))
        // ...and a re-encode of what was decoded is the canonical form.
        assertEquals("0001", hex(encode { it.writeUnsigned(0, 1) }))
    }

    @Test
    fun aSequenceEndWithANonZeroIdStillCloses() {
        // §4.9: the id of a sequence end is discarded — but still bounded by ID_MAX.
        assertEquals(listOf("seq{:1", "seq}"), decodeEvents(unhex("0e3f")))
        val input = feedAll(unhex("0e3f"))
        assertEquals(DecodeStatus.COMPLETE, input.status)
        // A conformant re-encode is the single byte 0x07.
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

    // --- INVALID is terminal -------------------------------------------------

    @Test
    fun invalidIsTerminalUntilReset() {
        val input = IStream()
        val v = RecordingVisitor()
        assertFailsWith<SofabException> { input.feed(unhex("07"), v) }
        assertEquals(DecodeStatus.INVALID, input.status)
        // Well-formed bytes afterwards do not revive it.
        val again = assertFailsWith<SofabException> { input.feed(unhex("0001"), v) }
        assertEquals(SofabError.INVALID_MSG, again.error)
        assertEquals(DecodeStatus.INVALID, input.status)
        input.reset()
        assertEquals(DecodeStatus.COMPLETE, input.status)
        input.feed(unhex("0001"), v)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(listOf("u:0:1"), v.events)
    }

    @Test
    fun aSchemaBoundRejectionFromTheVisitorLatchesToo() {
        // MESSAGE_SPEC §7: generated code reports an over-maxlen string, an
        // over-count array or an over-width scalar as the same INVALID outcome.
        val strict = object : Visitor {
            override fun fixlenBegin(id: Int, subtype: FixlenType, total: Int) {
                if (total > 2) throw SofabException(SofabError.INVALID_MSG, "maxlen 2")
            }
        }
        val input = IStream()
        val e = assertFailsWith<SofabException> { input.feed(unhex("021a414243"), strict) }
        assertEquals(SofabError.INVALID_MSG, e.error)
        assertEquals(DecodeStatus.INVALID, input.status)
    }

    @Test
    fun aReceiverLimitRejectionDoesNotLatch() {
        // §6.2.1: LIMIT_EXCEEDED is policy about well-formed bytes, a category
        // distinct from INVALID — so it must not be folded into the decode outcome.
        val capped = object : Visitor {
            var trip = true
            override fun fixlenBegin(id: Int, subtype: FixlenType, total: Int) {
                if (trip && total > 2) {
                    trip = false
                    throw SofabException(SofabError.LIMIT_EXCEEDED, "max_dyn_string_len")
                }
            }
        }
        val input = IStream()
        val e = assertFailsWith<SofabException> { input.feed(unhex("021a414243"), capped) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        assertTrue(input.status != DecodeStatus.INVALID, "a policy rejection is not a wire verdict")
    }

    // --- the fixlen/array announcement order (§4.8) --------------------------

    @Test
    fun aFixlenArrayIsAnnouncedOnlyOnceTheSubtypeIsKnown() {
        val seen = mutableListOf<String>()
        val v = object : Visitor {
            override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
                seen.add("begin:$kind:$count")
            }
            override fun fp32Bits(id: Int, bits: Int) {
                seen.add("elem")
            }
        }
        // A message ending between the count and the fixlen_word announces nothing
        // at all: INCOMPLETE, not INVALID, and no arrayBegin.
        val cut = IStream()
        cut.feed(unhex("0502"), v)
        assertEquals(DecodeStatus.INCOMPLETE, cut.status)
        assertEquals(emptyList(), seen)
        // With the word in hand the kind is the concrete subtype.
        IStream().feed(unhex("050220") + le32(0) + le32(0), v)
        assertEquals(listOf("begin:FP32:2", "elem", "elem"), seen)
    }

    @Test
    fun anEmptyFixlenArrayStillConsumesItsWord() {
        assertEquals(listOf("arr:0:FP32:0"), decodeEvents(unhex("050020")))
        assertEquals(listOf("arr:0:FP64:0"), decodeEvents(unhex("050041")))
        assertEquals(DecodeStatus.COMPLETE, feedAll(unhex("050020")).status)
        // ...on the byte-at-a-time path as well.
        assertEquals(listOf("arr:0:FP64:0"), decodeEventsChunked(unhex("050041"), 1))
    }

    @Test
    fun everyFixlenFieldIsAnnouncedAtItsLengthWord() {
        val seen = mutableListOf<String>()
        val v = object : Visitor {
            override fun fixlenBegin(id: Int, subtype: FixlenType, total: Int) {
                seen.add("$subtype:$total")
            }
        }
        val wire = encode {
            it.writeFp32(0, 1f)
            it.writeFp64(1, 1.0)
            it.writeString(2, "")
            it.writeBlob(3, byteArrayOf(9))
        }
        IStream().feed(wire, v)
        assertEquals(listOf("FP32:4", "FP64:8", "STRING:0", "BLOB:1"), seen)
        // The announcement survives a chunk boundary landing on the length word.
        seen.clear()
        val input = IStream()
        for (i in wire.indices) input.feed(wire, i, 1, v)
        assertEquals(listOf("FP32:4", "FP64:8", "STRING:0", "BLOB:1"), seen)
    }
}
