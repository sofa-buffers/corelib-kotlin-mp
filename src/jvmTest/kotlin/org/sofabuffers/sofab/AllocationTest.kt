/*
 * SofaBuffers Kotlin Multiplatform — the §6.6.4 allocation measurement.
 *
 * CORELIB_PLAN §6.6.4 makes conformance depend on two things, and a source read
 * is only the first: "an allocation count, or the heap high-water mark, over a
 * complete encode and a complete decode, **measured after the codec's one-time
 * construction**", which on a runtime that does not box the codec's values MUST
 * be zero. This file is that measurement, wired into the suite so a regression
 * fails CI rather than waiting for the next audit.
 *
 * **The measurement is a difference, not an absolute.** §6.6 permits the
 * constructor to allocate the codec's fixed-size state and forbids everything
 * after it, so each test below runs two loops that construct the same codec and
 * differ only in whether they then encode or decode. Their allocation counts must
 * be equal. A steady-state loop over a *reused* codec reads zero either way — that
 * is what let a lazily-grown pending run sit in this tree unnoticed — so the
 * reuse tests here pin the steady state and the difference tests pin the rule.
 *
 * It lives in jvmTest because the JVM is the one target of the four whose runtime
 * counts allocation per thread. Kotlin/Native and Kotlin/JS offer no comparable
 * facility, which the README states; the codec is one commonMain source set, so
 * what is measured here is the same code those targets run.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean

    /** Bytes this thread has allocated since it started. */
    private fun allocated(): Long = threads.currentThreadAllocatedBytes

    /**
     * Bytes allocated by [REPS] runs of [body], after [WARMUP] runs have settled
     * the JIT. Everything the measured region touches is constructed before the
     * first call, so what is left is the codec's own allocation and nothing else.
     */
    private inline fun measure(reps: Int, body: () -> Unit): Long {
        var w = 0
        while (w < WARMUP) {
            body()
            w++
        }
        val before = allocated()
        var i = 0
        while (i < reps) {
            body()
            i++
        }
        return allocated() - before
    }

    /** Folds every value it is handed, so nothing can be optimised away. */
    private class Fold : Visitor {
        var acc: Long = 0

        override fun unsigned(id: Int, value: Long) {
            acc += value
        }

        override fun signed(id: Int, value: Long) {
            acc += value
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

    @Test
    fun theRuntimeCanCountAllocation() {
        assertTrue(
            threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "per-thread allocation counting is what this file measures with",
        )
        // A control: the counter does move when something is allocated, so a zero
        // below is a real zero rather than a dead instrument.
        val before = allocated()
        escape = ByteArray(1 shl 16)
        assertTrue(allocated() - before >= (1 shl 16), "the counter tracks a known allocation")
    }

    // --- the rule: nothing after construction --------------------------------

    @Test
    fun aFreshEncoderAllocatesOnlyInItsConstructor() {
        // Two loops, both constructing an OStream; only the second encodes. The
        // message reaches MAX_DEPTH nesting and every writer, so a pending run
        // grown on the write path — or any other per-field allocation — shows up as
        // a difference. The stream escapes in both loops so escape analysis cannot
        // delete the construction from one and not the other.
        val buf = ByteArray(4096)
        val construct = measure(REPS) { escape = OStream(buf) }
        val encode = measure(REPS) {
            val os = OStream(buf)
            escape = os
            writeEverything(os)
        }
        assertEquals(
            construct,
            encode,
            "§6.6: write/flush allocate nothing; only the constructor may",
        )
    }

    @Test
    fun aFreshDecoderAllocatesOnlyInItsConstructor() {
        // The decode is driven one byte per feed, so every field goes through the
        // resumable machine — including the fp32/fp64 landing zone, the site a
        // whole-message decode never reaches.
        val wire = encodeSample()
        val fold = Fold()
        val construct = measure(REPS / 8) { escape = IStream() }
        val decode = measure(REPS / 8) {
            val input = IStream()
            escape = input
            var i = 0
            while (i < wire.size) {
                input.feed(wire, i, 1, fold)
                i++
            }
        }
        assertEquals(
            construct,
            decode,
            "§6.6: feed allocates nothing; only the constructor may",
        )
    }

    // --- the steady state: zero on a reused codec ----------------------------

    @Test
    fun aCompleteEncodeAllocatesNothingAfterConstruction() {
        val buf = ByteArray(4096)
        val os = OStream(buf)
        val bytes = measure(REPS) {
            os.reset(buf)
            writeEverything(os)
        }
        assertEquals(0, bytes, "§6.6.4: a complete encode allocates zero after construction")
    }

    @Test
    fun aCompleteDecodeAllocatesNothingAfterConstruction() {
        val wire = encodeSample()
        val input = IStream()
        val fold = Fold()
        val bytes = measure(REPS) {
            input.reset()
            input.feed(wire, 0, wire.size, fold)
        }
        assertEquals(0, bytes, "§6.6.4: a complete decode allocates zero after construction")
    }

    @Test
    fun aByteAtATimeDecodeAllocatesNothingAfterConstruction() {
        val wire = encodeSample()
        val input = IStream()
        val fold = Fold()
        val bytes = measure(REPS / 8) {
            input.reset()
            var i = 0
            while (i < wire.size) {
                input.feed(wire, i, 1, fold)
                i++
            }
        }
        assertEquals(0, bytes, "§6.6.2: the scalar landing zone is construction-sized")
    }

    @Test
    fun theMeasurementDoesNotGrowWithTheMessage() {
        // §6.6's own test — "can a sender make this allocation bigger by sending
        // different bytes?" — asked directly: a payload four orders of magnitude
        // larger costs the decoder the same nothing, and so does a count the sender
        // announced but never delivered.
        val smallWire = blobWire(10)
        val bigWire = blobWire(100_000)
        // id 1, ARRAY_UNSIGNED, count 1,000,000, and not one element behind it: the
        // decoder announces the count and waits, sizing nothing from it.
        val hostile = byteArrayOf(0x0b, 0xc0.toByte(), 0x84.toByte(), 0x3d)

        val input = IStream()
        val fold = Fold()

        val smallBytes = measure(REPS) {
            input.reset()
            input.feed(smallWire, 0, smallWire.size, fold)
        }
        val bigBytes = measure(REPS / 100) {
            input.reset()
            input.feed(bigWire, 0, bigWire.size, fold)
        }
        val hostileBytes = measure(REPS) {
            input.reset()
            input.feed(hostile, 0, hostile.size, fold)
        }

        assertEquals(0, smallBytes, "a ten-byte payload costs the codec nothing")
        assertEquals(0, bigBytes, "and a hundred-kilobyte one costs exactly the same nothing")
        assertEquals(0, hostileBytes, "an undelivered count sizes nothing")
        assertEquals(DecodeStatus.INCOMPLETE, input.status, "the hostile count is still awaited")
    }

    // --- fixtures ------------------------------------------------------------

    /**
     * Every writer the encoder has, plus nesting to `MAX_DEPTH`: the hold-back run
     * §6.0.1 requires to be construction-sized is reached only by the last part,
     * and the wire bytes are identical whether it was grown or not.
     */
    private fun writeEverything(os: OStream) {
        os.writeUnsigned(1, -1L)
        os.writeSigned(2, -12_345L)
        os.writeBoolean(3, true)
        os.writeFp32(4, 3.14159f)
        os.writeFp64(5, 2.718281828459045)
        os.writeString(6, "the quick brown fox jumps over the lazy dog")
        os.writeString(7, "aä€𝄞")
        os.writeArrayUnsigned(8, ARRAY)
        os.writeArrayFp64(9, FP64S)
        os.writeBlob(10, BLOB)
        var d = 0
        while (d < Sofab.MAX_DEPTH - 1) {
            os.writeSequenceBeginLazy(1)
            d++
        }
        os.writeUnsigned(2, 7)
        while (d > 0) {
            os.writeSequenceEnd()
            d--
        }
    }

    private fun encodeSample(): ByteArray {
        val buf = ByteArray(1024)
        val os = OStream(buf)
        os.writeUnsigned(1, -1L)
        os.writeSigned(2, -12_345L)
        os.writeFp32(3, 3.14159f)
        os.writeFp64(4, 2.718281828459045)
        os.writeString(5, "the quick brown fox jumps over the lazy dog")
        os.writeArrayUnsigned(6, ARRAY)
        os.writeArrayFp64(7, FP64S)
        os.writeBlob(8, BLOB)
        os.writeSequenceBeginLazy(9)
        os.writeSequenceBeginLazy(1)
        os.writeUnsigned(1, 99)
        os.writeSequenceEnd()
        os.writeSequenceEnd()
        return buf.copyOf(os.bytesUsed)
    }

    /** A one-field message carrying a blob of [len] bytes. */
    private fun blobWire(len: Int): ByteArray {
        val buf = ByteArray(len + 16)
        val os = OStream(buf)
        os.writeBlob(1, ByteArray(len) { it.toByte() })
        return buf.copyOf(os.bytesUsed)
    }

    private companion object {
        const val WARMUP = 20_000
        const val REPS = 2_000

        private val ARRAY = longArrayOf(1, 300, 70_000, Long.MIN_VALUE)
        private val FP64S = doubleArrayOf(1.5, 2.5, 3.5)
        private val BLOB = ByteArray(37) { it.toByte() }

        /**
         * Keeps a measured allocation reachable, so the JIT cannot delete a
         * construction from one loop of a difference test and not the other.
         */
        @JvmStatic
        @Volatile
        var escape: Any? = null
    }
}
