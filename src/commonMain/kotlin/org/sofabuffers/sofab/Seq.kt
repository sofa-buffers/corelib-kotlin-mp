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
 * **Three reservations, one shape.** Every wrapper array a schema can declare
 * reaches this object through one of three calls, which differ only in what a
 * slot holds: [placeElem] puts a decoded `string` or `blob` at the index its id
 * names; [reserveElem] makes the slot a `struct`, `union` or nested-array element
 * is routed into; [reserveRowBytes] and its peers reserve a matrix row. The
 * schema dependence of all three is exactly a bound, an element type and an
 * element default — an argument, a type parameter and an argument — which is the
 * whole of why they fit here at all. [checkIndex] is that bound on its own,
 * published for the one site that has no reservation to ride.
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
 * **An element index is untrusted too, and its two bounds travel as arguments.**
 * An array element's id *is* its index, so a placement grows the list to
 * `id + 1` and one over-index element is by itself the allocation an untrusted
 * index buys. Each call takes the array's schema `count` and the deployment's
 * receiver cap and rejects the id before the list grows — the count/index header,
 * the site CORELIB_PLAN §6.2.1 requires, reached through a call generated code
 * already makes. Both numbers are the **caller's**: supplied per call, used for
 * that one comparison, never retained. Which one applies is the schema's
 * decision, not this object's — a declared `count` makes an over-index element
 * malformed input (`INVALID_MSG`, MESSAGE_SPEC §7.1) and forbids the receiver cap
 * from touching the field at all, while an array the schema leaves uncounted is
 * bounded by the receiver cap and answers the `LIMIT_EXCEEDED` policy category
 * (§6.3). Nothing here holds, defaults to or clamps to a limit of its own — and a
 * call that states no cap at all admits no element either, reported as
 * `ARGUMENT`, the mistake being in the call rather than in a receiver policy
 * nobody configured.
 *
 * **What these bounds do not cover.** A `string` or `blob` element's own `maxlen`
 * is not one of these arguments: the payload arrives through the visitor's own
 * callback and its length has to be judged at the **length word**, so that a
 * message truncated right after that word is refused rather than reported
 * `INCOMPLETE` (MESSAGE_SPEC §5.2). There is no reservation here for it to ride;
 * [PayloadAcc.checkStringLength] and [PayloadAcc.string] own that half. Neither
 * is the **count** of a native array or of a matrix row: `n` below is a length
 * the caller has already bounded, and a native array reaches no call here at all.
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
    // Element placement
    // -----------------------------------------------------------------------

    /**
     * **Place a leaf element** — a wrapper array's `string` or `blob` — at the
     * index its wire id names, growing the list and filling the gaps that omitted
     * interior elements left (MESSAGE_SPEC §5.1, §2).
     *
     * Three rules of §5.1 sit in this one loop, and not one of them is visible in
     * the bytes: two implementations can disagree about every one of them and
     * still emit an identical message, which is why they are written here once
     * instead of being re-emitted per schema (CORELIB_PLAN §7.2 item 8 asks for
     * them separately for the same reason).
     *
     * - A missing id **fills a gap** with [def] rather than shifting every later
     *   element down by one — an interior element equal to the element default is
     *   omitted by a conformant encoder (§2).
     * - The array's length is **highest present id + 1**, so growing to `id + 1`
     *   per element is exactly right and no trailing fill is ever needed: the last
     *   element is never elided.
     * - A repeated id **replaces** rather than appends (§7.4), which an indexed
     *   set does by construction and an `add` could not.
     *
     * **The index is bounded before the list grows** (§7.2 item 8), so a refused
     * id leaves the list exactly as it was and a lower id delivered afterwards
     * still lands at its own index. [cap] and [rcap] are the two numbers
     * [checkIndex] chooses between, and exactly one of them can apply.
     *
     * **[def] is shared, not copied.** The gap value of an array of strings or
     * blobs is `""` or [EMPTY_BYTES] — immutable, or zero-length and so with no
     * state to share wrongly — so a gap costs no allocation. An element whose
     * default is a *mutable* object belongs in [reserveElem] instead, which makes
     * one per slot.
     *
     * This is not an `inline fun`: it takes no function argument, both element
     * types it serves ([String] and [ByteArray]) are reference types that a single
     * generic spans at no cost, and one shared body keeps the call sites small.
     * That was measured too: inlining it (or [checkIndex]) into the generated
     * visitor grew the callbacks past what the JIT inlines and cost about +3 % of
     * a decode, where the plain function is inlined by the JIT anyway.
     *
     * @param out the destination list, which this grows
     * @param id the element's wire id, which is its index
     * @param def the element default, filling any gap below [id]
     * @param value the decoded element
     * @param cap the array's schema `count`, or a negative number where the schema
     *     declares none
     * @param rcap the receiver's configured `max_dyn_array_count`, applied only
     *     where [cap] is negative; a negative [rcap] states no cap at all and
     *     admits no element
     * @param T element type
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     a stated [rcap]; [SofabError.ARGUMENT] when a schema-uncounted array was
     *     handed no cap at all ([rcap] negative)
     */
    public fun <T> placeElem(out: MutableList<T>, id: Int, def: T, value: T, cap: Int, rcap: Int) {
        checkIndex(id, cap, rcap)
        while (out.size <= id) out.add(def)
        out[id] = value
    }

    /**
     * **Reserve a framed element** — the slot a wrapper array's `struct`, `union`
     * or nested-array element is routed into: bound the index, then grow the list
     * to `id + 1`, giving each new slot its own element from [make]
     * (MESSAGE_SPEC §5.1, §2).
     *
     * The same three rules as [placeElem] and the same ordering — the index is
     * decided before the list grows (§7.2 item 8) — with one deliberate
     * difference: **a slot already present is left alone**. A framed element's
     * fields arrive one at a time and each is routed into the object this call
     * reserved, so a re-opened element id must *merge* into what its earlier
     * fields built rather than start the element again (§7.4); replacing the slot
     * would discard them. That is also why nothing is handed back: generated code
     * parks [id] in its own element-index register and reaches the element through
     * the list on the field arms that follow.
     *
     * **A factory, not a shared default.** A struct, union or nested row is
     * mutable and reachable by the caller, and an arriving element decodes *into*
     * the object placed here, so one shared instance would alias every element of
     * the array onto it — the single reason this is a second function rather than
     * [placeElem] with one more argument.
     *
     * **Why this one is `inline`.** It is the signature with a function argument,
     * and inlining is what makes that argument cost nothing: no lambda object is
     * created, no `invoke` is dispatched, and the generated constructor call lands
     * directly in the loop on JVM, JS and native alike. [make] comes last against
     * the "bounds last" order the rest of this object keeps, because Kotlin's
     * trailing-lambda syntax is what makes the call site read as a constructor.
     *
     * What this does **not** own is the **routing** — binding the element index,
     * switching into the element's scope, emptying a re-opened nested row. That
     * has a different shape per schema and stays generated. This owns growth and
     * the bound, and stops at the slot.
     *
     * @param out the destination list, which this grows
     * @param id the element's wire id, which is its index
     * @param cap the array's schema `count`, or a negative number where the schema
     *     declares none
     * @param rcap the receiver's configured `max_dyn_array_count`, applied only
     *     where [cap] is negative; a negative [rcap] states no cap at all and
     *     admits no element
     * @param make the element factory, called once per slot this creates
     * @param T element type
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     a stated [rcap]; [SofabError.ARGUMENT] when a schema-uncounted array was
     *     handed no cap at all ([rcap] negative)
     */
    public inline fun <T> reserveElem(
        out: MutableList<T>,
        id: Int,
        cap: Int,
        rcap: Int,
        make: () -> T,
    ) {
        checkIndex(id, cap, rcap)
        while (out.size <= id) out.add(make())
    }

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
     *     where [cap] is negative; a negative [rcap] states no cap at all and
     *     admits no row
     * @return the new row, now at index [id]
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     a stated [rcap]; [SofabError.ARGUMENT] when a schema-uncounted array was
     *     handed no cap at all ([rcap] negative)
     */
    public fun reserveRowBytes(rows: MutableList<ByteArray>, id: Int, n: Int, cap: Int, rcap: Int): ByteArray {
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
        checkIndex(id, cap, rcap)
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
     *     a stated [rcap]; [SofabError.ARGUMENT] when a schema-uncounted array was
     *     handed no cap at all ([rcap] negative)
     */
    public fun <T> reserveRowList(rows: MutableList<MutableList<T>>, id: Int, cap: Int, rcap: Int) {
        checkIndex(id, cap, rcap)
        while (rows.size < id) rows.add(mutableListOf())
        if (rows.size == id) {
            rows.add(mutableListOf())
            return
        }
        rows[id].clear()
    }

    // -----------------------------------------------------------------------
    // The index bound
    // -----------------------------------------------------------------------

    /**
     * Reject an element index the caller may not accept, before the list is grown
     * to hold it (CORELIB_PLAN §6.2.1).
     *
     * Every placement here runs this first — [placeElem], [reserveElem] and every
     * `reserveRow*` — and it is public for the one site that has **no reservation
     * to ride**: a generated `fixlenBegin` arm bounds a `string` or `blob`
     * element's index at the **length word**, so that a message ending right there
     * is refused rather than reported `INCOMPLETE` (MESSAGE_SPEC §5.2). The
     * placement that follows re-runs it, which costs one comparison against a
     * folded constant and spares the two sites from having to agree by inspection.
     *
     * Exactly one of the two numbers applies, and the schema picks which — the
     * bound and the category are one decision with two answers, which is why they
     * are one comparison here rather than two guards in the caller. A declared
     * [cap] is the array's schema `count`: an element past it contradicts
     * the schema both peers agreed on, so it is malformed input (MESSAGE_SPEC
     * §7.1) and the receiver cap must not be applied to that field at all. An
     * array the schema leaves uncounted grows to *highest present id + 1* by
     * design (§5.1), so its index is its length and [rcap] — the deployment's
     * capacity decision — is what bounds it; the bytes are well formed, the same
     * element decodes for a receiver configured more loosely, and the verdict is
     * the [SofabError.LIMIT_EXCEEDED] policy category (§6.3) — or, where the call
     * stated no cap at all, [SofabError.ARGUMENT]; see [refusal].
     *
     * Neither number is this object's. Both arrive per call, are used for this one
     * comparison and are not retained; nothing here defaults, invents or clamps to
     * a limit, and an over-index element is rejected, never dropped or folded into
     * a lower slot.
     *
     * @param id the element's wire id, which is its index
     * @param cap the array's schema `count`, or a negative number where the schema
     *     declares none
     * @param rcap the receiver's configured `max_dyn_array_count`, applied only
     *     where [cap] is negative
     * @throws SofabException [SofabError.INVALID_MSG] when [id] reaches a declared
     *     [cap]; [SofabError.LIMIT_EXCEEDED] when a schema-uncounted [id] reaches
     *     a stated [rcap]; [SofabError.ARGUMENT] when a schema-uncounted array was
     *     handed no cap at all ([rcap] negative)
     */
    public fun checkIndex(id: Int, cap: Int, rcap: Int) {
        if (id >= (if (cap >= 0) cap else rcap)) throw refusal(id, cap, rcap)
    }

    /**
     * Build the refusal an [id] that [checkIndex] rejected has earned: a declared
     * [cap] it reaches (malformed input, [SofabError.INVALID_MSG], MESSAGE_SPEC
     * §7.1), a stated receiver cap it reaches, or a cap that was never stated at
     * all (CORELIB_PLAN §6.3).
     *
     * Out of line, and **returned** for the caller to throw rather than thrown
     * here, so that [checkIndex] stays one compare and one `throw` — small enough
     * that a JIT inlines it at every call site whatever that site's frequency.
     * That is measured, not assumed: with the message building in its body (48
     * bytes of bytecode, and a `Nothing`-typed call would not help, since Kotlin
     * follows one with a `KotlinNothingValueException` throw of its own) HotSpot
     * C2 reported it "too big" at the generated `fixlenBegin` arms that bound a
     * string/blob element's index at the length word, and those calls cost +1.2 %
     * of a `vehicle_telemetry` decode (generator#587). At 22 bytes it is inlined
     * at every site and the whole #587 move reads −0.35 % instead.
     *
     * The two categories say different things and are not interchangeable.
     * [SofabError.LIMIT_EXCEEDED] means *"raise my limit, or the sender must send
     * less"*: a receiver cap was configured, these bytes are well formed, and the
     * same element decodes for a receiver configured more loosely. A negative
     * [rcap] is not a small limit and not an unlimited one — it is the **absence**
     * of the number §6.2.1 requires the caller to supply, so the mistake is in the
     * **call** and the category is [SofabError.ARGUMENT] (§6.3's
     * `InvalidArgument`). Reporting that as `LIMIT_EXCEEDED` would name a receiver
     * policy the deployment never set and promise a limit to raise that does not
     * exist. The refusal itself is the same either way: §6.2.1 forbids reading an
     * omitted cap as *unlimited*, so an unstated cap still admits no element.
     */
    private fun refusal(id: Int, cap: Int, rcap: Int): SofabException = when {
        cap >= 0 -> SofabException(SofabError.INVALID_MSG, "array index $id above declared count $cap")
        rcap < 0 -> SofabException(SofabError.ARGUMENT, "max_dyn_array_count not stated (cap $rcap) for array index $id")
        else -> SofabException(SofabError.LIMIT_EXCEEDED, "array index $id above configured limit $rcap")
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
