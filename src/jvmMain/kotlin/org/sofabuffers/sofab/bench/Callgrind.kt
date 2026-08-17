/*
 * SofaBuffers Kotlin — machine-independent instruction cost (Callgrind Ir/op).
 *
 * Companion to Bench.kt (throughput) and Perf.kt (per-op timing). Reports
 * instructions retired per operation: unlike wall-clock or cycle counts, an
 * instruction count is deterministic and independent of the host's clock speed and
 * scheduler, so the numbers compare across machines — and against the
 * C/C++/Rust/Go/Python/TypeScript tools, since the workloads, ids and values all
 * come from Workloads.kt.
 *
 * The JVM has no native `run_<workload>` symbol Callgrind could toggle on (the hot
 * code is JIT-compiled at runtime), so bench/run_callgrind.sh uses BENCH_SPEC's
 * two-rep-count subtraction, as the Python, TypeScript and Java ports do:
 *
 *     Ir/op = ( Ir(R2) - Ir(R1) ) / ( R2 - R1 )
 *
 * which cancels *all* fixed cost exactly — JVM startup, class loading, JIT
 * compilation and the one-time setup — leaving the pure per-op cost. For the
 * subtraction to be clean the two runs must differ *only* in the measured rep
 * count, so this program does a fixed warmup (independent of `reps`) that drives
 * the hot methods to their final compiled tier before the measured loop begins;
 * run_callgrind.sh pins compilation and disables GC so nothing else varies.
 *
 * Usage:  <workload> <reps>   — prints `bytes=<n>` on stderr.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

/**
 * Warmup operations per run, independent of `reps` so it cancels in the
 * subtraction.
 *
 * Its job is to put every measured op in *compiled* code: the script pins one
 * compile tier at `-XX:CompileThreshold=2000`, so a warmup below that would
 * measure the interpreter — and, worse, a warmup close to it would let the compile
 * land inside the high-rep run only, leaving the subtraction to charge one op with
 * the whole compilation. Everything here is comfortably above the threshold; the
 * bench-spec test checks that against the number the script actually passes.
 *
 * The `blob 1MB` rows get the smaller figure because they carry a megabyte of
 * copying per op, which is slow under Callgrind, and 2500 ops already clear the
 * threshold with room to spare. Override with `-Dsofab.warmup=`.
 */
internal fun warmupFor(workload: String): Int =
    Integer.getInteger("sofab.warmup", if (workload.contains("blob")) 2_500 else 5_000)

/**
 * One rep-mode run, returning the exit status instead of taking the JVM down, so a
 * caller in the same JVM (this tool's own test) can drive every workload and check
 * the rejection path.
 */
internal fun runCallgrind(args: Array<String>): Int {
    if (args.size < 2) {
        System.err.println("usage: Callgrind <workload> <reps>")
        return 2
    }
    val name = args[0]
    val reps = args[1].toInt()

    val workload = Workloads.all().firstOrNull { it.name == name }
    if (workload == null) {
        System.err.println("unknown workload: $name")
        return 2
    }

    var sink = 0L
    // Fixed warmup (cancels in the subtraction), then the measured ops.
    repeat(warmupFor(name)) { sink += workload.body() }
    repeat(reps) { sink += workload.body() }
    Loop.blackhole = sink

    // stderr feeds the size column; the sink keeps the work observable.
    System.err.println("bytes=${workload.bytes} sink=${Loop.blackhole} reps=$reps")
    return 0
}

public fun main(args: Array<String>) {
    val status = runCallgrind(args)
    if (status != 0) {
        kotlin.system.exitProcess(status)
    }
}
