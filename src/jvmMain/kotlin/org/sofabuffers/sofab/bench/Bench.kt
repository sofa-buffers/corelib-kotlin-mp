/*
 * SofaBuffers Kotlin — throughput benchmark (MB/s, CPU time).
 *
 * Mirror of the C, C++, Rust and Java tools: encode / decode throughput for
 * BENCH_SPEC's workload set — a 1000-element u64 array, a small "typical" mixed
 * message, an unbounded 1 MB blob and the "composite" message that reaches the
 * paths the flat datasets miss. Each workload runs in a ~1 s CPU-time loop and
 * reports MB/s in the same table layout as the other ports, so the
 * implementations can be compared directly. MB = 1e6 bytes.
 *
 * **Read the blob 1MB rows against each other, not against the others.** Five
 * bytes of that message are metadata and a million are payload, so its MB/s is
 * this machine's memory bandwidth rather than a statement about the corelib — and
 * the streamed row can even come out ahead of the one-shot one, since a 4 KiB
 * window stays in L1 while a one-shot encode writes a megabyte out to memory. The
 * flush machinery's own cost (CORELIB_PLAN §5.1) does not survive that:
 * bench/run_callgrind.sh is what measures it.
 *
 * The two `composite` decode rows carry a JVM-specific caveat of their own: they
 * share one process, so the visitor call sites inside IStream see both sinks and
 * neither row runs monomorphic. `skip-all` is the cheaper of the two in Ir/op,
 * where each workload gets a JVM to itself, and that is where to read it.
 *
 * Run with:  ./gradlew bench
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

/** Entry point: `./gradlew bench`. */
public fun main(): Unit = benchMain()

/**
 * The tool's body, named so its own test can drive it in-process (two top-level
 * `main`s in one package cannot both be called from Kotlin source).
 */
internal fun benchMain() {
    if (!Loop.supported()) {
        System.err.println("bench: thread CPU time not supported on this JVM")
        return
    }

    val workloads = Workloads.all()
    val mbs = DoubleArray(workloads.size)
    for (i in workloads.indices) {
        val w = workloads[i]
        mbs[i] = Loop.run(w.body).megabytesPerSecond(w.bytes)
    }

    println("=== SofaBuffers Kotlin throughput (CPU time, MB/s) ===")
    println(row("Workload", "MB/s"))
    println(row("--------", "----"))
    for (i in workloads.indices) {
        println(row(workloads[i].label, fixed2(mbs[i])))
    }
    println()
    println("MB = 1e6 bytes. ~1s CPU-time loop per workload.")
    println("blob 1MB is bandwidth-bound: read one-shot vs streaming, not either alone.")
    if (Loop.blackhole == 42L) {
        print("") // keep the blackhole observably live
    }
}

/** BENCH_SPEC's row shape: label left-justified to 26, value right-justified to 12. */
internal fun row(label: String, value: String): String =
    label.padEnd(26) + " " + value.padStart(12)

/** Two decimals, locale-independent — the harness parses these with a regex. */
internal fun fixed2(v: Double): String {
    val scaled = kotlin.math.round(v * 100.0).toLong()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
