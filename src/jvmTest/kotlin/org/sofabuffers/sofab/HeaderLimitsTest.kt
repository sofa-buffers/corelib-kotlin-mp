/*
 * SofaBuffers Kotlin Multiplatform - the shared `header_limits` block
 * (CORELIB_PLAN §6.2.1, §6.3).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fourth top-level block of the shared file: the **truncated over-ceiling
 * header** — bytes that *declare* a length or count and then end, with not one
 * payload byte behind them.
 *
 * ```
 * 02 a2 06   then EOF
 * ^^ id 0, wire type 2 (fixlen)
 *    ^^^^^ length word (100 << 3) | 2  ->  a 100-byte STRING is declared
 *            ... and the message ends.
 * ```
 *
 * A conformant decoder answers **at that word**, before the payload is asked for,
 * so the answer is the ceiling's and it is **terminal**. `INCOMPLETE` is the one
 * outcome it may not be: MESSAGE_SPEC §5.2.1 defines that as the outcome more bytes
 * *can* change, §5.2.4 has a streaming caller read it as "feed me the next chunk",
 * and after a ceiling has fired both are false statements about the state. Three
 * bytes claiming a hundred would otherwise hold a connection open — the
 * amplification the caps exist to close (ARCHITECTURE §9.5).
 *
 * **Which ceiling speaks is the subject.** The two give opposite answers on the
 * same word, and a case carries `schema` or `limits`, never both, because §6.2.1
 * forbids applying a receiver cap to a field the schema already bounds:
 *
 * | the case states | the ceiling | a breach is |
 * |---|---|---|
 * | `"schema": { "maxlen": N }` | the schema bound | `INVALID_MSG` (MESSAGE_SPEC §7.1) |
 * | `"limits": { "max_dyn_…": N }` | the receiver cap | `LIMIT_EXCEEDED` (§6.2.1, §6.3) |
 *
 * `header_string_schema_bounded` and `header_string_over_cap` carry the **identical
 * bytes** and differ only in which ceiling the case configures; that pair is what
 * keeps the two categories apart, and [theBlockPairsEveryRejectionWithAnInCapControl]
 * asserts the block still carries it.
 *
 * **Where this port answers.** The comparison itself is the library's, reachable on
 * its own as [PayloadAcc.checkStringLength] / [PayloadAcc.checkBlobLength], and this
 * reader makes it from [Visitor.fixlenBegin] — the callback the decoder raises at
 * the length word — exactly as generated code does (#31). The array **count** has no
 * such helper: an array destination is generated code's, so [HeaderDest] states that
 * rule itself, standing in for the generated layer as `SequenceGrowthTest`'s string
 * path does.
 *
 * **Bounds are absolute here, not cap-relative.** The opposite of `sequence_growth`,
 * and for a concrete reason: there the port *builds* the message and a cap-relative
 * index can be substituted at run time, while a case here **is** a fixed byte string
 * with the declared length baked into the varint, so it instead *tells* the port
 * which ceiling to configure. Nothing in this class is a claim about any deployment's
 * own configuration — the ceilings live per case and nothing outlives the run.
 */
class HeaderLimitsTest {

    private companion object {
        /**
         * Every `requires` tag this block can carry. A tag outside the set is one
         * this port has never been told about: the gate refuses it rather than
         * reading it as "unsupported", which would skip cases and still report a
         * pass.
         */
        val KNOWN_TAGS: Set<String> = setOf("fixlen", "array", "int64", "receiver_caps")

        /**
         * The tags **this** port satisfies.
         *
         * The wire tags are all of them: this corelib compiles every feature in and
         * has no `SOFAB_DISABLE_*` equivalent. `receiver_caps` is a **profile**
         * capability rather than a wire one — a port declares it when its generated
         * code carries §6.2.1 receiver caps *distinct from* schema bounds — and this
         * port does: [PayloadAcc.checkStringLength] and [PayloadAcc.checkBlobLength]
         * take the schema `maxlen` and the deployment's cap as two separate
         * arguments and answer two different categories.
         */
        val SATISFIED: Set<String> = KNOWN_TAGS

        /** A byte to feed after a terminal rejection; any byte does. */
        val ONE_MORE_BYTE = byteArrayOf(0x61)
    }

    private val cases: List<JsonObject> = Vectors.headerLimits

    /**
     * The payload the header announced, so an in-cap control can be **carried
     * through to `COMPLETE`**. That is what makes it a control rather than a second
     * rejection: §5.2.1 defines `INCOMPLETE` as the outcome more bytes can change,
     * and these bytes change it.
     */
    private fun payloadFor(dest: HeaderDest, declared: Int): ByteArray = when {
        dest.subtype == FixlenType.STRING || dest.subtype == FixlenType.BLOB -> ByteArray(declared) { 0x61 }
        dest.kind == ArrayKind.UNSIGNED || dest.kind == ArrayKind.SIGNED -> ByteArray(declared) { 1 }
        else -> error("no header was announced, so there is no payload to complete")
    }

    // --- the cases ---------------------------------------------------------------

    @Test
    fun headerCeilingCasesMatchTheirExpectations() {
        assertTrue(cases.isNotEmpty(), "no header_limits block: §6.2.1 has no corpus to run")
        var ran = 0
        var skipped = 0

        for (case in cases) {
            val name = case.name()
            val needs = case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (tag in needs) require(tag in KNOWN_TAGS) { "$name: unknown capability tag $tag" }
            // In THIS block an unsatisfied tag means SKIP, for every tag — not the
            // reduced-build rejection a *vector* gets. These cases already assert a
            // rejection with a specific category, so a build that cannot represent
            // the construct would reject it for an unrelated reason and appear to
            // pass while testing nothing.
            if (!needs.all { it in SATISFIED }) {
                skipped++
                continue
            }
            ran++

            val expect = case["expect"]!!.jsonObject
            val outcome = expect.str("outcome")
            val terminal = expect["terminal"]?.jsonPrimitive?.boolean == true
            // A Long, not an Int: a case may declare a length ABOVE the format
            // ceiling (§6.2), which is exactly what a header over `FIXLEN_MAX`
            // looks like, and `toInt()` would throw NumberFormatException while
            // parsing the block rather than letting the port answer.
            val declared = case["declared"]!!.jsonPrimitive.content.toLong()
            val dest = HeaderDest(ceilingOf(case), fieldId = case.int("field_id"))
            val stream = IStream()
            val parts = chunksOf(case)

            when (outcome) {
                "incomplete" -> {
                    assertNull(expect["terminal"], "$name: `terminal` on an incomplete case")
                    assertEquals(DecodeStatus.INCOMPLETE, feedAll(stream, dest, parts, name), "$name: outcome")
                    assertHeaderRead(name, case, dest, declared)
                    // The control's whole point: the ceiling admits this length, so
                    // the payload still decodes and the message completes.
                    assertEquals(
                        DecodeStatus.COMPLETE,
                        stream.feed(payloadFor(dest, declared.toInt()), dest),
                        "$name: the admitted payload completes",
                    )
                }
                "limit_exceeded" -> {
                    // A policy rejection of well-formed bytes, never folded into the
                    // wire verdict: the same message decodes for a receiver
                    // configured more loosely (§6.2.1, §6.3).
                    val thrown = assertFailsWith<SofabException>("$name: want a rejection") {
                        feedAll(stream, dest, parts, name)
                    }
                    assertEquals(SofabError.LIMIT_EXCEEDED, thrown.error, "$name: error category")
                    assertHeaderRead(name, case, dest, declared)
                    // §6.3 forbids the two other readings: INVALID would call
                    // well-formed bytes malformed, COMPLETE would report a message
                    // this decoder abandoned. The verdict itself rides the error
                    // channel, which is where the caller reads the category — and
                    // the only place it can be read, since `feed` threw and returned
                    // no outcome at all.
                    if (terminal) assertTerminal(name, stream, dest, SofabError.LIMIT_EXCEEDED)
                }
                "invalid" -> {
                    // The schema bound is a statement about VALIDITY: a longer
                    // payload contradicts the schema both peers agreed on
                    // (MESSAGE_SPEC §7.1), and no receiver cap may touch the field.
                    val thrown = assertFailsWith<SofabException>("$name: want a rejection") {
                        feedAll(stream, dest, parts, name)
                    }
                    assertEquals(SofabError.INVALID_MSG, thrown.error, "$name: error category")
                    assertHeaderRead(name, case, dest, declared)
                    if (terminal) assertTerminal(name, stream, dest, SofabError.INVALID_MSG)
                }
                else -> error("$name: unknown expected outcome $outcome")
            }
        }

        println("[test-vectors] %-26s %4d cases run, %d skipped by requires".format("header_limits", ran, skipped))
        assertTrue(ran > 0, "every header_limits case was skipped; the block tested nothing")
    }

    /**
     * The decoder announced the header the case describes — the number the ceiling
     * was compared against is the one on the wire, not something the reader supplied.
     */
    private fun assertHeaderRead(name: String, case: JsonObject, dest: HeaderDest, declared: Long) {
        val announced = if (dest.subtype != null) dest.declaredLength else dest.declaredCount
        // A header above the format ceiling is refused before any callback runs, so
        // there is no announced number to compare — the outcome is the whole assertion.
        if (declared > Int.MAX_VALUE.toLong()) {
            assertEquals(-1, announced, "$name: a header above the format ceiling was announced anyway")
            return
        }
        assertEquals(declared, announced.toLong(), "$name: the header the decoder announced")
        assertEquals(case.int("field_id"), fieldIdOf(dest), "$name: field id")
    }

    private fun fieldIdOf(dest: HeaderDest): Int = dest.events.first().split(":")[1].toInt()

    /**
     * §6.3: the rejection is terminal. A further feed **re-raises** with the same
     * category and consumes nothing — it is not an invitation to send more, which is
     * exactly what `INCOMPLETE` would have promised (§5.2.4).
     */
    private fun assertTerminal(name: String, stream: IStream, dest: HeaderDest, want: SofabError) {
        val before = dest.events.toList()
        val again = assertFailsWith<SofabException>("$name: a further feed must re-raise") {
            stream.feed(ONE_MORE_BYTE, dest)
        }
        assertEquals(want, again.error, "$name: the re-raised category")
        assertEquals(before, dest.events, "$name: the further feed was consumed rather than refused")
    }

    /**
     * NEGATIVE CONTROL. Run the block again with the **receiver caps lifted** and
     * nothing else changed.
     *
     * Every cap-driven rejection must fall back to `INCOMPLETE`, which is what shows
     * the verdicts above came from the ceiling and not from something incidental to
     * these byte strings — a decoder that rejected them for an unrelated reason would
     * reject them here too. The schema-bounded case is not part of that: its ceiling
     * is a schema bound, not a receiver policy, so lifting the caps leaves it
     * `INVALID` and it is asserted to stay there.
     */
    @Test
    fun liftingTheReceiverCapsFallsBackToIncomplete() {
        var fellBack = 0
        var keptTheirVerdict = 0

        for (case in cases) {
            val name = case.name()
            val needs = case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (!needs.all { it in SATISFIED }) continue
            val expect = case["expect"]!!.jsonObject
            if (expect.str("outcome") == "incomplete") continue

            val dest = HeaderDest(ceilingOf(case, Lift.CAPS), fieldId = case.int("field_id"))
            val stream = IStream()
            if (case["schema"] != null) {
                // A schema bound is not a receiver cap and was not lifted.
                val thrown = assertFailsWith<SofabException>("$name: the schema bound still speaks") {
                    feedAll(stream, dest, chunksOf(case), name)
                }
                assertEquals(SofabError.INVALID_MSG, thrown.error, "$name: error category, caps lifted")
                keptTheirVerdict++
            } else {
                assertEquals(
                    DecodeStatus.INCOMPLETE,
                    feedAll(stream, dest, chunksOf(case), name),
                    "$name: outcome with the cap lifted",
                )
                fellBack++
            }
        }

        println(
            "[test-vectors] %-26s %d cap rejections fell back to incomplete, %d kept a schema verdict"
                .format("header_limits/control", fellBack, keptTheirVerdict),
        )
        assertTrue(fellBack > 0, "no rejection fell back; the ceilings were never what decided them")
        assertEquals(1, keptTheirVerdict, "the schema-bounded case must not be lifted by a receiver cap")
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the
     * block does not fail this port, while a block that SHRANK — or a control that
     * vanished — is caught.
     *
     * The in-cap controls are the load-bearing half. Without them the block proves
     * nothing: a port that rejects every short read passes all six rejection cases
     * and is badly broken. The README says to treat a missing control as a bug in
     * the block, so a rejection whose ceiling has no in-cap case fails here.
     */
    @Test
    fun theBlockPairsEveryRejectionWithAnInCapControl() {
        assertTrue(cases.size >= 10, "header_limits carries ${cases.size} cases, want at least 10")

        val groups = cases.map { it.str("group") }.toSet()
        assertTrue("limits/header" in groups, "no case in group limits/header")
        val outcomes = cases.map { it["expect"]!!.jsonObject.str("outcome") }.toSet()
        for (o in listOf("limit_exceeded", "invalid", "incomplete")) {
            assertTrue(o in outcomes, "no case expecting $o")
        }
        assertTrue(cases.any { it["chunks"] != null }, "no case splits the length varint across a feed")

        // Which ceiling a case configures, as the key that has to be paired.
        fun ceilingKey(case: JsonObject): String =
            case["limits"]?.jsonObject?.keys?.single() ?: "schema"

        val admitted = cases.filter { it["expect"]!!.jsonObject.str("outcome") == "incomplete" }
            .map(::ceilingKey)
            .toSet()
        for (case in cases) {
            val expect = case["expect"]!!.jsonObject
            if (expect.str("outcome") == "incomplete") continue
            assertTrue(
                ceilingKey(case) in admitted,
                "${case.name()} rejects on ${ceilingKey(case)} with no in-cap control on the same ceiling",
            )
            assertTrue(expect["terminal"]?.jsonPrimitive?.boolean == true, "${case.name()}: a rejection is terminal")
        }

        // THE PAIR THAT KEEPS THE TWO CATEGORIES APART: identical bytes, opposite
        // answers, and the only difference is which ceiling the case configures. A
        // port that routes both to one category passes every other case and fails
        // this one.
        val byName = cases.associateBy { it.name() }
        val cap = byName.getValue("header_string_over_cap")
        val bound = byName.getValue("header_string_schema_bounded")
        assertEquals(cap.str("serialized"), bound.str("serialized"), "the pair no longer carries identical bytes")
        assertEquals("limit_exceeded", cap["expect"]!!.jsonObject.str("outcome"))
        assertEquals("invalid", bound["expect"]!!.jsonObject.str("outcome"))
        assertTrue(cap["limits"] != null && cap["schema"] == null, "header_string_over_cap states a receiver cap")
        assertTrue(bound["schema"] != null && bound["limits"] == null, "header_string_schema_bounded states a schema")
    }

    /**
     * `requires` is honoured per case, and every tag the block uses is one this port
     * has been told about. An unknown tag read as "unsupported" would skip the case
     * and still report a pass.
     */
    @Test
    fun everyCaseCarriesKnownRequiresTags() {
        for (case in cases) {
            val needs = case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(needs.isNotEmpty(), "${case.name()} carries no requires tags")
            for (tag in needs) assertTrue(tag in KNOWN_TAGS, "${case.name()}: unknown capability tag $tag")
        }
        // The cap cases are the ones gated on the profile capability; the
        // schema-bounded pair needs no cap and must stay runnable for a port that
        // has none.
        for (case in cases) {
            val needs = case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }
            val gated = "receiver_caps" in needs
            assertEquals(case["limits"] != null, gated, "${case.name()}: receiver_caps tag vs the ceiling it states")
        }
    }

    /**
     * A length word that breaches BOTH ceilings at once: 3,000,000,000 bytes is
     * above `FIXLEN_MAX` (2^31-1 — a format ceiling, whose breach is `INVALID` per
     * MESSAGE_SPEC §5.2.2) and above any receiver cap a deployment would configure
     * (whose breach is `LimitExceeded` per CORELIB_PLAN §6.2.1). The two carry
     * different categories and the spec ranks neither, so this pins what this port
     * actually does: the format ceiling is decided first and wins.
     *
     * It also guards the reader itself. `declared` is read as a Long precisely so a
     * case like this parses; reading it as an Int threw NumberFormatException before
     * a single byte was decoded, which would have looked like a broken block rather
     * than a port that answers.
     */
    @Test
    fun aFormatCeilingBreachOutranksAReceiverCapOnTheSameWord() {
        // 02 = id 0, wire type 2 (fixlen); then (3_000_000_000 << 3) | 2 as a varint.
        val bytes = byteArrayOf(0x02, 0x82.toByte(), 0xe0.toByte(), 0x8b.toByte(), 0xb4.toByte(), 0x59)
        val dest = HeaderDest(Ceiling(NO_SCHEMA_BOUND, 16, NO_CAP_STATED, NO_CAP_STATED))
        val stream = IStream()
        val thrown = assertFailsWith<SofabException> { stream.feed(bytes, dest) }
        assertEquals(
            SofabError.INVALID_MSG,
            thrown.error,
            "a length above FIXLEN_MAX is malformed (§5.2.2); LimitExceeded would promise that a " +
                "larger cap could accept these bytes, and none can",
        )
    }
}
