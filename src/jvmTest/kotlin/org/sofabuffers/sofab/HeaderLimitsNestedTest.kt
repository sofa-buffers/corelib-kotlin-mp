/*
 * SofaBuffers Kotlin Multiplatform — the shared `header_limits_nested` block
 * (CORELIB_PLAN §6.2.1, §6.3; MESSAGE_SPEC §7.1, §5.2).
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `header_limits`, **one or two sequence frames deeper** — and that is the entire
 * difference.
 *
 * ```
 * 3e 1e 02 a2 06   then EOF
 * ^^ id 7, wire type 6 (sequence open)
 *    ^^ id 3, wire type 6 (sequence open)
 *       ^^ id 0, wire type 2 (fixlen)
 *          ^^^^^ length word (100 << 3) | 2  ->  a 100-byte STRING is declared
 *                  ... and the message ends, with BOTH frames still open.
 * ```
 *
 * Every case of the flat block puts its field at `field_id 0` in the top-level scope,
 * which leaves one axis untested: the identical over-ceiling header delivered **inside
 * an open sequence**. A port can carry a schema bound into a nested scope and leave the
 * receiver cap bound at the top level, where these bytes never reach it; the flat block
 * cannot see that, and this block is exactly that axis and nothing else.
 *
 * **Why depth makes `INCOMPLETE` a better trap.** These cases end with the frames still
 * open, so a decoder has a *second, wholly independent* reason to answer `INCOMPLETE` —
 * the message is unterminated quite apart from the missing payload. §6.2.1 puts the
 * enforcement point at the count/length header all the same: the answer is the
 * ceiling's, it is given at that word, and §6.3 makes it terminal. A port that defers
 * the comparison until it has payload bytes hits end-of-input first and reports
 * `INCOMPLETE`, which §5.2.4 has a streaming caller read as "feed me the next chunk" —
 * a false statement about a state no further byte can lift.
 *
 * **The leaf is the flat block's leaf.** [HeaderDest] takes the frame chain as data and
 * is entered from both classes; the only thing this class supplies is a non-empty
 * `frames`. A second leaf here could pass the nested path through a comparison the flat
 * path never makes — coverage the block exists to deny.
 *
 * **[liftingTheCeilingChangesEveryNestedRejection] is load-bearing, not decoration.**
 * With a frame open, a port that rejects these bytes for an unrelated reason — a depth
 * guard, a refusal of unclosed frames, a strict-mode path — answers `LIMIT_EXCEEDED` or
 * `INVALID` and passes every assertion above while never having consulted the ceiling
 * under test. Lifting the ceiling and finding the answer unchanged is the only thing
 * that tells the two apart.
 */
class HeaderLimitsNestedTest {

    private companion object {
        /**
         * Every `requires` tag this block can carry. A tag outside the set is one this
         * port has never been told about: the gate refuses it rather than reading it as
         * "unsupported", which would skip cases and still report a pass.
         *
         * `sequence` is the tag the flat block has no use for and this one always
         * carries — the construct the whole block is about.
         */
        val KNOWN_TAGS: Set<String> = setOf("sequence", "fixlen", "array", "int64", "receiver_caps")

        /**
         * The tags **this** port satisfies.
         *
         * The wire tags are all of them: this corelib compiles every feature in and has
         * no `SOFAB_DISABLE_*` equivalent, so nothing here is gated and all eight cases
         * run. `receiver_caps` is a **profile** capability rather than a wire one — a
         * port declares it when its generated code carries §6.2.1 receiver caps
         * *distinct from* schema bounds — and this port does: [PayloadAcc.checkStringLength]
         * takes the schema `maxlen` and the deployment's cap as two separate arguments
         * and answers two different categories.
         */
        val SATISFIED: Set<String> = KNOWN_TAGS

        /**
         * The payload a terminal case's header promised, fed after the rejection.
         *
         * §6.3 is asserted by **feeding real bytes and watching the answer again**, not
         * by re-reading a stored status: a decoder that would have consumed the payload
         * and moved on looks terminal to anything that only asks what the last error
         * was. These bytes would complete the field if anything could.
         */
        val WOULD_BE_PAYLOAD = ByteArray(8) { 0x61 }
    }

    private val cases: List<JsonObject> = Vectors.headerLimitsNested

    /** The frame chain a case names: sequence field ids, outermost first. */
    private fun framesOf(case: JsonObject): List<Int> {
        val frames = case["frames"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertTrue(frames.isNotEmpty(), "${case.name()}: frames is empty; this block is about depth")
        return frames
    }

    private fun tagsOf(case: JsonObject): List<String> =
        case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** The first tag this port does not satisfy, or null when the case runs. */
    private fun gatedBy(case: JsonObject): String? {
        val needs = tagsOf(case)
        for (tag in needs) require(tag in KNOWN_TAGS) { "${case.name()}: unknown capability tag $tag" }
        return needs.firstOrNull { it !in SATISFIED }
    }

    /**
     * A receiver built to the case's `frames`: it descends the chain through the
     * decoder's ordinary nested-sequence callbacks and binds the ceiling to the field at
     * the **innermost** depth. Bound anywhere else it caps nothing, and the over-ceiling
     * cases answer `INCOMPLETE`.
     */
    private fun destFor(case: JsonObject, lift: Lift = Lift.NOTHING): HeaderDest =
        HeaderDest(ceilingOf(case, lift), framesOf(case), case.int("field_id"))

    // --- the cases ---------------------------------------------------------------

    @Test
    fun nestedHeaderCeilingCasesMatchTheirExpectations() {
        assertTrue(cases.isNotEmpty(), "no header_limits_nested block: the depth axis has no corpus to run")
        var ran = 0
        val gated = mutableListOf<String>()

        for (case in cases) {
            val name = case.name()
            // In THIS block an unsatisfied tag means SKIP, for every tag — not the
            // reduced-build rejection a *vector* gets. These cases already assert a
            // rejection with a specific category, so a build that cannot represent the
            // construct would reject it for an unrelated reason and appear to pass
            // while testing nothing.
            val gate = gatedBy(case)
            if (gate != null) {
                gated.add("$name (needs $gate)")
                continue
            }
            ran++

            val expect = case["expect"]!!.jsonObject
            val outcome = expect.str("outcome")
            val terminal = expect["terminal"]?.jsonPrimitive?.boolean == true
            val declared = case.int("declared")
            val dest = destFor(case)
            val stream = IStream()
            val parts = chunksOf(case)

            when (outcome) {
                "incomplete" -> {
                    assertNull(expect["terminal"], "$name: `terminal` on an incomplete case")
                    assertEquals(DecodeStatus.INCOMPLETE, feedAll(stream, dest, parts, name), "$name: outcome")
                    assertHeaderRead(name, case, dest, declared)
                    // Nothing more is fed. Unlike the flat block's control, these bytes
                    // cannot be carried to COMPLETE without closing the frames, and
                    // closing them would be a different message than the case states.
                }
                "limit_exceeded" -> {
                    // A policy rejection of well-formed bytes, never folded into the
                    // wire verdict: the same message decodes for a receiver configured
                    // more loosely (§6.2.1, §6.3).
                    val thrown = assertFailsWith<SofabException>("$name: want a rejection") {
                        feedAll(stream, dest, parts, name)
                    }
                    assertEquals(SofabError.LIMIT_EXCEEDED, thrown.error, "$name: error category")
                    assertHeaderRead(name, case, dest, declared)
                    if (terminal) assertTerminal(name, stream, dest, SofabError.LIMIT_EXCEEDED)
                }
                "invalid" -> {
                    // The schema bound is a statement about VALIDITY: a longer payload
                    // contradicts the schema both peers agreed on (MESSAGE_SPEC §7.1),
                    // and no receiver cap may touch the field. Identical bytes to the
                    // cap case above, at identical depth — a port that collapses the
                    // two categories passes seven cases and fails this one.
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

        // §5: `ran` and `gated` are reported and sum to the block. A mis-spelled
        // capability name or a probe that answers "unsupported" by accident turns this
        // runner into a no-op that reports green, and this line is the cheap way to see
        // it. A gated case names the tag that gated it.
        println(
            "[test-vectors] %-26s %4d cases run, %d gated%s".format(
                "header_limits_nested",
                ran,
                gated.size,
                if (gated.isEmpty()) "" else ": $gated",
            ),
        )
        assertEquals(cases.size, ran + gated.size, "every case is visited: ran + gated is the block")
        assertTrue(ran > 0, "every header_limits_nested case was gated; the block tested nothing")
        // Depth 2 is its own trap: a chain builder off by one, or a runner that descends
        // once and reads the inner sequence header as the target field, handles `[7]`
        // and mishandles `[7, 3]`. Assert a depth-2 case was among the ones that ran.
        assertTrue(
            cases.any { gatedBy(it) == null && framesOf(it).size >= 2 },
            "no depth-2 case ran; one level may be special-cased and this block would not see it",
        )
    }

    /**
     * The decoder announced the header the case describes, at the depth it describes:
     * the number the ceiling was compared against is the one on the wire, and it arrived
     * inside the frame chain rather than at the top level.
     */
    private fun assertHeaderRead(name: String, case: JsonObject, dest: HeaderDest, declared: Int) {
        assertEquals(framesOf(case), dest.opened.toList(), "$name: the frame chain the decoder opened")
        val announced = if (dest.subtype != null) dest.declaredLength else dest.declaredCount
        assertEquals(declared, announced, "$name: the header the decoder announced")
        assertEquals(case.int("field_id"), fieldIdOf(dest), "$name: field id inside the innermost frame")
    }

    private fun fieldIdOf(dest: HeaderDest): Int = dest.events.first().split(":")[1].toInt()

    /**
     * §6.3: the rejection is terminal. A further feed of the payload the header promised
     * **re-raises** with the same category and consumes nothing — it is not an
     * invitation to send more, which is exactly what `INCOMPLETE` would have promised
     * (§5.2.4).
     *
     * And nothing was materialized, checked *after* that second feed so a late
     * materialization is caught too: §6.2.1 is "rejected, never clamped", and a read
     * that truncates to the ceiling, keeps the head and *also* reports the error passes
     * every outcome assertion in this class and fails only here.
     */
    private fun assertTerminal(name: String, stream: IStream, dest: HeaderDest, want: SofabError) {
        val before = dest.events.toList()
        val again = assertFailsWith<SofabException>("$name: a further feed must re-raise") {
            stream.feed(WOULD_BE_PAYLOAD, dest)
        }
        assertEquals(want, again.error, "$name: the re-raised category")
        assertEquals(before, dest.events, "$name: the further feed was consumed rather than refused")
        assertTrue(dest.materialized.isEmpty(), "$name: payload was materialized for a rejected header")
        assertTrue(dest.elements.isEmpty(), "$name: elements were materialized for a rejected count")
    }

    /**
     * NEGATIVE CONTROL — the assertion this block is worth least without.
     *
     * Every rejection is replayed with the **same kind** of ceiling lifted far above
     * `declared` and nothing else changed: a `limits` case gets a lifted receiver cap, a
     * `schema` case a lifted schema bound. Lifting the other kind would prove nothing
     * (and lifting *both* would let a schema case pass for the cap's reason).
     *
     * The answer must **change**. It is asserted as an inequality rather than as
     * `INCOMPLETE`, because what is being shown is that the ceiling caused the
     * rejection — not what the alternative verdict happens to be. In practice it becomes
     * `INCOMPLETE`, since a frame is open; the report prints what each case actually
     * became, so a run says so rather than implying it.
     *
     * The checked count is asserted for the reason [nestedHeaderCeilingCasesMatchTheirExpectations]
     * reports `ran`: a control loop that `continue`s every iteration — a wrong outcome
     * name, a gate read differently in this pass — is green with zero coverage.
     */
    @Test
    fun liftingTheCeilingChangesEveryNestedRejection() {
        var checked = 0
        val became = mutableListOf<String>()

        for (case in cases) {
            val name = case.name()
            if (gatedBy(case) != null) continue
            val expect = case["expect"]!!.jsonObject
            // Only a rejection can be shown to depend on its ceiling.
            val rejection = when (expect.str("outcome")) {
                "limit_exceeded" -> SofabError.LIMIT_EXCEEDED
                "invalid" -> SofabError.INVALID_MSG
                else -> continue
            }

            val dest = destFor(case, Lift.EVERY_CEILING)
            val stream = IStream()
            val answer = runCatching { feedAll(stream, dest, chunksOf(case), name) }
            val label = answer.fold(
                onSuccess = { "status:$it" },
                onFailure = { "throw:" + ((it as? SofabException)?.error ?: it::class.simpleName) },
            )
            assertNotEquals(
                "throw:$rejection",
                label,
                "$name: the answer did not change with the ceiling lifted, so the ceiling is not what " +
                    "decided it — an unrelated guard (depth, an unclosed frame, a strict-mode path) is " +
                    "rejecting these bytes and the forward pass proves nothing",
            )
            became.add("$name -> $label")
            checked++
        }

        println("[test-vectors] %-26s %d rejections re-answered with the ceiling lifted: %s"
            .format("header_limits_nested/control", checked, became))

        val rejections = cases.count {
            gatedBy(it) == null && it["expect"]!!.jsonObject.str("outcome") in setOf("limit_exceeded", "invalid")
        }
        assertEquals(rejections, checked, "the control skipped a rejection it was admitted to check")
        // This block has no amplification case, so unlike the flat one it has no
        // exemption: every rejection it carries is reachable with a lifted ceiling, and
        // a full-capability port checks all four.
        assertTrue(checked >= 4, "only $checked rejections were controlled; the block carries four")
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the block
     * does not fail this port, while a block that SHRANK — or a control that vanished —
     * is caught.
     *
     * The in-cap controls are the load-bearing half. Without them the block proves
     * nothing: a port that rejects every short read *nested* passes all four rejections
     * and is badly broken. So a rejection whose ceiling has no in-cap case at the same
     * depth fails here.
     */
    @Test
    fun theNestedBlockPairsEveryRejectionWithAnInCapControl() {
        assertTrue(cases.size >= 8, "header_limits_nested carries ${cases.size} cases, want at least 8")

        val groups = cases.map { it.str("group") }.toSet()
        assertTrue("limits/header-nested" in groups, "no case in group limits/header-nested")
        val outcomes = cases.map { it["expect"]!!.jsonObject.str("outcome") }.toSet()
        for (o in listOf("limit_exceeded", "invalid", "incomplete")) {
            assertTrue(o in outcomes, "no case expecting $o")
        }
        // Every case is nested, or it belongs in the flat block.
        for (case in cases) assertTrue(framesOf(case).isNotEmpty(), "${case.name()}: frames is empty")
        assertTrue(cases.any { framesOf(it).size >= 2 }, "no case reaches depth 2")

        // Which ceiling a case configures AND at which depth — both have to be paired,
        // since an in-cap control at another depth would not control anything.
        fun pairKey(case: JsonObject): String =
            (case["limits"]?.jsonObject?.keys?.single() ?: "schema") + "@" + framesOf(case)

        val admitted = cases.filter { it["expect"]!!.jsonObject.str("outcome") == "incomplete" }
            .map(::pairKey)
            .toSet()
        for (case in cases) {
            val expect = case["expect"]!!.jsonObject
            if (expect.str("outcome") == "incomplete") continue
            assertTrue(
                pairKey(case) in admitted,
                "${case.name()} rejects on ${pairKey(case)} with no in-cap control on the same ceiling and depth",
            )
            assertTrue(expect["terminal"]?.jsonPrimitive?.boolean == true, "${case.name()}: a rejection is terminal")
        }

        // THE PAIR THAT KEEPS THE TWO CATEGORIES APART, one frame down: identical bytes
        // at identical depth, opposite answers, and the only difference is which ceiling
        // the case configured. A port that routes both to one category passes every
        // other case in the block and fails this one.
        val byName = cases.associateBy { it.name() }
        val cap = byName.getValue("nested_string_over_cap")
        val bound = byName.getValue("nested_string_schema_bounded")
        assertEquals(cap.str("serialized"), bound.str("serialized"), "the pair no longer carries identical bytes")
        assertEquals(framesOf(cap), framesOf(bound), "the pair is no longer at one depth")
        assertEquals("limit_exceeded", cap["expect"]!!.jsonObject.str("outcome"))
        assertEquals("invalid", bound["expect"]!!.jsonObject.str("outcome"))
        assertTrue(cap["limits"] != null && cap["schema"] == null, "nested_string_over_cap states a receiver cap")
        assertTrue(bound["schema"] != null && bound["limits"] == null, "nested_string_schema_bounded states a schema")
    }

    /**
     * `requires` is honoured per case, and every tag the block uses is one this port has
     * been told about. An unknown tag read as "unsupported" would gate the case and
     * still report a pass.
     */
    @Test
    fun everyNestedCaseCarriesKnownRequiresTags() {
        for (case in cases) {
            val needs = tagsOf(case)
            assertTrue(needs.isNotEmpty(), "${case.name()} carries no requires tags")
            for (tag in needs) assertTrue(tag in KNOWN_TAGS, "${case.name()}: unknown capability tag $tag")
            // Every case here is nested, so every case must say so — a case missing the
            // tag would run in a build with sequences compiled out and reject for a
            // reason that has nothing to do with the ceiling.
            assertTrue("sequence" in needs, "${case.name()}: a nested case must require `sequence`")
            // The cap cases are the ones gated on the profile capability; the
            // schema-bounded pair needs no cap and must stay runnable for a port that
            // has none.
            assertEquals(
                case["limits"] != null,
                "receiver_caps" in needs,
                "${case.name()}: receiver_caps tag vs the ceiling it states",
            )
        }
    }

    /**
     * The nested block's leaf **is** the flat block's leaf, entered with a non-empty
     * frame chain. Asserted rather than asserted-in-prose: the same [HeaderDest], the
     * same ceiling, the same bytes minus their sequence header, give the same answer at
     * depth 0 and at depth 1.
     *
     * Trap 5 of the block's specification is a nested leaf that does its own size
     * comparison — it would test the runner's arithmetic instead of the library's
     * enforcement point, and pass. This pins that the two paths are one path.
     */
    @Test
    fun theNestedLeafIsTheFlatLeafOneFrameDeeper() {
        val nested = cases.first { it.name() == "nested_string_over_cap" }
        val flat = Vectors.headerLimits.first { it.name() == "header_string_over_cap" }
        // The nested bytes are the flat bytes behind one sequence header, and nothing else.
        assertEquals("3e" + flat.str("serialized"), nested.str("serialized"), "the pair drifted apart")

        val atTop = HeaderDest(ceilingOf(flat), emptyList(), flat.int("field_id"))
        val atDepth = destFor(nested)
        val flatError = assertFailsWith<SofabException> { IStream().feed(unhex(flat.str("serialized")), atTop) }
        val nestedError = assertFailsWith<SofabException> { IStream().feed(unhex(nested.str("serialized")), atDepth) }
        assertEquals(flatError.error, nestedError.error, "one leaf, two depths, two categories")
        assertEquals(atTop.declaredLength, atDepth.declaredLength, "the same length word was read at both depths")
        assertEquals(emptyList(), atTop.opened.toList(), "the flat case opened a frame")
        assertEquals(listOf(7), atDepth.opened.toList(), "the nested case did not open the frame it names")
    }
}
