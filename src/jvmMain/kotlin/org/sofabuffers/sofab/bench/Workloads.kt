/*
 * SofaBuffers Kotlin — the BENCH_SPEC workload set, defined once.
 *
 * BENCH_SPEC is a cross-language contract: the same messages, built from the same
 * literal values, driven the same way, so the numbers from every port are directly
 * comparable. That only holds if a workload is defined in exactly one place — the
 * throughput tool and the instruction-count tool must measure the same code, or
 * their tables describe two different libraries.
 *
 * So the datasets and the one-operation bodies live here, in BENCH_SPEC's own
 * order, and the tools are thin drivers over [all]. The row labels the harness
 * parses are part of a workload's definition too, so a renamed row cannot get out
 * of step with the code that produced it.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

import org.sofabuffers.sofab.FlushSink
import org.sofabuffers.sofab.IStream
import org.sofabuffers.sofab.OStream
import org.sofabuffers.sofab.Visitor

/** One measurable workload. */
internal class Workload(
    /** The key `bench/run_callgrind.sh` drives it by. */
    val name: String,
    /** The row label BENCH_SPEC's output grammar prescribes. */
    val label: String,
    /** Encoded size of the message, the row's MB/s numerator. */
    val bytes: Int,
    /** Exactly one operation; the returned value is fed to a blackhole. */
    val body: () -> Long,
)

internal object Workloads {

    /** Elements in the `u64 array (1000)` dataset. */
    const val N = 1000

    /**
     * The one magic number in BENCH_SPEC's datasets: the `u64` array holds
     * `i * GOLDEN` and the blob payload its low byte, so both derive from the same
     * constant in every port.
     */
    const val GOLDEN = -0x61c8_8646_80b5_83ebL // 0x9E37_79B9_7F4A_7C15

    /** `blob 1MB` payload length, so MB/s reads against `MB = 1e6`. */
    const val BLOB_LEN = 1_000_000

    /**
     * Encoded size of the `blob 1MB` message: a one-byte header `(1 shl 3) or 2`, a
     * four-byte `fixlen_word` `(1000000 shl 3) or 3` and the payload. A cross-port
     * parity check, as the perf message's 170 is.
     */
    const val BLOB_ENCODED = BLOB_LEN + 5

    /**
     * Buffer size for the streaming `blob 1MB` rows — fixed by BENCH_SPEC at 4096
     * rather than taken from this port's own sizing, so the rows stay comparable
     * across languages. `MIN_OUTPUT_BUFFER` does not enter into it: it is at most
     * 20, so 4096 always satisfies it.
     */
    const val STREAM_BUFFER = 4096

    /**
     * One cycle of the composite string field: `a`, `ä`, `€` and U+1D11E — 1-, 2-,
     * 3- and 4-byte UTF-8, ten bytes in all. Written as escapes so the bytes cannot
     * depend on how a tool re-encodes this file.
     */
    const val COMPOSITE_TEXT = "aä€𝄞"

    /** Repetitions of [COMPOSITE_TEXT], giving 320 UTF-8 bytes. */
    const val COMPOSITE_REPEATS = 32

    /** Elements in the composite message's wrapper array. */
    const val COMPOSITE_ITEMS = 64

    /** The `typical` message's `u16` array, hoisted out of the op. */
    private val TYPICAL_ARRAY = shortArrayOf(10, 20, 30, 40)

    /** Decode sink that folds every value into a checksum (defeats elision). */
    internal class Checksum : Visitor {
        var acc: Long = 0

        override fun unsigned(id: Int, value: Long) {
            acc += value xor id.toLong()
        }

        override fun signed(id: Int, value: Long) {
            acc += value xor id.toLong()
        }

        override fun fp32Bits(id: Int, bits: Int) {
            acc += bits.toLong()
        }

        override fun fp64(id: Int, value: Double) {
            acc += value.toRawBits()
        }

        override fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
            acc += chunkLength.toLong()
        }

        override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
            acc += chunkLength.toLong()
        }
    }

    /**
     * Sink for the streaming `blob 1MB` row. BENCH_SPEC is explicit that it
     * **consumes and discards**: accumulating the bytes would charge the streaming
     * row a copy the one-shot row never pays, and I/O is not deterministic under
     * Callgrind. Folding one byte per call is the minimum that keeps the call from
     * being optimised away. It never calls `bufferSet`, so it is a *copying* sink
     * and the encoder resumes in the same buffer (CORELIB_PLAN §5.1) — which is
     * what the row measures.
     */
    internal class Discard : FlushSink {
        var acc: Byte = 0

        override fun flush(data: ByteArray, offset: Int, length: Int) {
            if (length > 0) {
                acc = (acc.toInt() xor data[offset].toInt()).toByte()
            }
        }
    }

    /**
     * `decode: composite skip-all`: a visitor that overrides nothing. In a push port
     * that is what "materialize nothing" means — the decoder still walks every
     * header, count and payload length, but no value reaches a destination. Its
     * distance from `decode: composite` is what not-decoding is worth.
     */
    private val SKIP_ALL = object : Visitor {}

    fun makeU64Array(): LongArray = LongArray(N) { it * GOLDEN }

    /** `b[i] = (i * GOLDEN) and 0xFF`, exactly 1,000,000 bytes. */
    fun makeBlob(): ByteArray = ByteArray(BLOB_LEN) { (it * GOLDEN).toByte() }

    /** `"item-0" .. "item-63"`, the composite wrapper array's elements. */
    fun makeItems(): Array<String> = Array(COMPOSITE_ITEMS) { "item-$it" }

    /** A small mixed message: scalars, a float, a short string, an array, a sequence. */
    fun encodeTypical(os: OStream) {
        os.writeUnsigned(1, 0xDEAD_BEEFL)
        os.writeSigned(2, -12345)
        os.writeBoolean(3, true)
        os.writeFp32(4, 3.14159f)
        os.writeString(5, "sofab")
        os.writeArrayUnsigned(6, TYPICAL_ARRAY)
        os.writeSequenceBeginLazy(7)
        os.writeUnsigned(1, 99)
        os.writeSigned(2, -7)
        os.writeSequenceEnd()
    }

    /**
     * The `composite` message: every encoder path the flat datasets miss.
     *
     * - id 1 — the suite's only **wrapper array** (MESSAGE_SPEC §5.1): one field
     *   header per element, element id = array index, so ids 0–15 take a one-byte
     *   header and 16–63 a two-byte one.
     * - id 2 — 320 UTF-8 bytes covering 1-, 2-, 3- and 4-byte sequences, so the §6.4
     *   validation pass runs on something that is not ASCII (and, in a UTF-16
     *   runtime such as this one, on a surrogate pair).
     * - id 3 — nesting at depth 3, so the lazy hold-back run grows past the single
     *   level `typical` and `perf` reach.
     * - id 4 — a struct equal to its declared default: every child is then equal to
     *   its own default and omitted, so the sequence never receives content and
     *   `writeSequenceEnd` discards the held-back frame (MESSAGE_SPEC §2). The one
     *   field in the suite the encoder must *not* write.
     * - id 130 — the suite's only two-byte field header, `(130 shl 3) or 0`.
     */
    fun encodeComposite(os: OStream, items: Array<String>, text: String) {
        os.writeSequenceBeginLazy(1)
        for (i in items.indices) {
            os.writeString(i, items[i])
        }
        os.writeSequenceEnd()

        os.writeString(2, text)

        os.writeSequenceBeginLazy(3)
        os.writeSequenceBeginLazy(1)
        os.writeSequenceBeginLazy(1)
        os.writeUnsigned(1, 7)
        os.writeSequenceEnd()
        os.writeSequenceEnd()
        os.writeSigned(2, -1)
        os.writeSequenceEnd()

        os.writeSequenceBeginLazy(4)
        os.writeSequenceEnd()

        os.writeUnsigned(130, 0xDEAD_BEEFL)
    }

    /** Encode once into a scratch buffer of [room] bytes -> the exact wire bytes. */
    private fun wireOf(room: Int, what: (OStream) -> Unit): ByteArray {
        val buf = ByteArray(room)
        val os = OStream(buf)
        what(os)
        return buf.copyOf(os.bytesUsed)
    }

    /**
     * Every workload, in the order BENCH_SPEC's output grammar lists it.
     *
     * All setup — building the datasets, encoding the decode inputs and allocating
     * the encode targets — happens here, so an operation is the codec call and
     * nothing else. `encode: blob 1MB passthrough` is BENCH_SPEC's one optional row
     * and is absent: this port implements no pass-through (CORELIB_PLAN §5.1 makes
     * it a MAY), so the row is omitted entirely rather than printed as a
     * placeholder.
     */
    fun all(): List<Workload> {
        val src = makeU64Array()
        val blob = makeBlob()
        val items = makeItems()
        val text = COMPOSITE_TEXT.repeat(COMPOSITE_REPEATS)

        val u64Wire = wireOf(N * 11 + 16) { it.writeArrayUnsigned(1, src) }
        val typWire = wireOf(256) { encodeTypical(it) }
        val blobWire = wireOf(BLOB_ENCODED) { it.writeBlob(1, blob) }
        val compWire = wireOf(4096) { encodeComposite(it, items, text) }

        // Reused encode targets: allocation belongs to the setup, not to the op.
        val encU64Out = ByteArray(N * 11 + 16)
        val encTypOut = ByteArray(256)
        val encBlobOut = ByteArray(BLOB_ENCODED) // sized by hand, per BENCH_SPEC
        val encBlobScratch = ByteArray(STREAM_BUFFER)
        val encCompOut = ByteArray(compWire.size)
        val discard = Discard()

        return listOf(
            Workload("encode_u64_array", "encode: u64 array (1000)", u64Wire.size) {
                val os = OStream(encU64Out)
                os.writeArrayUnsigned(1, src)
                os.bytesUsed.toLong()
            },
            Workload("encode_typical", "encode: typical message", typWire.size) {
                val os = OStream(encTypOut)
                encodeTypical(os)
                os.bytesUsed.toLong()
            },
            // The floor: one contiguous write into a buffer that holds the whole
            // message, with no sink and so no flush logic at all.
            Workload("encode_blob_oneshot", "encode: blob 1MB one-shot", BLOB_ENCODED) {
                val os = OStream(encBlobOut)
                os.writeBlob(1, blob)
                os.bytesUsed.toLong()
            },
            // The same bytes through ~245 flushes of a 4096-byte buffer. The gap to
            // the row above is the divisible-run path (CORELIB_PLAN §5.1) — the only
            // place in this suite where it runs at all.
            Workload("encode_blob_streaming", "encode: blob 1MB streaming", BLOB_ENCODED) {
                val os = OStream(encBlobScratch, 0, discard)
                os.writeBlob(1, blob)
                os.flush().toLong() + discard.acc
            },
            Workload("encode_composite", "encode: composite", compWire.size) {
                val os = OStream(encCompOut)
                encodeComposite(os, items, text)
                os.bytesUsed.toLong()
            },
            Workload("decode_u64_array", "decode: u64 array (1000)", u64Wire.size) {
                val c = Checksum()
                IStream().feed(u64Wire, c)
                c.acc
            },
            Workload("decode_typical", "decode: typical message", typWire.size) {
                val c = Checksum()
                IStream().feed(typWire, c)
                c.acc
            },
            // Fed in 4096-byte chunks: the streaming decode surface, not one feed of
            // a megabyte.
            Workload("decode_blob", "decode: blob 1MB", blobWire.size) {
                val c = Checksum()
                val input = IStream()
                var off = 0
                while (off < blobWire.size) {
                    input.feed(blobWire, off, minOf(STREAM_BUFFER, blobWire.size - off), c)
                    off += STREAM_BUFFER
                }
                c.acc
            },
            Workload("decode_composite", "decode: composite", compWire.size) {
                val c = Checksum()
                IStream().feed(compWire, c)
                c.acc
            },
            Workload("decode_composite_skip", "decode: composite skip-all", compWire.size) {
                val input = IStream()
                input.feed(compWire, SKIP_ALL)
                input.status.ordinal.toLong()
            },
        )
    }
}
