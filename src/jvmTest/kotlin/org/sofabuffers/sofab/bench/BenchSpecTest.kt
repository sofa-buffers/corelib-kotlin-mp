/*
 * SofaBuffers Kotlin — the benchmark tools against BENCH_SPEC.
 *
 * BENCH_SPEC is the cross-language contract for `bench` / `perf` /
 * `run_callgrind.sh`: the same workloads on the same data, printed in a grammar a
 * central harness parses into the comparison tables. Two things can silently break
 * that, and neither is visible from inside the library:
 *
 *   * a **dataset** that drifts — the encoded sizes (the perf message's 170 bytes,
 *     the blob message's 1,000,005, the composite message's 956) are the spec's own
 *     parity checks, and a port whose numbers differ is not encoding what the other
 *     ports encode;
 *   * a **row** that goes missing or gets misspelled — the harness matches row
 *     labels by regex, so a renamed or absent row is dropped from the table rather
 *     than reported.
 *
 * So the tools are run here over a millisecond-scale loop, not the reportable ~1 s
 * one, and their output is matched against the spec's own regexes. This is a format
 * and dataset test, never a performance assertion: no timing figure is checked.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchSpecTest {

    // --- the harness's own regexes (BENCH_SPEC "Output grammar") -------------

    private val throughputHeader = Regex("=== SofaBuffers (.+?) throughput")
    private val perOpHeader = Regex("=== SofaBuffers (.+?) per-op")
    private val rowPattern = Regex(
        "^(encode|decode):\\s+(u64 array \\(1000\\)|typical message|blob 1MB one-shot" +
            "|blob 1MB streaming|blob 1MB passthrough|blob 1MB|composite skip-all" +
            "|composite)\\s+([\\d.]+)$",
    )

    /**
     * Every row BENCH_SPEC requires, in the order it lists them. The optional
     * `blob 1MB passthrough` row is absent on purpose: this port implements no
     * pass-through, and BENCH_SPEC says such a port omits the row rather than
     * printing a placeholder.
     */
    private val requiredRows = listOf(
        "encode: u64 array (1000)",
        "encode: typical message",
        "encode: blob 1MB one-shot",
        "encode: blob 1MB streaming",
        "encode: composite",
        "decode: u64 array (1000)",
        "decode: typical message",
        "decode: blob 1MB",
        "decode: composite",
        "decode: composite skip-all",
    )

    /** A measurement loop short enough that these tests check shape, not speed. */
    private val fastLoop = "0.001"

    @AfterTest
    fun clearLoopOverride() {
        System.clearProperty("sofab.bench.seconds")
    }

    // --- the workload set ----------------------------------------------------

    @Test
    fun theWorkloadSetIsExactlyTheRowsBenchSpecRequires() {
        assertEquals(
            requiredRows,
            Workloads.all().map { it.label },
            "the tools measure whatever this list holds, so it is the row set",
        )
    }

    // --- datasets ------------------------------------------------------------

    @Test
    fun theU64ArrayIsTheLiteralFormula() {
        val src = Workloads.makeU64Array()
        assertEquals(1000, src.size)
        assertContentEquals(LongArray(1000) { it * -0x61c8_8646_80b5_83ebL }, src)
    }

    @Test
    fun theBlobPayloadIsTheLiteralFormula() {
        val blob = Workloads.makeBlob()
        assertEquals(1_000_000, blob.size)
        assertContentEquals(ByteArray(1_000_000) { (it * -0x61c8_8646_80b5_83ebL).toByte() }, blob)
    }

    @Test
    fun theCompositeStringCoversEveryUtf8Width() {
        // 1-, 2-, 3- and 4-byte sequences; ten bytes per cycle, 320 in the field.
        val bytes = Workloads.COMPOSITE_TEXT.encodeToByteArray()
        assertEquals(10, bytes.size)
        assertEquals(320, Workloads.COMPOSITE_TEXT.repeat(Workloads.COMPOSITE_REPEATS).encodeToByteArray().size)
        assertEquals(64, Workloads.makeItems().size)
        assertEquals("item-0", Workloads.makeItems().first())
        assertEquals("item-63", Workloads.makeItems().last())
    }

    // --- encoded sizes: BENCH_SPEC's cross-port parity checks -----------------

    @Test
    fun theEncodedSizesAreTheParityChecks() {
        val byName = Workloads.all().associate { it.name to it.bytes }
        assertEquals(1_000_005, byName["encode_blob_oneshot"], "the blob message is 1,000,005 bytes")
        assertEquals(1_000_005, byName["decode_blob"])
        assertEquals(956, byName["encode_composite"], "the composite message is 956 bytes")
        assertEquals(37, byName["encode_typical"], "the typical message is ~37 bytes")
        assertEquals(byName["encode_u64_array"], byName["decode_u64_array"])
    }

    @Test
    fun thePerfMessageIsExactly170Bytes() {
        // "if your perf prints a different message size, your encoding diverges."
        assertEquals(170, perfEncode(ByteArray(512)))
    }

    // --- output grammar ------------------------------------------------------

    @Test
    fun theThroughputTableMatchesTheGrammar() {
        val out = capture { benchMain() }
        val lines = out.lines()
        assertTrue(throughputHeader.containsMatchIn(lines.first()), "header: ${lines.first()}")
        assertEquals("Kotlin", throughputHeader.find(lines.first())!!.groupValues[1].trim())
        val rows = lines.mapNotNull { rowPattern.find(it.trimEnd()) }
            .map { "${it.groupValues[1]}: ${it.groupValues[2]}" }
        assertEquals(requiredRows, rows, "every required row parses, in order")
        assertTrue(out.contains("MB = 1e6 bytes"), "the trailing convention line is part of the grammar")
    }

    @Test
    fun thePerOpReportMatchesTheGrammar() {
        val out = capture { perfMain() }
        assertTrue(perOpHeader.containsMatchIn(out), "the per-op header is what the harness keys on")
        assertTrue(out.contains("--- perf: serialize (stream API) ---"))
        assertTrue(out.contains("--- perf: deserialize (stream API) ---"))
        assertTrue(Regex("message size  : 170 bytes").containsMatchIn(out))
        // No hardware cycle counter on the JVM: the spec's parenthetical fallback.
        assertTrue(Regex("cycles/op\\s+: \\(").containsMatchIn(out))
        assertTrue(Regex("CPU time/op   : [\\d.]+ ns").containsMatchIn(out))
        assertTrue(Regex("throughput    : [\\d.]+ MB/s").containsMatchIn(out))
        assertTrue(out.trimEnd().endsWith("cycles/op tracks code cost; MB/s is this machine's throughput."))
    }

    @Test
    fun theRowFormattingIsTheSpecsColumnLayout() {
        // Label left-justified to 26 chars, value right-justified to 12 with 2 decimals.
        val line = row("encode: typical message", fixed2(1234.5))
        assertEquals(39, line.length)
        assertTrue(line.endsWith("      1234.50"), line)
        assertEquals("0.00", fixed2(0.0))
        assertEquals("0.10", fixed2(0.1))
        assertEquals("3.1", fixed1(3.14))
    }

    // --- the instruction-cost tool -------------------------------------------

    @Test
    fun everyWorkloadIsDrivenByTheCallgrindScript() {
        val script = File("bench/run_callgrind.sh").readText()
        for (w in Workloads.all()) {
            assertTrue(script.contains(w.name), "run_callgrind.sh does not drive ${w.name}")
            assertTrue(script.contains(w.label), "run_callgrind.sh has no label for ${w.name}")
        }
    }

    @Test
    fun theWarmupClearsThePinnedCompileThreshold() {
        // The script pins -XX:CompileThreshold=2000 so both rep counts measure the
        // same compiled code; a warmup at or below it would let the compile land
        // inside one run only and charge the subtraction with it.
        val script = File("bench/run_callgrind.sh").readText()
        val threshold = Regex("CompileThreshold=(\\d+)").find(script)!!.groupValues[1].toInt()
        assertTrue(warmupFor("encode_typical") > threshold, "cheap workloads")
        assertTrue(warmupFor("encode_blob_oneshot") > threshold, "blob workloads")
    }

    @Test
    fun theCallgrindEntryPointRunsEveryWorkloadAndRejectsUnknownOnes() {
        System.setProperty("sofab.warmup", "1")
        try {
            for (w in Workloads.all()) {
                assertEquals(0, runCallgrind(arrayOf(w.name, "1")), w.name)
            }
            assertEquals(2, runCallgrind(arrayOf("no_such_workload", "1")))
            assertEquals(2, runCallgrind(arrayOf("encode_typical")))
        } finally {
            System.clearProperty("sofab.warmup")
        }
    }

    /** Run [body] with a millisecond loop, capturing what it prints. */
    private fun capture(body: () -> Unit): String {
        System.setProperty("sofab.bench.seconds", fastLoop)
        val buffer = ByteArrayOutputStream()
        val saved = System.out
        try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            body()
        } finally {
            System.setOut(saved)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
