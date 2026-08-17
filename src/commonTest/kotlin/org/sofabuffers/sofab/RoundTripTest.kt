/*
 * SofaBuffers Kotlin Multiplatform — round-trip and skip tests (§7.2 kinds 3, 7).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripTest {

    /** A message that reaches every wire type, nesting included. */
    private fun composite(os: OStream) {
        os.writeUnsigned(1, 0xDEAD_BEEFL)
        os.writeSigned(2, -12345)
        os.writeBoolean(3, true)
        os.writeFp32(4, 3.14159f)
        os.writeFp64(5, 2.718281828459045)
        os.writeString(6, "sofab — ä€𝄞")
        os.writeBlob(7, ByteArray(9) { (it * 7).toByte() })
        os.writeArrayUnsigned(8, longArrayOf(0, 1, 127, 128, Long.MIN_VALUE))
        os.writeArraySigned(9, intArrayOf(-1, 0, 1, Int.MIN_VALUE))
        os.writeArrayFp32(10, floatArrayOf(0f, -0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
        os.writeArrayFp64(11, doubleArrayOf(1.5, -1.5))
        os.writeSequenceBeginLazy(12)
        os.writeUnsigned(1, 99)
        os.writeSequenceBeginLazy(2)
        os.writeString(0, "nested")
        os.writeSequenceEnd()
        os.writeSequenceEnd()
    }

    private val expected = listOf(
        "u:1:3735928559",
        "s:2:-12345",
        "u:3:1",
        "f32:4:${3.14159f.toRawBits()}",
        "f64:5:${2.718281828459045.toRawBits()}",
        "str:6:sofab — ä€𝄞",
        "blob:7:00070e151c232a3138",
        "arr:8:UNSIGNED:5", "u:8:0", "u:8:1", "u:8:127", "u:8:128", "u:8:9223372036854775808",
        "arr:9:SIGNED:4", "s:9:-1", "s:9:0", "s:9:1", "s:9:-2147483648",
        "arr:10:FP32:4",
        "f32:10:${0f.toRawBits()}",
        "f32:10:${(-0f).toRawBits()}",
        "f32:10:${Float.POSITIVE_INFINITY.toRawBits()}",
        "f32:10:${Float.NEGATIVE_INFINITY.toRawBits()}",
        "arr:11:FP64:2", "f64:11:${1.5.toRawBits()}", "f64:11:${(-1.5).toRawBits()}",
        // Inside the sequence the ids start over: a sequence opens a fresh scope.
        "seq{:12", "u:1:99", "seq{:2", "str:0:nested", "seq}", "seq}",
    )

    @Test
    fun oneShotRoundTrip() {
        val wire = encode(512) { composite(it) }
        val v = RecordingVisitor()
        val input = feedAll(wire, v)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertEquals(expected, v.events)
    }

    @Test
    fun everyChunkingAgrees() {
        val wire = encode(512) { composite(it) }
        for (chunk in intArrayOf(1, 2, 3, 4, 5, 7, 11, 13, 32)) {
            assertEquals(expected, decodeEventsChunked(wire, chunk), "chunk size $chunk")
        }
    }

    @Test
    fun streamedEncodeEqualsOneShotAtEverySize() {
        val reference = encode(512) { composite(it) }
        for (size in intArrayOf(1, 2, 3, 5, 8, 13, 64, 512)) {
            assertContentEquals(reference, encodeStreaming(size) { composite(it) }, "buffer $size")
        }
    }

    @Test
    fun reEncodingWhatWasDecodedReproducesTheBytes() {
        // decode -> re-encode is byte-identical, which is the property a relay or a
        // transcoder depends on (and what §6.5's float rules exist to protect).
        val wire = encode(512) { composite(it) }
        val out = ByteArray(512)
        val os = OStream(out)
        val relay = ReEncoder(os)
        IStream().feed(wire, relay)
        assertContentEquals(wire, out.copyOf(os.bytesUsed))
    }
}

class SkipTest {

    /** Ignores the ids in [skip], and everything under a skipped sequence. */
    private class Skipping(private val skip: Set<Int>) : Visitor {
        val out = RecordingVisitor()
        private var depth = 0
        private var skipUntil = -1

        private fun keep(id: Int) = skipUntil < 0 && id !in skip

        override fun unsigned(id: Int, value: Long) {
            if (keep(id)) out.unsigned(id, value)
        }

        override fun signed(id: Int, value: Long) {
            if (keep(id)) out.signed(id, value)
        }

        override fun fp32Bits(id: Int, bits: Int) {
            if (keep(id)) out.fp32Bits(id, bits)
        }

        override fun fp64(id: Int, value: Double) {
            if (keep(id)) out.fp64(id, value)
        }

        override fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
            if (keep(id)) out.string(id, total, offset, data, chunkOffset, chunkLength)
        }

        override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
            if (keep(id)) out.blob(id, total, offset, data, chunkOffset, chunkLength)
        }

        override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
            if (keep(id)) out.arrayBegin(id, kind, count)
        }

        override fun sequenceBegin(id: Int) {
            if (skipUntil < 0 && id in skip) {
                skipUntil = depth
            } else if (skipUntil < 0) {
                out.sequenceBegin(id)
            }
            depth++
        }

        override fun sequenceEnd() {
            depth--
            if (skipUntil >= 0) {
                if (depth == skipUntil) skipUntil = -1
                return
            }
            out.sequenceEnd()
        }
    }

    private fun message(os: OStream) {
        os.writeUnsigned(1, 1)
        os.writeString(2, "skipped payload that is long enough to straddle a chunk")
        os.writeArrayUnsigned(3, longArrayOf(1, 2, 3, 400_000))
        os.writeSequenceBeginLazy(4)
        os.writeUnsigned(1, 9)
        os.writeSequenceBeginLazy(2)
        os.writeBlob(0, ByteArray(20))
        os.writeSequenceEnd()
        os.writeSequenceEnd()
        os.writeSigned(5, -7)
    }

    @Test
    fun skippedFieldsAndSubTreesResyncOnTheNextField() {
        val wire = encode(512) { message(it) }
        val v = Skipping(setOf(2, 3, 4))
        val input = IStream()
        input.feed(wire, v)
        assertEquals(DecodeStatus.COMPLETE, input.status, "the message is still fully consumed")
        assertEquals(listOf("u:1:1", "s:5:-7"), v.out.events)
    }

    @Test
    fun skippingSurvivesEveryChunkBoundary() {
        val wire = encode(512) { message(it) }
        for (chunk in intArrayOf(1, 3, 7)) {
            val v = Skipping(setOf(2, 3, 4))
            val input = IStream()
            var i = 0
            while (i < wire.size) {
                val n = minOf(chunk, wire.size - i)
                input.feed(wire, i, n, v)
                i += n
            }
            assertEquals(DecodeStatus.COMPLETE, input.status, "chunk $chunk")
            assertEquals(listOf("u:1:1", "s:5:-7"), v.out.events, "chunk $chunk")
        }
    }

    @Test
    fun aVisitorThatOverridesNothingStillWalksTheWholeMessage() {
        // "Materialize nothing" is what skip means in a push decoder: the walk still
        // has to consume every header, count and payload length.
        val wire = encode(512) { message(it) }
        val input = IStream()
        input.feed(wire, object : Visitor {})
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertTrue(wire.isNotEmpty())
    }
}

/**
 * Re-encodes every decoded field onto an [OStream], moving `fp32` through its raw
 * wire bits so the round trip is bit-exact on every target (CORELIB_PLAN §6.5).
 */
internal class ReEncoder(private val os: OStream) : Visitor {
    private var arrayId = -1
    private var arrayKind = ArrayKind.UNSIGNED
    private val ints = ArrayList<Long>()
    private val f32 = ArrayList<Int>()
    private val f64 = ArrayList<Double>()
    private var arrayCount = 0
    private var pending: ByteArray? = null
    private var pendingIsString = false
    private var pendingId = 0

    override fun unsigned(id: Int, value: Long) {
        if (id == arrayId) ints.add(value) else os.writeUnsigned(id, value)
        finishArrayIfDone()
    }

    override fun signed(id: Int, value: Long) {
        if (id == arrayId) ints.add(value) else os.writeSigned(id, value)
        finishArrayIfDone()
    }

    override fun fp32Bits(id: Int, bits: Int) {
        if (id == arrayId) f32.add(bits) else os.writeFp32Bits(id, bits)
        finishArrayIfDone()
    }

    override fun fp64(id: Int, value: Double) {
        if (id == arrayId) f64.add(value) else os.writeFp64(id, value)
        finishArrayIfDone()
    }

    override fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        collect(id, total, offset, data, chunkOffset, chunkLength, true)
    }

    override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        collect(id, total, offset, data, chunkOffset, chunkLength, false)
    }

    private fun collect(
        id: Int,
        total: Int,
        offset: Int,
        data: ByteArray,
        chunkOffset: Int,
        chunkLength: Int,
        isString: Boolean,
    ) {
        var buf = pending
        if (buf == null || offset == 0) {
            buf = ByteArray(total)
            pending = buf
            pendingIsString = isString
            pendingId = id
        }
        data.copyInto(buf, offset, chunkOffset, chunkOffset + chunkLength)
        if (offset + chunkLength >= total) {
            os.writeFixlen(
                pendingId,
                buf,
                0,
                buf.size,
                if (pendingIsString) FixlenType.STRING else FixlenType.BLOB,
            )
            pending = null
        }
    }

    override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
        arrayId = id
        arrayKind = kind
        arrayCount = count
        ints.clear()
        f32.clear()
        f64.clear()
        finishArrayIfDone()
    }

    private fun finishArrayIfDone() {
        if (arrayId < 0) return
        val have = ints.size + f32.size + f64.size
        if (have < arrayCount) return
        when (arrayKind) {
            ArrayKind.UNSIGNED -> os.writeArrayUnsigned(arrayId, ints.toLongArray())
            ArrayKind.SIGNED -> os.writeArraySigned(arrayId, ints.toLongArray())
            ArrayKind.FP32 -> os.writeArrayFp32Bits(arrayId, f32.toIntArray())
            ArrayKind.FP64 -> os.writeArrayFp64(arrayId, f64.toDoubleArray())
        }
        arrayId = -1
    }

    override fun sequenceBegin(id: Int) {
        os.writeSequenceBeginLazy(id)
    }

    override fun sequenceEnd() {
        // A relay cannot know whether the frame was load-bearing (MESSAGE_SPEC §5.1),
        // so it keeps what it saw — the safe direction of the two.
        os.writeSequenceEndKeep()
    }
}
