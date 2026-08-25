/*
 * SofaBuffers Kotlin Multiplatform — streaming input decoder.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

// --- decoder states ---------------------------------------------------------
//
// Plain Int constants rather than an enum: the feed loop tests the state once per
// field, and an enum `when` costs an ordinal load and a table lookup per dispatch.

/** At a clean field boundary — no field header or value partially read. */
private const val S_IDLE = 0

/** A field-header varint is partially accumulated. */
private const val S_HEADER = 1
private const val S_VARINT_UNSIGNED = 2
private const val S_VARINT_SIGNED = 3
private const val S_FIXLEN_LEN = 4
private const val S_FIXLEN_VAL = 5
private const val S_FIXLEN_RAW = 6
private const val S_ARRAY_COUNT = 7

/** [IStream.bulkW] values: no offer taken, then the four destination widths. */
private const val W_NONE = 0
private const val W_BYTE8 = 1
private const val W_SHORT16 = 2
private const val W_INT32 = 3
private const val W_LONG64 = 4

/** Shared zero-length payload handed to the visitor for an empty string/blob. */
private val EMPTY = ByteArray(0)

/**
 * Stand-ins for the three bulk destinations an array is *not* filling.
 *
 * The element loops hoist all four destinations into locals before they start, and
 * exactly one of them is live — the one [IStream.bulkW] names. Substituting an
 * empty array for the others keeps every local non-null, so the loop carries one
 * bounds check per element instead of a null check as well; the empty arrays are
 * never indexed, because the arm that would index them is the arm that is not
 * taken.
 */
private val NO_BYTES = ByteArray(0)
private val NO_SHORTS = ShortArray(0)
private val NO_INTS = IntArray(0)
private val NO_LONGS = LongArray(0)

/**
 * Streaming SofaBuffers decoder.
 *
 * Feed it arbitrary chunks with [feed]; it parses field headers and pushes decoded
 * fields to your [Visitor]. Because all parse state lives inside the decoder, a
 * message may be split across any number of `feed` calls at any byte boundary —
 * true streaming on the input side.
 *
 * **Two decode paths.** When a clean field boundary and a contiguous run of bytes
 * are both in hand, the decoder advances a cursor straight over the buffer,
 * reading whole field headers, scalars and array elements with no per-byte state
 * dispatch. The moment a field — or array element — would run past the end of the
 * supplied bytes, a resumable byte-at-a-time state machine takes over, suspends,
 * and resumes on the next `feed`. Only that one construct pays for the split: the
 * moment it completes, the rest of the chunk goes back to the fast path — within
 * an array as much as between fields, so a boundary inside a long array costs one
 * element and not its remainder. The two paths are byte-for-byte equivalent.
 *
 * Unlike the C decoder there is no per-field "bind a destination" step and no
 * explicit skip bookkeeping: a [Visitor] simply ignores fields it does not care
 * about. Scalars and floats are delivered whole; string / blob payloads are
 * delivered in chunks (so they may exceed RAM); array elements are announced with
 * [Visitor.arrayBegin] and then delivered through the scalar / float callbacks.
 * Every fixlen field is announced with [Visitor.fixlenBegin] at its length word,
 * before any payload byte, so a length bound can be enforced there rather than
 * after the payload assembles.
 *
 * **Chunk lifetime** (CORELIB_PLAN §6): a fed chunk is borrowed only for the
 * duration of the `feed` call. The decoder retains no view into it — a
 * string/blob window handed to the visitor is valid for that callback only — so
 * the caller may reuse, overwrite or free the chunk the moment `feed` returns.
 *
 * **Three-valued outcome (CORELIB_PLAN §5.2).** Malformed bytes throw a
 * [SofabException] with [SofabError.INVALID_MSG] from `feed`. Running out of bytes
 * mid-field is *not* an error: `feed` suspends and returns normally, and a
 * subsequent `feed` resumes it. To tell a message that is *complete* from one that
 * was *truncated*, read [status] after the final `feed`: it returns
 * [DecodeStatus.COMPLETE] at a clean field boundary or [DecodeStatus.INCOMPLETE]
 * if the last bytes ended inside a field or with an open (unclosed) sequence.
 * `status` is a pure, non-throwing accessor — there is no required finish/finalize
 * step; the caller owns end-of-input.
 *
 * **`INVALID` is terminal.** Malformed bytes are malformed regardless of what
 * follows, so a rejection sticks: [status] answers [DecodeStatus.INVALID] from
 * then on and every further `feed` throws without decoding, so a caller that
 * catches the exception and keeps feeding cannot resume mid-stream on a message
 * the decoder has already proven broken, nor read a `COMPLETE` verdict for it.
 * [reset] — resynchronising onto the next message — is what clears it.
 *
 * This class is not thread-safe; decode one message from one thread.
 *
 * ```
 * class Sink : Visitor {
 *     var a = 0L
 *     var b = 0L
 *     override fun unsigned(id: Int, value: Long) { if (id == 1) a = value }
 *     override fun signed(id: Int, value: Long) { if (id == 2) b = value }
 * }
 * val sink = Sink()
 * val input = IStream()
 * input.feed(buf, sink)
 * if (input.status == DecodeStatus.INCOMPLETE) {
 *     // buf ended mid-message; wait for more bytes (or treat as truncation).
 * }
 * ```
 */
public class IStream {

    // incremental varint accumulator
    private var varintValue = 0L
    private var varintShift = 0
    private var varintOut = 0L

    private var state = S_IDLE
    private var id = 0

    // array context
    private var arrayKind = ArrayKind.UNSIGNED
    private var arrayRemaining = 0
    private var inArray = false

    /**
     * Whether the array being read is a fixlen (fp32/fp64) array. Its element kind
     * is not known at the count word — it is carried by the `fixlen_word` that
     * follows — so [arrayKind] is only settled, and [Visitor.arrayBegin] only
     * fired, once that word has been read (CORELIB_PLAN §4.8). Integer arrays
     * settle both at the count word.
     */
    private var fixlenArray = false

    // fixlen context
    private var fixlenSubtype = F_FP32
    private var fixlenTotal = 0
    private var fixlenRemaining = 0

    /**
     * Landing zone for a float payload split across feeds — eight bytes, the
     * widest fixlen scalar the format carries.
     *
     * **Sized at construction** (CORELIB_PLAN §6.6.2): its size comes from this
     * document, never from the wire, so it is bounded working state and belongs in
     * the constructor. Allocating it on the first straddling float would put an
     * allocation on a `feed` path, which §6.6 forbids.
     */
    private val acc = ByteArray(8)
    private var accLen = 0

    /**
     * Destination of an accepted [Visitor.arrayBulk] offer. [bulkW] says which of
     * the four is live — [W_NONE] when the elements go through the per-element
     * callbacks instead — and it is resolved ONCE per array, so the element loops
     * branch on a hoisted local rather than a type test. Live only between the
     * offer and [Visitor.arrayBulkEnd], so the fill also survives a feed boundary
     * inside the array: both the bulk element loops and the resumable machine write
     * through it, and [bulkAt] counts what has been written so far.
     */
    private var bulkB: ByteArray? = null
    private var bulkS: ShortArray? = null
    private var bulkI: IntArray? = null
    private var bulkL: LongArray? = null
    private var bulkW = W_NONE
    private var bulkAt = 0

    /** Sequence nesting depth (for balanced start/end validation). */
    private var depth = 0

    /**
     * Latched [DecodeStatus.INVALID]: the bytes fed so far were determined
     * malformed, which CORELIB_PLAN §5.2 makes **terminal** — no continuation can
     * make them valid. Set from [feed]'s handler on the way out, so every rejection
     * latches wherever in this class it is raised (and a schema-bound rejection
     * raised by the [Visitor] does too).
     */
    private var invalid = false

    /**
     * Latched receiver-limit stop: the [Visitor] rejected a schema-**unbounded**
     * count or length against a configured cap (CORELIB_PLAN §6.2.1). §6.3 calls
     * that "a **terminal**, receiver-local **policy** rejection" — the decode stops
     * where it stopped and this decoder will not finish the message — so it latches
     * like [invalid] does, and for the same reason: the parse position it held is
     * meaningless afterwards.
     *
     * It is a **separate** latch and never sets [invalid]: §6.2.1 forbids folding a
     * policy rejection into the `INVALID` outcome, because the same bytes decode
     * under a looser limit. The category reaches the caller on the error channel,
     * as [SofabError.LIMIT_EXCEEDED], which is one of the two surfaces §6.3 leaves
     * open; what [status] must not do is answer [DecodeStatus.COMPLETE] for a
     * message this decoder abandoned.
     */
    private var limitStopped = false

    /**
     * Position just past the word most recently read by [readWord], or `-1` when
     * that word ran past the end of the supplied bytes.
     */
    private var scratchPos = 0

    /**
     * Bytes of the current message handed to the resumable byte-at-a-time machine
     * ([step]). The decoder never reads it: the two decode paths are byte-for-byte
     * equivalent, so this counter is the only thing that tells them apart, and the
     * streaming tests read it to prove that a chunk boundary inside an array costs
     * the machine the one straddling element rather than the array's whole
     * remainder. Internal, not public API.
     */
    internal var machineBytes: Long = 0

    /**
     * Return this decoder to its just-constructed state so it can decode another
     * message. Equivalent to allocating a new [IStream], without the allocation: a
     * caller decoding many messages in a row (a server loop, a generated `decode`
     * helper) can hold one instance and reset it per message.
     *
     * Discards any partially decoded field and any open sequence nesting, so it
     * must not be called mid-message unless that is the intent — after an
     * [SofabError.INVALID_MSG] it is exactly how a stream decoder resynchronises
     * onto the next message, and the *only* way: that outcome is terminal
     * (CORELIB_PLAN §5.2), so until this call [status] keeps answering
     * [DecodeStatus.INVALID] and [feed] keeps refusing bytes. A
     * [SofabError.LIMIT_EXCEEDED] stop (§6.2.1, §6.3) clears here too, and it is
     * likewise the only way to clear it.
     *
     * [acc] is construction-sized state and is not reallocated: only its first
     * [accLen] bytes are ever read, and that counter is zeroed here.
     */
    public fun reset() {
        varintValue = 0
        varintShift = 0
        varintOut = 0
        state = S_IDLE
        id = 0
        arrayKind = ArrayKind.UNSIGNED
        arrayRemaining = 0
        inArray = false
        fixlenArray = false
        fixlenSubtype = F_FP32
        fixlenTotal = 0
        fixlenRemaining = 0
        accLen = 0
        bulkB = null
        bulkS = null
        bulkI = null
        bulkL = null
        bulkW = W_NONE
        bulkAt = 0
        depth = 0
        invalid = false
        limitStopped = false
        machineBytes = 0
        // Pure scratch — every path writes it before it reads it — but cleared
        // anyway so "reset restores every declared field" needs no exception.
        scratchPos = 0
    }

    /**
     * Whether the bytes fed so far end exactly at a field boundary. Read after the
     * final [feed]: [DecodeStatus.COMPLETE] when the decoder is at a clean field
     * boundary with no open sequence, [DecodeStatus.INCOMPLETE] when the last bytes
     * ended inside a field — a partial varint (field header or value), a
     * fixlen/array payload shorter than declared, an array with elements still
     * pending — or with an open (unclosed) nested sequence.
     *
     * A *malformed* message answers [DecodeStatus.INVALID], which outranks both
     * other outcomes and is **terminal** (CORELIB_PLAN §5.2): [feed] threw when it
     * read the malformed construct and the verdict is latched from there on, so no
     * continuation — and in particular no later `feed` that would have ended at a
     * clean field boundary — can turn it back into `COMPLETE` or `INCOMPLETE`.
     * [reset] clears it, because that starts a new message.
     *
     * Per the finish-less contract this is a pure accessor: it never throws, never
     * mutates decoder state, and never promotes an incomplete decode to an error.
     */
    public val status: DecodeStatus
        get() {
            // INVALID first: it is a property of bytes already consumed and no later
            // state can revise it (§5.2, "INVALID wins over INCOMPLETE" and terminal).
            if (invalid) {
                return DecodeStatus.INVALID
            }
            // A receiver-limit stop is terminal too, but the bytes are well-formed:
            // reporting INVALID would fold a policy rejection into the wire verdict,
            // which §6.2.1 forbids. INCOMPLETE is what actually happened — the
            // decoder stopped part-way through a message and will not finish it —
            // and the LIMIT_EXCEEDED category is on the error channel (§6.3). The
            // test guards a rejection thrown from a callback at a *field boundary*,
            // where state and depth are untouched and COMPLETE would otherwise be
            // reported for a message whose payload was never consumed.
            if (limitStopped) {
                return DecodeStatus.INCOMPLETE
            }
            // COMPLETE only at a true field boundary: no partial field header varint
            // (that is its own state, S_HEADER), no in-progress value/payload/array
            // element (S_IDLE covers the resumable machine and mid-array between
            // elements), and every opened sequence closed.
            return if (state == S_IDLE && depth == 0) DecodeStatus.COMPLETE else DecodeStatus.INCOMPLETE
        }

    /**
     * Feed a whole chunk of encoded bytes, pushing decoded fields to [visitor].
     *
     * @param data encoded bytes
     * @param visitor sink for decoded fields
     * @throws SofabException [SofabError.INVALID_MSG] on malformed input
     */
    public fun feed(data: ByteArray, visitor: Visitor) {
        feed(data, 0, data.size, visitor)
    }

    /**
     * Feed a slice of encoded bytes, pushing decoded fields to [visitor]. Decoding
     * can continue across many `feed` calls; the decoder keeps all state
     * internally.
     *
     * The [SofabError.INVALID_MSG] outcome is **terminal** (CORELIB_PLAN §5.2):
     * once any fed bytes have been rejected as malformed, this function decodes
     * nothing further and rethrows for every subsequent call, and [status] keeps
     * reporting [DecodeStatus.INVALID], until [reset] begins a new message. Running
     * out of bytes mid-field is *not* that: it suspends and resumes on the next
     * call.
     *
     * A [SofabError.LIMIT_EXCEEDED] rejection — a receiver cap the [Visitor]
     * applied to a schema-unbounded count or length (§6.2.1) — is terminal in the
     * same way (§6.3) and latches separately: further feeds are refused with that
     * same code, and [status] reports [DecodeStatus.INCOMPLETE] rather than
     * [DecodeStatus.COMPLETE] for a message this decoder abandoned. It is never
     * folded into [DecodeStatus.INVALID], because the bytes are well-formed.
     *
     * @param data backing array
     * @param off start offset
     * @param len number of bytes to consume
     * @param visitor sink for decoded fields
     * @throws SofabException [SofabError.INVALID_MSG] on malformed input, or on any
     *   call after malformed input was already rejected; [SofabError.LIMIT_EXCEEDED]
     *   on any call after a receiver limit stopped the decode
     */
    public fun feed(data: ByteArray, off: Int, len: Int, visitor: Visitor) {
        if (invalid || limitStopped) {
            throwLatched()
        }
        try {
            decode(data, off, len, visitor)
        } catch (e: SofabException) {
            // Latch on the way out rather than at each of the two dozen throw sites:
            // a rejection raised anywhere in this class — fast path, resumable state
            // machine, or a schema-bound rejection thrown by generated code from
            // inside a Visitor callback — is terminal, and this is the one place all
            // of them pass through. LIMIT_EXCEEDED is terminal too (§6.3) but is a
            // policy rejection of well-formed bytes, so it latches *separately*:
            // §6.2.1 forbids folding it into the INVALID outcome.
            when (e.error) {
                SofabError.INVALID_MSG -> invalid = true
                SofabError.LIMIT_EXCEEDED -> limitStopped = true
                else -> Unit
            }
            throw e
        }
    }

    /**
     * Rethrow the latched terminal verdict. Out of line, and out of [feed]'s body,
     * for the same reason [decode] is: [feed] is the hottest entry point in the
     * class, and message construction inline in it is bytecode the JIT has to carry
     * through every inlining decision it makes about the caller.
     */
    private fun throwLatched(): Nothing =
        if (invalid) {
            throw SofabException(
                SofabError.INVALID_MSG,
                "decode already INVALID; reset() to start a new message",
            )
        } else {
            throw SofabException(
                SofabError.LIMIT_EXCEEDED,
                "decode already stopped by a receiver limit; reset() to start a new message",
            )
        }

    /**
     * Decode [len] bytes of [data] from [off]. The body of [feed], split out so the
     * decode loop itself carries no exception handler.
     */
    private fun decode(data: ByteArray, off: Int, len: Int, visitor: Visitor) {
        var i = off
        val end = off + len
        while (i < end) {
            if (state != S_IDLE) {
                i = resume(data, i, end, visitor)
                continue
            }

            // --- clean field boundary: decode a whole field in place -------------
            //
            // The header, the scalar-value read and the visitor hand-off are all
            // written out here rather than behind a call. A field is only a few dozen
            // instructions of real work, so an out-of-line call per field — with its
            // own frame, spills and reloads — is a large fraction of the cost of
            // decoding one. Only the shapes that genuinely need more code (fixlen,
            // arrays) are reached through a call.
            var header: Long
            var p = i
            if (end - p >= 10) {
                val w = leGetLong(data, p)
                if ((w and 0x80L) == 0L) {
                    // One byte — every field header with id < 16.
                    header = w and 0x7FL
                    p = i + 1
                } else if ((w and 0x8000L) == 0L) {
                    header = (w and 0x7FL) or ((w ushr 1) and (0x7FL shl 7))
                    p = i + 2
                } else {
                    header = wideVarint(data, p, w)
                    p = scratchPos
                }
            } else if (data[p] >= 0) {
                // Single-byte varint in the buffer's last nine bytes: the shape every
                // small id takes, read without the out-of-line reader. `i < end` is
                // the loop's own condition, so the byte is there.
                header = data[p].toLong()
                p = i + 1
            } else {
                header = readWord(data, p, end)
                p = scratchPos
                if (p < 0) {
                    // Header runs past the buffer: hand this byte to the state machine
                    // (which accumulates it in S_HEADER) and let the loop drive the rest.
                    step(data[i].toInt() and 0xFF, visitor)
                    i++
                    continue
                }
            }
            val wireType = (header and 0x07L).toInt()
            val idValue = header ushr 3
            // ID_MAX bounds every header's id, sequence end included (§6.2) — the id
            // is validated where it is read, before any branch on wire type.
            if (idValue > ID_MAX) {
                throw SofabException(SofabError.INVALID_MSG, "id $idValue")
            }
            val fieldId = idValue.toInt()
            id = fieldId
            inArray = false

            if (wireType <= T_VARINT_SIGNED) {
                var value: Long
                if (end - p >= 10) {
                    val w = leGetLong(data, p)
                    if ((w and 0x80L) == 0L) {
                        value = w and 0x7FL
                        p += 1
                    } else if ((w and 0x8000L) == 0L) {
                        value = (w and 0x7FL) or ((w ushr 1) and (0x7FL shl 7))
                        p += 2
                    } else {
                        value = wideVarint(data, p, w)
                        p = scratchPos
                    }
                } else if (p < end && data[p] >= 0) {
                    value = data[p].toLong()
                    p++
                } else {
                    value = readWord(data, p, end)
                    val q = scratchPos
                    if (q < 0) {
                        // Value spills past the buffer: the machine reads it from p.
                        state = if (wireType == T_VARINT_UNSIGNED) S_VARINT_UNSIGNED else S_VARINT_SIGNED
                        i = p
                        continue
                    }
                    p = q
                }
                if (wireType == T_VARINT_UNSIGNED) {
                    visitor.unsigned(fieldId, value)
                } else {
                    visitor.signed(fieldId, zigzagDecode(value))
                }
                i = p
                continue
            }

            if (wireType == T_SEQUENCE_START) {
                if (depth >= Sofab.MAX_DEPTH) {
                    throw SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH")
                }
                depth++
                visitor.sequenceBegin(fieldId)
                i = p
                continue
            }
            if (wireType == T_SEQUENCE_END) {
                if (depth == 0) {
                    throw SofabException(SofabError.INVALID_MSG, "dangling sequence end")
                }
                depth--
                visitor.sequenceEnd()
                i = p
                continue
            }
            i = fastCompound(data, p, end, visitor, wireType)
        }
    }

    /**
     * Continue a field whose bytes were split across `feed` calls: stream a
     * string/blob payload in bulk, or hand one byte to the resumable state machine.
     * Returns the index just past the bytes consumed.
     *
     * Only the construct that actually straddled the boundary needs the machine.
     * When that byte completes an array element (or the count / `fixlen_word` that
     * arms one) and elements remain, the rest of the chunk goes straight back to the
     * bulk element loop, so a boundary anywhere inside an array costs one element
     * rather than every element after it.
     */
    private fun resume(data: ByteArray, i: Int, end: Int, visitor: Visitor): Int {
        // Bulk path: stream string/blob payloads with one callback per chunk rather
        // than one per byte.
        if (state == S_FIXLEN_RAW) {
            val avail = end - i
            val take = if (avail < fixlenRemaining) avail else fixlenRemaining
            val chunkOffset = fixlenTotal - fixlenRemaining
            // S_FIXLEN_RAW is armed for F_STRING and F_BLOB only — the two sub-types
            // with a streaming payload — so there is no third case to guard against.
            if (fixlenSubtype == F_STRING) {
                visitor.string(id, fixlenTotal, chunkOffset, data, i, take)
            } else {
                visitor.blob(id, fixlenTotal, chunkOffset, data, i, take)
            }
            fixlenRemaining -= take
            if (fixlenRemaining == 0) {
                state = S_IDLE
            }
            return i + take
        }
        // Every other non-idle state covers a partially read value or payload, and
        // S_HEADER covers a partially read field header.
        step(data[i].toInt() and 0xFF, visitor)
        val p = i + 1
        if (inArray && p < end) {
            // Back at an element boundary with elements left and bytes left: the
            // element loops pick up exactly where the machine stopped, reading
            // arrayRemaining and id from the fields the machine just updated.
            // "Nothing accumulated" is what says the machine is between elements —
            // varintShift == 0 for a varint array, accLen == 0 for a float one — and
            // it also holds right after a count word or fixlen_word completes, which
            // arms the same states with the array already announced.
            when (state) {
                S_VARINT_UNSIGNED -> if (varintShift == 0) return unsignedElements(data, p, end, visitor)
                S_VARINT_SIGNED -> if (varintShift == 0) return signedElements(data, p, end, visitor)
                S_FIXLEN_VAL -> if (accLen == 0) return fixlenElements(data, p, end, visitor, fixlenTotal)
                else -> {}
            }
        }
        return p
    }

    /**
     * Read a varint of three or more bytes starting at [p], given [w], the eight
     * bytes already loaded there. Leaves the next position in [scratchPos]. Kept out
     * of line so the one- and two-byte forms that dominate real messages stay in the
     * caller's straight-line code.
     */
    private fun wideVarint(data: ByteArray, p: Int, w: Long): Long {
        val stop = w.inv() and CONT_BITS
        if (stop != 0L) {
            val nz = stop.countTrailingZeroBits() // 23, 31, ... 63
            scratchPos = p + (nz ushr 3) + 1
            return gather7(w and (-1L ushr (63 - nz)))
        }
        var v = gather7(w)
        val b8 = data[p + 8]
        v = v or ((b8.toLong() and 0x7F) shl 56)
        if (b8 >= 0) {
            scratchPos = p + 9
            return v
        }
        scratchPos = p + 10
        return v or tenthByte(data, p)
    }

    /**
     * Decode a field whose shape needs more than the straight-line code in [decode]:
     * a fixlen scalar or either kind of array. [p] points just past the field header,
     * whose [wireType] is passed in. Returns the index just past the bytes consumed;
     * when the field cannot be completed within the buffer the resumable state
     * machine is armed and the index is left at the first byte the machine must
     * re-read.
     *
     * Only wire types 2..5 arrive here: [decode] handles 0/1 inline and both sequence
     * markers before the call, so the four cases below are exhaustive.
     */
    private fun fastCompound(data: ByteArray, p: Int, end: Int, visitor: Visitor, wireType: Int): Int =
        when (wireType) {
            T_FIXLEN -> fastFixlenScalar(data, p, end, visitor)
            T_VARINTARRAY_UNSIGNED -> {
                arrayKind = ArrayKind.UNSIGNED
                fixlenArray = false
                fastVarintArray(data, p, end, visitor, false)
            }
            T_VARINTARRAY_SIGNED -> {
                arrayKind = ArrayKind.SIGNED
                fixlenArray = false
                fastVarintArray(data, p, end, visitor, true)
            }
            else -> {
                // T_FIXLENARRAY: arrayKind stays unsettled until the fixlen_word
                // names the subtype.
                fixlenArray = true
                fastFixlenArray(data, p, end, visitor)
            }
        }

    /**
     * The decoder's one bounded varint reader: consume a varint from
     * `data[i..end)` wherever the buffer might end inside it — a field header, a
     * scalar value or an array element in the last nine bytes, and a `fixlen_word`
     * or array count anywhere.
     *
     * The value itself is the return value — every 64-bit pattern is a legal one —
     * so completion is signalled out of band: [scratchPos] is left at the position
     * just past the varint, or set to `-1` when it runs past [end] and the caller
     * must arm the state machine.
     *
     * The >64-bit overflow test (§4.1/§6.3) can only fire on the tenth byte — the
     * sole shift (63) at which a payload bit spills past bit 63 — so it is tested as
     * `shift == 63` rather than recomputed per byte.
     */
    private fun readWord(data: ByteArray, i: Int, end: Int): Long {
        var v = 0L
        var shift = 0
        var p = i
        while (true) {
            if (p >= end) {
                scratchPos = -1
                return 0
            }
            val b = data[p++].toInt() and 0xFF
            if (shift == 63 && (b and 0x7F) > 1) {
                throw SofabException(SofabError.INVALID_MSG, "varint overflow")
            }
            v = v or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if ((b and 0x80) == 0) {
                break
            }
            if (shift >= VALUE_BITS) {
                throw SofabException(SofabError.INVALID_MSG, "varint overflow")
            }
        }
        scratchPos = p
        return v
    }

    /** Fast path for a scalar fixlen field; [i] points at its length header. */
    private fun fastFixlenScalar(data: ByteArray, i: Int, end: Int, visitor: Visitor): Int {
        // A fixlen_word is one byte for any payload up to 15 bytes — every float, and
        // most strings — so that case is read here instead of through the general
        // reader.
        val fh: Long
        var p: Int
        if (i < end && data[i] >= 0) {
            fh = data[i].toLong()
            p = i + 1
        } else {
            fh = readWord(data, i, end)
            p = scratchPos
            if (p < 0) {
                state = S_FIXLEN_LEN // machine re-reads the length header from i
                return i
            }
        }
        val subtype = checkFixlenWord(fh)
        // Judged against the ceiling above, so the conversion cannot lose bits.
        val length = (fh ushr 3).toInt()
        // The width rule below is the half of §4.6 that stays with the arm that
        // selects the sub-type (see checkFixlenWord): fastFixlenArray and
        // stepFixlenLen carry it too, and the three must change together.
        when (subtype) {
            F_FP32 -> {
                if (length != 4) {
                    throw SofabException(SofabError.INVALID_MSG, "fp32 length $length")
                }
                // §5.2: announce before the payload-availability test below, so a
                // message ending right here still delivers the header event and can be
                // judged INVALID rather than decaying to INCOMPLETE.
                visitor.fixlenBegin(id, FixlenType.FP32, 4)
                if (end - p < 4) {
                    armFixlenVal(F_FP32, 4)
                    return p
                }
                visitor.fp32Bits(id, leGetInt(data, p))
                return p + 4
            }
            F_FP64 -> {
                if (length != 8) {
                    throw SofabException(SofabError.INVALID_MSG, "fp64 length $length")
                }
                visitor.fixlenBegin(id, FixlenType.FP64, 8)
                if (end - p < 8) {
                    armFixlenVal(F_FP64, 8)
                    return p
                }
                visitor.fp64(id, Double.fromBits(leGetLong(data, p)))
                return p + 8
            }
            else -> {
                // The check above leaves 0..3, so string and blob are what remains.
                val isString = subtype == F_STRING
                fixlenSubtype = subtype
                fixlenTotal = length
                fixlenRemaining = length
                accLen = 0
                visitor.fixlenBegin(id, if (isString) FixlenType.STRING else FixlenType.BLOB, length)
                if (length == 0) {
                    if (isString) {
                        visitor.string(id, 0, 0, EMPTY, 0, 0)
                    } else {
                        visitor.blob(id, 0, 0, EMPTY, 0, 0)
                    }
                    state = S_IDLE
                    return p
                }
                state = S_FIXLEN_RAW // the payload streams in chunks from here
                if (end - p < length) {
                    return p // it straddles: resume() takes the rest chunk by chunk
                }
                // The whole payload is in hand, which is the one-shot case and the
                // common streaming one alike. Deliver it here rather than returning to
                // the feed loop only to be dispatched straight back in.
                if (isString) {
                    visitor.string(id, length, 0, data, p, length)
                } else {
                    visitor.blob(id, length, 0, data, p, length)
                }
                fixlenRemaining = 0
                state = S_IDLE
                return p + length
            }
        }
    }

    /**
     * Fast path for an unsigned/signed varint array; [i] points at the count.
     *
     * The element loop is specialised per signedness rather than testing a flag
     * inside it: the flag does not fold away even though every call site passes a
     * constant, and a shared loop measures materially worse on a 1000-element
     * decode. The duplication is bought, not accidental.
     */
    private fun fastVarintArray(data: ByteArray, i: Int, end: Int, visitor: Visitor, signed: Boolean): Int {
        val p = fastArrayHeader(data, i, end, visitor, true)
        if (p < 0) {
            return i // count header spilled past the buffer; machine reads it
        }
        return if (signed) signedElements(data, p, end, visitor) else unsignedElements(data, p, end, visitor)
    }

    /** Elements of an unsigned varint array; [start] points at element 0. */
    private fun unsignedElements(data: ByteArray, start: Int, end: Int, visitor: Visitor): Int {
        // Hoist the per-element fields into locals: the loop runs once per array
        // element, so reading `id` and writing `arrayRemaining` straight from memory
        // each time would add a load/store to every element.
        var p = start
        var remaining = arrayRemaining
        val fieldId = id
        // Resolved once at the header and hoisted whole: the destination cannot
        // change while the array runs, so the loop reads locals, not fields.
        val bw = bulkW // W_NONE = per-element
        val dB = bulkB ?: NO_BYTES
        val dS = bulkS ?: NO_SHORTS
        val dI = bulkI ?: NO_INTS
        val dL = bulkL ?: NO_LONGS
        var k = bulkAt
        val safe = end - 10 // last start position with a full varint's room
        while (remaining > 0) {
            var value: Long
            if (p <= safe) {
                val w = leGetLong(data, p)
                if ((w and 0x80L) == 0L) {
                    // Single-byte element: the whole u8/u16 small-value case, for two
                    // instructions on top of the load the wide path needs anyway.
                    value = w and 0x7FL
                    p += 1
                } else {
                    val stop = w.inv() and CONT_BITS
                    if (stop != 0L) {
                        val nz = stop.countTrailingZeroBits()
                        value = gather7(w and (-1L ushr (63 - nz)))
                        p += (nz ushr 3) + 1
                    } else {
                        value = gather7(w)
                        val b8 = data[p + 8]
                        value = value or ((b8.toLong() and 0x7F) shl 56)
                        if (b8 >= 0) {
                            p += 9
                        } else {
                            value = value or tenthByte(data, p)
                            p += 10
                        }
                    }
                }
            } else if (p < end && data[p] >= 0) {
                // Single-byte element in the buffer's last nine bytes.
                value = data[p].toLong()
                p++
            } else {
                val tail = readWord(data, p, end)
                if (scratchPos < 0) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining
                    bulkAt = k
                    state = S_VARINT_UNSIGNED
                    return p
                }
                value = tail
                p = scratchPos
            }
            // One predictable branch instead of a call: the whole array takes the same
            // arm, and the destination was resolved once at the header.
            when (bw) {
                W_LONG64 -> dL[k++] = value
                W_INT32 -> dI[k++] = narrowU32(value)
                W_SHORT16 -> dS[k++] = narrowU16(value)
                W_BYTE8 -> dB[k++] = narrowU8(value)
                else -> visitor.unsigned(fieldId, value) // W_NONE
            }
            remaining--
        }
        arrayRemaining = remaining
        inArray = false
        state = S_IDLE
        if (bw != W_NONE) {
            bulkAt = k
            endBulk(visitor, fieldId)
        }
        return p
    }

    /** Elements of a signed (ZigZag) varint array; [start] points at element 0. */
    private fun signedElements(data: ByteArray, start: Int, end: Int, visitor: Visitor): Int {
        var p = start
        var remaining = arrayRemaining
        val fieldId = id
        val bw = bulkW // W_NONE = per-element
        val dB = bulkB ?: NO_BYTES
        val dS = bulkS ?: NO_SHORTS
        val dI = bulkI ?: NO_INTS
        val dL = bulkL ?: NO_LONGS
        var k = bulkAt
        val safe = end - 10
        while (remaining > 0) {
            var raw: Long
            if (p <= safe) {
                val w = leGetLong(data, p)
                if ((w and 0x80L) == 0L) {
                    raw = w and 0x7FL
                    p += 1
                } else {
                    val stop = w.inv() and CONT_BITS
                    if (stop != 0L) {
                        val nz = stop.countTrailingZeroBits()
                        raw = gather7(w and (-1L ushr (63 - nz)))
                        p += (nz ushr 3) + 1
                    } else {
                        raw = gather7(w)
                        val b8 = data[p + 8]
                        raw = raw or ((b8.toLong() and 0x7F) shl 56)
                        if (b8 >= 0) {
                            p += 9
                        } else {
                            raw = raw or tenthByte(data, p)
                            p += 10
                        }
                    }
                }
            } else if (p < end && data[p] >= 0) {
                raw = data[p].toLong()
                p++
            } else {
                val tail = readWord(data, p, end)
                if (scratchPos < 0) {
                    arrayRemaining = remaining
                    bulkAt = k
                    state = S_VARINT_SIGNED
                    return p
                }
                raw = tail
                p = scratchPos
            }
            val value = zigzagDecode(raw)
            when (bw) {
                W_LONG64 -> dL[k++] = value
                W_INT32 -> dI[k++] = narrowI32(value)
                W_SHORT16 -> dS[k++] = narrowI16(value)
                W_BYTE8 -> dB[k++] = narrowI8(value)
                else -> visitor.signed(fieldId, value) // W_NONE
            }
            remaining--
        }
        arrayRemaining = remaining
        inArray = false
        state = S_IDLE
        if (bw != W_NONE) {
            bulkAt = k
            endBulk(visitor, fieldId)
        }
        return p
    }

    /** Fast path for a fixlen (fp32/fp64) array; [i] points at the count. */
    private fun fastFixlenArray(data: ByteArray, i: Int, end: Int, visitor: Visitor): Int {
        // §4.8 step 1/2: the count word only sets up the array context — the format
        // ceiling fires there, but arrayBegin does NOT, because the element subtype
        // it must report is still one varint away.
        var p = fastArrayHeader(data, i, end, visitor, false)
        if (p < 0) {
            return i // count header spilled past the buffer; machine reads it
        }
        // §4.8: a fixlen array always carries its fixlen_word, even when empty, so an
        // empty fp32 array is distinguishable from an empty fp64 array. Read it
        // unconditionally; the payload loop below runs zero times when empty. The
        // element length header is encoded once and reused for every element.
        val lenStart = p
        val fh: Long
        if (p < end && data[p] >= 0) {
            fh = data[p].toLong()
            p = lenStart + 1
        } else {
            fh = readWord(data, lenStart, end)
            p = scratchPos
            if (p < 0) {
                state = S_FIXLEN_LEN // machine re-reads the element header from lenStart
                return lenStart
            }
        }
        val subtype = checkFixlenWord(fh)
        val lengthValue = fh ushr 3
        // Width rule, the per-arm half of §4.6 (see checkFixlenWord): the same rule
        // lives in fastFixlenScalar and stepFixlenLen, and the three change together.
        val size: Int
        if (subtype == F_FP32) {
            if (lengthValue != 4L) {
                throw SofabException(SofabError.INVALID_MSG, "fp32 length $lengthValue")
            }
            size = 4
        } else if (subtype == F_FP64) {
            if (lengthValue != 8L) {
                throw SofabException(SofabError.INVALID_MSG, "fp64 length $lengthValue")
            }
            size = 8
        } else {
            // What the shared check above leaves: string/blob, which are not valid as
            // fixlen-array elements (§4.8). This is a FORMAT violation, judged before
            // the visitor is offered the field, so it can never become a §7.3 skip.
            throw SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element")
        }
        fixlenSubtype = subtype
        fixlenTotal = size
        // §4.8 step 3: the word is format-valid, so the subtype is now known and the
        // array can be announced. Firing here (rather than on the count word) is what
        // lets a visitor skip a field whose subtype contradicts its schema without
        // judging the count against a bound that does not apply to it.
        arrayBeginFixlen(if (size == 4) ArrayKind.FP32 else ArrayKind.FP64, visitor)
        return fixlenElements(data, p, end, visitor, size)
    }

    /**
     * Payload elements of a fixlen (fp32/fp64) array; [start] points at the next
     * element and [size] is its width, 4 or 8. Split out of [fastFixlenArray] because
     * [resume] re-enters it once the machine has finished an element that straddled a
     * chunk boundary: by then the count, the `fixlen_word` and `arrayBegin` are all
     * behind us, so only the payload loop may run again.
     */
    private fun fixlenElements(data: ByteArray, start: Int, end: Int, visitor: Visitor, size: Int): Int {
        var p = start
        var remaining = arrayRemaining
        val fieldId = id
        if (size == 4) {
            while (remaining > 0) {
                if (end - p < 4) {
                    return spillFixlenArray(remaining, 4, p)
                }
                visitor.fp32Bits(fieldId, leGetInt(data, p))
                p += 4
                remaining--
            }
        } else {
            while (remaining > 0) {
                if (end - p < 8) {
                    return spillFixlenArray(remaining, 8, p)
                }
                visitor.fp64(fieldId, Double.fromBits(leGetLong(data, p)))
                p += 8
                remaining--
            }
        }
        arrayRemaining = remaining
        inArray = false
        state = S_IDLE
        return p
    }

    /** Element bytes spill past the buffer: arm the machine to accumulate from [p]. */
    private fun spillFixlenArray(remaining: Int, size: Int, p: Int): Int {
        arrayRemaining = remaining
        fixlenRemaining = size
        accLen = 0
        state = S_FIXLEN_VAL
        return p
    }

    /**
     * Read and validate an array count header at [i] and set up the array context
     * ([arrayRemaining], [inArray]), emitting `arrayBegin` when [emitBegin] is set.
     * Returns the index after the count, or `-1` if the count spilled past the buffer
     * — in which case nothing is emitted and the state machine re-reads the count from
     * [i] ([arrayKind] / [fixlenArray] are already set), so `arrayBegin` fires exactly
     * once.
     *
     * [emitBegin] is `false` for a fixlen array: its element kind is only known once
     * the `fixlen_word` after the count has been read, so the caller fires the hook
     * there (CORELIB_PLAN §4.8). The `ARRAY_MAX` format ceiling below is *not*
     * deferred with it — §4.8 step 1 keeps it on the count word, whatever the subtype
     * turns out to be, and nothing is allocated on the strength of the count either
     * way.
     */
    private fun fastArrayHeader(data: ByteArray, i: Int, end: Int, visitor: Visitor, emitBegin: Boolean): Int {
        val count = readWord(data, i, end)
        if (scratchPos < 0) {
            state = S_ARRAY_COUNT
            return -1
        }
        // count == 0 is a valid empty array (§4.7/§4.8); only an oversized count is
        // rejected. The comparison is unsigned: a count with bit 63 set is a huge
        // value, not a negative one.
        if (count.toULong() > ARRAY_MAX.toULong()) {
            throw SofabException(SofabError.INVALID_MSG, "array count")
        }
        val c = count.toInt()
        arrayRemaining = c
        inArray = true
        if (emitBegin) {
            // emitBegin is false only for a fixlen array, whose kind is not settled
            // until its fixlen_word: this is the integer-array path, the one the bulk
            // offer covers.
            visitor.arrayBegin(id, arrayKind, c)
            armBulk(visitor, c)
        }
        return scratchPos
    }

    /**
     * Announce a fixlen array now that its `fixlen_word` has been read and found
     * format-valid: settle [arrayKind] to the concrete subtype and fire
     * [Visitor.arrayBegin] exactly once for the field.
     */
    private fun arrayBeginFixlen(kind: ArrayKind, visitor: Visitor) {
        arrayKind = kind
        visitor.arrayBegin(id, kind, arrayRemaining)
    }

    /**
     * Put the bulk offer to the visitor for an integer array of [c] elements and arm
     * the fill if it is taken.
     *
     * A destination the caller handed over that is **shorter than the announced
     * count** is refused with [SofabError.ARGUMENT] — CORELIB_PLAN §6.6.3's third
     * refusal tier, the one §6.3 names `InvalidArgument`: the message is
     * well-formed and inside every bound it declares, so `INVALID_MSG` would call
     * good bytes malformed and `LIMIT_EXCEEDED` would promise a limit to raise that
     * nobody configured. What is wrong is the storage this caller offered, and
     * neither partially filling it nor growing it is an option. Declining the offer
     * outright — returning `null`, or anything that is not one of the four
     * primitive integer arrays — is not a destination at all and still falls back
     * to per-element delivery.
     */
    private fun armBulk(visitor: Visitor, c: Int) {
        bulkB = null
        bulkS = null
        bulkI = null
        bulkL = null
        bulkW = W_NONE
        bulkAt = 0
        if (c == 0) {
            return
        }
        // One virtual call and one type resolution per ARRAY. A mistyped destination
        // is no offer and the elements go the ordinary way; a rightly typed one that
        // is too short is the caller's mistake and is reported, never overrun.
        when (val dst = visitor.arrayBulk(id, arrayKind, c)) {
            is LongArray -> {
                requireRoom(dst.size, c)
                bulkL = dst
                bulkW = W_LONG64
            }
            is IntArray -> {
                requireRoom(dst.size, c)
                bulkI = dst
                bulkW = W_INT32
            }
            is ShortArray -> {
                requireRoom(dst.size, c)
                bulkS = dst
                bulkW = W_SHORT16
            }
            is ByteArray -> {
                requireRoom(dst.size, c)
                bulkB = dst
                bulkW = W_BYTE8
            }
            else -> {}
        }
    }

    /**
     * The §6.6.3 destination check: a bulk destination must hold the announced
     * count. Split out so the four arms share one throw site and stay inlinable.
     */
    private fun requireRoom(size: Int, c: Int) {
        if (size < c) {
            throw SofabException(
                SofabError.ARGUMENT,
                "bulk destination holds $size elements, the array announced $c",
            )
        }
    }

    /** Arm the state machine to accumulate a fixed-size fixlen value (fp32/fp64). */
    private fun armFixlenVal(subtype: Int, size: Int) {
        fixlenSubtype = subtype
        fixlenTotal = size
        fixlenRemaining = size
        accLen = 0
        state = S_FIXLEN_VAL
    }

    /**
     * Resumable state machine: feed one byte at the current [state]. This is the
     * byte-at-a-time counterpart to the fast path, used whenever a field, value or
     * array element was split across `feed` calls. Each `step*` handler consumes the
     * byte, and on completing its value emits to the visitor and transitions [state]
     * to the next field or element.
     */
    private fun step(b: Int, visitor: Visitor) {
        machineBytes++
        when (state) {
            S_IDLE, S_HEADER -> stepIdle(b, visitor)
            S_VARINT_UNSIGNED -> stepVarintUnsigned(b, visitor)
            S_VARINT_SIGNED -> stepVarintSigned(b, visitor)
            S_FIXLEN_LEN -> stepFixlenLen(b, visitor)
            S_FIXLEN_VAL -> stepFixlenVal(b, visitor)
            S_ARRAY_COUNT -> stepArrayCount(b, visitor)
            else -> {} // S_FIXLEN_RAW is handled in resume()'s bulk path
        }
    }

    /**
     * Feed one byte into the varint accumulator.
     *
     * @return `true` if a complete value is now in [varintOut]; `false` if more bytes
     *   are needed
     */
    private fun varintPush(b: Int): Boolean {
        // Reject an overlong (>64-bit) varint: payload bits that would spill past bit
        // 63 are malformed, not silently truncated (§4.1/§6.3). Shift 63 is the only
        // one at which that can happen.
        if (varintShift == 63 && (b and 0x7F) > 1) {
            varintValue = 0
            varintShift = 0
            throw SofabException(SofabError.INVALID_MSG, "varint overflow")
        }
        varintValue = varintValue or ((b and 0x7F).toLong() shl varintShift)
        varintShift += 7

        if ((b and 0x80) == 0) {
            varintOut = varintValue
            varintValue = 0
            varintShift = 0
            return true
        }

        if (varintShift >= VALUE_BITS) {
            varintValue = 0
            varintShift = 0
            throw SofabException(SofabError.INVALID_MSG, "varint overflow")
        }
        return false
    }

    /**
     * Accumulate the field-header varint at a clean boundary; once complete, validate
     * the id, record the wire type, and arm the state for the value that follows.
     * Sequence start/end are emitted here and leave the machine `S_IDLE` (they carry
     * no value).
     *
     * The wire type is three bits and all eight values are real cases (§4.3), so the
     * dispatch is exhaustive and carries no unknown-type arm.
     */
    private fun stepIdle(b: Int, visitor: Visitor) {
        if (!varintPush(b)) {
            state = S_HEADER // header still incomplete: status reports INCOMPLETE
            return
        }
        val header = varintOut
        val wireType = (header and 0x07L).toInt()
        val idValue = header ushr 3
        if (idValue > ID_MAX) {
            throw SofabException(SofabError.INVALID_MSG, "id $idValue")
        }
        id = idValue.toInt()
        inArray = false

        when (wireType) {
            T_VARINT_UNSIGNED -> state = S_VARINT_UNSIGNED
            T_VARINT_SIGNED -> state = S_VARINT_SIGNED
            T_FIXLEN -> state = S_FIXLEN_LEN
            T_VARINTARRAY_UNSIGNED -> {
                arrayKind = ArrayKind.UNSIGNED
                fixlenArray = false
                state = S_ARRAY_COUNT
            }
            T_VARINTARRAY_SIGNED -> {
                arrayKind = ArrayKind.SIGNED
                fixlenArray = false
                state = S_ARRAY_COUNT
            }
            T_FIXLENARRAY -> {
                // arrayKind stays unsettled until the fixlen_word names the subtype.
                fixlenArray = true
                state = S_ARRAY_COUNT
            }
            T_SEQUENCE_START -> {
                if (depth >= Sofab.MAX_DEPTH) {
                    throw SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH")
                }
                depth++
                state = S_IDLE
                visitor.sequenceBegin(id)
            }
            else -> { // 0..7 are all named above; this is type 7, sequence end
                if (depth == 0) {
                    throw SofabException(SofabError.INVALID_MSG, "dangling sequence end")
                }
                depth--
                state = S_IDLE
                visitor.sequenceEnd()
            }
        }
    }

    /**
     * Accumulate an unsigned varint value; on completion emit it and advance to the
     * next array element or back to idle. Serves both scalar fields and
     * unsigned-array elements.
     */
    private fun stepVarintUnsigned(b: Int, visitor: Visitor) {
        if (varintPush(b)) {
            if (inArray && bulkW != W_NONE) {
                storeBulk(varintOut)
            } else {
                visitor.unsigned(id, varintOut)
            }
            advanceAfterElement()
            endBulkIfArrayOver(visitor)
        }
    }

    /**
     * Accumulate a signed varint value (ZigZag-decoded on completion); otherwise the
     * signed counterpart of [stepVarintUnsigned].
     */
    private fun stepVarintSigned(b: Int, visitor: Visitor) {
        if (varintPush(b)) {
            if (inArray && bulkW != W_NONE) {
                storeBulkSigned(zigzagDecode(varintOut))
            } else {
                visitor.signed(id, zigzagDecode(varintOut))
            }
            advanceAfterElement()
            endBulkIfArrayOver(visitor)
        }
    }

    /**
     * Close an armed bulk fill once [advanceAfterElement] has taken the array off the
     * machine's hands. The bulk element loops close their own; this is the path where
     * the LAST element of the array straddled a feed boundary, so the machine, not
     * the loop, delivered it.
     */
    private fun endBulkIfArrayOver(visitor: Visitor) {
        if (bulkW != W_NONE && !inArray) {
            endBulk(visitor, id)
        }
    }

    /**
     * The element loops' store, for the one element the byte-at-a-time machine
     * delivers when an element straddles a feed boundary. Off the hot path, so it
     * reads the fields rather than hoisting them.
     */
    private fun storeBulk(v: Long) {
        when (bulkW) {
            W_LONG64 -> bulkL!![bulkAt++] = v
            W_INT32 -> bulkI!![bulkAt++] = narrowU32(v)
            W_SHORT16 -> bulkS!![bulkAt++] = narrowU16(v)
            else -> bulkB!![bulkAt++] = narrowU8(v)
        }
    }

    /** [storeBulk] for a SIGNED array. */
    private fun storeBulkSigned(v: Long) {
        when (bulkW) {
            W_LONG64 -> bulkL!![bulkAt++] = v
            W_INT32 -> bulkI!![bulkAt++] = narrowI32(v)
            W_SHORT16 -> bulkS!![bulkAt++] = narrowI16(v)
            else -> bulkB!![bulkAt++] = narrowI8(v)
        }
    }

    /** Release the armed destination and tell the consumer how much was written. */
    private fun endBulk(visitor: Visitor, fieldId: Int) {
        val n = bulkAt
        bulkB = null
        bulkS = null
        bulkI = null
        bulkL = null
        bulkW = W_NONE
        visitor.arrayBulkEnd(fieldId, n)
    }

    /** Shared "next element or back to idle" logic for varint scalars/arrays. */
    private fun advanceAfterElement() {
        if (inArray) {
            arrayRemaining--
            if (arrayRemaining > 0) {
                return // stay in the same state for the next element
            }
            inArray = false
        }
        state = S_IDLE
    }

    /**
     * Accumulate a fixlen length header (`(len shl 3) or subtype`). Floats arm
     * [S_FIXLEN_VAL] to read their bytes; a non-empty string/blob arms
     * [S_FIXLEN_RAW] so the payload streams in bulk, while an empty one is emitted
     * immediately. String/blob are rejected as fixlen-array elements.
     */
    private fun stepFixlenLen(b: Int, visitor: Visitor) {
        if (!varintPush(b)) {
            return
        }
        val header = varintOut
        val subtype = checkFixlenWord(header)
        // Judged against the ceiling above, so the conversion cannot lose bits.
        val length = (header ushr 3).toInt()

        fixlenSubtype = subtype
        fixlenTotal = length
        fixlenRemaining = length
        accLen = 0

        // Width rule, the per-arm half of §4.6 (see checkFixlenWord): the same rule
        // lives in fastFixlenScalar and fastFixlenArray, and the three change together.
        when (subtype) {
            F_FP32 -> {
                if (length != 4) {
                    throw SofabException(SofabError.INVALID_MSG, "fp32 length $length")
                }
                // §4.8 step 3: the array's deferred arrayBegin lands here, once the
                // word is known format-valid and its subtype settled. inArray is only
                // true for a fixlen array in this state; a scalar fixlen field
                // announces itself instead, and announces here for the same reason the
                // fast path does (§5.2): this is the last point before the verdict
                // could decay to INCOMPLETE on a message that ends at the length word.
                if (inArray) {
                    arrayBeginFixlen(ArrayKind.FP32, visitor)
                } else {
                    visitor.fixlenBegin(id, FixlenType.FP32, 4)
                }
                state = afterFixlenWord()
            }
            F_FP64 -> {
                if (length != 8) {
                    throw SofabException(SofabError.INVALID_MSG, "fp64 length $length")
                }
                if (inArray) {
                    arrayBeginFixlen(ArrayKind.FP64, visitor)
                } else {
                    visitor.fixlenBegin(id, FixlenType.FP64, 8)
                }
                state = afterFixlenWord()
            }
            else -> {
                // String/blob are not valid as fixlen-array elements: a FORMAT
                // violation (§4.8), rejected before the field is ever offered to the
                // visitor, so it can never become a §7.3 skip.
                if (inArray) {
                    throw SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element")
                }
                visitor.fixlenBegin(
                    id,
                    if (subtype == F_STRING) FixlenType.STRING else FixlenType.BLOB,
                    length,
                )
                if (length == 0) {
                    if (subtype == F_STRING) {
                        visitor.string(id, 0, 0, EMPTY, 0, 0)
                    } else {
                        visitor.blob(id, 0, 0, EMPTY, 0, 0)
                    }
                    state = S_IDLE
                } else {
                    state = S_FIXLEN_RAW
                }
            }
        }
    }

    /**
     * Next state after a `fixlen_word` has been read. For an empty fixlen array the
     * word is the whole field — no payload follows (§4.8) — so the array is closed
     * and the machine returns to idle; otherwise the fixed-size value bytes follow.
     */
    private fun afterFixlenWord(): Int {
        if (inArray && arrayRemaining == 0) {
            inArray = false
            return S_IDLE
        }
        return S_FIXLEN_VAL
    }

    /**
     * Accumulate the fixed-size bytes of a float value into [acc]; once all are in,
     * decode the fp32/fp64 from little-endian, emit it, and advance to the next array
     * element (reusing the element size) or back to idle.
     *
     * [S_FIXLEN_VAL] is armed for fp32 and fp64 alone — string and blob stream
     * through [S_FIXLEN_RAW] instead — so fp64 is simply the other case, with nothing
     * left over to reject.
     */
    private fun stepFixlenVal(b: Int, visitor: Visitor) {
        val a = acc
        a[accLen++] = b.toByte()
        fixlenRemaining--
        if (fixlenRemaining != 0) {
            return
        }

        // The carry buffer is a plain ByteArray, so the same little-endian reads the
        // one-shot path uses decode it here — one read each instead of a shift/or
        // chain, and the two paths cannot drift.
        if (fixlenSubtype == F_FP32) {
            visitor.fp32Bits(id, leGetInt(a, 0))
        } else {
            visitor.fp64(id, Double.fromBits(leGetLong(a, 0)))
        }

        // Next array element (reuse the element size) or back to idle.
        if (inArray) {
            arrayRemaining--
            if (arrayRemaining > 0) {
                fixlenRemaining = fixlenTotal
                accLen = 0
                return
            }
            inArray = false
        }
        state = S_IDLE
    }

    /**
     * Accumulate an array count header; on completion validate it, set up the array
     * context, and arm the per-element state. An integer array is announced here via
     * [Visitor.arrayBegin] — its count word is the whole header. A fixlen array is
     * not: it advances into [S_FIXLEN_LEN] and [stepFixlenLen] announces it once the
     * `fixlen_word` has named the element subtype (CORELIB_PLAN §4.8). Either way the
     * hook fires exactly once per array field.
     */
    private fun stepArrayCount(b: Int, visitor: Visitor) {
        if (!varintPush(b)) {
            return
        }
        val count = varintOut
        // §4.8 step 1: the format ceiling applies to the count word itself, whatever
        // the element subtype turns out to be. count == 0 is a valid empty array
        // (§4.7/§4.8); only an oversized count is rejected. Nothing is allocated on
        // the strength of the count.
        if (count.toULong() > ARRAY_MAX.toULong()) {
            throw SofabException(SofabError.INVALID_MSG, "array count")
        }
        val c = count.toInt()
        arrayRemaining = c
        inArray = true

        if (fixlenArray) {
            // A fixlen array always carries its fixlen_word, even when empty (§4.8),
            // so an empty one still advances into S_FIXLEN_LEN to consume it.
            // stepFixlenLen fires arrayBegin there and finishes an empty array once
            // the word is read (no payload follows). Ending the message between the
            // two words therefore announces nothing at all: INCOMPLETE, not INVALID.
            state = S_FIXLEN_LEN
            return
        }

        visitor.arrayBegin(id, arrayKind, c)
        armBulk(visitor, c)

        if (c == 0) {
            // Empty varint array: no elements and no fixlen_word follow; the field
            // ends at the count.
            inArray = false
            state = S_IDLE
            return
        }
        state = if (arrayKind == ArrayKind.SIGNED) S_VARINT_SIGNED else S_VARINT_UNSIGNED
    }
}

/**
 * Value contributed by the *tenth* byte of a maximal varint, at `p + 9`, once the
 * ninth byte at `p + 8` has been found to carry a continuation flag. Bit 63 is the
 * only value bit left at that point, so this byte's payload is 0 or 1 and anything
 * else — a higher payload bit, or an eleventh byte implied by a continuation flag —
 * exceeds the 64-bit value range and is malformed (§4.1/§6.3). This is the only
 * place a varint can do so.
 *
 * The ninth byte itself is read by each caller inline rather than here: it ends the
 * varint in the common case, and returning both its contribution and the new
 * position from one call meant handing one of them back through a field that the
 * caller then reloaded — a store/load per long array element. The caller guarantees
 * ten readable bytes from `p`, so no length test is needed on either byte.
 */
private fun tenthByte(data: ByteArray, p: Int): Long {
    val b = data[p + 9].toInt()
    if ((b and 0xFE) != 0) {
        throw SofabException(SofabError.INVALID_MSG, "varint overflow")
    }
    return b.toLong() shl 63
}

/**
 * Judge the sub-type-independent half of a `fixlen_word` (§4.6) and return its
 * sub-type. The decoder reads that word from three places — a scalar field, a
 * fixlen-array element and the resumable machine — and these two rules belong to
 * the word rather than to the reader, so they live here once: a reserved sub-type
 * (4..7) is malformed, and a declared length above `SOFAB_FIXLEN_MAX` is malformed
 * whatever the sub-type turns out to be. Their order is fixed here too, so a word
 * that breaks both names the same rule wherever it is read.
 *
 * The third §4.6 rule — fp32 declares four bytes, fp64 eight — deliberately stays
 * behind, in each caller's arm for that sub-type: it is the one rule that needs the
 * sub-type already selected, and every caller selects on it anyway. All three sites
 * carry a comment naming the other two so the rule stays changed in lockstep, and
 * the decode-rule test drives every wrong width through all of them.
 */
private fun checkFixlenWord(word: Long): Int {
    val subtype = (word and 0x07L).toInt()
    // Reserved sub-types 0x4..0x7 are malformed (§4.6), and are rejected before the
    // length is judged.
    if (subtype > F_BLOB) {
        throw SofabException(SofabError.INVALID_MSG, "fixlen type $subtype")
    }
    // The shift is unsigned, so a word with the top bits set yields a huge length
    // rather than a negative one, and the ceiling catches it.
    val length = word ushr 3
    if (length > ARRAY_MAX) {
        throw SofabException(SofabError.INVALID_MSG, "fixlen length $length")
    }
    return subtype
}

/**
 * Narrow one decoded element to the width of the destination the consumer handed
 * back, rejecting a value that width cannot hold.
 *
 * Handing back an array narrower than [LongArray] declares the elements that wide,
 * so a value with a bit past it is malformed input (MESSAGE_SPEC §7.1) and never
 * silently truncated. Unsigned widths test the bits above the width; signed ones
 * test that narrowing and widening again gives the value back, which is the same
 * statement.
 */
private fun narrowU32(v: Long): Int {
    if ((v and 0xFFFF_FFFFL.inv()) != 0L) throw tooWide()
    return v.toInt()
}

private fun narrowU16(v: Long): Short {
    if ((v and 0xFFFFL.inv()) != 0L) throw tooWide()
    return v.toShort()
}

private fun narrowU8(v: Long): Byte {
    if ((v and 0xFFL.inv()) != 0L) throw tooWide()
    return v.toByte()
}

private fun narrowI32(v: Long): Int {
    if (v.toInt().toLong() != v) throw tooWide()
    return v.toInt()
}

private fun narrowI16(v: Long): Short {
    if (v.toShort().toLong() != v) throw tooWide()
    return v.toShort()
}

private fun narrowI8(v: Long): Byte {
    if (v.toByte().toLong() != v) throw tooWide()
    return v.toByte()
}

private fun tooWide(): SofabException =
    SofabException(SofabError.INVALID_MSG, "array element wider than its destination")
