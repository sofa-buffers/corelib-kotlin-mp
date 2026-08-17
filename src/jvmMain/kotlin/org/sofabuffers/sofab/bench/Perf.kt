/*
 * SofaBuffers Kotlin — per-operation cost benchmark.
 *
 * Mirror of the C, C++, Rust and Java tools: encodes and decodes the identical
 * message (same field ids, types and values) through the streaming API and prints
 * the same report, so the implementations can be compared directly. The message
 * encodes to 170 bytes on every implementation — BENCH_SPEC's parity check: a
 * different `message size` here means the encoding diverges.
 *
 * Two metrics per workload:
 *   1. cycles/op — cost of the code itself, read off a hardware cycle counter. The
 *      JVM exposes no portable cycle counter, so this line reports that it is
 *      unavailable (the C/Rust tools print the same fallback off-arch).
 *   2. throughput MB/s + CPU time/op — a "speedtest" for this machine, derived
 *      from *thread CPU time* (not wall-clock). MB = 1e6 bytes.
 *
 * For a CPU-speed-independent figure on this host, run bench/run_callgrind.sh
 * (instructions retired per op). Workloads, timing rules and output grammar are
 * specified in BENCH_SPEC.md; the timed loop itself is Loop.kt, shared with Bench.
 *
 * Run with:  ./gradlew perf
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

import org.sofabuffers.sofab.IStream
import org.sofabuffers.sofab.OStream
import org.sofabuffers.sofab.Visitor

// --- message under test (identical to perf.c / perf.rs) ---------------------
internal const val PERF_STRING = "perf-benchmark-message"
private val PERF_SAMPLES = intArrayOf(
    1_000_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000, 7_000_000, 8_000_000,
)
private val PERF_DELTAS = intArrayOf(
    -100_000, -200_000, -300_000, -400_000, -500_000, -600_000, -700_000, -800_000,
)
private val PERF_FP64 = doubleArrayOf(3.14159265, 6.28318530, 9.42477795, 12.56637060)

internal fun perfEncode(buf: ByteArray): Int {
    val os = OStream(buf)
    os.writeUnsigned(1, 0xDEAD_BEEFL)
    os.writeSigned(2, -12345)
    os.writeUnsigned(3, 0x0123_4567_89AB_CDEFL)
    os.writeSigned(4, -5_000_000_000_000L)
    os.writeBoolean(5, true)
    os.writeFp32(6, 3.14159f)
    os.writeFp64(7, 2.718281828459045)
    os.writeString(8, PERF_STRING)
    os.writeArrayUnsigned(9, PERF_SAMPLES)
    os.writeArraySigned(10, PERF_DELTAS)
    os.writeArrayFp64(11, PERF_FP64)
    os.writeSequenceBeginLazy(12)
    os.writeUnsigned(1, 99)
    os.writeSigned(2, -7)
    os.writeSequenceEnd()
    return os.bytesUsed
}

/** Decode sink: folds every value into a checksum and captures id 1 / id 8. */
internal class PerfOut : Visitor {
    var acc: Long = 0
    private var depth = 0
    var u32Top: Long = 0
    val strBuf: ByteArray = ByteArray(32)
    var strLen: Int = 0

    override fun unsigned(id: Int, value: Long) {
        acc += value xor id.toLong()
        if (depth == 0 && id == 1) {
            u32Top = value and 0xFFFF_FFFFL
        }
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
        if (id == 8 && offset < strBuf.size) {
            val end = minOf(offset + chunkLength, strBuf.size)
            data.copyInto(strBuf, offset, chunkOffset, chunkOffset + (end - offset))
            strLen = end
        }
    }

    override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        acc += chunkLength.toLong()
    }

    override fun sequenceBegin(id: Int) {
        depth++
    }

    override fun sequenceEnd() {
        depth--
    }
}

internal fun perfDecode(buf: ByteArray, len: Int, out: PerfOut) {
    IStream().feed(buf, 0, len, out)
}

private fun report(what: String, r: Loop.Result, bytes: Int) {
    println()
    println("--- perf: $what ---")
    println("  iterations    : ${r.iterations}")
    println("  message size  : $bytes bytes")
    println("  cycles/op     : (hardware cycle counter unavailable on the JVM)")
    println("  CPU time/op   : ${fixed1(r.nanosPerOp())} ns  (thread CPU time, not wall-clock)")
    println("  throughput    : ${fixed1(r.megabytesPerSecond(bytes))} MB/s  (speedtest, MB = 1e6 bytes)")
}

/** One decimal, locale-independent. */
internal fun fixed1(v: Double): String {
    val scaled = kotlin.math.round(v * 10.0).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

/** Entry point: `./gradlew perf`. */
public fun main(): Unit = perfMain()

/** The tool's body, named so its own test can drive it in-process. */
internal fun perfMain() {
    if (!Loop.supported()) {
        System.err.println("perf: thread CPU time not supported on this JVM")
        return
    }

    val buffer = ByteArray(512)
    val msgSize = perfEncode(buffer)

    println("=== SofaBuffers Kotlin per-op cost (cycles/op + throughput MB/s) ===")

    val enc = Loop.run { perfEncode(buffer).toLong() }
    report("serialize (stream API)", enc, msgSize)

    // Self-check that the decode actually reproduced the data.
    val check = PerfOut()
    perfDecode(buffer, msgSize, check)
    val expectString = PERF_STRING.encodeToByteArray()
    if (check.u32Top != 0xDEAD_BEEFL ||
        !check.strBuf.copyOf(check.strLen).contentEquals(expectString)
    ) {
        System.err.println("perf: decode self-check failed")
        kotlin.system.exitProcess(1)
    }

    // The per-op PerfOut allocation stays inside the body — it is part of the op,
    // as the decode destination is in any real caller.
    val dec = Loop.run {
        val o = PerfOut()
        perfDecode(buffer, msgSize, o)
        o.acc
    }
    report("deserialize (stream API)", dec, msgSize)

    println()
    println("cycles/op tracks code cost; MB/s is this machine's throughput.")
    if (Loop.blackhole == 42L) {
        print("") // keep the blackhole observably live
    }
}
