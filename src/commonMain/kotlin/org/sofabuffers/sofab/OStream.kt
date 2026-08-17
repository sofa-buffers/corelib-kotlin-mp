/*
 * SofaBuffers Kotlin Multiplatform — streaming output encoder.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * Streaming SofaBuffers encoder writing into a caller-provided [ByteArray].
 *
 * The encoder never allocates the output buffer itself: it writes into the array
 * you hand it (CORELIB_PLAN §5.1 — a corelib **must not** allocate, grow or
 * reallocate an output buffer). When that array fills, the accumulated bytes are
 * passed to an optional [FlushSink] and writing resumes at the start of the
 * buffer, so a message larger than the buffer (or larger than RAM) can be
 * streamed out. With no sink, a full buffer raises [SofabError.BUFFER_FULL].
 *
 * An initial [offset] reserves space at the front of the buffer for a lower-layer
 * protocol header, avoiding a copy.
 *
 * A buffer installed **with** a sink must leave at least
 * [Sofab.MIN_OUTPUT_BUFFER] usable bytes and is rejected where it is handed over
 * if it does not; a buffer installed without one is subject to no minimum.
 *
 * **Speed.** Writes take a fast path that advances a cursor over the buffer with
 * no per-byte bounds check whenever the remaining room is known to be sufficient
 * (a varint is at most ten bytes; a float four or eight); a buffer-spanning slow
 * path that flushes mid-value is used only when the buffer is too small to hold
 * the value outright. Raw string / blob payloads are copied in bulk up to each
 * buffer boundary. **The wire output is identical regardless of buffer size.**
 *
 * A field is written with as few stores as its shape allows: a multi-byte varint
 * is assembled in a register and written with one eight-byte store, a one-byte
 * header and one-byte value go out together as one two-byte store, and a whole
 * `fp32` field — header, `fixlen_word` and payload — fits one eight-byte store. A
 * store may therefore reach past the field it wrote, leaving up to seven **scratch
 * bytes** in the buffer immediately after the current write position. They are
 * never part of the message and never escape: they sit strictly between
 * [bytesUsed] and the end of the buffer, are overwritten by the next write, and
 * only `[0, bytesUsed)` is ever handed to a [FlushSink]. Bytes before the starting
 * [offset] — the region reserved for a lower-layer header — are never touched. A
 * buffer with fewer than ten bytes free falls back to the byte-at-a-time path, so
 * small buffers see no scratch writes at all.
 *
 * **Sequences are framed lazily**: [writeSequenceBeginLazy] holds the header back
 * until the sequence turns out to have content. A sequence-typed *field* that
 * receives none is therefore dropped entirely rather than emitted as an empty
 * frame (MESSAGE_SPEC §2). That is still not true for a wrapper-array *element*,
 * whose presence carries the array's length (§5.1); that position closes with
 * [writeSequenceEndKeep], which forces the frame out. Held-back ids are encoder
 * state, not buffer content, so this never interacts with flushing, and the run
 * grows to the full [Sofab.MAX_DEPTH] so the output is canonical at every legal
 * depth.
 *
 * This class is not thread-safe; encode one message from one thread.
 *
 * ```
 * val buf = ByteArray(64)
 * val os = OStream(buf)
 * os.writeUnsigned(1, 42)
 * os.writeSigned(2, -7)
 * os.writeString(3, "hi")
 * val used = os.bytesUsed
 * ```
 *
 * @param buffer caller-owned output buffer (non-empty)
 * @param offset initial write position (`0..buffer.size`)
 * @param sink flush sink, or `null` for none
 * @throws IllegalArgumentException if the buffer is empty, the offset is out of
 *   range, or a sink is given and the buffer leaves less than
 *   [Sofab.MIN_OUTPUT_BUFFER] usable bytes
 */
public class OStream(
    buffer: ByteArray,
    offset: Int = 0,
    private val sink: FlushSink? = null,
) {

    private var buffer: ByteArray
    private var end: Int
    private var pos: Int

    /**
     * Set by [bufferSet], read and cleared by the flush the call happened inside.
     * CORELIB_PLAN §5.1: a sink that **takes** the buffer it was handed installs a
     * replacement before returning, and writing then resumes at *that
     * installation's* offset; a sink that returns without installing anything
     * copied, and writing resumes at offset 0 in the still-active buffer. The
     * encoder cannot tell the two apart by any other means, so this flag is what
     * keeps a flush from overwriting the cursor an installation just set.
     *
     * The offset belongs to the installation and is consumed by the flush it
     * belongs to, so the flag is cleared at every handover: a `bufferSet` made
     * outside a sink arms nothing for a later flush.
     */
    private var installed = false

    /** Number of nested sequences currently open; bounded by [Sofab.MAX_DEPTH]. */
    private var depth = 0

    /**
     * Ids of the innermost open sequences whose header has not been written yet
     * (MESSAGE_SPEC §2 lazy framing). Always a contiguous suffix of the open
     * sequences — every entry is held back, and nothing below it is — so
     * [writeSequenceEnd] can simply pop the last entry. Writing any field commits
     * the whole run at once, and there is no other way to leave it; the invariant
     * therefore holds by construction.
     *
     * Allocated on the first nested [writeSequenceBeginLazy] — an encoder whose
     * sequences never nest never allocates it — and grown on demand, so the
     * hold-back reaches the full [Sofab.MAX_DEPTH].
     */
    private var pending: IntArray? = null

    /**
     * The outermost held-back id, kept out of [pending] so that a stream whose
     * sequences never nest — much the commonest shape — holds one back without
     * allocating an array at all. [pending] carries entries two and beyond.
     */
    private var pending0 = 0

    /** Total number of held-back ids: [pending0] plus [pending]. */
    private var nPending = 0

    init {
        checkHandover(buffer, offset, sink)
        this.buffer = buffer
        this.end = buffer.size
        this.pos = offset
    }

    /**
     * Number of bytes written to the active buffer since the last flush.
     */
    public val bytesUsed: Int
        get() = pos

    /**
     * Flush any pending bytes to the sink (if one is set) and report how many bytes
     * were pending. With no sink the buffer is left intact.
     *
     * This is a buffer handover like the automatic one: a sink that takes the
     * buffer may install a replacement with [bufferSet], and writing then resumes
     * at that installation's offset rather than at 0.
     *
     * @return number of bytes that were pending
     */
    public fun flush(): Int {
        val used = pos
        if (used > 0 && sink != null) {
            handOver(used)
        }
        return used
    }

    /**
     * Replace the active buffer (typically from within a flush sink), resuming
     * writes at [offset] in the new buffer.
     *
     * Called from within a [FlushSink] this is how a sink that **takes** the buffer
     * it was handed gives the encoder a replacement (CORELIB_PLAN §5.1); a sink
     * that returns without calling it copied, and writing resumes at 0 in the
     * buffer that is still active. The start offset belongs to the installation,
     * not to the buffer: passing the **same** array again is a new installation
     * like any other, which is how a sink re-arms header room in every flushed
     * unit. The offset is consumed by the flush it was installed in, so the next
     * flush the sink returns from bare resumes at 0 again.
     *
     * On a stream that carries a sink the replacement must leave at least
     * [Sofab.MIN_OUTPUT_BUFFER] usable bytes and is rejected here if it does not,
     * which is why a sink cannot hand back storage the encoder could not write a
     * single byte into. On a sink-less stream no minimum applies.
     *
     * @param buffer new caller-owned output buffer (non-empty)
     * @param offset initial write position (`0..buffer.size`)
     * @throws IllegalArgumentException if the buffer is empty, the offset is out of
     *   range, or this stream carries a sink and the buffer leaves less than
     *   [Sofab.MIN_OUTPUT_BUFFER] usable bytes
     */
    public fun bufferSet(buffer: ByteArray, offset: Int) {
        checkHandover(buffer, offset, sink)
        this.buffer = buffer
        this.end = buffer.size
        this.pos = offset
        this.installed = true
    }

    /**
     * Return this stream to its just-constructed state, writing into [buffer] from
     * its start, so a caller encoding many messages in a row (a server loop, a
     * generated `encode` helper) can hold one instance instead of letting each
     * encode allocate and immediately discard one.
     *
     * The [FlushSink] is **not** part of what is reset: it is fixed at
     * construction, so this resets a sink-carrying stream to a sink-carrying one
     * and a sink-less stream to a sink-less one. Reuse therefore stays within one
     * output discipline.
     *
     * Unlike [bufferSet] this also clears the sequence nesting depth and the
     * held-back sequence run. That is the whole point for reuse: an encode that
     * threw part-way leaves the depth counter non-zero and can leave sequence
     * headers pending, and carrying either into the next message on the same
     * thread would corrupt its nesting validation or prepend a stale
     * `sequence start` to it.
     *
     * The [pending] array keeps its allocation: retaining it is the point of
     * reuse, and it is never read while [nPending] is zero, which is cleared here.
     *
     * @param buffer caller-owned output buffer (non-empty)
     */
    public fun reset(buffer: ByteArray) {
        bufferSet(buffer, 0)
        depth = 0
        nPending = 0
        pending0 = 0
        // bufferSet marks an installation; a reset is not one made from inside a
        // flush, so it arms nothing for the next handover.
        installed = false
    }

    // --- primitives ---------------------------------------------------------

    /**
     * Hand the full buffer to the sink and resume writing, or fail if there is none.
     *
     * The write in flight always has room afterwards, so there is no
     * "still full" case to re-check: a sink that returns bare resumes at offset 0 of
     * a non-empty buffer, and a sink that installs a replacement went through
     * [checkHandover], which on a sink-carrying stream rejects anything leaving less
     * than [Sofab.MIN_OUTPUT_BUFFER] usable bytes.
     */
    private fun flushFull() {
        if (sink == null) {
            throw SofabException(SofabError.BUFFER_FULL)
        }
        handOver(pos)
    }

    /**
     * Hand [used] buffered bytes to the sink and settle where writing goes next
     * (CORELIB_PLAN §5.1): the offset of the replacement the sink installed if it
     * took the buffer, or 0 in the still-active buffer if it copied. The flag is
     * cleared first, so only an installation made from inside *this* call counts,
     * and it is consumed here.
     */
    private fun handOver(used: Int) {
        installed = false
        sink!!.flush(buffer, 0, used)
        if (installed) {
            installed = false
        } else {
            pos = 0
        }
    }

    /** Append one byte, flushing the full buffer first if it has no room. */
    private fun pushByte(b: Int) {
        if (pos >= end) {
            flushFull()
        }
        buffer[pos++] = b.toByte()
    }

    private fun pushRaw(data: ByteArray, from: Int, len: Int) {
        // Copy in bulk up to each buffer boundary instead of byte-by-byte, so a
        // large payload streams out in a handful of array copies.
        var src = from
        var remaining = len
        while (remaining > 0) {
            if (pos >= end) {
                flushFull()
            }
            val n = if (end - pos < remaining) end - pos else remaining
            data.copyInto(buffer, pos, src, src + n)
            pos += n
            src += n
            remaining -= n
        }
    }

    private fun writeVarint(value: Long) {
        // Fast path: a base-128 varint is at most 10 bytes. When that much room is
        // guaranteed, write it with no per-byte bounds or flush check. Single-byte
        // values (field headers, small scalars) are by far the most common and
        // cost one store.
        val p = pos
        if (end - p >= VARINT_ROOM) {
            pos = putVarint(buffer, p, value)
            return
        }
        writeVarintSlow(value)
    }

    /** Buffer-spanning varint write: flushes mid-value when the buffer is tiny. */
    private fun writeVarintSlow(value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                b = b or 0x80
            }
            pushByte(b)
        } while (v != 0L)
    }

    /** Write four little-endian bytes, fast when the buffer has room. */
    private fun putLe32(bits: Int) {
        val p = pos
        if (end - p >= 4) {
            leSetInt(buffer, p, bits)
            pos = p + 4
            return
        }
        pushByte(bits and 0xFF)
        pushByte((bits ushr 8) and 0xFF)
        pushByte((bits ushr 16) and 0xFF)
        pushByte((bits ushr 24) and 0xFF)
    }

    /** Write eight little-endian bytes, fast when the buffer has room. */
    private fun putLe64(bits: Long) {
        val p = pos
        if (end - p >= 8) {
            leSetLong(buffer, p, bits)
            pos = p + 8
            return
        }
        for (i in 0 until 8) {
            pushByte(((bits ushr (i * 8)) and 0xFF).toInt())
        }
    }

    /**
     * Validate [id] and write the field header varint `(id shl 3) or wireType`.
     *
     * This is the single choke point every field write in this class passes
     * through — the scalar, fixlen, float, string, blob and both array writers all
     * compose their header here — so it is also where a held-back sequence run is
     * committed: the field about to be written is content, which means every
     * enclosing sequence is non-default and must be framed after all
     * (MESSAGE_SPEC §2).
     */
    private fun writeIdType(id: Int, wireType: Int) {
        beginField(id)
        writeVarint((id.toLong() shl 3) or wireType.toLong())
    }

    /**
     * Validate [id] and settle any held-back sequence run, the two things every
     * field write does before its first byte reaches the buffer. Split out of
     * [writeIdType] so a writer that emits its header and value together can share
     * them without also being forced through a separate header write.
     */
    private fun beginField(id: Int) {
        // The id ceiling is ID_MAX == INT32_MAX (§6.2), so an Int argument can only
        // leave the range downwards: the sign test is the whole check, and this is
        // the encoder's per-field choke point.
        if (id < 0) {
            throw SofabException(SofabError.ARGUMENT, "id $id")
        }
        // No wire-type exemption is needed here. A sequence *header* never passes
        // through this function — writeSequenceBeginLazy holds it back and
        // commitPending emits it directly — and both closers settle the run
        // themselves before they get here, so the end marker only ever arrives with
        // an empty run.
        if (nPending != 0) {
            commitPending()
        }
    }

    /**
     * Write a field header and a scalar varint value together. Both are at most
     * fifteen bytes, so one room test and one cursor cover the pair, halving the
     * bounds/flush checks and the cursor round trips a field costs.
     */
    private fun writeIdTypeValue(id: Int, wireType: Int, value: Long) {
        beginField(id)
        val p = pos
        if (end - p >= FIELD_ROOM) {
            val b = buffer
            val header = (id.toLong() shl 3) or wireType.toLong()
            if (((header or value) and 0x7FL.inv()) == 0L) {
                // Both halves are one byte — an id below 16 with a small value,
                // which is most of a typical message — so the whole field is one
                // two-byte store instead of two bounds-checked single-byte ones.
                leSetShort(b, p, (header or (value shl 8)).toInt())
                pos = p + 2
                return
            }
            pos = putVarint(b, putVarint(b, p, header), value)
            return
        }
        writeVarint((id.toLong() shl 3) or wireType.toLong())
        writeVarint(value)
    }

    /**
     * Write out the held-back sequence headers, outermost first. Runs at most once
     * per non-default sequence run, never per field.
     */
    private fun commitPending() {
        val n = nPending
        nPending = 0
        writeVarint((pending0.toLong() shl 3) or T_SEQUENCE_START.toLong())
        val p = pending
        for (i in 0 until n - 1) {
            writeVarint((p!![i].toLong() shl 3) or T_SEQUENCE_START.toLong())
        }
    }

    // --- scalar writers -----------------------------------------------------

    /**
     * Write an unsigned-integer field. The [Long] is treated as an unsigned 64-bit
     * value.
     *
     * @param id field id (`0..ID_MAX`)
     * @param value unsigned value
     */
    public fun writeUnsigned(id: Int, value: Long) {
        writeIdTypeValue(id, T_VARINT_UNSIGNED, value)
    }

    /**
     * Write an unsigned-integer field from a [ULong], for callers that model
     * unsignedness in the type system rather than in a comment.
     *
     * @param id field id
     * @param value unsigned value
     */
    public fun writeUnsigned(id: Int, value: ULong) {
        writeIdTypeValue(id, T_VARINT_UNSIGNED, value.toLong())
    }

    /**
     * Write a signed-integer field (ZigZag + varint).
     *
     * @param id field id
     * @param value signed value
     */
    public fun writeSigned(id: Int, value: Long) {
        writeIdTypeValue(id, T_VARINT_SIGNED, zigzagEncode(value))
    }

    /**
     * Write a boolean as an unsigned `0` / `1` — booleans have no wire type of
     * their own (CORELIB_PLAN §4.4).
     *
     * @param id field id
     * @param value boolean value
     */
    public fun writeBoolean(id: Int, value: Boolean) {
        writeIdTypeValue(id, T_VARINT_UNSIGNED, if (value) 1L else 0L)
    }

    // --- fixed-length writers ----------------------------------------------

    /**
     * Write a fixed-length field: the id header, a `(len shl 3) or subtype` length
     * header, then [length] raw bytes from [data] (already in wire /
     * little-endian order for floats).
     *
     * This is the **byte-container** string entry point, so it carries the same
     * strict-UTF-8 encode obligation as [writeString] (CORELIB_PLAN §6.4,
     * MESSAGE_SPEC §8): with [FixlenType.STRING] the payload range is validated and
     * a malformed one is refused with [SofabError.ARGUMENT] *before* any byte is
     * emitted, so this API cannot produce a message that the family's own decoders
     * reject. Every other sub-type — including [FixlenType.BLOB], the type for
     * opaque bytes — passes through unvalidated at the cost of one comparison.
     *
     * @param id field id
     * @param data payload bytes
     * @param from start offset within [data]
     * @param length number of payload bytes
     * @param subtype fixed-length sub-type
     * @throws SofabException [SofabError.ARGUMENT] if [length] is negative, or if
     *   [subtype] is [FixlenType.STRING] and the payload range is not well-formed
     *   UTF-8
     */
    public fun writeFixlen(id: Int, data: ByteArray, from: Int, length: Int, subtype: FixlenType) {
        if (length < 0) {
            throw SofabException(SofabError.ARGUMENT, "length $length")
        }
        if (subtype == FixlenType.STRING && !Utf8.valid(data, from, from + length)) {
            throw SofabException(SofabError.ARGUMENT, "invalid UTF-8 string payload")
        }
        writeIdTypeValue(id, T_FIXLEN, (length.toLong() shl 3) or subtype.raw.toLong())
        pushRaw(data, from, length)
    }

    /**
     * Write a 32-bit float field.
     *
     * The value's **raw** bits reach the wire — `toRawBits` never normalizes a NaN
     * — so on a target with a native 32-bit float this is bit-exact for every
     * value. On Kotlin/JS, where a [Float] is a double, a signaling NaN has already
     * been quieted before it gets here; [writeFp32Bits] is the bit-exact entry
     * point there (CORELIB_PLAN §6.5).
     *
     * @param id field id
     * @param value value
     */
    public fun writeFp32(id: Int, value: Float) {
        writeFp32Bits(id, value.toRawBits())
    }

    /**
     * Write a 32-bit float field from its **raw wire bits** — the IEEE-754 binary32
     * bit pattern, little-endian on the wire.
     *
     * This is the bit-exact `fp32` path required by CORELIB_PLAN §6.5 for a
     * decode → re-encode: paired with [Visitor.fp32Bits] it moves the four payload
     * bytes through unchanged, so a signaling NaN survives even on a target whose
     * only float value is a double.
     *
     * @param id field id
     * @param bits IEEE-754 binary32 bit pattern
     */
    public fun writeFp32Bits(id: Int, bits: Int) {
        beginField(id)
        val p = pos
        // Header (<=5) + the constant one-byte fixlen_word + four payload bytes.
        if (end - p >= FIELD_ROOM) {
            val b = buffer
            val header = (id.toLong() shl 3) or T_FIXLEN.toLong()
            if ((header and 0x7FL.inv()) == 0L) {
                // Six bytes — a one-byte header, the constant fixlen_word and the
                // payload — fit one eight-byte store, so the commonest float field
                // costs a single bounds-checked write. FIELD_ROOM covers the two
                // scratch bytes past it.
                leSetLong(
                    b,
                    p,
                    header or
                        (((4L shl 3) or F_FP32.toLong()) shl 8) or
                        ((bits.toLong() and 0xFFFF_FFFFL) shl 16),
                )
                pos = p + 6
                return
            }
            val q = putVarint(b, p, header)
            b[q] = ((4 shl 3) or F_FP32).toByte()
            leSetInt(b, q + 1, bits)
            pos = q + 5
            return
        }
        writeVarint((id.toLong() shl 3) or T_FIXLEN.toLong())
        writeVarint((4L shl 3) or F_FP32.toLong())
        putLe32(bits)
    }

    /**
     * Write a 64-bit float field. A native double holds all 64 bits verbatim on
     * every target, so this is bit-exact everywhere and needs no raw counterpart
     * (CORELIB_PLAN §6.5).
     *
     * @param id field id
     * @param value value
     */
    public fun writeFp64(id: Int, value: Double) {
        val bits = value.toRawBits()
        beginField(id)
        val p = pos
        // Header (<=5) + the constant one-byte fixlen_word + eight payload bytes.
        if (end - p >= FIELD_ROOM) {
            val b = buffer
            val header = (id.toLong() shl 3) or T_FIXLEN.toLong()
            if ((header and 0x7FL.inv()) == 0L) {
                // A one-byte header and the constant fixlen_word go out together,
                // then the payload: two stores for the commonest fp64 field.
                leSetShort(b, p, (header or (((8L shl 3) or F_FP64.toLong()) shl 8)).toInt())
                leSetLong(b, p + 2, bits)
                pos = p + 10
                return
            }
            val q = putVarint(b, p, header)
            b[q] = ((8 shl 3) or F_FP64).toByte()
            leSetLong(b, q + 1, bits)
            pos = q + 9
            return
        }
        writeVarint((id.toLong() shl 3) or T_FIXLEN.toLong())
        writeVarint((8L shl 3) or F_FP64.toLong())
        putLe64(bits)
    }

    /**
     * Write a string field (raw UTF-8 bytes, no NUL on the wire).
     *
     * Encoding is **always strict** UTF-8 (MESSAGE_SPEC §8): a [String] is a
     * Unicode string type, so the only value it can hold that is not representable
     * as well-formed UTF-8 is an unpaired UTF-16 surrogate. Such a string is
     * rejected with [SofabError.ARGUMENT] *before* any bytes are written, rather
     * than being silently lossily replaced — there is no strict mode to toggle,
     * because for a Unicode string type the check is unconditional (CORELIB_PLAN
     * §6.4).
     *
     * The text is encoded straight into the output buffer rather than through an
     * intermediate byte array: one pass measures the UTF-8 length for the fixlen
     * header (and validates), the second emits the bytes. An all-ASCII string —
     * identifiers, codes, most of what a schema carries — gets the second pass
     * nearly for free, because the measuring pass has to find the first non-ASCII
     * char anyway and, where there is none, the payload is a bulk copy of the
     * string's own storage.
     *
     * @param id field id
     * @param text string value
     * @throws SofabException [SofabError.ARGUMENT] if [text] contains an unpaired
     *   surrogate (invalid UTF-8)
     */
    public fun writeString(id: Int, text: String) {
        val len = text.length
        val ascii = asciiPrefix(text)
        if (ascii == len) {
            writeIdTypeValue(id, T_FIXLEN, (len.toLong() shl 3) or F_STRING.toLong())
            // Room-gated because the bulk copy cannot flush mid-string, and the
            // room is read AFTER the header: writing it runs beginField, which may
            // commit held-back sequence headers and flush, so the position before
            // it is not the position the payload starts at. Length-gated because
            // the copy is a call with its own bounds checks; below ASCII_BULK_MIN
            // the char loop is already fewer instructions than setting one up.
            if (len >= ASCII_BULK_MIN && end - pos >= len && copyAsciiInto(text, len, buffer, pos)) {
                pos += len
            } else {
                writeUtf8(text, len)
            }
            return
        }
        val n = utf8Length(text, ascii)
        writeIdTypeValue(id, T_FIXLEN, (n.toLong() shl 3) or F_STRING.toLong())
        writeUtf8(text, n)
    }

    /**
     * Write a binary blob field.
     *
     * @param id field id
     * @param data blob bytes
     */
    public fun writeBlob(id: Int, data: ByteArray) {
        writeFixlen(id, data, 0, data.size, FixlenType.BLOB)
    }

    /**
     * Write a slice of a byte array as a binary blob field.
     *
     * @param id field id
     * @param data backing array
     * @param from start offset
     * @param length number of bytes
     */
    public fun writeBlob(id: Int, data: ByteArray, from: Int, length: Int) {
        writeFixlen(id, data, from, length, FixlenType.BLOB)
    }

    /**
     * Emit [s] as strict, well-formed UTF-8. [n] is the exact byte length already
     * measured by [utf8Length], which also rejected any unpaired surrogate, so this
     * pass never encounters one; the surrogate branch throws defensively rather
     * than emitting a replacement byte. When the buffer has room for [n] bytes they
     * are written with a local cursor and no per-byte bounds/flush check.
     */
    private fun writeUtf8(s: String, n: Int) {
        val len = s.length
        var p = pos
        if (end - p >= n) {
            val b = buffer
            var i = 0
            // ASCII run: the common case is one byte per char.
            while (i < len) {
                val c = s[i].code
                if (c >= 0x80) break
                b[p++] = c.toByte()
                i++
            }
            while (i < len) {
                val c = s[i].code
                when {
                    c < 0x80 -> b[p++] = c.toByte()
                    c < 0x800 -> {
                        b[p++] = (0xC0 or (c shr 6)).toByte()
                        b[p++] = (0x80 or (c and 0x3F)).toByte()
                    }
                    c in 0xD800..0xDBFF && i + 1 < len && s[i + 1].code in 0xDC00..0xDFFF -> {
                        i++
                        val cp = 0x10000 + ((c - 0xD800) shl 10) + (s[i].code - 0xDC00)
                        b[p++] = (0xF0 or (cp shr 18)).toByte()
                        b[p++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                        b[p++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        b[p++] = (0x80 or (cp and 0x3F)).toByte()
                    }
                    c in 0xD800..0xDFFF ->
                        throw SofabException(SofabError.ARGUMENT, "invalid UTF-8: unpaired surrogate")
                    else -> {
                        b[p++] = (0xE0 or (c shr 12)).toByte()
                        b[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                        b[p++] = (0x80 or (c and 0x3F)).toByte()
                    }
                }
                i++
            }
            pos = p
            return
        }
        writeUtf8Slow(s)
    }

    /** Buffer-spanning UTF-8 write: per-byte pushes that can flush mid-string. */
    private fun writeUtf8Slow(s: String) {
        val len = s.length
        var i = 0
        while (i < len) {
            val c = s[i].code
            when {
                c < 0x80 -> pushByte(c)
                c < 0x800 -> {
                    pushByte(0xC0 or (c shr 6))
                    pushByte(0x80 or (c and 0x3F))
                }
                c in 0xD800..0xDBFF && i + 1 < len && s[i + 1].code in 0xDC00..0xDFFF -> {
                    i++
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (s[i].code - 0xDC00)
                    pushByte(0xF0 or (cp shr 18))
                    pushByte(0x80 or ((cp shr 12) and 0x3F))
                    pushByte(0x80 or ((cp shr 6) and 0x3F))
                    pushByte(0x80 or (cp and 0x3F))
                }
                c in 0xD800..0xDFFF ->
                    throw SofabException(SofabError.ARGUMENT, "invalid UTF-8: unpaired surrogate")
                else -> {
                    pushByte(0xE0 or (c shr 12))
                    pushByte(0x80 or ((c shr 6) and 0x3F))
                    pushByte(0x80 or (c and 0x3F))
                }
            }
            i++
        }
    }

    // --- array writers ------------------------------------------------------

    /**
     * Write an array field header (id header then element count). A zero count is
     * valid (§4.7) and yields exactly `[ header ][ count = 0 ]`. The count is a
     * Kotlin array's `size` at every call site, so it needs no range test: it is
     * non-negative by construction and `ARRAY_MAX` is `INT32_MAX`.
     */
    private fun writeArrayHeader(id: Int, wireType: Int, count: Int) {
        writeIdTypeValue(id, wireType, count.toLong())
    }

    /**
     * Whether [count] elements of at most [maxBytes] varint bytes each are certain
     * to fit in what is left of the buffer, so the element loop can run with no
     * per-element room test and no flush check.
     *
     * The bound is not `count * maxBytes`: [putVarint] assembles a varint with an
     * eight-byte store and so needs [VARINT_ROOM] bytes of room at *every* element,
     * the last one included. What must fit is therefore the first `count - 1`
     * elements at their worst case plus a full varint's room for the last.
     */
    private fun bulkRoom(count: Int, maxBytes: Int): Boolean =
        count > 0 && (end.toLong() - pos) >= (count - 1).toLong() * maxBytes + VARINT_ROOM

    /**
     * Write an array of unsigned 8-bit integers (each element zero-extended).
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayUnsigned(id: Int, data: ByteArray) {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.size)
        if (bulkRoom(data.size, W_BYTE)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, elem.toLong() and 0xFF)
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(elem.toLong() and 0xFF)
        }
    }

    /**
     * Write an array of unsigned 16-bit integers (each element zero-extended).
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayUnsigned(id: Int, data: ShortArray) {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.size)
        if (bulkRoom(data.size, W_SHORT)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, elem.toLong() and 0xFFFF)
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(elem.toLong() and 0xFFFF)
        }
    }

    /**
     * Write an array of unsigned 32-bit integers (each element zero-extended).
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayUnsigned(id: Int, data: IntArray) {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.size)
        if (bulkRoom(data.size, W_INT)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, elem.toLong() and 0xFFFF_FFFFL)
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(elem.toLong() and 0xFFFF_FFFFL)
        }
    }

    /**
     * Write an array of unsigned 64-bit integers (each [Long] treated as an
     * unsigned value).
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayUnsigned(id: Int, data: LongArray) {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.size)
        if (bulkRoom(data.size, W_LONG)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, elem)
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(elem)
        }
    }

    /**
     * Write an array of signed 8-bit integers.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArraySigned(id: Int, data: ByteArray) {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.size)
        if (bulkRoom(data.size, W_BYTE)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, zigzagEncode(elem.toLong()))
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(zigzagEncode(elem.toLong()))
        }
    }

    /**
     * Write an array of signed 16-bit integers.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArraySigned(id: Int, data: ShortArray) {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.size)
        if (bulkRoom(data.size, W_SHORT)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, zigzagEncode(elem.toLong()))
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(zigzagEncode(elem.toLong()))
        }
    }

    /**
     * Write an array of signed 32-bit integers.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArraySigned(id: Int, data: IntArray) {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.size)
        if (bulkRoom(data.size, W_INT)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, zigzagEncode(elem.toLong()))
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(zigzagEncode(elem.toLong()))
        }
    }

    /**
     * Write an array of signed 64-bit integers.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArraySigned(id: Int, data: LongArray) {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.size)
        if (bulkRoom(data.size, W_LONG)) {
            val b = buffer
            var p = pos
            for (elem in data) {
                p = putVarint(b, p, zigzagEncode(elem))
            }
            pos = p
            return
        }
        for (elem in data) {
            writeVarint(zigzagEncode(elem))
        }
    }

    /**
     * Write an array of 32-bit floats.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayFp32(id: Int, data: FloatArray) {
        writeArrayHeader(id, T_FIXLENARRAY, data.size)
        // §4.8: a fixlen array always carries its fixlen_word (the shared element
        // subtype/width), even when empty, so an empty fp32 array is
        // distinguishable on the wire from an empty fp64 array. The payload loop
        // simply runs zero times when the array is empty.
        writeVarint((4L shl 3) or F_FP32.toLong())
        var p = pos
        if (end.toLong() - p >= data.size.toLong() * 4) {
            // The whole payload fits: no element can overflow, so run a tight loop
            // with no per-element room check or flush test.
            val b = buffer
            for (v in data) {
                leSetInt(b, p, v.toRawBits())
                p += 4
            }
            pos = p
            return
        }
        for (v in data) {
            putLe32(v.toRawBits())
        }
    }

    /**
     * Write an array of 32-bit floats from their **raw wire bits**, the bit-exact
     * `fp32` array path of CORELIB_PLAN §6.5 (see [writeFp32Bits]).
     *
     * @param id field id
     * @param bits one IEEE-754 binary32 bit pattern per element
     */
    public fun writeArrayFp32Bits(id: Int, bits: IntArray) {
        writeArrayHeader(id, T_FIXLENARRAY, bits.size)
        writeVarint((4L shl 3) or F_FP32.toLong())
        var p = pos
        if (end.toLong() - p >= bits.size.toLong() * 4) {
            val b = buffer
            for (v in bits) {
                leSetInt(b, p, v)
                p += 4
            }
            pos = p
            return
        }
        for (v in bits) {
            putLe32(v)
        }
    }

    /**
     * Write an array of 64-bit floats.
     *
     * @param id field id
     * @param data elements
     */
    public fun writeArrayFp64(id: Int, data: DoubleArray) {
        writeArrayHeader(id, T_FIXLENARRAY, data.size)
        // §4.8: the fixlen_word is present even when the array is empty, so an
        // empty fp64 array stays distinguishable from an empty fp32 one.
        writeVarint((8L shl 3) or F_FP64.toLong())
        var p = pos
        if (end.toLong() - p >= data.size.toLong() * 8) {
            val b = buffer
            for (v in data) {
                leSetLong(b, p, v.toRawBits())
                p += 8
            }
            pos = p
            return
        }
        for (v in data) {
            putLe64(v.toRawBits())
        }
    }

    // --- sequence writers ---------------------------------------------------

    /**
     * Open a nested sequence with the given field [id], whose header is **held
     * back** until the sequence turns out to have content. Fields written until the
     * matching close belong to the sequence and form a fresh id scope.
     *
     * MESSAGE_SPEC §2 omits a sequence-typed field whose value equals its declared
     * default, and "not one child was written" is exactly that condition —
     * evaluated per child field, recursively, for free, because the message layer
     * already omits every child equal to its default. A sequence closed with
     * nothing in it therefore emits **nothing** instead of a two-byte empty frame,
     * and an all-default message becomes the empty byte string.
     *
     * The predicate is never a byte image of the object, so in-memory layout cannot
     * influence it, and a non-zero nested default is handled by the caller's
     * ordinary per-field test.
     *
     * Held-back ids are encoder *state*, not buffer content, so a flush can never
     * split a pending run: an output buffer far smaller than the message produces
     * exactly the one-shot bytes.
     *
     * The hold-back reaches the full [Sofab.MAX_DEPTH]: the pending run grows on
     * demand, so this encoder is canonical at every legal nesting depth. Bounding
     * the run and framing eagerly beyond the bound is an allowance for heap-free
     * profiles only (CORELIB_PLAN §6), and no Kotlin target is one.
     *
     * This is the only way to open a sequence. How it closes decides whether a
     * contentless one survives: [writeSequenceEnd] drops it, [writeSequenceEndKeep]
     * forces the frame out.
     *
     * @param id field id of the sequence
     * @throws SofabException [SofabError.ARGUMENT] if opening this sequence would
     *   exceed [Sofab.MAX_DEPTH] nesting levels, or if [id] is out of range
     */
    public fun writeSequenceBeginLazy(id: Int) {
        if (depth >= Sofab.MAX_DEPTH) {
            throw SofabException(SofabError.ARGUMENT, "sequence nesting exceeds MAX_DEPTH")
        }
        // As in beginField: ID_MAX is INT32_MAX, so the sign test is the whole
        // range check. This opener does not route its header through beginField —
        // it holds it back — so it carries its own.
        if (id < 0) {
            throw SofabException(SofabError.ARGUMENT, "id $id")
        }
        if (nPending == 0) {
            // Depth-one hold-back: a scalar, so an encoder whose sequences never
            // nest never allocates the overflow array at all.
            pending0 = id
            nPending = 1
        } else {
            val slot = nPending - 1 // pending[] carries entries two and beyond
            var p = pending
            if (p == null) {
                p = IntArray(PENDING_INITIAL)
                pending = p
            } else if (slot == p.size) {
                // Grow rather than fall back to eager framing. nPending <= depth <
                // MAX_DEPTH, so the run can never need more than MAX_DEPTH slots.
                p = p.copyOf(if (p.size * 2 < Sofab.MAX_DEPTH) p.size * 2 else Sofab.MAX_DEPTH)
                pending = p
            }
            p[slot] = id
            nPending++
        }
        depth++
    }

    /**
     * Close the most recently opened nested sequence, letting it **vanish** if it
     * received no content.
     *
     * Use it wherever absence encodes the same value as an empty frame: a
     * `struct`/`union` field, and an array field whose declared `default` is the
     * empty collection (MESSAGE_SPEC §2). Where the frame must be visible, close
     * with [writeSequenceEndKeep] instead.
     *
     * An end with no matching begin is not rejected here: the encoder writes what
     * it is told and the resulting bytes are then malformed, which is the decoder's
     * verdict to make. Every other port behaves this way; the depth counter simply
     * stops at zero so the `MAX_DEPTH` check on begin cannot be fooled by an
     * underflow.
     */
    public fun writeSequenceEnd() {
        if (nPending != 0) {
            // The innermost open sequence is the last held-back one: drop it,
            // header and end marker both.
            nPending--
            if (depth > 0) {
                depth--
            }
            return
        }
        writeIdType(0, T_SEQUENCE_END)
        if (depth > 0) {
            depth--
        }
    }

    /**
     * Close the most recently opened nested sequence, **keeping** its frame even
     * when it received no content.
     *
     * Behaves like a write: it first emits any held-back headers — this frame's and
     * every enclosing one's — and then the end marker, so an empty sequence reaches
     * the wire as `begin` + `end`.
     *
     * Required wherever the frame carries information beyond its contents:
     * - a **wrapper-array element** (`struct`/`union`/nested row): element presence
     *   is what carries a dynamic array's length — *highest present id + 1*
     *   (MESSAGE_SPEC §5.1) — so dropping an all-default element would change the
     *   decoded length, not just the bytes;
     * - an array field already known to **differ from a non-empty declared
     *   `default`**: absence would reconstruct that default, so the empty frame is
     *   the only encoding of "explicitly empty" (§2, §3).
     *
     * The two failure directions are not symmetric, which is why this is the safe
     * choice when in doubt: using it where [writeSequenceEnd] would do costs one
     * non-canonical empty frame that a decoder normalizes away, while the reverse
     * silently changes an array's length.
     */
    public fun writeSequenceEndKeep() {
        if (nPending != 0) {
            commitPending()
        }
        writeIdType(0, T_SEQUENCE_END)
        if (depth > 0) {
            depth--
        }
    }

    private companion object {

        /**
         * Initial capacity of the held-back-header run. It is a starting size, not a
         * limit: the run grows on demand and can reach [Sofab.MAX_DEPTH], which is
         * what makes this encoder canonical at every legal nesting depth.
         */
        const val PENDING_INITIAL = 8

        /**
         * Bytes of room that let [putVarint] assemble a varint in a single
         * eight-byte store: ten covers the longest varint, and the store itself
         * always touches eight bytes from the write position.
         */
        const val VARINT_ROOM = 10

        /**
         * Room needed to write a field header and a scalar value with one cursor and
         * one bounds test: a header is at most five bytes (`id shl 3` spans 34 bits)
         * and a value at most ten.
         */
        const val FIELD_ROOM = 15

        /**
         * Widest varint an element of each integer array type can encode to: a
         * zero-extended byte spans two bytes, a short three, an int five, and a
         * `Long` the full ten — and ZigZag never widens past its own type's bound,
         * since it is a bijection on the same bit width.
         */
        const val W_BYTE = 2
        const val W_SHORT = 3
        const val W_INT = 5
        const val W_LONG = 10

        /**
         * Shortest string the ASCII bulk copy in [writeString] is used for. Below it
         * the char-at-a-time loop is cheaper than the copy's call and bounds checks;
         * the exact crossover is not sharp, and this sits comfortably past it.
         */
        const val ASCII_BULK_MIN = 16

        /**
         * Validate a buffer where it is handed over — at construction and at every
         * mid-stream [bufferSet] — so a buffer that cannot be written into is refused
         * there rather than partway through a message (CORELIB_PLAN §5.1).
         *
         * [Sofab.MIN_OUTPUT_BUFFER] applies only when a `sink` is present. Without
         * one no flush can occur, so §5.1 imposes no minimum: the buffer either holds
         * the message or reports [SofabError.BUFFER_FULL], and a caller sizing from a
         * generated `MAX_SIZE` keeps an exact fit.
         */
        fun checkHandover(buffer: ByteArray, offset: Int, sink: FlushSink?) {
            require(buffer.isNotEmpty()) { "buffer must be non-empty" }
            require(offset in 0..buffer.size) { "offset out of range" }
            require(sink == null || buffer.size - offset >= Sofab.MIN_OUTPUT_BUFFER) {
                "streaming buffer leaves ${buffer.size - offset} usable bytes, " +
                    "minimum is ${Sofab.MIN_OUTPUT_BUFFER}"
            }
        }

        /** Index of the first char at or above `U+0080`, or the length if none. */
        fun asciiPrefix(s: String): Int {
            val len = s.length
            var i = 0
            while (i < len && s[i].code < 0x80) {
                i++
            }
            return i
        }

        /**
         * Exact UTF-8 byte length, matching [writeUtf8]. Doubles as the strict UTF-8
         * validation pass: [String] is a Unicode string type, so the only way it can
         * fail to encode to well-formed UTF-8 is an unpaired UTF-16 surrogate
         * (MESSAGE_SPEC §8). Running this before [writeString] emits any bytes means
         * an invalid string is rejected without producing partial wire output.
         *
         * [from] is the caller's already-scanned ASCII prefix length; those chars are
         * one byte each and are not re-examined.
         */
        fun utf8Length(s: String, from: Int): Int {
            val len = s.length
            var i = from
            var bytes = i
            while (i < len) {
                val c = s[i].code
                when {
                    c < 0x80 -> bytes += 1
                    c < 0x800 -> bytes += 2
                    c in 0xD800..0xDBFF && i + 1 < len && s[i + 1].code in 0xDC00..0xDFFF -> {
                        bytes += 4
                        i++
                    }
                    c in 0xD800..0xDFFF -> throw SofabException(
                        SofabError.ARGUMENT,
                        "invalid UTF-8: unpaired surrogate at index $i",
                    )
                    else -> bytes += 3
                }
                i++
            }
            return bytes
        }
    }
}

/**
 * Emit [v] as a base-128 varint into [b] at [p], returning the next write
 * position. The caller guarantees at least ten bytes of room (`b.size - p >= 10`).
 *
 * Single-byte values — field headers, small scalars, most array elements — take a
 * direct store. Anything longer is assembled whole in a 64-bit register (seven
 * payload bits per lane, continuation bits set, then cleared on the final lane)
 * and written with **one** eight-byte store, rather than a per-byte loop that pays
 * a test, a shift and a bounds-checked store for every byte. The ninth and tenth
 * bytes of a maximal varint follow in one two-byte store.
 *
 * The eight-byte store always touches eight bytes even when the varint is shorter,
 * so up to seven scratch bytes can land in the buffer *past* the new write
 * position. They are never part of the message: the caller advances by the
 * varint's true length, the next write overwrites them, and only
 * `[0, bytesUsed)` is ever handed to a sink. The ten-byte room requirement keeps
 * the store inside the buffer.
 */
internal fun putVarint(b: ByteArray, p: Int, v: Long): Int {
    if ((v and 0x7FL.inv()) == 0L) {
        b[p] = v.toByte()
        return p + 1
    }
    // ceil(bits / 7) where bits = 64 - countLeadingZeroBits(v); 2..10 here.
    val n = (70 - v.countLeadingZeroBits()) / 7
    val w = scatter7(v) or CONT_BITS
    if (n <= 8) {
        // Clear the continuation bit of the last lane; higher lanes are scratch.
        leSetLong(b, p, w and (0x80L shl ((n - 1) shl 3)).inv())
        return p + n
    }
    leSetLong(b, p, w)
    val hi = v ushr 56
    // For n == 10 bit 63 of v is bit 7 of hi, which is exactly the continuation
    // flag the ninth byte needs; for n == 9 it is clear, which is what it needs.
    // Both trailing bytes go out in one two-byte store: the tenth is scratch when
    // n == 9, and the ten-byte room requirement keeps it inside the buffer either
    // way — one bounds check and one branch fewer per maximal varint.
    leSetShort(b, p + 8, ((hi and 0xFF) or ((hi ushr 7) shl 8)).toInt())
    return p + n
}
