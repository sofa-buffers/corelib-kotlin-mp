/*
 * SofaBuffers Kotlin Multiplatform — the streaming guarantees of CORELIB_PLAN
 * §5.1 / §5.2 and the chunked test kinds of §7.2 item 4.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingTest {

    /** Everything a flush divides: a 10-byte varint, a long string, an fp64, an array. */
    private fun message(os: OStream) {
        os.writeUnsigned(1, -1L) // 10-byte varint
        os.writeString(2, "the quick brown fox jumps over the lazy dog")
        os.writeFp64(3, 3.141592653589793)
        os.writeArrayUnsigned(4, longArrayOf(1, 300, 70000, Long.MIN_VALUE))
        os.writeSequenceBeginLazy(5)
        os.writeBlob(0, ByteArray(37) { it.toByte() })
        os.writeSequenceEnd()
    }

    private fun oneShot(): ByteArray = encode(512) { message(it) }

    @Test
    fun minOutputBufferIsDeclaredAndWithinTheCeiling() {
        assertTrue(Sofab.MIN_OUTPUT_BUFFER >= 1, "a declaration below 1 is meaningless")
        assertTrue(Sofab.MIN_OUTPUT_BUFFER <= 20, "§5.1 caps the declaration at 20")
    }

    @Test
    fun encodingAtExactlyTheMinimumMatchesTheOneShotOutput() {
        // §7.2 item 4: the port's own declared minimum is the size that proves the
        // constant is real. The message carries a string and a blob longer than the
        // buffer, so a divisible run is what the flush splits.
        assertContentEquals(oneShot(), encodeStreaming(Sofab.MIN_OUTPUT_BUFFER) { message(it) })
    }

    @Test
    fun everySizeAtOrAboveTheMinimumMatchesTheOneShotOutput() {
        val reference = oneShot()
        for (size in intArrayOf(1, 2, 3, 7, 11, 20, 64, 4096)) {
            if (size < Sofab.MIN_OUTPUT_BUFFER) continue
            assertContentEquals(reference, encodeStreaming(size) { message(it) }, "buffer size $size")
        }
    }

    @Test
    fun anUndersizedSinkBufferIsRejectedWhereItIsHandedOver() {
        val room = Sofab.MIN_OUTPUT_BUFFER - 1
        val buf = ByteArray(8)
        val sink = FlushSink { _, _, _ -> }
        assertFailsWith<IllegalArgumentException> { OStream(buf, buf.size - room, sink) }
        // ...and at every mid-stream buffer-set, not partway through a message.
        val os = OStream(ByteArray(8), 0, sink)
        assertFailsWith<IllegalArgumentException> { os.bufferSet(buf, buf.size - room) }
    }

    @Test
    fun aSinklessBufferIsSubjectToNoMinimum() {
        // The converse, and the reason the guard is confined to the sink path: no
        // flush can occur, so a caller sizing from a generated MAX_SIZE stays exact.
        val room = Sofab.MIN_OUTPUT_BUFFER - 1
        val buf = ByteArray(8)
        val reserved = OStream(buf, buf.size - room)
        assertEquals(buf.size - room, reserved.bytesUsed)

        val tight = ByteArray(2)
        val os = OStream(tight)
        os.writeUnsigned(1, 1)
        assertEquals(2, os.bytesUsed)
        assertContentEquals(byteArrayOf(0x08, 0x01), tight)
    }

    @Test
    fun aZeroRoomSinkBufferNeverReachesTheSink() {
        var flushes = 0
        val buf = ByteArray(4) { 0x5A }
        assertFailsWith<IllegalArgumentException> { OStream(buf, 4, { _, _, _ -> flushes++ }) }
        assertEquals(0, flushes)
    }

    @Test
    fun aCopyingSinkResumesInTheSameBuffer() {
        // §5.1: returning without installing a buffer says the sink copied, so the
        // encoder resumes at offset 0 in the buffer that is still active.
        val out = ArrayList<Byte>()
        val buf = ByteArray(4)
        val os = OStream(buf, 0, { data, off, len ->
            assertSame(buf, data, "a sink without pass-through only ever sees its own buffer")
            for (i in off until off + len) out.add(data[i])
        })
        message(os)
        os.flush()
        assertContentEquals(oneShot(), out.toByteArray())
    }

    @Test
    fun aTakingSinkInstallsAReplacement() {
        // §5.1's zero-copy handover: the sink takes the buffer it was handed, scrubs
        // it, and installs a fresh one before returning. An encoder that kept writing
        // into the buffer it gave away would read back the fill pattern — which the
        // one-byte-buffer test above could never notice, since that sink copies.
        val out = ArrayList<Byte>()
        var stream: OStream? = null
        val buf = ByteArray(8)
        val os = OStream(buf, 0, { data, off, len ->
            for (i in off until off + len) out.add(data[i])
            data.fill(0x5A.toByte()) // the transport now owns these bytes
            stream!!.bufferSet(ByteArray(8), 0)
        })
        stream = os
        message(os)
        os.flush()
        assertContentEquals(oneShot(), out.toByteArray())
    }

    @Test
    fun theStartOffsetBelongsToTheInstallation() {
        // A sink that wants framing-header room in every flushed unit re-arms it by
        // installing a buffer on each flush; a bare return resumes at 0.
        val units = ArrayList<ByteArray>()
        var stream: OStream? = null
        val buf = ByteArray(12)
        val os = OStream(buf, 2, { data, off, len ->
            units.add(data.copyOfRange(off, off + len))
            stream!!.bufferSet(data, 2) // same array, new installation
        })
        stream = os
        message(os)
        os.flush()
        // Every unit begins with its own two reserved bytes; strip them and the
        // message is exactly the one-shot encoding.
        val body = ArrayList<Byte>()
        for (u in units) {
            assertTrue(u.size >= 2, "each unit carries its header reservation")
            for (i in 2 until u.size) body.add(u[i])
        }
        assertContentEquals(oneShot(), body.toByteArray())
    }

    @Test
    fun noForeignMemoryReachesASinkThatWasNotGrantedPassThrough() {
        // §7.2 item 4: this port never passes a payload run through, so every
        // callback argument must be the installed buffer itself.
        val buf = ByteArray(64)
        val big = ByteArray(4096) { it.toByte() }
        var calls = 0
        val os = OStream(buf, 0, { data, _, _ ->
            assertSame(buf, data)
            calls++
        })
        os.writeBlob(1, big)
        os.flush()
        assertTrue(calls > 1, "a 4 KiB blob through a 64-byte buffer flushes repeatedly")
    }

    // --- decode side ---------------------------------------------------------

    @Test
    fun decodingOneByteAtATimeMatchesOneFeed() {
        val wire = oneShot()
        val whole = decodeEvents(wire)
        for (chunk in intArrayOf(1, 2, 3, 5, 7, 13)) {
            assertEquals(whole, decodeEventsChunked(wire, chunk), "chunk size $chunk")
        }
    }

    @Test
    fun aFedChunkIsBorrowedOnlyForTheCall() {
        // CORELIB_PLAN §6: once feed returns, the caller may scrub the chunk and the
        // decoded message must be unaffected. A decoder that kept a slice into a fed
        // chunk reads back the fill pattern, and nothing else in this file notices.
        val wire = oneShot()
        val expected = decodeEvents(wire)
        val v = RecordingVisitor()
        val input = IStream()
        val chunkSize = 5
        val scratch = ByteArray(chunkSize)
        var i = 0
        while (i < wire.size) {
            val n = minOf(chunkSize, wire.size - i)
            wire.copyInto(scratch, 0, i, i + n)
            input.feed(scratch, 0, n, v)
            scratch.fill(0x5A.toByte()) // the caller reuses its buffer immediately
            i += n
        }
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(expected, v.events)
    }

    @Test
    fun aBoundaryInsideAnArrayCostsOneElementNotTheRemainder() {
        // The two decode paths are byte-for-byte equivalent, so the only observable
        // difference is how much work the byte-at-a-time machine did.
        val src = LongArray(200) { (it + 1).toLong() * 0x9E37_79B9L }
        val wire = encode(4096) { it.writeArrayUnsigned(1, src) }
        val split = wire.size / 2
        val v = RecordingVisitor()
        val input = IStream()
        input.feed(wire, 0, split, v)
        input.feed(wire, split, wire.size - split, v)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(src.size + 1, v.events.size)
        assertTrue(
            input.machineBytes <= 10,
            "one straddling element at most, not the array's remainder (was ${input.machineBytes})",
        )
    }

    @Test
    fun aLargePayloadArrivesInChunks() {
        val payload = ByteArray(10_000) { (it * 31).toByte() }
        val wire = encode(16_384) { it.writeBlob(7, payload) }
        val seen = ByteArray(payload.size)
        var chunks = 0
        val v = object : Visitor {
            override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
                assertEquals(payload.size, total)
                data.copyInto(seen, offset, chunkOffset, chunkOffset + chunkLength)
                chunks++
            }
        }
        val input = IStream()
        var i = 0
        while (i < wire.size) {
            val n = minOf(512, wire.size - i)
            input.feed(wire, i, n, v)
            i += n
        }
        assertTrue(chunks > 1, "a 10 kB payload fed in 512-byte chunks arrives in pieces")
        assertContentEquals(payload, seen)
        assertEquals(DecodeStatus.COMPLETE, input.status)
    }

    @Test
    fun resetLetsOneDecoderRunManyMessages() {
        val a = encode { it.writeUnsigned(1, 7) }
        val b = encode { it.writeString(2, "hi") }
        val input = IStream()
        val v1 = RecordingVisitor()
        input.feed(a, v1)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        input.reset()
        val v2 = RecordingVisitor()
        input.feed(b, v2)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(listOf("u:1:7"), v1.events)
        assertEquals(listOf("str:2:hi"), v2.events)
    }
}
