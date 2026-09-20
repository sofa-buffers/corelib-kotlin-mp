/*
 * SofaBuffers Kotlin Multiplatform - the shared `boolean_tolerant` block
 * (CORELIB_PLAN §4.4).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fifth top-level block of the shared file: **canonical on encode, tolerant on
 * decode** (CORELIB_PLAN §4.4).
 *
 * ```
 * 00 80 02
 * ^^ id 0, wire type 0 (unsigned varint) - a boolean has no wire type of its own
 *    ^^^^^ varint 256
 * ```
 *
 * An encoder MUST write `true` as `1`; a decoder MUST read **every** value other
 * than `0` as `true`. Such a value is not `INVALID` — §4.4 lifts the width bound a
 * type carries, which is exactly what separates a `bool` from an `enum` or a
 * `bitfield` (MESSAGE_SPEC §1) — it is *normalized away*, and a re-encode emits `1`.
 *
 * **Why the positive `vectors` block cannot reach this.** Those bytes are produced
 * by replaying `fields[]` through a conforming encoder, and a conforming encoder
 * never emits a non-canonical boolean. `2`, `256` and `2^64-1` at a boolean position
 * only ever arrive from *someone else's* encoder, which is why the corpus carries
 * them hand-authored in a block of their own.
 *
 * **Three defects, three assertions.** Each half of the run catches one, and no two
 * catch the same one:
 *
 * | defect | caught by |
 * |---|---|
 * | answers `INVALID` for `256`, reading a boolean as a one-byte type | the outcome |
 * | masks the varint to the destination width before the zero-test, so `256` becomes `false` | the decoded values |
 * | stores the raw `2`, which is `true` under every truthiness test | the **re-encode**, which must emit `1` |
 *
 * A runner that asserts only the outcome certifies a decoder that violates §4.4 in
 * two of the three ways; one that adds a *truthy* value check still certifies the
 * third. That is why every case is decoded **and** written back out here.
 *
 * **Which surface this port is asked with.** This corelib delivers a boolean through
 * [Visitor.unsigned] — there is no `bool` callback, because there is no `bool` on the
 * wire — and the normalization to a language boolean is the `value != 0L` that
 * generated code emits. [BooleanDest] is that generated layer, standing in for it as
 * `HeaderLimitsTest`'s array destination does. On the encode side the surfaces are
 * the library's own: [OStream.writeBoolean] for a scalar and
 * [Seq.boolsToBytes] + [OStream.writeArrayUnsigned] for an array, which is the path
 * the README documents for `bool` — the one element kind with no `writeArray*`
 * overload of its own.
 *
 * **The bulk offer is deliberately declined** ([Visitor.arrayBulk] is left at its
 * `null` default). Handing back a [ByteArray] would *declare the elements 8 bits
 * wide*, and this decoder honours a narrow destination as a bound: element `256`
 * would then be `INVALID_MSG`. That is correct for a `u8` array and wrong for a
 * `bool` one, since §4.4 gives a boolean no width to exceed — so generated code must
 * take booleans through the per-element path, and so does this runner.
 */
class BooleanTolerantTest {

    private companion object {
        /** A byte to feed after a terminal rejection; any byte does. */
        val ONE_MORE_BYTE = byteArrayOf(0x00)

        /** Every case in the block carries this outcome; the runner still reads it. */
        const val COMPLETE = "complete"
    }

    private val cases: List<JsonObject> = Vectors.booleanTolerant

    // --- the destination, standing in for the generated layer --------------------

    /**
     * The boolean read surface: every element arrives through [Visitor.unsigned] and
     * is normalized by the `!= 0L` that generated code emits for a `bool`.
     *
     * The slots start **poisoned** with the complement of what the case expects, so a
     * decoder that never writes them fails instead of matching a zero-initialized
     * buffer — `boolean_tolerant_zero` expects `false`, which is what an untouched
     * [BooleanArray] already holds.
     *
     * Nothing here asserts: a throw from inside a decoder callback is absorbed by the
     * stream's own error path and can mask the very failure it reports. What the
     * callbacks saw is recorded and graded after `feed` returns.
     */
    private class BooleanDest(expected: List<Boolean>, private val fieldId: Int) : Visitor {
        val values: BooleanArray = BooleanArray(expected.size) { !expected[it] }

        /** How many elements arrived — more than [values] holds is a defect, not an overflow. */
        var delivered: Int = 0
            private set

        /** The element count the array header announced, or -1 for a scalar case. */
        var announcedCount: Int = -1
            private set

        /** The array kind announced, so a boolean array is seen to ride ARRAY_UNSIGNED. */
        var announcedKind: ArrayKind? = null
            private set

        /** Ids that are not the case's: recorded, graded after the feed. */
        val strayIds: MutableList<Int> = mutableListOf()

        override fun unsigned(id: Int, value: Long) {
            if (id != fieldId) strayIds.add(id)
            // §4.4's whole rule, in one comparison against the FULL accumulator: a
            // mask to any narrower width here is the truncation `boolean_tolerant_256`
            // exists to catch.
            if (delivered < values.size) values[delivered] = value != 0L
            delivered++
        }

        override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
            if (id != fieldId) strayIds.add(id)
            announcedKind = kind
            announcedCount = count
        }
    }

    // --- one case ----------------------------------------------------------------

    /** The `expect.values` of a case, as real Kotlin booleans. */
    private fun expectedValues(case: JsonObject): List<Boolean> =
        case["expect"]!!.jsonObject.arr("values").map { it.jsonPrimitive.boolean }

    /**
     * Decode one case, in one feed or a byte at a time, and return the destination.
     *
     * The verdict is asserted here because it is half of what the case states; the
     * values are graded by the caller, which also owns the re-encode.
     */
    private fun decodeCase(case: JsonObject, expected: List<Boolean>, chunk: Int): BooleanDest {
        val name = case.name()
        val wire = unhex(case.str("serialized_hex"))
        // A fresh stream per case AND per chunking: a latched verdict or a half-read
        // varint from an earlier run must never reach this one.
        val dest = BooleanDest(expected, case.int("id"))
        val stream = IStream()
        var outcome = DecodeStatus.INCOMPLETE
        if (chunk == 0) {
            outcome = stream.feed(wire, dest)
        } else {
            var i = 0
            while (i < wire.size) {
                val n = minOf(chunk, wire.size - i)
                outcome = stream.feed(wire, i, n, dest)
                i += n
            }
        }
        assertEquals(DecodeStatus.COMPLETE, outcome, "$name: outcome (chunk $chunk)")
        assertEquals(emptyList(), dest.strayIds, "$name: a field arrived at an id the case does not carry")
        assertEquals(expected.size, dest.delivered, "$name: elements delivered (chunk $chunk)")
        // The values, compared STRICTLY against booleans the JSON parser produced.
        // Coercing either side first - `Boolean(x)`, `!!x`, `x != 0` - is what maps a
        // stored 2 onto true and passes the case it was meant to fail.
        assertEquals(
            expected,
            dest.values.toList(),
            "$name: decoded values (chunk $chunk); 256 read as false is the §4.4 truncation",
        )
        if (expected.size > 1) {
            assertEquals(ArrayKind.UNSIGNED, dest.announcedKind, "$name: a boolean array rides ARRAY_UNSIGNED")
            // The wire's own count, against the number of elements the case states: a
            // decoder that delivers fewer than it announced is caught here cheaply.
            assertEquals(expected.size, dest.announcedCount, "$name: announced element count")
        } else {
            assertEquals(-1, dest.announcedCount, "$name: a scalar boolean is not an array")
        }
        return dest
    }

    /**
     * Write the decoded values back out, at the case's own id, through this port's
     * boolean write surfaces — and never from `expect.values`, which would make the
     * re-encode trivially correct and leave the decode half unverified.
     *
     * The encoder's own success indication is the **absence** of a [SofabException]:
     * `BUFFER_FULL` and `ARGUMENT` ride the error channel, so reaching the byte
     * comparison at all is that assertion. Nothing is buffered either — [encode] holds
     * no flush sink, so `bytesUsed` is the whole message and there is no half-emitted
     * buffer a short case could be compared against.
     */
    private fun reencode(case: JsonObject, dest: BooleanDest): String {
        val id = case.int("id")
        val bytes = if (dest.values.size == 1) {
            encode(16) { it.writeBoolean(id, dest.values[0]) }
        } else {
            // `bool` is the one element kind with no writeArray* overload: it travels
            // as an unsigned 0/1 (§4.4), and `boolsToBytes` is the materialization the
            // README names for it. The element width never reaches the wire (§4.7).
            encode(64) { it.writeArrayUnsigned(id, Seq.boolsToBytes(dest.values)) }
        }
        return hex(bytes)
    }

    // --- the block ---------------------------------------------------------------

    /**
     * Every case: decode through the boolean read surface, then write the decoded
     * values back out and compare the bytes.
     *
     * `requires` is honoured per case and, in **this** block, an unsatisfied tag means
     * the message must be **rejected**, not skipped: §4.4 lifts the width bound the
     * *type* carries, not the one a *build* has, so a boolean carrying `2^64-1`
     * overflows a narrowed accumulator and `INVALID` is the conformant answer there
     * (§6.2.2, §5.2.2). Skipping would assert nothing at all in exactly the build most
     * likely to truncate. This corelib compiles every feature in and has no
     * `SOFAB_DISABLE_*` equivalent, so [CAPABILITIES] is the complete set, every case
     * runs positively and the reject path is unreachable — it is implemented anyway,
     * so a port that ever gains a reduced profile grades itself instead of being
     * rewritten.
     */
    @Test
    fun everyToleratedBooleanDecodesNormalizedAndReencodesCanonical() {
        assertTrue(cases.isNotEmpty(), "no boolean_tolerant block: §4.4 has no corpus to run")
        var decoded = 0
        var rejected = 0
        var checks = 0

        for (case in cases) {
            val name = case.name()
            // An unrecognized tag contributes nothing to the needed set, so the case
            // runs positively - the forward-compatibility rule the reference runner
            // follows, and the one that keeps all eleven ports answering alike when
            // the corpus grows a tag. That a tag is one this port knows at all is a
            // separate, louder assertion: [everyCaseCarriesKnownRequiresTags].
            val needs = case.arr("requires").map { it.jsonPrimitive.content }
            if (!needs.filter { it in KNOWN_CAPABILITIES }.all { it in CAPABILITIES }) {
                expectRejected(case)
                rejected++
                checks++
                continue
            }

            assertEquals(COMPLETE, case["expect"]!!.jsonObject.str("outcome"), "$name: unexpected outcome in the block")
            val expected = expectedValues(case)
            assertTrue(expected.isNotEmpty(), "$name: a case with no expected values asserts nothing")

            val dest = decodeCase(case, expected, chunk = 0)
            checks++
            // What the decoder produced, written back out: for cases 3-8 the
            // re-encode differs from the bytes fed in, and that difference IS the
            // test. Comparing against `serialized_hex` would pass the two canonical
            // cases and nothing else.
            assertEquals(
                case["expect"]!!.jsonObject.str("reencoded_hex"),
                reencode(case, dest),
                "$name: the re-encode is canonical (§4.4 writes true as 1)",
            )
            checks++
            decoded++
        }

        println(
            "[test-vectors] %-26s found %d, decoded %d, rejected %d, %d checks"
                .format("boolean_tolerant", cases.size, decoded, rejected, checks),
        )
        assertEquals(cases.size, decoded + rejected, "every case is either decoded or rejected")
        assertTrue(decoded + rejected > 0, "the boolean_tolerant block ran nothing")
    }

    /**
     * The reject path of §7, for a build whose accumulator or feature set cannot carry
     * the case: the message is `INVALID` — not `INCOMPLETE`, and not the
     * `LIMIT_EXCEEDED` receiver-cap tier, which is a policy category and not a width
     * overflow (§5.2.2) — and the verdict is terminal under one further byte.
     *
     * Unreachable in this port (see the class KDoc); kept so the gate is a gate.
     */
    private fun expectRejected(case: JsonObject) {
        val name = case.name()
        val stream = IStream()
        // Binds nothing: what is asserted is the verdict, not what arrived first.
        val sink = object : Visitor {}
        val thrown = assertFailsWith<SofabException>("$name: want a rejection") {
            stream.feed(unhex(case.str("serialized_hex")), sink)
        }
        assertEquals(SofabError.INVALID_MSG, thrown.error, "$name: error category")
        val again = assertFailsWith<SofabException>("$name: a further feed must re-raise") {
            stream.feed(ONE_MORE_BYTE, sink)
        }
        assertEquals(SofabError.INVALID_MSG, again.error, "$name: the re-raised category")
    }

    /**
     * The same run, fed **one byte at a time**. Cases 6 and 8 carry a ten-byte varint,
     * so this is where an accumulator that only works inside one chunk shows up — and
     * the values and the verdict may not depend on where the split fell (§7.2 item 4).
     */
    @Test
    fun theSameValuesSurviveAByteAtATimeFeed() {
        var ran = 0
        for (case in cases) {
            val needs = case.arr("requires").map { it.jsonPrimitive.content }
            if (!needs.filter { it in KNOWN_CAPABILITIES }.all { it in CAPABILITIES }) continue
            val expected = expectedValues(case)
            val dest = decodeCase(case, expected, chunk = 1)
            assertEquals(
                case["expect"]!!.jsonObject.str("reencoded_hex"),
                reencode(case, dest),
                "${case.name()}: the re-encode is canonical after a byte-at-a-time feed",
            )
            ran++
        }
        println("[test-vectors] %-26s %d cases fed one byte at a time".format("boolean_tolerant/chunked", ran))
        assertTrue(ran > 0, "the chunked run covered nothing")
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the block
     * does not fail this port, while a block that SHRANK — or a loader that stopped
     * seeing part of it — is caught instead of reporting a smaller green run.
     *
     * The two halves that must not vanish are named individually: the scalar `256`
     * case is the truncation trap, and the array cases are the only ones that reach
     * the element-level half of the rule. A runner that quietly filtered on
     * `values.size == 1` would still pass everything above.
     */
    @Test
    fun theBlockCarriesBothHalvesOfTheRule() {
        assertTrue(cases.size >= 8, "boolean_tolerant carries ${cases.size} cases, want at least 8")
        val byName = cases.associateBy { it.name() }
        for (required in listOf("boolean_tolerant_zero", "boolean_tolerant_256", "boolean_tolerant_array")) {
            assertTrue(required in byName, "boolean_tolerant no longer carries $required")
        }
        // 256 is the case that separates "reads a wide varint" from "truncates to the
        // destination width", and 255 sits one step below it so the pair means that.
        assertEquals("008002", byName.getValue("boolean_tolerant_256").str("serialized_hex"))
        assertEquals("0001", byName.getValue("boolean_tolerant_256")["expect"]!!.jsonObject.str("reencoded_hex"))
        assertTrue(byName["boolean_tolerant_255"] != null, "the one-byte/two-byte pair lost its control")

        assertTrue(cases.all { it.str("group") == "boolean/tolerant" }, "a case outside the block's group")
        assertTrue(
            cases.all { it["expect"]!!.jsonObject.str("outcome") == COMPLETE },
            "a tolerated value is not a rejected one: every case in this block completes",
        )
        // Both halves, counted on what the cases actually state rather than on their
        // names: at least one scalar and at least two arrays (one of them u64-wide).
        assertTrue(cases.count { expectedValues(it).size == 1 } >= 6, "the scalar half of the block shrank")
        assertTrue(cases.count { expectedValues(it).size > 1 } >= 2, "the array half of the block shrank")
        // A re-encode that differs from the bytes fed in is where normalization
        // becomes observable; a block where none differed would assert nothing.
        assertTrue(
            cases.count { it["expect"]!!.jsonObject.str("reencoded_hex") != it.str("serialized_hex") } >= 6,
            "no case re-encodes to something other than what it was fed",
        )
    }

    /**
     * `requires` is honoured per case, and every tag the block uses is one this port
     * has been told about. The gate above reads an unknown tag as "no requirement" for
     * forward compatibility; this is what keeps that from being silent — a corpus that
     * grows a capability is reported here, loudly, before a build that lacks it can
     * pass by ignoring it.
     */
    @Test
    fun everyCaseCarriesKnownRequiresTags() {
        for (case in cases) {
            for (tag in case.arr("requires")) {
                assertTrue(
                    tag.jsonPrimitive.content in KNOWN_CAPABILITIES,
                    "${case.name()}: unknown capability tag $tag",
                )
            }
        }
        // The unconditional floor: the cases every build runs positively, however
        // reduced. If these ever gained a tag, a reduced build would assert a
        // rejection where §4.4 demands a decode.
        val untagged = cases.filter { it.arr("requires").isEmpty() }
        assertTrue(untagged.size >= 5, "the block's untagged floor shrank to ${untagged.size} cases")
        assertTrue(
            untagged.all { expectedValues(it).size == 1 },
            "an array case carries no `array` tag",
        )
    }
}
