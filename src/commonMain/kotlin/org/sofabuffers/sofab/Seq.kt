/*
 * SofaBuffers Kotlin Multiplatform — support layer for generated code: element
 * placement, array growth and the shared empty arrays.
 *
 * SPDX-License-Identifier: MIT
 */
@file:OptIn(ExperimentalUnsignedTypes::class)

package org.sofabuffers.sofab

/**
 * Element placement and array growth for generated decode destinations — the
 * **support layer**, not the codec.
 *
 * Nothing here touches the wire. These are the operations a generated message
 * class performs *around* a [Visitor] callback: put an element at the index its
 * id names, grow a primitive array as elements actually arrive, reserve a matrix
 * row. Their code has the same shape for every schema — a `count`, a `maxlen` or
 * a capacity arrives as an argument and an element type as an overload — which is
 * why they live in the corelib rather than being emitted, rationale and all, into
 * every generated package (generator#345).
 *
 * This is the one place in this library that names [MutableList]. The codec
 * itself stays array- and primitive-based; a generated message is what holds a
 * list, because a wrapper array of strings, blobs or sub-messages has no
 * primitive form.
 *
 * **Why one member per array type.** Kotlin's unsigned arrays are `value` classes
 * over their signed peers rather than subtypes of a common array interface, so
 * neither a generic parameter nor an interface can span them; the growth rule is
 * the same for all eleven and only the element width differs. [ensureCap]
 * overloads on the array type, and the members that cannot overload — a
 * `MutableList<ByteArray>` and a `MutableList<IntArray>` erase to the same JVM
 * signature — carry the element name in the member name instead.
 *
 * Two rules run through all of it. **Ids are positions** (MESSAGE_SPEC §5.1): an
 * array element's id *is* its index, an interior element equal to the element
 * default may be omitted, and the highest id present is what gives the decoded
 * array its length — so a missing id fills a gap rather than shifting every later
 * element down by one, and a repeated id replaces rather than appends.
 * **A count is untrusted**: it is the wire's claim about how many elements
 * follow, bounded by nothing until a schema `count` or a receiver limit bounds
 * it, so no function here allocates from a count alone.
 *
 * **A row index is untrusted too, and its two bounds travel as arguments.** A
 * matrix row's id *is* its index, so `reserveRow*` grows the outer list to
 * `id + 1` and one over-index element is by itself the allocation an untrusted
 * index buys. Each takes the outer array's schema `count` and the deployment's
 * receiver cap and rejects the id before the list grows — the count/index header,
 * the site CORELIB_PLAN §6.2.1 requires, reached through a call generated code
 * already makes. Both numbers are the **caller's**: supplied per call, used for
 * that one comparison, never retained. Which one applies is the schema's
 * decision, not this object's — a declared `count` makes an over-index element
 * malformed input (`INVALID_MSG`, MESSAGE_SPEC §7.1) and forbids the receiver cap
 * from touching the field at all, while an array the schema leaves uncounted is
 * bounded by the receiver cap and answers the `LIMIT_EXCEEDED` policy category
 * (§6.3). Nothing here holds, defaults to or clamps to a limit of its own.
 */
public object Seq {

    /**
     * Initial element capacity for an array whose length is not bounded by the
     * schema. The announced count decides the ceiling, never the first
     * allocation: a decoder that sized the destination from an untrusted count
     * would let a three-byte header ask for gigabytes, so growth starts here and
     * [ensureCap] doubles it against elements that have actually arrived.
     */
    public const val ARRAY_INIT_CAP: Int = 16

    /**
     * Shared zero-length arrays. A generated field initializer and a generated
     * `reset()` reference one of these instead of allocating a fresh empty array
     * per instance — a decode replaces the value anyway, and a zero-length array
     * has no state to share wrongly.
     */
    public val EMPTY_BYTES: ByteArray = ByteArray(0)

    /** The shared empty [ShortArray]; see [EMPTY_BYTES]. */
    public val EMPTY_SHORTS: ShortArray = ShortArray(0)

    /** The shared empty [IntArray]; see [EMPTY_BYTES]. */
    public val EMPTY_INTS: IntArray = IntArray(0)

    /** The shared empty [LongArray]; see [EMPTY_BYTES]. */
    public val EMPTY_LONGS: LongArray = LongArray(0)

    /** The shared empty [UByteArray]; see [EMPTY_BYTES]. */
    public val EMPTY_UBYTES: UByteArray = UByteArray(0)

    /** The shared empty [UShortArray]; see [EMPTY_BYTES]. */
    public val EMPTY_USHORTS: UShortArray = UShortArray(0)

    /** The shared empty [UIntArray]; see [EMPTY_BYTES]. */
    public val EMPTY_UINTS: UIntArray = UIntArray(0)

    /** The shared empty [ULongArray]; see [EMPTY_BYTES]. */
    public val EMPTY_ULONGS: ULongArray = ULongArray(0)

    /** The shared empty [FloatArray]; see [EMPTY_BYTES]. */
    public val EMPTY_FLOATS: FloatArray = FloatArray(0)

    /** The shared empty [DoubleArray]; see [EMPTY_BYTES]. */
    public val EMPTY_DOUBLES: DoubleArray = DoubleArray(0)

    /** The shared empty [BooleanArray]; see [EMPTY_BYTES]. */
    public val EMPTY_BOOLEANS: BooleanArray = BooleanArray(0)

    // -----------------------------------------------------------------------
    // Row placement
    // -----------------------------------------------------------------------

    /**
     * Reserve the row at index [id] of a matrix — an array whose elements are
     * themselves arrays — as a fresh row of [n] elements, growing the outer list
     * with empty rows so that a gap in the ids decodes as an empty row instead of
     * shifting every later row down by one.
     *
     * Gaps are ordinary: an interior row equal to the element default (the empty
     * row) is omitted by a conformant encoder (MESSAGE_SPEC §2), and only the
     * *last* row is guaranteed present — which is what makes the decoded length,
     * highest present id + 1, exact. The row is replaced rather than merged into,
     * because an array wrapper *is* the array's value (§7.4): a later occurrence
     * of its element id replaces it whole.
     *
     * The new row is handed back so the caller can fill it by index instead of
     * reading it out of the list once per element.
     *
     * [id] is the wire's, and it is bounded here before the list grows: against
     * [cap] where the schema counts the outer array, against [rcap] where it does
     * not. [n] is the caller's capped reservation and never the raw wire count —
     * an untrusted count must not be able to force an up-front allocation — and
     * [ensureCap] grows the row as elements arrive.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or a negative number where the
     *     schema declares none
     * @param rcap the receiver's configured `max_dyn_array_count`, applied only
     *     where [cap] is negative
     * @return the new row, now at index [id]
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     [rcap]
     */
    public fun reserveRowBytes(rows: MutableList<ByteArray>, id: Int, n: Int, cap: Int, rcap: Int): ByteArray {
        boundIndex(id, cap, rcap)
        val row = ByteArray(n)
        while (rows.size < id) rows.add(EMPTY_BYTES)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [ShortArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowShorts(rows: MutableList<ShortArray>, id: Int, n: Int, cap: Int, rcap: Int): ShortArray {
        boundIndex(id, cap, rcap)
        val row = ShortArray(n)
        while (rows.size < id) rows.add(EMPTY_SHORTS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for an [IntArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowInts(rows: MutableList<IntArray>, id: Int, n: Int, cap: Int, rcap: Int): IntArray {
        boundIndex(id, cap, rcap)
        val row = IntArray(n)
        while (rows.size < id) rows.add(EMPTY_INTS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [LongArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowLongs(rows: MutableList<LongArray>, id: Int, n: Int, cap: Int, rcap: Int): LongArray {
        boundIndex(id, cap, rcap)
        val row = LongArray(n)
        while (rows.size < id) rows.add(EMPTY_LONGS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [UByteArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowUBytes(rows: MutableList<UByteArray>, id: Int, n: Int, cap: Int, rcap: Int): UByteArray {
        boundIndex(id, cap, rcap)
        val row = UByteArray(n)
        while (rows.size < id) rows.add(EMPTY_UBYTES)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [UShortArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowUShorts(rows: MutableList<UShortArray>, id: Int, n: Int, cap: Int, rcap: Int): UShortArray {
        boundIndex(id, cap, rcap)
        val row = UShortArray(n)
        while (rows.size < id) rows.add(EMPTY_USHORTS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [UIntArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowUInts(rows: MutableList<UIntArray>, id: Int, n: Int, cap: Int, rcap: Int): UIntArray {
        boundIndex(id, cap, rcap)
        val row = UIntArray(n)
        while (rows.size < id) rows.add(EMPTY_UINTS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [ULongArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowULongs(rows: MutableList<ULongArray>, id: Int, n: Int, cap: Int, rcap: Int): ULongArray {
        boundIndex(id, cap, rcap)
        val row = ULongArray(n)
        while (rows.size < id) rows.add(EMPTY_ULONGS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [FloatArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowFloats(rows: MutableList<FloatArray>, id: Int, n: Int, cap: Int, rcap: Int): FloatArray {
        boundIndex(id, cap, rcap)
        val row = FloatArray(n)
        while (rows.size < id) rows.add(EMPTY_FLOATS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [DoubleArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowDoubles(rows: MutableList<DoubleArray>, id: Int, n: Int, cap: Int, rcap: Int): DoubleArray {
        boundIndex(id, cap, rcap)
        val row = DoubleArray(n)
        while (rows.size < id) rows.add(EMPTY_DOUBLES)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a [BooleanArray] row.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param n initial length of the new row
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @return the new row, now at index [id]
     */
    public fun reserveRowBooleans(rows: MutableList<BooleanArray>, id: Int, n: Int, cap: Int, rcap: Int): BooleanArray {
        boundIndex(id, cap, rcap)
        val row = BooleanArray(n)
        while (rows.size < id) rows.add(EMPTY_BOOLEANS)
        if (rows.size == id) rows.add(row) else rows[id] = row
        return row
    }

    /**
     * [reserveRowBytes] for a row that is itself a wrapper array (a list of
     * strings, blobs or sub-messages): the same id-keyed placement and the same
     * gap fill with the empty row.
     *
     * "Replaced" is a statement about the *value*, not the object: an
     * already-present row is emptied in place rather than swapped for a fresh
     * list, so decoding N rows allocates N lists and not 2N — one to grow into
     * the slot and one to overwrite it. A caller holding a reference to a row
     * across a decode into the same destination therefore sees it emptied; a
     * decode destination is not shared.
     *
     * Nothing is handed back: a wrapper row is filled through the list, which the
     * caller reaches as `rows[id]`.
     *
     * @param rows the outer list, one entry per row
     * @param id index of the row to reserve
     * @param cap the outer array's schema `count`, or negative where it has none
     * @param rcap the receiver's configured `max_dyn_array_count`
     * @param T row element type
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     [rcap]
     */
    public fun <T> reserveRowList(rows: MutableList<MutableList<T>>, id: Int, cap: Int, rcap: Int) {
        boundIndex(id, cap, rcap)
        while (rows.size < id) rows.add(mutableListOf())
        if (rows.size == id) {
            rows.add(mutableListOf())
            return
        }
        rows[id].clear()
    }

    /**
     * Reject a row index the caller may not accept, before the list is grown to
     * hold it (CORELIB_PLAN §6.2.1).
     *
     * Exactly one of the two numbers applies, and the schema picks which — the
     * bound and the category are one decision with two answers, which is why they
     * are one comparison here rather than two guards in the caller. A declared
     * [cap] is the outer array's schema `count`: an element past it contradicts
     * the schema both peers agreed on, so it is malformed input (MESSAGE_SPEC
     * §7.1) and the receiver cap must not be applied to that field at all. An
     * array the schema leaves uncounted grows to *highest present id + 1* by
     * design (§5.1), so its index is its length and [rcap] — the deployment's
     * capacity decision — is what bounds it; the bytes are well formed, the same
     * element decodes for a receiver configured more loosely, and the verdict is
     * the [SofabError.LIMIT_EXCEEDED] policy category (§6.3).
     *
     * Neither number is this object's. Both arrive per call, are used for this one
     * comparison and are not retained; nothing here defaults, invents or clamps to
     * a limit, and an over-index element is rejected, never dropped or folded into
     * a lower slot.
     */
    private fun boundIndex(id: Int, cap: Int, rcap: Int) {
        if (cap >= 0) {
            if (id >= cap) {
                throw SofabException(SofabError.INVALID_MSG, "row index $id above declared count $cap")
            }
        } else if (id >= rcap) {
            throw SofabException(SofabError.LIMIT_EXCEEDED, "row index $id above configured limit $rcap")
        }
    }

    // -----------------------------------------------------------------------
    // Growth
    // -----------------------------------------------------------------------

    /**
     * Enlarge [a] so index [i] can be written, doubling its length but never
     * exceeding [cap].
     *
     * This is the growth policy for an array being filled element by element, and
     * its whole point is that it tracks elements that have **actually arrived**.
     * The alternative — sizing the destination from the announced count — hands an
     * attacker a multi-gigabyte allocation for a three-byte header, since a count
     * is bounded only by [Sofab.ARRAY_MAX] until a schema `count` or a receiver
     * limit bounds it. Doubling keeps the fill amortized O(n), and the [cap] clamp
     * means a valid array of the announced length still ends up exactly
     * right-sized rather than at the next power of two.
     *
     * [cap] is a ceiling on the *result*, not a bound the caller is relieved of
     * checking: it is the announced count for an unbounded field and the schema
     * capacity for a bounded one, both already validated by the caller, and a fill
     * that stays below its own count therefore never sees it clamp. Returns [a]
     * untouched whenever [i] already fits, so the call sits on the hot path
     * unguarded.
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: ByteArray, i: Int, cap: Int): ByteArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [ShortArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: ShortArray, i: Int, cap: Int): ShortArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for an [IntArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: IntArray, i: Int, cap: Int): IntArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [LongArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: LongArray, i: Int, cap: Int): LongArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [UByteArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: UByteArray, i: Int, cap: Int): UByteArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [UShortArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: UShortArray, i: Int, cap: Int): UShortArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [UIntArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: UIntArray, i: Int, cap: Int): UIntArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [ULongArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: ULongArray, i: Int, cap: Int): ULongArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [FloatArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: FloatArray, i: Int, cap: Int): FloatArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [DoubleArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: DoubleArray, i: Int, cap: Int): DoubleArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    /**
     * [ensureCap] for a [BooleanArray].
     *
     * @param a the array so far
     * @param i index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return [a], or a longer copy of it
     */
    public fun ensureCap(a: BooleanArray, i: Int, cap: Int): BooleanArray =
        if (i < a.size) a else a.copyOf(grownTo(a.size, i, cap))

    // -----------------------------------------------------------------------
    // Encode side
    // -----------------------------------------------------------------------

    /**
     * Convert a boolean array to the `0`/`1` bytes the encoder writes.
     *
     * This is the one materialization the encode side still needs. Every other
     * native array — top level or a matrix row — is already a primitive array of
     * its declared width and reaches [OStream.writeArrayUnsigned] and friends
     * unconverted; a boolean array is the one with no overload of its own, `bool`
     * being carried on the wire as an unsigned 0 or 1 (MESSAGE_SPEC §4.4).
     *
     * @param values the field's value
     * @return one byte per element, 1 for true and 0 for false
     */
    public fun boolsToBytes(values: BooleanArray): ByteArray {
        val out = ByteArray(values.size)
        for (i in values.indices) out[i] = if (values[i]) 1 else 0
        return out
    }

    /**
     * The one growth rule the [ensureCap] overloads share: double, but reach at
     * least index [i], and stop at [cap].
     *
     * Everything is computed in [Long] — `len * 2` and `i + 1` both overflow [Int]
     * near [Int.MAX_VALUE], and an overflowed length would come back *shorter*
     * than the index that asked for it.
     */
    private fun grownTo(len: Int, i: Int, cap: Int): Int {
        var n = len.toLong() * 2
        if (n < i + 1L) n = i + 1L
        if (n > cap.toLong()) n = cap.toLong()
        return n.toInt()
    }
}
