/*
 * SofaBuffers Kotlin Multiplatform — the support layer's element placement and
 * array growth.
 *
 * SPDX-License-Identifier: MIT
 */
@file:OptIn(ExperimentalUnsignedTypes::class)

package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeqTest {

    /**
     * The two numbers every `reserveRow*` call carries (CORELIB_PLAN §6.2.1).
     * This test class is the caller, so it states them: [NO_COUNT] is what
     * generated code passes for an outer array whose schema declares no `count`,
     * and [RCAP] stands in for the deployment's configured `max_dyn_array_count`
     * — above every id used below, so only the two bound tests meet it.
     */
    private val NO_COUNT = -1

    /** @see NO_COUNT */
    private val RCAP = 16384

    // -----------------------------------------------------------------------
    // Growth
    // -----------------------------------------------------------------------

    @Test
    fun anIndexThatAlreadyFitsIsNotCopied() {
        // The call sits on the hot path unguarded, so the common case has to be
        // the identity — not a copy of the same length.
        val a = IntArray(4)
        assertSame(a, Seq.ensureCap(a, 0, 4))
        assertSame(a, Seq.ensureCap(a, 3, 4))
    }

    @Test
    fun growthStartsAtOneAndDoubles() {
        // From zero there is nothing to double, so the first element gets a
        // one-element array and the length doubles from there.
        var a = Seq.EMPTY_INTS
        val lengths = mutableListOf<Int>()
        for (i in 0 until 9) {
            a = Seq.ensureCap(a, i, 100)
            a[i] = i
            lengths.add(a.size)
        }
        assertContentEquals(intArrayOf(1, 2, 4, 4, 8, 8, 8, 8, 16), lengths.toIntArray())
        assertContentEquals(IntArray(9) { it }, a.copyOf(9))
    }

    @Test
    fun growthStopsExactlyAtTheAnnouncedCount() {
        // A valid array of the announced length ends up exactly right-sized rather
        // than at the next power of two: the ceiling clamps the last doubling.
        var a = Seq.EMPTY_LONGS
        for (i in 0 until 5) {
            a = Seq.ensureCap(a, i, 5)
            a[i] = i.toLong()
        }
        assertEquals(5, a.size)
    }

    @Test
    fun theCeilingBindsAtTheLastIndexAndAtTheFirstOneBeyondIt() {
        // cap - 1 is the last index a valid array can carry, and it fits exactly.
        // cap itself is the caller's guard, not this one's: growth refuses to go
        // past the ceiling, so an unguarded caller gets a short array rather than
        // an oversized allocation.
        assertEquals(8, Seq.ensureCap(Seq.EMPTY_INTS, 7, 8).size)
        assertEquals(8, Seq.ensureCap(Seq.EMPTY_INTS, 8, 8).size)
        assertEquals(8, Seq.ensureCap(IntArray(8), 8, 8).size)
    }

    @Test
    fun anAnnouncedCountNearTwoToThe31AllocatesNothing() {
        // The adversarial case: a three-byte header claiming 2^31 - 1 elements. The
        // ceiling is not an allocation — only elements that actually arrive are —
        // so the first element costs one slot and eight elements cost eight.
        var a = Seq.ensureCap(Seq.EMPTY_INTS, 0, Int.MAX_VALUE)
        assertEquals(1, a.size)
        for (i in 1 until 5) a = Seq.ensureCap(a, i, Int.MAX_VALUE)
        assertEquals(8, a.size)
    }

    @Test
    fun everyElementWidthGrowsTheSameWay() {
        // Kotlin's unsigned arrays are value classes rather than subtypes of a
        // common array interface, so each width is its own overload; they must all
        // implement the one growth rule.
        assertEquals(4, Seq.ensureCap(ByteArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(ShortArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(IntArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(LongArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(UByteArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(UShortArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(UIntArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(ULongArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(FloatArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(DoubleArray(2), 2, 9).size)
        assertEquals(4, Seq.ensureCap(BooleanArray(2), 2, 9).size)
        // Untouched where the index already fits, for every width.
        assertEquals(2, Seq.ensureCap(ByteArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(ShortArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(IntArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(LongArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(UByteArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(UShortArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(UIntArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(ULongArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(FloatArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(DoubleArray(2), 1, 9).size)
        assertEquals(2, Seq.ensureCap(BooleanArray(2), 1, 9).size)
    }

    @Test
    fun growthKeepsTheElementsAlreadyDelivered() {
        // Growth is a copy, not a reset: what arrived before the array was enlarged
        // has to survive it, for every width.
        val a = Seq.ensureCap(byteArrayOf(1, 2), 2, 4)
        a[2] = 3
        assertContentEquals(byteArrayOf(1, 2, 3, 0), a)
        assertContentEquals(shortArrayOf(1, 2, 0, 0), Seq.ensureCap(shortArrayOf(1, 2), 2, 4))
        assertContentEquals(intArrayOf(1, 2, 0, 0), Seq.ensureCap(intArrayOf(1, 2), 2, 4))
        assertContentEquals(longArrayOf(1, 2, 0, 0), Seq.ensureCap(longArrayOf(1, 2), 2, 4))
        assertContentEquals(ubyteArrayOf(1u, 2u, 0u, 0u), Seq.ensureCap(ubyteArrayOf(1u, 2u), 2, 4))
        assertContentEquals(ushortArrayOf(1u, 2u, 0u, 0u), Seq.ensureCap(ushortArrayOf(1u, 2u), 2, 4))
        assertContentEquals(uintArrayOf(1u, 2u, 0u, 0u), Seq.ensureCap(uintArrayOf(1u, 2u), 2, 4))
        assertContentEquals(ulongArrayOf(1u, 2u, 0u, 0u), Seq.ensureCap(ulongArrayOf(1u, 2u), 2, 4))
        assertContentEquals(floatArrayOf(1f, 2f, 0f, 0f), Seq.ensureCap(floatArrayOf(1f, 2f), 2, 4))
        assertContentEquals(doubleArrayOf(1.0, 2.0, 0.0, 0.0), Seq.ensureCap(doubleArrayOf(1.0, 2.0), 2, 4))
        assertContentEquals(
            booleanArrayOf(true, false, false, false),
            Seq.ensureCap(booleanArrayOf(true, false), 2, 4),
        )
    }

    // -----------------------------------------------------------------------
    // Row placement
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // The two bounds the caller passes in (CORELIB_PLAN §6.2.1)
    // -----------------------------------------------------------------------

    @Test
    fun anUncountedRowIndexAboveTheReceiverCapIsLimitExceeded() {
        // A wrapper array carries no count header: the element's id IS the length
        // it forces, so the id is what has to be bounded — and before the list is
        // grown to hold it. The bytes are well formed, so the category is the
        // policy one and not INVALID_MSG (§6.3).
        val rows = mutableListOf<IntArray>()
        val e = assertFailsWith<SofabException> { Seq.reserveRowInts(rows, 4, 1, NO_COUNT, 4) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        assertEquals(0, rows.size, "rejected before the list grows")

        // The boundary: an id of cap - 1 is the last one the cap admits.
        assertEquals(1, Seq.reserveRowInts(rows, 3, 1, NO_COUNT, 4).size)
        assertEquals(4, rows.size)
    }

    @Test
    fun aCountedRowIndexAboveTheSchemaCountIsInvalid() {
        // A declared `count` is a statement about validity (MESSAGE_SPEC §7.1): an
        // element past it contradicts the schema both peers agreed on, so it is
        // malformed input and never the policy category.
        val rows = mutableListOf<LongArray>()
        val e = assertFailsWith<SofabException> { Seq.reserveRowLongs(rows, 2, 1, 2, RCAP) }
        assertEquals(SofabError.INVALID_MSG, e.error)
        assertEquals(0, rows.size, "rejected before the list grows")
    }

    @Test
    fun theReceiverCapIsNeverAppliedToASchemaCountedArray() {
        // §6.2.1: the caps govern only arrays the schema left uncounted. A row
        // inside its declared count is placed whatever the receiver cap says, so
        // the two can never both be in play.
        val rows = mutableListOf<IntArray>()
        assertEquals(1, Seq.reserveRowInts(rows, 3, 1, 4, 1).size)
        assertEquals(4, rows.size)
    }

    @Test
    fun aWrapperRowTakesTheSameTwoBounds() {
        // reserveRowList grows the same outer list to id + 1 and is bounded the
        // same way.
        val rows = mutableListOf<MutableList<String>>()
        val e = assertFailsWith<SofabException> { Seq.reserveRowList(rows, 4, NO_COUNT, 4) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        val ie = assertFailsWith<SofabException> { Seq.reserveRowList(rows, 2, 2, RCAP) }
        assertEquals(SofabError.INVALID_MSG, ie.error)
        assertEquals(0, rows.size, "rejected before the list grows")

        Seq.reserveRowList(rows, 1, NO_COUNT, RCAP)
        assertEquals(2, rows.size)
    }

    @Test
    fun anUnstatedReceiverCapIsAnArgumentErrorAndNotALimit() {
        // §6.2.1 requires the caller to state the cap and forbids reading an
        // omitted one as unlimited, so a negative rcap still admits no row. But it
        // is NOT LIMIT_EXCEEDED: that category means "raise my limit, or the
        // sender must send less" and so presupposes a limit somebody set.
        // Reporting an absent cap as one names a receiver policy the deployment
        // never configured. The mistake is in the CALL — §6.3's InvalidArgument.
        for (rcap in listOf(-1, Int.MIN_VALUE)) {
            val rows = mutableListOf<IntArray>()
            val e = assertFailsWith<SofabException> { Seq.reserveRowInts(rows, 0, 1, NO_COUNT, rcap) }
            assertEquals(SofabError.ARGUMENT, e.error)
            assertEquals(0, rows.size, "fail-closed: the list is not grown either")

            val lists = mutableListOf<MutableList<String>>()
            val le = assertFailsWith<SofabException> { Seq.reserveRowList(lists, 0, NO_COUNT, rcap) }
            assertEquals(SofabError.ARGUMENT, le.error)
            assertEquals(0, lists.size)
        }
    }

    @Test
    fun aCapOfZeroIsAStatedLimitAndNotAnAbsentOne() {
        // The line between the two categories is at 0, not below it: a receiver
        // that configured 0 rows admits none, and that refusal is a real limit to
        // raise rather than a call that forgot to state one.
        val rows = mutableListOf<IntArray>()
        val e = assertFailsWith<SofabException> { Seq.reserveRowInts(rows, 0, 1, NO_COUNT, 0) }
        assertEquals(SofabError.LIMIT_EXCEEDED, e.error)
        assertEquals(0, rows.size)
    }

    @Test
    fun aSchemaCountedArrayIsUnaffectedByAnUnstatedReceiverCap() {
        // §6.2.1: where the schema counts the array the receiver cap is not in
        // play at all, so the absent-cap check must not leak into that branch and
        // turn a perfectly good row placement into an argument error.
        val rows = mutableListOf<IntArray>()
        assertEquals(1, Seq.reserveRowInts(rows, 3, 1, 4, -1).size)
        assertEquals(4, rows.size)
    }

    @Test
    fun anIdGapFillsWithEmptyRowsRatherThanShifting() {
        // MESSAGE_SPEC §5.1: an element's id is its index. Rows 0 and 1 were
        // omitted because they are empty, so row 2 has to land at index 2 — not at
        // index 0 with everything after it shifted down.
        val rows = mutableListOf<IntArray>()
        val row = Seq.reserveRowInts(rows, 2, 3, NO_COUNT, RCAP)
        assertEquals(3, rows.size)
        assertSame(Seq.EMPTY_INTS, rows[0])
        assertSame(Seq.EMPTY_INTS, rows[1])
        assertSame(row, rows[2])
        assertEquals(3, row.size)
    }

    @Test
    fun aRepeatedIdReplacesTheRowRatherThanAppending() {
        // §7.4: an array wrapper is the array's value, so a second occurrence of the
        // same element id overwrites the first — the outer array does not grow.
        val rows = mutableListOf<IntArray>()
        Seq.reserveRowInts(rows, 0, 4, NO_COUNT, RCAP)[0] = 7
        val second = Seq.reserveRowInts(rows, 0, 1, NO_COUNT, RCAP)
        assertEquals(1, rows.size)
        assertSame(second, rows[0])
        assertEquals(1, second.size)
        assertEquals(0, second[0])
    }

    @Test
    fun rowsArrivingInOrderJustAppend() {
        val rows = mutableListOf<LongArray>()
        for (id in 0 until 3) Seq.reserveRowLongs(rows, id, id + 1, NO_COUNT, RCAP)[id] = id.toLong()
        assertEquals(3, rows.size)
        assertContentEquals(intArrayOf(1, 2, 3), IntArray(3) { rows[it].size })
        assertEquals(2L, rows[2][2])
    }

    @Test
    fun everyRowWidthPlacesFillsAndReplacesTheSameWay() {
        // Each width in turn: reserve row 1 (which fills row 0 with the shared empty
        // row), then reserve row 1 again (which replaces it in place rather than
        // appending a second one).
        val bytes = mutableListOf<ByteArray>()
        assertEquals(2, Seq.reserveRowBytes(bytes, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_BYTES, bytes[0])
        assertEquals(3, Seq.reserveRowBytes(bytes, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, bytes.size)

        val shorts = mutableListOf<ShortArray>()
        assertEquals(2, Seq.reserveRowShorts(shorts, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_SHORTS, shorts[0])
        assertEquals(3, Seq.reserveRowShorts(shorts, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, shorts.size)

        val longs = mutableListOf<LongArray>()
        assertEquals(2, Seq.reserveRowLongs(longs, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_LONGS, longs[0])
        assertEquals(3, Seq.reserveRowLongs(longs, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, longs.size)

        val floats = mutableListOf<FloatArray>()
        assertEquals(2, Seq.reserveRowFloats(floats, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_FLOATS, floats[0])
        assertEquals(3, Seq.reserveRowFloats(floats, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, floats.size)

        val doubles = mutableListOf<DoubleArray>()
        assertEquals(2, Seq.reserveRowDoubles(doubles, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_DOUBLES, doubles[0])
        assertEquals(3, Seq.reserveRowDoubles(doubles, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, doubles.size)

        val booleans = mutableListOf<BooleanArray>()
        assertEquals(2, Seq.reserveRowBooleans(booleans, 1, 2, NO_COUNT, RCAP).size)
        assertSame(Seq.EMPTY_BOOLEANS, booleans[0])
        assertEquals(3, Seq.reserveRowBooleans(booleans, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, booleans.size)

        // The unsigned widths are value classes over the signed ones; comparing the
        // gap fill by identity would compare two boxes, so compare its length.
        val ubytes = mutableListOf<UByteArray>()
        assertEquals(2, Seq.reserveRowUBytes(ubytes, 1, 2, NO_COUNT, RCAP).size)
        assertEquals(0, ubytes[0].size)
        assertEquals(3, Seq.reserveRowUBytes(ubytes, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, ubytes.size)

        val ushorts = mutableListOf<UShortArray>()
        assertEquals(2, Seq.reserveRowUShorts(ushorts, 1, 2, NO_COUNT, RCAP).size)
        assertEquals(0, ushorts[0].size)
        assertEquals(3, Seq.reserveRowUShorts(ushorts, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, ushorts.size)

        val uints = mutableListOf<UIntArray>()
        assertEquals(2, Seq.reserveRowUInts(uints, 1, 2, NO_COUNT, RCAP).size)
        assertEquals(0, uints[0].size)
        assertEquals(3, Seq.reserveRowUInts(uints, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, uints.size)

        val ulongs = mutableListOf<ULongArray>()
        assertEquals(2, Seq.reserveRowULongs(ulongs, 1, 2, NO_COUNT, RCAP).size)
        assertEquals(0, ulongs[0].size)
        assertEquals(3, Seq.reserveRowULongs(ulongs, 1, 3, NO_COUNT, RCAP).size)
        assertEquals(2, ulongs.size)
    }

    @Test
    fun aWrapperRowIsEmptiedInPlaceRatherThanSwapped() {
        // Decoding N rows must allocate N lists, not 2N: an already-present row is
        // cleared, so the object stays and only its value is replaced.
        val rows = mutableListOf<MutableList<String>>()
        Seq.reserveRowList(rows, 1, NO_COUNT, RCAP)
        rows[1].add("a")
        val held = rows[1]
        Seq.reserveRowList(rows, 1, NO_COUNT, RCAP)
        assertEquals(2, rows.size)
        assertSame(held, rows[1])
        assertTrue(held.isEmpty())
    }

    @Test
    fun aWrapperRowGapFillsWithEmptyLists() {
        val rows = mutableListOf<MutableList<String>>()
        Seq.reserveRowList(rows, 2, NO_COUNT, RCAP)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.isEmpty() })
        // The gap fills are separate lists, not one shared instance: filling row 2
        // must not appear in row 0.
        rows[2].add("x")
        assertTrue(rows[0].isEmpty())
    }

    // -----------------------------------------------------------------------
    // Constants and encode-side conversion
    // -----------------------------------------------------------------------

    @Test
    fun theSharedEmptyArraysAreEmpty() {
        assertEquals(0, Seq.EMPTY_BYTES.size)
        assertEquals(0, Seq.EMPTY_SHORTS.size)
        assertEquals(0, Seq.EMPTY_INTS.size)
        assertEquals(0, Seq.EMPTY_LONGS.size)
        assertEquals(0, Seq.EMPTY_UBYTES.size)
        assertEquals(0, Seq.EMPTY_USHORTS.size)
        assertEquals(0, Seq.EMPTY_UINTS.size)
        assertEquals(0, Seq.EMPTY_ULONGS.size)
        assertEquals(0, Seq.EMPTY_FLOATS.size)
        assertEquals(0, Seq.EMPTY_DOUBLES.size)
        assertEquals(0, Seq.EMPTY_BOOLEANS.size)
        assertEquals(16, Seq.ARRAY_INIT_CAP)
    }

    @Test
    fun booleansBecomeTheZeroOneBytesTheWireCarries() {
        assertContentEquals(
            byteArrayOf(1, 0, 1, 1),
            Seq.boolsToBytes(booleanArrayOf(true, false, true, true)),
        )
        assertEquals(0, Seq.boolsToBytes(booleanArrayOf()).size)
    }

    @Test
    fun aConvertedBooleanArrayIsWhatTheEncoderWrites() {
        // The conversion exists for one call, so check it through that call: the
        // bytes have to be the unsigned array of 0/1 elements MESSAGE_SPEC §4.4
        // describes.
        val wire = encode(64) { it.writeArrayUnsigned(1, Seq.boolsToBytes(booleanArrayOf(true, false, true))) }
        val out = mutableListOf<Long>()
        IStream().feed(
            wire,
            object : Visitor {
                override fun unsigned(id: Int, value: Long) {
                    out.add(value)
                }
            },
        )
        assertContentEquals(longArrayOf(1, 0, 1), out.toLongArray())
    }
}
