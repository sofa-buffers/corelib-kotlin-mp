/*
 * SofaBuffers Kotlin Multiplatform — decoder visitor.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * Receives decoded fields pushed by an [IStream].
 *
 * The decoder follows the *visitor pattern*: rather than binding a destination
 * buffer per field (as the C API does), it calls back into a `Visitor` as each
 * field is decoded. Every member has a default no-op implementation, so an
 * implementor overrides only the field kinds it cares about; unhandled fields are
 * simply dropped (the equivalent of "not interested" / skip in the C API). This
 * keeps generated message classes small: a generated `Visitor` is typically one
 * `when` on the field id.
 *
 * **Streaming contract.** Scalars and floats are delivered whole. String and blob
 * payloads are delivered in one or more chunks so they can exceed the input chunk
 * size (and even RAM); each chunk reports the field's `total` length and the byte
 * offset of the chunk within the field. Array elements are announced once via
 * [arrayBegin] and then delivered through the scalar / float callbacks with the
 * same `id`. A fixlen field — string, blob, fp32 or fp64 — is announced once via
 * [fixlenBegin] at its length word, before any payload byte.
 *
 * **Buffer ownership.** The `data` array handed to [string] and [blob] is the
 * caller's input buffer and is only valid for the duration of the call
 * (CORELIB_PLAN §6, chunk lifetime). A visitor that needs to retain bytes must
 * copy the `[chunkOffset, chunkOffset + chunkLength)` range.
 */
public interface Visitor {

    /**
     * An unsigned-integer field, or an unsigned array element.
     *
     * @param id field id
     * @param value the value (unsigned 64-bit; interpret with [ULong] or
     *   `toULong()`)
     */
    public fun unsigned(id: Int, value: Long) {}

    /**
     * A signed-integer field, or a signed array element.
     *
     * @param id field id
     * @param value the value
     */
    public fun signed(id: Int, value: Long) {}

    /**
     * A 32-bit float field, or an `fp32` array element, as its **raw wire bits**
     * — the little-endian payload read back as an [Int].
     *
     * This is the callback the decoder actually invokes for every `fp32`
     * position, and its default implementation widens the bits into a [Float] and
     * calls [fp32]. Two hooks exist for one wire type because of the
     * signaling-NaN hazard of CORELIB_PLAN §6.5: an `fp32` payload must round-trip
     * **bit-for-bit**, and on a target whose only float value is a double
     * (Kotlin/JS) passing the value through [Float] sets the quiet bit and
     * destroys a signaling NaN. A consumer that re-encodes — a transcoder, a
     * relay, a round-trip test — overrides **this** method and hands the bits to
     * [OStream.writeFp32Bits] unchanged; a consumer that only wants the value
     * overrides [fp32] and pays one forwarding call the JIT removes.
     *
     * @param id field id
     * @param bits the IEEE-754 binary32 bit pattern, exactly as it was on the wire
     */
    public fun fp32Bits(id: Int, bits: Int) {
        fp32(id, Float.fromBits(bits))
    }

    /**
     * A 32-bit float field, or an `fp32` array element, as a value.
     *
     * Reached through the default implementation of [fp32Bits]. On a target with
     * a native 32-bit float (Kotlin/JVM, Kotlin/Native) the value is bit-exact;
     * on Kotlin/JS, where every float is a double, a signaling NaN is quieted on
     * the way here — see [fp32Bits].
     *
     * @param id field id
     * @param value the value
     */
    public fun fp32(id: Int, value: Float) {}

    /**
     * A 64-bit float field, or an `fp64` array element.
     *
     * No raw-bits counterpart is needed: a native double holds all 64 bits
     * verbatim on every target, so `value.toRawBits()` is the wire payload
     * (CORELIB_PLAN §6.5).
     *
     * @param id field id
     * @param value the value
     */
    public fun fp64(id: Int, value: Double) {}

    /**
     * Start of a fixlen field — string, blob, fp32 or fp64. Announced once the
     * `fixlen_word` has been read and validated as a format matter, and before any
     * payload byte is delivered. Called exactly once per fixlen field, `total == 0`
     * included; the payload then follows through [string] / [blob] (in chunks) or
     * [fp32Bits] / [fp64] (whole).
     *
     * **Why the decoder announces it here.** CORELIB_PLAN §5.2 makes `INVALID`
     * dominate `INCOMPLETE`: once the bytes seen so far are already malformed,
     * running out of input cannot downgrade the verdict. A `maxlen` violation is
     * fully established by the length word — the number that exceeds the bound is
     * already on the wire, and no later byte can make it legal. The payload
     * callbacks carry `total` too, but they only fire once payload bytes exist, so
     * a message truncated immediately after the length word would produce no event
     * at all and decay to `INCOMPLETE`, while the same bytes arriving in one chunk
     * would be `INVALID`. That is a chunk-boundary-dependent outcome, which §6.4
     * and §7.2 item 4 forbid outright. Announcing at the length word is what lets
     * generated code latch the violation there; *raising* from this callback is
     * what turns the field `INVALID`.
     *
     * `subtype` is the subtype that *arrived*, which the corelib knows, not the
     * one the schema *declared*, which it does not: a subtype contradicting the
     * declared field type must be ignored as a MESSAGE_SPEC §7.3 skip rather than
     * measured against this field's bound.
     *
     * This fires for a fixlen *field* only. A fixlen array's shared `fixlen_word`
     * is the array's header and is announced by [arrayBegin] instead; its elements
     * produce no `fixlenBegin`.
     *
     * @param id field id
     * @param subtype the fixlen subtype read from the wire
     * @param total declared payload length in bytes (4 for fp32, 8 for fp64)
     */
    public fun fixlenBegin(id: Int, subtype: FixlenType, total: Int) {}

    /**
     * A chunk of a string field (raw UTF-8 bytes, no NUL terminator).
     *
     * The bytes are **not** validated by the corelib: validation runs where a
     * string is *materialized* (CORELIB_PLAN §6.4), which is here, in the
     * consumer. Pass the range to [Utf8.valid] before turning it into a [String],
     * and reject rather than replace.
     *
     * For an empty string this is called once with `total == 0` and
     * `chunkLength == 0`.
     *
     * @param id field id
     * @param total full field length in bytes
     * @param offset byte position of this chunk within the field
     * @param data backing array containing the chunk
     * @param chunkOffset start of the chunk within [data]
     * @param chunkLength number of bytes in the chunk
     */
    public fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {}

    /**
     * A chunk of a blob field. See [string] for the chunking model.
     *
     * @param id field id
     * @param total full field length in bytes
     * @param offset byte position of this chunk within the field
     * @param data backing array containing the chunk
     * @param chunkOffset start of the chunk within [data]
     * @param chunkLength number of bytes in the chunk
     */
    public fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {}

    /**
     * Start of an array field. The `count` elements follow through the scalar /
     * float callbacks with the same `id`.
     *
     * **When it fires.** For an integer array (wire types `ARRAY_UNSIGNED` /
     * `ARRAY_SIGNED`) this is called immediately after the element-count varint —
     * that word is the whole header. For a fixlen array (`ARRAY_FIXLEN`) it is
     * called only after the `fixlen_word` that follows the count has been read
     * *and* validated as a format matter, so [kind] always names the concrete
     * element subtype ([ArrayKind.FP32] / [ArrayKind.FP64]). CORELIB_PLAN §4.8
     * requires that ordering: a fixlen array whose subtype contradicts the
     * declared element type is skipped under MESSAGE_SPEC §7.3 and its `count`
     * must not be judged against a schema bound, so the subtype has to be known
     * before the visitor is asked about the field. Either way the call happens
     * exactly once per array field, before any element callback; a message that
     * ends between the two words produces no call at all (it is `INCOMPLETE`, not
     * `INVALID`).
     *
     * @param id field id
     * @param kind element category, naming the fixlen subtype for a fixlen array
     * @param count number of elements
     */
    public fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {}

    /**
     * Offer to take an **integer** array's elements in bulk instead of one call at
     * a time. Asked once per array, immediately after [arrayBegin] and before any
     * element, for [ArrayKind.UNSIGNED] and [ArrayKind.SIGNED] arrays with at
     * least one element.
     *
     * Return a [ByteArray], [ShortArray], [IntArray] or [LongArray] of **at least
     * `count`** elements and the decoder writes straight into `[0, count)` —
     * already ZigZag-decoded for a signed array, exactly the values [signed] would
     * have delivered — with no per-element callback at all, then calls
     * [arrayBulkEnd]. Return `null`, the default, and the elements arrive through
     * [unsigned] / [signed] as before. A shorter array than `count`, or any other
     * type, is treated as `null`, so a miscounted or mistyped destination cannot
     * overrun.
     *
     * **The array's width is a bound.** Handing back a narrower array than
     * [LongArray] says the elements are declared that wide, so a value that does
     * not fit it is malformed input (MESSAGE_SPEC §7.1) and the decode fails with
     * [SofabError.INVALID_MSG] — zero-extended against the width for an
     * [ArrayKind.UNSIGNED] array, sign-extended for a [ArrayKind.SIGNED] one. The
     * decoder never truncates silently. That is the reason to hand back a narrow
     * array at all: the check and the narrowing happen in the same pass that
     * decodes, instead of a second one afterwards.
     *
     * The return type is [Any] rather than four overloads because the decoder must
     * resolve the destination in ONE virtual call per array — asking four times,
     * three of them for null, costs more than the pass it saves.
     *
     * **`count` is untrusted.** It is the wire's claim, bounded only by the format
     * ceiling. Return `null` unless the array's length is already bounded by
     * something the consumer trusts (a schema `count`), or an oversized claim
     * becomes an up-front allocation.
     *
     * @param id field id
     * @param kind [ArrayKind.UNSIGNED] or [ArrayKind.SIGNED]
     * @param count number of elements the wire announced
     * @return destination of at least `count` elements, or `null` for per-element
     */
    public fun arrayBulk(id: Int, kind: ArrayKind, count: Int): Any? = null

    /**
     * The bulk fill offered at [arrayBulk] is complete: [n] elements were written
     * into the destination. Called exactly once per accepted offer, after the last
     * element and before any following field. A truncated message never reaches it
     * — the array did not end.
     *
     * @param id field id
     * @param n number of elements written (the announced count)
     */
    public fun arrayBulkEnd(id: Int, n: Int) {}

    /**
     * Start of a nested sequence (a new id scope).
     *
     * @param id field id of the sequence
     */
    public fun sequenceBegin(id: Int) {}

    /** End of the current nested sequence. */
    public fun sequenceEnd() {}
}
