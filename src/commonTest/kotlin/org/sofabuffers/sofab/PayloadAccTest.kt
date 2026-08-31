/*
 * SofaBuffers Kotlin Multiplatform — the support layer's growable byte sink:
 * chunk reassembly on decode, flush-sink drain on encode.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadAccTest {

    /**
     * The two numbers every decode call carries (CORELIB_PLAN §6.2.1). This test
     * class is the caller, so it states them: [NO_MAXLEN] is what generated code
     * passes for a field whose schema declares no `maxlen`, and [RCAP] is a
     * receiver cap standing in for the deployment's configured one — bigger than
     * any payload here, so nothing but the two limit tests ever meets it.
     */
    private val NO_MAXLEN = -1

    /** @see NO_MAXLEN */
    private val RCAP = 1 shl 20

    /** One, two, three and four-byte sequences, an embedded NUL and an ASCII tail. */
    private val text = "h\u00E9\u20AC\uD834\uDD1E\u0000!"
    private val payload = text.encodeToByteArray()

    /**
     * Feed [bytes] to [acc] as a `string` payload cut at [at], the way a chunked
     * decode delivers it: the first chunk from offset 0, the second from offset
     * [at]. The value comes back on exactly one of the two chunks.
     */
    private fun splitString(acc: PayloadAcc, bytes: ByteArray, at: Int): String? {
        val head = acc.string(bytes.size, 0, bytes, 0, at, NO_MAXLEN, RCAP)
        if (head != null) return head
        return acc.string(bytes.size, at, bytes, at, bytes.size - at, NO_MAXLEN, RCAP)
    }

    private fun splitBlob(acc: PayloadAcc, bytes: ByteArray, at: Int): ByteArray? {
        val head = acc.blob(bytes.size, 0, bytes, 0, at, NO_MAXLEN, RCAP)
        if (head != null) return head
        return acc.blob(bytes.size, at, bytes, at, bytes.size - at, NO_MAXLEN, RCAP)
    }

    // -----------------------------------------------------------------------
    // Decode: reassembly
    // -----------------------------------------------------------------------

    @Test
    fun aPayloadArrivingWholeNeverTouchesTheBuffer() {
        val acc = PayloadAcc()
        assertEquals(text, acc.string(payload.size, 0, payload, 0, payload.size, NO_MAXLEN, RCAP))
        assertEquals(0, acc.size, "an accumulator that is never needed buffers nothing")
        assertContentEquals(payload, acc.blob(payload.size, 0, payload, 0, payload.size, NO_MAXLEN, RCAP))
        assertEquals(0, acc.size)
    }

    @Test
    fun everySplitOfOnePayloadYieldsTheSameString() {
        // CORELIB_PLAN §6.4: the outcome must not depend on where the chunk
        // boundary fell — including a boundary inside a multi-byte character, which
        // is why validation runs on the reassembled payload and not per chunk.
        for (at in 0..payload.size) {
            val acc = PayloadAcc()
            assertEquals(text, splitString(acc, payload, at), "split at $at")
        }
        // And with one accumulator reused across every split, as a visitor's is.
        val reused = PayloadAcc()
        for (at in 0..payload.size) {
            assertEquals(text, splitString(reused, payload, at), "reused, split at $at")
        }
    }

    @Test
    fun everySplitOfOnePayloadYieldsTheSameBlob() {
        for (at in 0..payload.size) {
            val acc = PayloadAcc()
            assertContentEquals(payload, splitBlob(acc, payload, at), "split at $at")
        }
    }

    @Test
    fun oneBytePerChunkReassemblesToo() {
        // The extreme of the same rule: a payload delivered one byte at a time.
        val acc = PayloadAcc()
        for (i in 0 until payload.size - 1) {
            assertNull(acc.string(payload.size, i, payload, i, 1, NO_MAXLEN, RCAP), "byte $i completes nothing")
        }
        val last = payload.size - 1
        assertEquals(text, acc.string(payload.size, last, payload, last, 1, NO_MAXLEN, RCAP))
    }

    @Test
    fun aChunkIsReadFromItsOffsetWithinTheCallersBuffer() {
        // `chunkOffset` indexes the decoder's input buffer and has nothing to do
        // with the payload length, so it can sit at or past `total` — a case the
        // shared vectors do not reach. Both paths, whole and split, must read from
        // it rather than from 0.
        val framed = ByteArray(payload.size + 40) { 0x7f }
        payload.copyInto(framed, 32)
        val acc = PayloadAcc()
        assertEquals(text, acc.string(payload.size, 0, framed, 32, payload.size, NO_MAXLEN, RCAP))
        assertNull(acc.string(payload.size, 0, framed, 32, 3, NO_MAXLEN, RCAP))
        assertEquals(text, acc.string(payload.size, 3, framed, 35, payload.size - 3, NO_MAXLEN, RCAP))
        assertContentEquals(payload, acc.blob(payload.size, 0, framed, 32, payload.size, NO_MAXLEN, RCAP))
    }

    @Test
    fun anEmptyPayloadCompletesOnItsOneChunk() {
        // A zero-length string or blob is reported once, with total == 0.
        val acc = PayloadAcc()
        assertEquals("", acc.string(0, 0, payload, 0, 0, NO_MAXLEN, RCAP))
        assertContentEquals(ByteArray(0), acc.blob(0, 0, payload, 0, 0, NO_MAXLEN, RCAP))
    }

    @Test
    fun invalidUtf8IsRejectedWhereverTheSplitFalls() {
        // `C3 28` is a lead byte followed by a non-continuation and `ED A0 80` is a
        // surrogate: both are invalid whole and invalid split, and neither may come
        // back repaired as U+FFFD (MESSAGE_SPEC §8).
        val cases = listOf(
            byteArrayOf(0x68, 0xC3.toByte(), 0x28),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        for (bad in cases) {
            for (at in 0..bad.size) {
                val acc = PayloadAcc()
                val e = assertFailsWith<SofabException>("split at $at") { splitString(acc, bad, at) }
                assertEquals(SofabError.INVALID_MSG, e.error)
            }
            // The same bytes as a blob are not text, and stay legal.
            assertContentEquals(bad, PayloadAcc().blob(bad.size, 0, bad, 0, bad.size, NO_MAXLEN, RCAP))
        }
    }

    @Test
    fun aPayloadThatNeverCompletedDoesNotPrefixTheNextOne() {
        // A stream that ended mid-field leaves bytes in the buffer. The next
        // payload starts at offset 0, and that is where they are dropped — there is
        // no separate re-arming step for a caller to forget.
        val acc = PayloadAcc()
        assertNull(acc.string(payload.size, 0, payload, 0, 2, NO_MAXLEN, RCAP))
        assertEquals(2, acc.size)
        assertNull(acc.string(4, 0, byteArrayOf(0x61, 0x62), 0, 2, NO_MAXLEN, RCAP))
        assertEquals("abcd", acc.string(4, 2, byteArrayOf(0x63, 0x64), 0, 2, NO_MAXLEN, RCAP))
    }

    @Test
    fun aReturnedBlobIsTheCallersToKeep() {
        // Never a view into the decoder's input buffer or into the accumulator: a
        // later chunk arriving in the same array must not rewrite a value already
        // handed over.
        val src = byteArrayOf(1, 2, 3, 4)
        val whole = PayloadAcc().blob(4, 0, src, 0, 4, NO_MAXLEN, RCAP)!!
        val split = splitBlob(PayloadAcc(), src, 2)!!
        src.fill(9)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), whole)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), split)
    }

    @Test
    fun aLongPayloadCostsNoMoreThanItsAnnouncedLength() {
        // The buffer doubles against bytes that have actually arrived and stops at
        // the announced total, so a payload that arrives whole after one partial
        // chunk lands in an exactly-sized buffer.
        val big = ByteArray(1000) { (it and 0x7f).toByte() }
        val acc = PayloadAcc()
        assertNull(acc.blob(big.size, 0, big, 0, 1, NO_MAXLEN, RCAP))
        assertContentEquals(big, acc.blob(big.size, 1, big, 1, big.size - 1, NO_MAXLEN, RCAP))
    }

    // -----------------------------------------------------------------------
    // The two bounds the caller passes in (CORELIB_PLAN §6.2.1)
    // -----------------------------------------------------------------------

    @Test
    fun anUnboundedPayloadAboveTheReceiverCapIsLimitExceeded() {
        // The cap is the CALLER's number, applied at the length header: the
        // rejection lands on the first chunk, before a byte is buffered, and the
        // category is the policy one — the same bytes decode for a receiver
        // configured more loosely, so INVALID_MSG would be wrong (§6.3).
        for (acc in listOf(PayloadAcc(), PayloadAcc())) {
            val e = assertFailsWith<SofabException> { acc.string(9, 0, payload, 0, 1, NO_MAXLEN, 8) }
            assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
            assertEquals(0, acc.size, "rejected at the header, before anything is buffered")
        }
        val acc = PayloadAcc()
        val e = assertFailsWith<SofabException> { acc.blob(9, 0, payload, 0, 1, NO_MAXLEN, 8) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        assertEquals(0, acc.size)
    }

    @Test
    fun aPayloadAtTheReceiverCapIsAccepted() {
        // Rejected, never clamped, and the boundary is `>` and not `>=`: a payload
        // of exactly the configured length is what the configuration allows.
        val eight = ByteArray(8) { 0x61 }
        assertEquals("aaaaaaaa", PayloadAcc().string(8, 0, eight, 0, 8, NO_MAXLEN, 8))
        assertContentEquals(eight, PayloadAcc().blob(8, 0, eight, 0, 8, NO_MAXLEN, 8))
    }

    @Test
    fun aDeclaredMaxlenIsMalformedInputAndNotAPolicyRejection() {
        // A schema `maxlen` is a statement about validity (MESSAGE_SPEC §7.1): a
        // longer payload contradicts the schema both peers agreed on, so it is
        // INVALID_MSG and never LIMIT_EXCEEDED, which would promise a limit to
        // raise.
        val acc = PayloadAcc()
        val e = assertFailsWith<SofabException> { acc.string(9, 0, payload, 0, 1, 8, RCAP) }
        assertEquals(SofabError.INVALID_MSG, e.error)
        assertEquals(0, acc.size)
        val eb = assertFailsWith<SofabException> { PayloadAcc().blob(9, 0, payload, 0, 1, 8, RCAP) }
        assertEquals(SofabError.INVALID_MSG, eb.error)
    }

    @Test
    fun theReceiverCapIsNeverAppliedToASchemaBoundedField() {
        // §6.2.1: the caps govern only fields the schema left unbounded. A payload
        // inside its declared maxlen decodes whatever the receiver cap says, so the
        // two can never both be in play — which is what the pair of arguments makes
        // structural rather than a caller's discipline.
        val eight = ByteArray(8) { 0x62 }
        assertEquals("bbbbbbbb", PayloadAcc().string(8, 0, eight, 0, 8, 16, 2))
        assertContentEquals(eight, PayloadAcc().blob(8, 0, eight, 0, 8, 16, 2))
    }

    @Test
    fun aSplitPayloadIsRejectedOnItsFirstChunk() {
        // The check is at the length header, not at completion: an over-cap payload
        // arriving one byte at a time never gets to accumulate its first byte.
        val acc = PayloadAcc()
        val e = assertFailsWith<SofabException> { acc.blob(1000, 0, payload, 0, 1, NO_MAXLEN, 999) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        assertEquals(0, acc.size)
    }

    @Test
    fun anUnstatedReceiverCapIsAnArgumentErrorAndNotALimit() {
        // §6.2.1 requires the caller to state the cap and forbids reading an
        // omitted one as unlimited, so a negative rmaxlen still decodes nothing.
        // But it is NOT LIMIT_EXCEEDED: that category means "raise my limit, or
        // the sender must send less" and so presupposes a limit somebody set.
        // Reporting an absent cap as one names a receiver policy the deployment
        // never configured and promises a limit to raise that does not exist. The
        // mistake is in the CALL, which is §6.3's InvalidArgument.
        for (rmax in listOf(-1, Int.MIN_VALUE)) {
            val acc = PayloadAcc()
            val e = assertFailsWith<SofabException> { acc.string(1, 0, payload, 0, 1, NO_MAXLEN, rmax) }
            assertEquals(SofabError.ARGUMENT, e.error)
            assertEquals(0, acc.size, "fail-closed: nothing is buffered either")

            val be = assertFailsWith<SofabException> { acc.blob(1, 0, payload, 0, 1, NO_MAXLEN, rmax) }
            assertEquals(SofabError.ARGUMENT, be.error)
            assertEquals(0, acc.size)
        }
    }

    @Test
    fun anUnstatedCapRefusesEvenAnEmptyPayload() {
        // The one payload a cap of 0 admits is still refused when no cap was
        // stated at all — the negative number is the absence of a limit, never the
        // loosest one.
        val acc = PayloadAcc()
        val e = assertFailsWith<SofabException> { acc.string(0, 0, payload, 0, 0, NO_MAXLEN, -1) }
        assertEquals(SofabError.ARGUMENT, e.error)
        val be = assertFailsWith<SofabException> { acc.blob(0, 0, payload, 0, 0, NO_MAXLEN, -1) }
        assertEquals(SofabError.ARGUMENT, be.error)
    }

    @Test
    fun aCapOfZeroIsAStatedLimitAndNotAnAbsentOne() {
        // The line between the two categories is at 0, not below it: a receiver
        // that configured 0 admits the empty payload and refuses every other one,
        // and that refusal is a real limit to raise.
        assertEquals("", PayloadAcc().string(0, 0, payload, 0, 0, NO_MAXLEN, 0))
        assertContentEquals(ByteArray(0), PayloadAcc().blob(0, 0, payload, 0, 0, NO_MAXLEN, 0))
        val e = assertFailsWith<SofabException> { PayloadAcc().string(1, 0, payload, 0, 1, NO_MAXLEN, 0) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
    }

    @Test
    fun aSchemaBoundedFieldIsUnaffectedByAnUnstatedReceiverCap() {
        // §6.2.1: where the schema bounds the field the receiver cap is not in
        // play at all, so the absent-cap check must not leak into that branch and
        // turn a perfectly good decode into an argument error.
        val eight = ByteArray(8) { 0x63 }
        assertEquals("cccccccc", PayloadAcc().string(8, 0, eight, 0, 8, 16, -1))
        assertContentEquals(eight, PayloadAcc().blob(8, 0, eight, 0, 8, 16, -1))
    }

    // -----------------------------------------------------------------------
    // Encode: draining a flush sink
    // -----------------------------------------------------------------------

    @Test
    fun theAccumulatorDrainsAFlushSinkIntoTheWholeMessage() {
        // The second job: a message the schema cannot bound is written through a
        // small scratch buffer, and what comes out has to be byte-identical to the
        // one-shot encode of the same message (CORELIB_PLAN §7.2 item 4).
        val write: (OStream) -> Unit = { os ->
            os.writeUnsigned(1, 42)
            os.writeString(2, "a rather longer string than the scratch buffer holds")
            os.writeArrayUnsigned(3, LongArray(64) { it.toLong() * 7 })
        }
        val oneShot = encode(512, write)
        val acc = PayloadAcc()
        val os = OStream(ByteArray(Sofab.MIN_OUTPUT_BUFFER), 0, acc)
        write(os)
        os.flush()
        assertEquals(oneShot.size, acc.size)
        assertContentEquals(oneShot, acc.toByteArray())
    }

    @Test
    fun writeGrowsFromNothingAndKeepsWhatItHas() {
        val acc = PayloadAcc()
        assertEquals(0, acc.size)
        assertEquals(0, acc.toByteArray().size)
        val src = ByteArray(300) { it.toByte() }
        var written = 0
        while (written < src.size) {
            val n = minOf(7, src.size - written)
            acc.write(src, written, n)
            written += n
        }
        assertEquals(src.size, acc.size)
        assertContentEquals(src, acc.toByteArray())
    }

    @Test
    fun toByteArrayCopiesAndResetRearms() {
        val acc = PayloadAcc()
        acc.write(byteArrayOf(1, 2, 3), 0, 3)
        val first = acc.toByteArray()
        first[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), acc.toByteArray(), "the accumulator kept its own bytes")
        acc.reset()
        assertEquals(0, acc.size)
        acc.write(byteArrayOf(4), 0, 1)
        assertContentEquals(byteArrayOf(4), acc.toByteArray())
    }

    @Test
    fun anAccumulatorIsAFlushSink() {
        // Handing one to OStream as its sink is the whole point of the encode job,
        // and it copies rather than taking the buffer: the encoder keeps writing
        // into the same array, so a sink that kept a reference would alias it.
        val acc = PayloadAcc()
        val sink: FlushSink = acc
        val buf = byteArrayOf(1, 2, 3, 4)
        sink.flush(buf, 1, 2)
        buf.fill(0)
        assertTrue(acc.size == 2)
        assertContentEquals(byteArrayOf(2, 3), acc.toByteArray())
    }
}
