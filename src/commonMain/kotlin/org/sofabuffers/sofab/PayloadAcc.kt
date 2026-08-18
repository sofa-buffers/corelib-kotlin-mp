/*
 * SofaBuffers Kotlin Multiplatform — support layer for generated code: the
 * growable byte sink, for a payload delivered in chunks and for a flush sink
 * being drained.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * A growable byte sink — the **support layer**, not the codec.
 *
 * Two jobs, both of them cases where the size is not known in advance, and both
 * of them the same buffer:
 *
 * - **Decode.** [Visitor.string] and [Visitor.blob] deliver a payload in one or
 *   more chunks, split wherever the input happened to be split, and the `data`
 *   array they hand over is only valid for the duration of the call. A consumer
 *   that wants the whole value has to buffer the pieces: [string] and [blob] do
 *   that and hand the value back on the chunk that completes it.
 * - **Encode.** A message whose size the schema cannot bound is written through a
 *   fixed scratch buffer and a [FlushSink]; this *is* such a sink, so the bytes
 *   land here and come out of [toByteArray] at the end. Memory is then bounded by
 *   the message rather than by a buffer guessed up front, which is what
 *   CORELIB_PLAN §5.1 leaves to the generated layer.
 *
 * Its code has the same shape for every schema, so it lives here rather than
 * being emitted into every generated package (generator#345).
 *
 * Hold one per visitor and pass the callback's arguments straight through; the
 * value comes back on the chunk that completes it, and `null` before that:
 *
 * ```
 * override fun string(id: Int, total: Int, offset: Int, data: ByteArray, co: Int, cl: Int) {
 *     val s = acc.string(total, offset, data, co, cl) ?: return   // more chunks to come
 *     ...                                                        // route s to its field
 * }
 * ```
 *
 * **A payload that arrives whole never touches the buffer.** The common case —
 * one chunk carrying the entire field — is answered straight out of the caller's
 * input array, so an accumulator that is never needed never allocates one byte.
 *
 * **`total` is not an allocation.** The announced length is the wire's claim,
 * bounded by nothing this class knows about, so the buffer grows by doubling
 * against bytes that have actually arrived. A caller with a schema `maxlen` or a
 * receiver limit rejects an oversized `total` before the first chunk reaches here
 * (MESSAGE_SPEC §7.1); a caller without one still cannot be made to allocate more
 * than the peer actually sent.
 *
 * **No re-arming step on the decode side.** Every payload's first chunk is
 * reported at offset 0, and that is where the buffer is emptied — so an
 * accumulator still holding the remains of a payload that never completed (a
 * stream that ended mid-field) is correct again the moment the next one starts,
 * whether or not the visitor around it was reused.
 *
 * **The split must not be observable.** CORELIB_PLAN §6.4 forbids an outcome that
 * depends on where a chunk boundary fell — for the same bytes, the value and the
 * UTF-8 verdict are the same whether they arrive in one piece or one byte at a
 * time.
 */
public class PayloadAcc : FlushSink {

    /** Accumulated bytes; empty until something is actually buffered. */
    private var buf: ByteArray = Seq.EMPTY_BYTES

    /**
     * How many bytes stand in the accumulator: the length of the encoded message
     * drained so far, or of the payload reassembled so far.
     */
    public var size: Int = 0
        private set

    /**
     * Append [length] bytes of [data] starting at [offset].
     *
     * This is the encode side's entry point, and the raw one: nothing is
     * announced in advance and nothing completes, so the buffer simply doubles as
     * bytes arrive. Reassembling a decoded payload goes through [string] or
     * [blob] instead, which know the announced total and can stop growing at it.
     *
     * @param data source array
     * @param offset start of the bytes to append within [data]
     * @param length number of bytes to append
     */
    public fun write(data: ByteArray, offset: Int, length: Int) {
        grow(size + length, 0)
        data.copyInto(buf, size, offset, offset + length)
        size += length
    }

    /**
     * [write], under the name [FlushSink] gives it: an encode can hand this
     * accumulator to [OStream] directly as its sink.
     *
     * This sink **copies** — it never takes the encoder's buffer — so it installs
     * no replacement and the encoder resumes at offset 0 of the same buffer
     * (CORELIB_PLAN §5.1).
     *
     * @param data the encoder's active buffer
     * @param offset start of the pending bytes
     * @param length number of pending bytes
     */
    override fun flush(data: ByteArray, offset: Int, length: Int) {
        write(data, offset, length)
    }

    /**
     * Empty the accumulator, keeping the buffer it has already grown.
     *
     * Nothing on the decode side needs this — a payload's first chunk empties the
     * buffer itself — but an encoder reusing one accumulator for a second message
     * does.
     */
    public fun reset() {
        size = 0
    }

    /**
     * The accumulated bytes as an array of exactly [size] bytes.
     *
     * A copy: the accumulator keeps its buffer and stays usable afterwards.
     *
     * @return the bytes accumulated so far
     */
    public fun toByteArray(): ByteArray = buf.copyOf(size)

    /**
     * Offer a chunk of a `string` payload; returns the decoded string once the
     * last chunk has arrived, `null` while more are expected.
     *
     * Validation happens on the reassembled payload, once, at the point it is
     * complete — never per chunk, which would reject a multi-byte character split
     * across a boundary.
     *
     * @param total full payload length in bytes, as [Visitor.string] reports it
     * @param offset byte position of this chunk within the payload
     * @param data backing array containing the chunk
     * @param chunkOffset start of the chunk within [data]
     * @param chunkLength number of bytes in the chunk
     * @return the completed string, or null while the payload is incomplete
     * @throws SofabException [SofabError.INVALID_MSG] when the completed payload
     *     is not valid UTF-8
     */
    public fun string(total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int): String? {
        if (offset == 0 && chunkLength >= total) return Utf8.decode(data, chunkOffset, total)
        if (!append(total, offset, data, chunkOffset, chunkLength)) return null
        size = 0
        return Utf8.decode(buf, 0, total)
    }

    /**
     * Offer a chunk of a `blob` payload; returns the payload once the last chunk
     * has arrived, `null` while more are expected.
     *
     * The returned array is the caller's to keep: it is a copy, never a view into
     * the decoder's input buffer or into this accumulator.
     *
     * @param total full payload length in bytes, as [Visitor.blob] reports it
     * @param offset byte position of this chunk within the payload
     * @param data backing array containing the chunk
     * @param chunkOffset start of the chunk within [data]
     * @param chunkLength number of bytes in the chunk
     * @return the completed payload, or null while it is incomplete
     */
    public fun blob(total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int): ByteArray? {
        if (offset == 0 && chunkLength >= total) {
            return data.copyOfRange(chunkOffset, chunkOffset + total)
        }
        if (!append(total, offset, data, chunkOffset, chunkLength)) return null
        size = 0
        return buf.copyOf(total)
    }

    /**
     * Append a chunk of an announced payload, growing the buffer as bytes
     * actually arrive.
     *
     * @return true once [total] bytes stand in the buffer
     */
    private fun append(total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int): Boolean {
        if (offset == 0) {
            // A payload starting over: whatever stands here belongs to one that
            // never completed — a stream that ended mid-field — and must not be
            // prefixed onto this one.
            size = 0
        }
        grow(size + chunkLength, total)
        data.copyInto(buf, size, chunkOffset, chunkOffset + chunkLength)
        size += chunkLength
        return size >= total
    }

    /**
     * Make room for [need] bytes in total, doubling but never below what the
     * caller needs — and, where a payload announced its [ceiling], never above it
     * either: a payload that arrives whole after one partial chunk then lands in
     * an exactly-sized buffer instead of at the next power of two. A [ceiling] of
     * 0 means there is none, which is the encode side, where no total is
     * announced.
     *
     * The arithmetic is in [Long]: `size * 2` overflows [Int] near
     * [Int.MAX_VALUE], and an overflowed length would come back *shorter* than
     * what was asked for.
     */
    private fun grow(need: Int, ceiling: Int) {
        if (need <= buf.size) return
        var grown = buf.size.toLong() * 2
        if (grown < need) grown = need.toLong()
        if (ceiling > 0 && grown > ceiling) grown = maxOf(ceiling, need).toLong()
        buf = buf.copyOf(grown.toInt())
    }
}
