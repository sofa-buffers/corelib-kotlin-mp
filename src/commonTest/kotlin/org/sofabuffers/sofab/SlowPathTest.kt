/*
 * SofaBuffers Kotlin Multiplatform — the paths a tiny buffer or a badly-placed
 * chunk boundary forces the codec onto.
 *
 * Both sides carry two implementations of the same bytes: a bulk path taken when
 * the room (or the input) is there, and a byte-at-a-time path taken when it is
 * not. The whole design rests on the two producing identical results — CORELIB_PLAN
 * §5.1's "byte-identical to the one-shot path" and §5.2's resumable state machine —
 * so the slow halves need driving as deliberately as the fast ones.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlowPathTest {

    /** One-shot bytes and streamed-through-a-tiny-buffer bytes must be identical. */
    private fun bothPaths(what: String, block: (OStream) -> Unit) {
        val oneShot = encode(4096, block)
        for (room in intArrayOf(1, 2, 3, 9)) {
            assertContentEquals(oneShot, encodeStreaming(room) { block(it) }, "$what through a $room-byte buffer")
        }
        // ...and the result decodes to the same events either way.
        assertEquals(decodeEvents(oneShot), decodeEventsChunked(oneShot, 1), what)
    }

    @Test
    fun everyArrayWriterHasAWorkingSmallBufferPath() {
        // Each width has its own bulk loop and its own fallback; a buffer smaller
        // than the payload is what selects the second.
        bothPaths("u8 array") { it.writeArrayUnsigned(1, byteArrayOf(0, 1, -1, 127)) }
        bothPaths("u16 array") { it.writeArrayUnsigned(2, shortArrayOf(0, 1, -1, 300)) }
        bothPaths("u32 array") { it.writeArrayUnsigned(3, intArrayOf(0, 1, -1, 70000)) }
        bothPaths("u64 array") { it.writeArrayUnsigned(4, longArrayOf(0, 1, -1, Long.MIN_VALUE)) }
        bothPaths("i8 array") { it.writeArraySigned(5, byteArrayOf(0, 1, -1, -128)) }
        bothPaths("i16 array") { it.writeArraySigned(6, shortArrayOf(0, 1, -1, -32768)) }
        bothPaths("i32 array") { it.writeArraySigned(7, intArrayOf(0, 1, -1, Int.MIN_VALUE)) }
        bothPaths("i64 array") { it.writeArraySigned(8, longArrayOf(0, 1, -1, Long.MIN_VALUE)) }
        bothPaths("fp32 array") { it.writeArrayFp32(9, floatArrayOf(1f, -2f, 3.5f)) }
        bothPaths("fp64 array") { it.writeArrayFp64(10, doubleArrayOf(1.0, -2.0, 3.5)) }
        bothPaths("fp32 bits array") { it.writeArrayFp32Bits(11, intArrayOf(0, -1, 0x7F80_0001.toInt())) }
    }

    @Test
    fun everyScalarWriterHasAWorkingSmallBufferPath() {
        bothPaths("scalars") {
            it.writeUnsigned(1, Long.MIN_VALUE)
            it.writeSigned(2, Long.MIN_VALUE)
            it.writeBoolean(3, true)
            it.writeFp32(4, 3.14159f)
            it.writeFp64(5, 2.718281828459045)
            it.writeBlob(6, ByteArray(40) { i -> i.toByte() })
        }
        // Multi-byte field headers take the other arm of every scalar writer: the
        // one that cannot fold the header and the value into one store.
        bothPaths("wide ids") {
            it.writeUnsigned(1000, 1)
            it.writeSigned(1001, -1)
            it.writeFp32(1002, 1f)
            it.writeFp64(1003, 1.0)
            it.writeString(1004, "wide")
            it.writeArrayUnsigned(1005, longArrayOf(1, 2))
            it.writeArrayFp32(1006, floatArrayOf(1f))
        }
    }

    @Test
    fun stringsTakeBothEncodingPaths() {
        // Below the bulk threshold, above it, and too long for the room left — the
        // three arms of writeString — at every UTF-8 width.
        bothPaths("short ascii") { it.writeString(1, "abc") }
        bothPaths("long ascii") { it.writeString(2, "identifier-0123456789-abcdefghijklmnop") }
        bothPaths("multi-byte") { it.writeString(3, "ä€𝄞") }
        bothPaths("long multi-byte") { it.writeString(4, "aä€𝄞".repeat(32)) }
        bothPaths("empty") { it.writeString(5, "") }
        // An exactly-sized buffer: a one-byte header, a two-byte fixlen_word for a
        // 20-byte payload, and the payload — the bulk copy's room test is `>=`, so
        // an exact fit takes it rather than falling back.
        val text = "0123456789abcdefghij"
        val buf = ByteArray(text.length + 3)
        val os = OStream(buf)
        os.writeString(0, text)
        assertEquals(buf.size, os.bytesUsed)
        assertTrue(hex(buf).endsWith(hex(text.encodeToByteArray())))
    }

    @Test
    fun blobSlicesAreWrittenFromTheirOffset() {
        val data = ByteArray(64) { it.toByte() }
        val wire = encode { it.writeBlob(1, data, 8, 4) }
        assertEquals(listOf("blob:1:08090a0b"), decodeEvents(wire))
        assertContentEquals(encode { it.writeBlob(1, byteArrayOf(8, 9, 10, 11)) }, wire)
    }

    @Test
    fun flushWithoutASinkOrWithoutBytesIsANoOp() {
        val os = OStream(ByteArray(8))
        assertEquals(0, os.flush(), "nothing pending")
        os.writeUnsigned(1, 1)
        assertEquals(2, os.flush(), "no sink: the bytes stay in the buffer")
        assertEquals(2, os.bytesUsed)
    }

    @Test
    fun resetOnAStreamingEncoderKeepsItsSink() {
        val out = ArrayList<Byte>()
        val buf = ByteArray(4)
        val os = OStream(buf, 0, { data, off, len -> for (i in off until off + len) out.add(data[i]) })
        os.writeString(1, "first message that spills the buffer")
        os.flush()
        val first = out.size
        os.reset(buf)
        os.writeString(1, "second message that also spills the buffer")
        os.flush()
        assertTrue(out.size > first, "the sink survives a reset")
        assertContentEquals(
            encode(256) { it.writeString(1, "second message that also spills the buffer") },
            out.subList(first, out.size).toByteArray(),
        )
    }

    // --- decoder: the resumable machine's own arms ---------------------------

    @Test
    fun aSplitMultiByteHeaderRoutesEveryWireTypeThroughTheMachine() {
        // A one-byte header is read inline even from a one-byte feed, so only an id
        // of 16 or more puts the header itself on the resumable path — and that is
        // where each wire type's arm of the state machine lives.
        val wire = encode(512) {
            it.writeUnsigned(16, 300)
            it.writeSigned(17, -300)
            it.writeFp32(18, 1.5f)
            it.writeFp64(19, 2.5)
            it.writeString(20, "text")
            it.writeBlob(21, byteArrayOf(1, 2, 3))
            it.writeArrayUnsigned(22, longArrayOf(1, 300))
            it.writeArraySigned(23, longArrayOf(-1, -300))
            it.writeArrayFp32(24, floatArrayOf(1f, 2f))
            it.writeArrayFp64(25, doubleArrayOf(1.0))
            it.writeArrayUnsigned(26, LongArray(0))
            it.writeArrayFp32(27, FloatArray(0))
            it.writeSequenceBeginLazy(28)
            it.writeUnsigned(16, 1)
            it.writeSequenceEndKeep()
        }
        val whole = decodeEvents(wire)
        assertEquals(whole, decodeEventsChunked(wire, 1), "one byte at a time")
        assertEquals(whole, decodeEventsChunked(wire, 2), "two bytes at a time")
        val input = IStream()
        for (i in wire.indices) input.feed(wire, i, 1, RecordingVisitor())
        assertEquals(DecodeStatus.COMPLETE, input.status)
        assertTrue(input.machineBytes > 0, "the machine is what decoded this")
    }

    @Test
    fun aStraddlingElementIsStoredThroughEveryBulkWidth() {
        // The machine delivers the one element that crossed the boundary, so its
        // narrowing store — a different arm per destination width — only runs here.
        val src = longArrayOf(1, 300, 70_000, 5, 6, 7)
        val unsignedWire = encode(256) { it.writeArrayUnsigned(1, src) }
        val signedWire = encode(256) { it.writeArraySigned(1, src) }
        for (wire in listOf(unsignedWire, signedWire)) {
            for (dst in listOf<Any>(LongArray(src.size), IntArray(src.size))) {
                val input = IStream()
                val v = object : Visitor {
                    var ended = -1
                    override fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any = dst
                    override fun arrayBulkEnd(id: Int, n: Int) {
                        ended = n
                    }
                }
                for (i in wire.indices) input.feed(wire, i, 1, v)
                assertEquals(DecodeStatus.COMPLETE, input.status)
                assertEquals(src.size, v.ended)
                when (dst) {
                    is LongArray -> assertContentEquals(src, dst)
                    is IntArray -> assertContentEquals(IntArray(src.size) { src[it].toInt() }, dst)
                }
            }
        }
        // A narrow destination still rejects a value it cannot hold, on this path too.
        val wide = encode(256) { it.writeArrayUnsigned(1, longArrayOf(1, 1L shl 40)) }
        val input = IStream()
        val v = object : Visitor {
            override fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any = ShortArray(2)
        }
        var threw = false
        try {
            for (i in wide.indices) input.feed(wide, i, 1, v)
        } catch (e: SofabException) {
            threw = e.error == SofabError.INVALID_MSG
        }
        assertTrue(threw, "an over-width element is INVALID on the machine path as well")
    }
}
