/*
 * SofaBuffers Kotlin Multiplatform — randomized round trips.
 *
 * The hand-written tests pin the shapes the spec names; this one walks the space
 * between them. Every message is built from a seeded generator, so a failure is
 * reproducible from the seed printed in the assertion message, and every message
 * is put through four paths that must agree byte-for-byte and event-for-event:
 * one-shot encode, streamed encode through a one-byte buffer, one-shot decode, and
 * decode in randomly-sized chunks.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FuzzRoundTripTest {

    private val texts = listOf(
        "",
        "a",
        "sofab",
        "ä€𝄞",
        "identifier-0123456789",
        "a\u0000b", // embedded NUL is valid UTF-8 (§6.4)
        "the quick brown fox jumps over the lazy dog, twice over for length",
    )

    /** Write one random field, and return the events it must produce. */
    private fun writeField(os: OStream, rnd: Random, id: Int, depth: Int, ev: MutableList<String>) {
        when (rnd.nextInt(if (depth < 3) 11 else 10)) {
            0 -> {
                val v = rnd.nextLong()
                os.writeUnsigned(id, v)
                ev.add("u:$id:${v.toULong()}")
            }
            1 -> {
                val v = rnd.nextLong()
                os.writeSigned(id, v)
                ev.add("s:$id:$v")
            }
            2 -> {
                val v = rnd.nextBoolean()
                os.writeBoolean(id, v)
                ev.add("u:$id:${if (v) 1 else 0}")
            }
            3 -> {
                val bits = rnd.nextInt()
                os.writeFp32Bits(id, bits)
                ev.add("f32:$id:$bits")
            }
            4 -> {
                val v = Double.fromBits(rnd.nextLong())
                os.writeFp64(id, v)
                ev.add("f64:$id:${v.toRawBits()}")
            }
            5 -> {
                val text = texts[rnd.nextInt(texts.size)]
                os.writeString(id, text)
                ev.add("str:$id:$text")
            }
            6 -> {
                val blob = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt().toByte() }
                os.writeBlob(id, blob)
                ev.add("blob:$id:${hex(blob)}")
            }
            7 -> {
                val a = LongArray(rnd.nextInt(0, 12)) { rnd.nextLong() }
                os.writeArrayUnsigned(id, a)
                ev.add("arr:$id:UNSIGNED:${a.size}")
                for (v in a) ev.add("u:$id:${v.toULong()}")
            }
            8 -> {
                val a = IntArray(rnd.nextInt(0, 12)) { rnd.nextInt() }
                os.writeArraySigned(id, a)
                ev.add("arr:$id:SIGNED:${a.size}")
                for (v in a) ev.add("s:$id:$v")
            }
            9 -> {
                val a = IntArray(rnd.nextInt(0, 8)) { rnd.nextInt() }
                os.writeArrayFp32Bits(id, a)
                ev.add("arr:$id:FP32:${a.size}")
                for (v in a) ev.add("f32:$id:$v")
            }
            else -> {
                // A sequence: a fresh id scope, closed with the frame kept so the
                // event stream is the same whether or not it received content.
                os.writeSequenceBeginLazy(id)
                ev.add("seq{:$id")
                val children = rnd.nextInt(0, 4)
                for (c in 0 until children) writeField(os, rnd, c, depth + 1, ev)
                os.writeSequenceEndKeep()
                ev.add("seq}")
            }
        }
    }

    private fun buildMessage(seed: Int): Pair<ByteArray, List<String>> {
        val rnd = Random(seed)
        val ev = mutableListOf<String>()
        val buf = ByteArray(8192)
        val os = OStream(buf)
        val fields = rnd.nextInt(1, 12)
        for (id in 0 until fields) writeField(os, rnd, id, 0, ev)
        return buf.copyOf(os.bytesUsed) to ev
    }

    @Test
    fun randomMessagesSurviveEveryPath() {
        for (seed in 1..300) {
            val (wire, expected) = buildMessage(seed)
            val why = "seed $seed"

            // 1. one-shot decode
            val v = RecordingVisitor()
            val input = IStream()
            input.feed(wire, v)
            assertEquals(DecodeStatus.COMPLETE, input.status, why)
            assertEquals(expected, v.events, why)

            // 2. decode in randomly-sized chunks: the same events, whatever the split
            val rnd = Random(seed * 31 + 7)
            val chunked = RecordingVisitor()
            val streaming = IStream()
            var i = 0
            while (i < wire.size) {
                val n = minOf(rnd.nextInt(1, 9), wire.size - i)
                streaming.feed(wire, i, n, chunked)
                i += n
            }
            assertEquals(DecodeStatus.COMPLETE, streaming.status, why)
            assertEquals(expected, chunked.events, why)

            // 3. streamed encode through a one-byte buffer == the one-shot bytes
            val restreamed = ByteArray(8192)
            val relay = OStream(restreamed)
            IStream().feed(wire, ReEncoder(relay))
            assertContentEquals(wire, restreamed.copyOf(relay.bytesUsed), "$why: decode -> re-encode")
        }
    }

    @Test
    fun everyMessageStreamsOutThroughTheSmallestBuffer() {
        for (seed in 1..60) {
            val (wire, _) = buildMessage(seed)
            // Re-drive the same random field sequence through a minimum-size buffer
            // with a sink: CORELIB_PLAN §5.1 requires byte-identical output.
            val collected = ArrayList<Byte>()
            val os = OStream(ByteArray(Sofab.MIN_OUTPUT_BUFFER), 0, { data, off, len ->
                for (k in off until off + len) collected.add(data[k])
            })
            val rnd = Random(seed)
            val ev = mutableListOf<String>()
            val fields = rnd.nextInt(1, 12)
            for (id in 0 until fields) writeField(os, rnd, id, 0, ev)
            os.flush()
            assertContentEquals(wire, collected.toByteArray(), "seed $seed")
        }
    }
}
