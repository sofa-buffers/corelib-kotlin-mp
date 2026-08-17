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
    fun aRefusedOfferFallsBackToPerElementDelivery() {
        val src = longArrayOf(5, 6, 7)
        val wire = encode(256) { it.writeArrayUnsigned(1, src) }
        for (dst in listOf(null, LongArray(2), FloatArray(3), "not an array")) {
            val v = Bulk(dst)
            IStream().feed(wire, v)
            assertEquals(listOf(5L, 6L, 7L), v.perElement, "destination $dst")
            assertEquals(-1, v.ended, "a refused offer never reports a bulk end")
        }
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
