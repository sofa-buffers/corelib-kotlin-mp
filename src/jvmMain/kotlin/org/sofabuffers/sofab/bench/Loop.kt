/*
 * SofaBuffers Kotlin — the measurement loop the timed tools share.
 *
 * BENCH_SPEC's "Timing" section is one rule set for both timed tools: warm up
 * first, then run a ~1 s loop against a *process/thread CPU* clock, never
 * wall-clock, and derive MB/s as message_bytes * iterations / cpu_seconds / 1e6.
 * The two tools differ only in what they print, so the loop lives here once.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

import java.lang.management.ManagementFactory

internal object Loop {

    private val THREADS = ManagementFactory.getThreadMXBean()

    /**
     * Length of the reportable measurement loop, in CPU seconds. BENCH_SPEC says
     * ~1 s, which is the default; the tools' own test shrinks it to a millisecond,
     * because a check on the *shape* of the output would otherwise spend ten seconds
     * per tool measuring numbers it does not look at.
     *
     * The derived budgets scale with it: a batch is a hundredth of the loop, so the
     * clock read that ends it stays a rounding error against the work it timed
     * (`getCurrentThreadCpuTime()` costs on the order of a microsecond, and reading
     * it once per operation would time the clock rather than the codec), and the
     * warmup gets a quarter of it.
     */
    fun seconds(): Double = System.getProperty("sofab.bench.seconds", "1.0").toDouble()

    /** Warmup operation cap: past this the JIT has nothing left to learn. */
    private const val WARMUP_OPS = 200_000

    /** Consumed after the loops so the JIT cannot elide the measured work. */
    var blackhole: Long = 0

    /** Whether this JVM can time a thread's CPU consumption at all. */
    fun supported(): Boolean {
        if (!THREADS.isCurrentThreadCpuTimeSupported) {
            return false
        }
        THREADS.isThreadCpuTimeEnabled = true
        return true
    }

    /** Thread CPU time in seconds (not wall-clock). */
    fun cpuNow(): Double = THREADS.currentThreadCpuTime / 1e9

    /** One measured run: [iterations] operations in [seconds] CPU seconds. */
    class Result(val iterations: Long, val seconds: Double) {
        fun nanosPerOp(): Double = seconds / iterations * 1e9

        fun megabytesPerSecond(bytes: Int): Double = bytes.toDouble() * iterations / seconds / 1e6
    }

    /** Warm up, then run [body] for ~[seconds] of CPU time. */
    fun run(body: () -> Long): Result {
        val budget = seconds()
        warmup(body, budget / 4.0)
        val batch = calibrate(body, budget / 100.0)
        var iterations = 0L
        var acc = 0L
        val t0 = cpuNow()
        var elapsed: Double
        do {
            for (k in 0 until batch) {
                acc += body()
            }
            iterations += batch
            elapsed = cpuNow() - t0
        } while (elapsed < budget)
        blackhole += acc
        return Result(iterations, elapsed)
    }

    /**
     * Drive the hot methods to their final JIT tier. Bounded by *time* as well as by
     * [WARMUP_OPS] because the workloads span four orders of magnitude per op:
     * 200 000 operations is a warmup for the typical message and minutes of memory
     * bandwidth for the 1 MB blob.
     */
    private fun warmup(body: () -> Long, budget: Double) {
        val deadline = cpuNow() + budget
        var acc = 0L
        for (i in 0 until WARMUP_OPS) {
            acc += body()
            if ((i and 0x3F) == 0x3F && cpuNow() >= deadline) {
                break
            }
        }
        blackhole += acc
    }

    /** Grow a batch until it spans [budget] CPU seconds. */
    private fun calibrate(body: () -> Long, budget: Double): Long {
        var acc = 0L
        var batch = 1L
        while (true) {
            val t0 = cpuNow()
            for (k in 0 until batch) {
                acc += body()
            }
            if (cpuNow() - t0 >= budget) {
                blackhole += acc
                return batch
            }
            batch *= 2
        }
    }
}
