/*
 * SofaBuffers Kotlin Multiplatform — public status, error and tag types.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * The three-valued decode outcome of CORELIB_PLAN §5.2. The outcomes are
 * identical for one-shot and streaming decodes, and there is **no**
 * finish/finalize step: the caller owns end-of-input.
 */
public enum class DecodeStatus {
    /** The consumed bytes end exactly at a field boundary — a valid message. */
    COMPLETE,

    /**
     * The consumed bytes end *inside* a field (a partial varint, a fixlen/array
     * payload shorter than declared) or with an open, unclosed sequence. This is
     * **not** an error: more bytes could complete the message, and the caller
     * decides whether a trailing `INCOMPLETE` is a truncation it cares about.
     */
    INCOMPLETE,

    /**
     * The bytes are malformed regardless of what follows. Surfaced as a thrown
     * [SofabException] with [SofabError.INVALID_MSG] and then latched:
     * [IStream.status] reports it until [IStream.reset], and no continuation can
     * change it back.
     */
    INVALID,
}

/**
 * Error categories raised by the encoder and decoder.
 *
 * Mirrors the C `sofab_ret_t` status codes (minus `OK`, which this API models as
 * a normal return), plus [LIMIT_EXCEEDED], a receiver-side policy category with
 * no wire-format equivalent (CORELIB_PLAN §6.2.1).
 */
public enum class SofabError {
    /** Invalid caller argument (e.g. a field id outside `0..ID_MAX`). */
    ARGUMENT,

    /** The output buffer is full and no [FlushSink] is available. */
    BUFFER_FULL,

    /**
     * The input bytes are not a valid Sofab message — malformed **regardless of
     * what follows**: an overlong (>64-bit) varint, a reserved fixlen subtype, a
     * wrong-width `fp32`/`fp64`, an oversized id/length/count, nesting past
     * `MAX_DEPTH`, a dangling sequence end.
     *
     * Deliberately distinct from [LIMIT_EXCEEDED], which is a policy decision
     * about otherwise well-formed bytes. **Truncation is not this**: bytes short
     * of a complete field are [DecodeStatus.INCOMPLETE], a first-class non-error.
     */
    INVALID_MSG,

    /**
     * A well-formed message field exceeds a receiver-configured decode limit for
     * an unbounded (dynamic) field — one whose schema declares no
     * `count`/`maxlen` (CORELIB_PLAN §6.2.1). The limits
     * (`max_dyn_array_count`, `max_dyn_string_len`, `max_dyn_blob_len`) are
     * configured per deployment, and the **number is always generated code's** —
     * the codec holds none, defaults none and clamps to none. The **comparison**
     * runs wherever the count or length is already in hand before an allocation:
     * in the generated visitor for a native array's count header, and inside
     * [PayloadAcc] and [Seq] for a payload length and a row index, which take the
     * caller's cap as an argument (§6.2.1, "passing a limit in is not the codec
     * holding one"). Each rule is enforced in exactly one of the two places.
     *
     * **Not wire malformation.** The same message decodes under a looser or
     * unset limit, so this category is kept strictly distinct from
     * [INVALID_MSG] — policy divergence between two differently configured
     * receivers is not a conformance divergence. It is never clamped and never
     * truncated.
     */
    LIMIT_EXCEEDED,
}

/**
 * Thrown by the encoder and decoder on protocol or buffer errors.
 *
 * It extends [RuntimeException] rather than a checked exception so that Java
 * callers of the JVM artifact are not forced to declare what Kotlin cannot: a
 * Kotlin function never carries a `throws` clause, so a checked exception here
 * would be uncatchable from Java. The specific category is [error].
 */
public class SofabException(
    /** The error category that caused this exception. */
    public val error: SofabError,
    detail: String? = null,
) : RuntimeException(if (detail == null) "sofab: $error" else "sofab: $error ($detail)")

/**
 * Sub-type of a fixed-length field — the 3-bit tag in the low bits of a
 * `fixlen_word` (CORELIB_PLAN §4.6).
 *
 * The tag travels *outwards* only: encoders name a sub-type with one of these
 * constants and [raw] turns it into the wire tag, while the decoder narrows an
 * incoming tag itself at each site that reads a fixlen word — rejecting the
 * reserved values `0x4..0x7` there — and hands the visitor the matching constant.
 */
public enum class FixlenType(
    /** The 3-bit wire tag for this sub-type (0..3). */
    public val raw: Int,
) {
    /** 32-bit IEEE-754 float, little-endian on the wire. */
    FP32(F_FP32),

    /** 64-bit IEEE-754 double, little-endian on the wire. */
    FP64(F_FP64),

    /** UTF-8 text, no NUL terminator on the wire. */
    STRING(F_STRING),

    /** Arbitrary raw bytes. */
    BLOB(F_BLOB),
}

/**
 * Element category of an array field, reported to a [Visitor] via
 * [Visitor.arrayBegin] just before the elements are delivered.
 *
 * A fixlen array names its concrete element subtype ([FP32] / [FP64]), never a
 * collapsed "floating point" category: CORELIB_PLAN §4.8 has the element subtype
 * decide whether a field contradicts the schema and must be skipped
 * (MESSAGE_SPEC §7.3), so the subtype has to reach the visitor. The ordinals are
 * normative across the family: `UNSIGNED = 0`, `SIGNED = 1`, `FP32 = 2`,
 * `FP64 = 3`.
 */
public enum class ArrayKind {
    /** Unsigned-integer elements, delivered through [Visitor.unsigned]. */
    UNSIGNED,

    /** Signed-integer elements, delivered through [Visitor.signed]. */
    SIGNED,

    /** IEEE-754 32-bit float elements, delivered through [Visitor.fp32Bits]. */
    FP32,

    /** IEEE-754 64-bit double elements, delivered through [Visitor.fp64]. */
    FP64,
}

/**
 * Sink that receives buffered bytes when an [OStream]'s buffer fills (or when
 * [OStream.flush] is called).
 *
 * A `fun interface`, so a sink can be written as a lambda. Implementing it lets a
 * message larger than the output buffer (or larger than RAM) be streamed out
 * incrementally.
 *
 * **What returning means** (CORELIB_PLAN §5.1): returning *without* installing a
 * buffer says the sink **copied** — the encoder resumes writing into the same
 * buffer at offset 0. A sink that **takes** the buffer (hands it to a transport,
 * queues it, gives it to DMA) MUST install a replacement with
 * [OStream.bufferSet] before returning.
 */
public fun interface FlushSink {

    /**
     * Consume [length] bytes starting at [offset] in [data].
     *
     * @param data the encoder's active buffer
     * @param offset start of the pending bytes
     * @param length number of pending bytes
     */
    public fun flush(data: ByteArray, offset: Int, length: Int)
}
