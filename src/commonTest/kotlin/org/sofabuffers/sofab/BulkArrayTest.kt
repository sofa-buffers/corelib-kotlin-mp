/*
 * SofaBuffers Kotlin Multiplatform — the Visitor.arrayBulk fast path.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BulkArrayTest {

    /** Takes the bulk offer with [dst] and records what the decoder did with it. */
    private class Bulk(private val dst: Any?) : Visitor {
        var ended = -1
        val perElement: MutableList<Long> = mutableListOf()

        override fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any? = dst

        override fun arrayBulkEnd(id: Int, n: Int) {
            ended = n
        }

        override fun unsigned(id: Int, value: Long) {
            perElement.add(value)
        }

        override fun signed(id: Int, value: Long) {
            perElement.add(value)
        }
    }

    @Test
    fun aLongDestinationTakesEveryElementWithNoPerElementCallback() {
        val src = longArrayOf(0, 1, 127, 128, 300, Long.MIN_VALUE, -1)
        val wire = encode(256) { it.writeArrayUnsigned(1, src) }
        val dst = LongArray(src.size)
        val v = Bulk(dst)
        IStream().feed(wire, v)
        assertContentEquals(src, dst)
        assertEquals(src.size, v.ended)
        assertTrue(v.perElement.isEmpty(), "a taken offer bypasses the per-element callbacks")
    }

    @Test
    fun signedElementsArriveZigZagDecoded() {
        val src = longArrayOf(0, -1, 1, -2, Long.MIN_VALUE, Long.MAX_VALUE)
        val wire = encode(256) { it.writeArraySigned(1, src) }
        val dst = LongArray(src.size)
        IStream().feed(wire, Bulk(dst))
        assertContentEquals(src, dst)
    }

    @Test
    fun narrowDestinationsNarrowAndCheck() {
        val wire = encode(256) { it.writeArrayUnsigned(1, intArrayOf(1, 2, 65535)) }
        val ints = IntArray(3)
        IStream().feed(wire, Bulk(ints))
        assertContentEquals(intArrayOf(1, 2, 65535), ints)

        val shorts = ShortArray(3)
        IStream().feed(wire, Bulk(shorts))
        assertContentEquals(shortArrayOf(1, 2, -1), shorts) // 65535 as a u16 bit pattern

        val bytes = ByteArray(3)
        val e = assertFailsWith<SofabException> { IStream().feed(wire, Bulk(bytes)) }
        assertEquals(SofabError.INVALID_MSG, e.error, "65535 does not fit a u8 destination")
    }

    @Test
    fun aValueWiderThanTheDestinationIsInvalidNotTruncated() {
        // MESSAGE_SPEC §7.1: the destination's width is a declared bound, so an
        // over-width value is malformed input, never silently masked.
        val wire = encode(256) { it.writeArrayUnsigned(1, longArrayOf(1, 1L shl 40)) }
        for (dst in listOf<Any>(IntArray(2), ShortArray(2), ByteArray(2))) {
            val input = IStream()
            val e = assertFailsWith<SofabException> { input.feed(wire, Bulk(dst)) }
            assertEquals(SofabError.INVALID_MSG, e.error)
            assertEquals(DecodeStatus.INVALID, input.status)
        }
        // The signed side tests the same statement from the other direction.
        val signed = encode(256) { it.writeArraySigned(1, longArrayOf(-1, Int.MIN_VALUE.toLong() - 1)) }
        assertFailsWith<SofabException> { IStream().feed(signed, Bulk(IntArray(2))) }
    }

    @Test
    fun aDeclinedOfferFallsBackToPerElementDelivery() {
        val src = longArrayOf(5, 6, 7)
        val wire = encode(256) { it.writeArrayUnsigned(1, src) }
        // null is the default decline; anything that is not one of the four
        // primitive integer arrays is not a destination at all.
        for (dst in listOf(null, FloatArray(3), "not an array")) {
            val v = Bulk(dst)
            IStream().feed(wire, v)
            assertEquals(listOf(5L, 6L, 7L), v.perElement, "destination $dst")
            assertEquals(-1, v.ended, "a declined offer never reports a bulk end")
        }
    }

    @Test
    fun aDestinationTooShortForTheAnnouncedCountIsInvalidArgument() {
        // §6.6.3's third refusal tier, §6.3's `InvalidArgument`: the bytes are
        // well-formed and break no bound they declare — the caller's storage is
        // what does not fit. Not INVALID_MSG (the message is fine, and a larger
        // destination decodes it), not LIMIT_EXCEEDED (no limit was configured to
        // raise), and never a silent fall-back to per-element delivery, which would
        // leave a miscounted consumer none the wiser.
        val wire = encode(256) { it.writeArrayUnsigned(1, longArrayOf(5, 6, 7)) }
        for (dst in listOf(LongArray(2), IntArray(2), ShortArray(2), ByteArray(2))) {
            val v = Bulk(dst)
            val e = assertFailsWith<SofabException> { IStream().feed(wire, v) }
            assertEquals(SofabError.ARGUMENT, e.error, "destination $dst")
            assertEquals(emptyList(), v.perElement, "no silent downgrade")
            assertEquals(-1, v.ended)
        }
        // The boundary: exactly `count` is enough, and one more is fine too.
        val exact = Bulk(LongArray(3))
        IStream().feed(wire, exact)
        assertEquals(3, exact.ended)
    }

    /**
     * Records every field the decoder actually delivered, in order, so a test can
     * assert on the *events* and not only on [IStream.status].
     */
    private class Recording(private val dst: Any?) : Visitor {
        val events: MutableList<String> = mutableListOf()
        var offers = 0

        override fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any? {
            offers++
            return dst
        }

        override fun arrayBulkEnd(id: Int, n: Int) {
            events.add("bulkEnd:$id:$n")
        }

        override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
            events.add("arr:$id:$kind:$count")
        }

        override fun unsigned(id: Int, value: Long) {
            events.add("u:$id:${value.toULong()}")
        }

        override fun signed(id: Int, value: Long) {
            events.add("s:$id:$value")
        }

        override fun sequenceBegin(id: Int) {
            events.add("seq{:$id")
        }

        override fun sequenceEnd() {
            events.add("seq}")
        }
    }

    /**
     * Finding CORELIB_KOTLIN_MP-01 — a destination-too-short refusal must latch.
     *
     * §6.3 lists the refusal itself as the third tier — *"broke neither, but does
     * not fit the destination the caller handed over (§6.6.3) | the codec |
     * `InvalidArgument`"* — and the sibling `LimitExceeded` tier as *"a terminal,
     * receiver-local policy rejection"* that *"terminates a decode on well-formed
     * input"*. This refusal is terminal in exactly the same way: the offer site has
     * been passed, the destination cannot be re-offered, and the array's elements
     * were never consumed. §5.2.1 then forbids the outcome the port leaves behind
     * — the consumed bytes do **not** end at a field boundary, so answering
     * `COMPLETE` is precisely the *"folding into `COMPLETE`"* it calls
     * non-conformant. §7.2 item 4 requires the byte-at-a-time path to agree with
     * the whole-input path on identical bytes.
     *
     * The array's elements are chosen so that, read as top-level field headers,
     * they form two well-formed unsigned fields (`08 2a` -> id 1 = 42, `10 63` ->
     * id 2 = 99). Nothing on the wire ever declared those fields: they exist only
     * if an unlatched decoder re-enters header parsing at the desynchronised
     * position the refusal left behind.
     */
    @Test
    fun corelibKotlinMp01ARefusedBulkDestinationLatchesAndInventsNoFields() {
        val wire = encode(256) { it.writeArrayUnsigned(1, longArrayOf(8, 42, 16, 99)) }
        // 0b = (1 << 3) | T_VARINTARRAY_UNSIGNED, 04 = count, then the four elements.
        assertEquals("0b04082a1063", hex(wire), "the bytes this finding is about")
        val elements = wire.copyOfRange(2, wire.size)

        // --- fed whole -------------------------------------------------------
        val whole = Recording(LongArray(2)) // two slots for the four the wire announces
        val input = IStream()
        val refused = assertFailsWith<SofabException> { input.feed(wire, whole) }
        assertEquals(SofabError.ARGUMENT, refused.error, "§6.3's third refusal tier")

        // (a) the verdict is terminal, and it is not COMPLETE: not one element was
        // decoded, so the consumed bytes do not end at a field boundary. INVALID is
        // equally wrong (the bytes are well-formed and a longer destination decodes
        // them), which leaves INCOMPLETE — the same answer the port already gives
        // for the LIMIT_EXCEEDED tier.
        assertTrue(
            input.status != DecodeStatus.COMPLETE,
            "a decode abandoned before its first element is not COMPLETE",
        )
        assertTrue(input.status != DecodeStatus.INVALID, "a §6.6.3 refusal is not a wire verdict")
        assertEquals(DecodeStatus.INCOMPLETE, input.status, "the message was abandoned, not completed")

        // The verdict the refusal left behind, captured here — after the refusal and
        // before the reset() further down clears it — because §7.2 item 4 compares
        // *this* against the byte-at-a-time decoder's own post-refusal verdict.
        val wholeStatus = input.status

        // (b) a further feed does not resume. These are the very bytes the refusal
        // skipped, arriving as the caller's next chunk; a latched decoder re-reports
        // the terminal verdict instead of parsing them.
        val again = assertFailsWith<SofabException> { input.feed(elements, whole) }
        assertEquals(SofabError.ARGUMENT, again.error, "the latched verdict is re-reported")
        assertEquals(1, whole.offers, "a latched stream makes no further bulk offer")
        assertEquals(DecodeStatus.INCOMPLETE, input.status, "and the verdict does not drift")

        // (c) no field the sender never wrote reached the visitor. The array header
        // was announced; nothing else was ever on the wire.
        assertEquals(
            listOf("arr:1:UNSIGNED:4"),
            whole.events,
            "the refused payload's own bytes must never surface as fields",
        )

        // reset() is the only way out, exactly as for the other two terminal codes.
        input.reset()
        assertEquals(DecodeStatus.COMPLETE, input.status)

        // --- fed one byte at a time (§7.2 item 4: identical result) -----------
        val chunked = Recording(LongArray(2))
        val stream = IStream()
        var thrown: SofabException? = null
        var i = 0
        while (i < wire.size) {
            try {
                stream.feed(wire, i, 1, chunked)
            } catch (e: SofabException) {
                thrown = e
                break
            }
            i++
        }
        assertEquals(SofabError.ARGUMENT, thrown?.error, "the same bytes refuse the same way")
        assertEquals(wholeStatus, stream.status, "whole-input and byte-at-a-time must agree")
        assertEquals(DecodeStatus.INCOMPLETE, stream.status)
        assertEquals(whole.events, chunked.events, "and must deliver the same events")

        // The same continuation, on the resumable path: still terminal, still silent.
        val resumed = assertFailsWith<SofabException> { stream.feed(elements, chunked) }
        assertEquals(SofabError.ARGUMENT, resumed.error)
        assertEquals(1, chunked.offers, "a latched stream makes no further bulk offer")
        assertEquals(listOf("arr:1:UNSIGNED:4"), chunked.events)

        // The control: the identical bytes against a destination that fits decode
        // cleanly, which is what makes the refusal a property of the call and not
        // of the message.
        val fits = Recording(LongArray(4))
        val ok = IStream()
        ok.feed(wire, fits)
        assertEquals(listOf("arr:1:UNSIGNED:4", "bulkEnd:1:4"), fits.events)
        assertEquals(DecodeStatus.COMPLETE, ok.status)
    }

    /**
     * The other half of CORELIB_KOTLIN_MP-01: the latch keys on the refusal
     * **site**, not on the error code.
     *
     * §6.3 makes `InvalidArgument` the catch-all for every caller mistake — an id
     * out of range, a bad scalar width, invalid UTF-8 — so a visitor raising it
     * from inside its own callback, for a reason of its own, says nothing about the
     * decode. Latching on the code would strand a caller whose message was
     * well-formed and whose destinations all fitted. Only [IStream.requireRoom]'s
     * §6.6.3 refusal is a decode verdict, and §5.3.1 wants that rule implemented in
     * exactly one place — which is where the flag is set.
     */
    @Test
    fun corelibKotlinMp01BVisitorsOwnArgumentErrorDoesNotLatchTheDecoder() {
        val first = encode(64) { it.writeUnsigned(1, 42) }
        val second = encode(64) { it.writeUnsigned(2, 99) }

        val thrower = object : Visitor {
            override fun unsigned(id: Int, value: Long) {
                throw SofabException(SofabError.ARGUMENT, "the caller's own complaint, not the codec's")
            }
        }
        val input = IStream()
        val raised = assertFailsWith<SofabException> { input.feed(first, thrower) }
        assertEquals(SofabError.ARGUMENT, raised.error)

        // Nothing the codec refused, so nothing is latched: the next message is
        // decoded, not answered with the terminal verdict.
        val seen = Recording(null)
        input.feed(second, seen)
        assertEquals(listOf("u:2:99"), seen.events, "a visitor's own error must not strand the decoder")
        assertEquals(DecodeStatus.COMPLETE, input.status)
    }

    @Test
    fun theOfferIsNotMadeForAnEmptyOrFloatArray() {
        var offers = 0
        val v = object : Visitor {
            override fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any? {
                offers++
                return null
            }
        }
        IStream().feed(encode { it.writeArrayUnsigned(1, LongArray(0)) }, v)
        IStream().feed(encode { it.writeArrayFp32(1, floatArrayOf(1f, 2f)) }, v)
        IStream().feed(encode { it.writeArrayFp64(1, doubleArrayOf(1.0)) }, v)
        assertEquals(0, offers)
    }

    @Test
    fun theBulkFillSurvivesEveryChunkBoundary() {
        val src = LongArray(64) { (it.toLong() + 1) * 0x9E37_79B9L }
        val wire = encode(1024) { it.writeArrayUnsigned(1, src) }
        for (chunk in intArrayOf(1, 2, 3, 5, 7)) {
            val dst = LongArray(src.size)
            val v = Bulk(dst)
            val input = IStream()
            var i = 0
            while (i < wire.size) {
                val n = minOf(chunk, wire.size - i)
                input.feed(wire, i, n, v)
                i += n
            }
            assertContentEquals(src, dst, "chunk $chunk")
            assertEquals(src.size, v.ended, "chunk $chunk")
            assertTrue(v.perElement.isEmpty(), "chunk $chunk")
            assertEquals(DecodeStatus.COMPLETE, input.status)
        }
    }

    @Test
    fun aLongerDestinationIsAcceptedAndOnlyCountElementsAreWritten() {
        val wire = encode(256) { it.writeArrayUnsigned(1, longArrayOf(1, 2)) }
        val dst = LongArray(5) { -1 }
        val v = Bulk(dst)
        IStream().feed(wire, v)
        assertContentEquals(longArrayOf(1, 2, -1, -1, -1), dst)
        assertEquals(2, v.ended)
    }
}
