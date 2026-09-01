/*
 * SofaBuffers Kotlin Multiplatform - the shared `sequence_growth` block
 * (CORELIB_PLAN §7.2 item 8).
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
import kotlin.test.assertTrue

/**
 * The third top-level block of the shared file, run per CORELIB_PLAN §7.2 item 8.
 *
 * A wrapper (sequence) array carries no element count on the wire: its length is
 * *highest present id + 1* (MESSAGE_SPEC §5.1), so the size is known only once the
 * array ends and the container **grows** as elements arrive. That is the one
 * allocation shape where growth is conformant, and it happens in the static helper
 * layer — [Seq] — never in the codec (§6.6.1).
 *
 * **Why these cases cannot be vectors.** Two ports that grow differently emit
 * *identical bytes* and reach identical outcomes, so no `serialized.hex` can tell
 * them apart. The block is therefore keyed by a **delivery sequence of element
 * ids**, and the port builds the message itself from `deliver` and asserts
 * `expect`: container length and outcome only, no allocator instrumentation, which
 * is what makes the cases portable across the family.
 *
 * **What this port owns.** The struct cases run through [Seq.reserveRowList], this
 * library's own wrapper-array placement: it bounds the element index before the
 * list grows (§6.2.1), fills a gap with the empty row rather than shifting later
 * rows down, and replaces rather than merges. A struct element here is a framed
 * sub-sequence carrying one unsigned field, which is exactly a row holding one
 * value. The string cases have no such helper — a `MutableList<String>`
 * destination is placed by generated code — so that path states the same contract
 * itself, standing in for the generated layer.
 *
 * The destination reads its length off the **container** rather than from a counter
 * beside it. A counter updated only after a successful placement reports the right
 * length even when the container was already extended toward a rejected index,
 * which is the very defect §6.2.1's *"before the container it indexes into is
 * extended"* forbids.
 */
class SequenceGrowthTest {

    private companion object {
        /**
         * THIS port's `max_dyn_array_count` for the block's run.
         *
         * The block never names an absolute boundary: a receiver cap is per-target
         * configuration and §6.2.1 fixes no family-wide number, so every case's
         * `id_from_cap` / `length_from_cap` is an OFFSET onto whatever the port
         * picks (-1 → cap-1, 0 → cap). The cases assume a cap of at least 4; 4 is
         * the smallest value that satisfies them.
         */
        const val CAP = 4

        /**
         * This port's answer to the `dynamic_arrays` capability the block gates on:
         * a Kotlin `MutableList` destination grows as elements arrive, so the block
         * runs.
         *
         * Not a wire capability like the tags on a vector — it states how a port
         * ALLOCATES, not what it can parse, which is why it is the one tag a
         * full-format port still has to honour (test_vectors_README.md, "Gating").
         */
        const val GROWS_DYNAMIC_ARRAYS = true

        /** No schema `count:` here, which is what puts the receiver cap in charge. */
        const val NO_SCHEMA_COUNT = -1
    }

    private val cases: List<JsonObject> = Vectors.sequenceGrowth

    /**
     * Turn a possibly cap-relative index into an absolute one. Exactly one of the
     * two keys is present per the README; neither, or both, is a corrupt case
     * rather than something to guess at.
     */
    private fun resolve(owner: JsonObject, absKey: String, capKey: String, what: String): Int {
        val abs = owner[absKey]
        val rel = owner[capKey]
        require(!(abs != null && rel != null)) { "$what carries both $absKey and $capKey" }
        abs?.let { return it.jsonPrimitive.int }
        rel?.let { return CAP + it.jsonPrimitive.int }
        error("$what carries neither $absKey nor $capKey")
    }

    // --- building the message the delivery sequence describes --------------------

    private fun build(case: JsonObject): ByteArray {
        val name = case["name"]!!.jsonPrimitive.content
        val structElements = case["element_type"]!!.jsonPrimitive.content == "struct"
        val buffer = ByteArray(4096)
        val out = OStream(buffer)

        // The frame is KEPT even when empty: element presence is what carries the
        // array's length, so an empty wrapper is framed rather than omitted (§5.1).
        out.writeSequenceBeginLazy(case["field_id"]!!.jsonPrimitive.int)
        case["deliver"]!!.jsonArray.forEachIndexed { i, element ->
            val d = element.jsonObject
            val id = resolve(d, "id", "id_from_cap", "$name: deliver[$i]")
            if (structElements) {
                out.writeSequenceBeginLazy(id)
                out.writeUnsigned(0, d["value"]!!.jsonPrimitive.int.toLong())
                out.writeSequenceEndKeep()
            } else {
                out.writeString(id, d["value"]!!.jsonPrimitive.content)
            }
        }
        out.writeSequenceEndKeep()

        return buffer.copyOfRange(0, out.bytesUsed)
    }

    // --- the destination, standing in for the generated layer --------------------

    private class GrowthDest(private val structElements: Boolean) : Visitor {
        val strings: MutableList<String> = mutableListOf()
        val rows: MutableList<MutableList<Long>> = mutableListOf()
        private var depth = 0
        private var element = -1
        private var payload = ByteArray(0)

        /** Read off the container, never from a counter beside it — see the class doc. */
        val length: Int
            get() = if (structElements) rows.size else strings.size

        override fun sequenceBegin(id: Int) {
            depth++
            // depth 1 is the wrapper itself; depth 2 is a struct element.
            if (depth == 2 && structElements) {
                // The library's own placement: bounds the index against the receiver
                // cap, gap-fills with the empty row, never shifts later rows down.
                Seq.reserveRowList(rows, id, NO_SCHEMA_COUNT, CAP)
                element = id
            }
        }

        override fun sequenceEnd() {
            if (depth == 2) element = -1
            depth--
        }

        override fun unsigned(id: Int, value: Long) {
            if (depth == 2 && structElements && element >= 0 && id == 0) {
                rows[element].add(value)
            }
        }

        override fun string(
            id: Int,
            total: Int,
            offset: Int,
            data: ByteArray,
            chunkOffset: Int,
            chunkLength: Int,
        ) {
            if (depth != 1 || structElements) return
            // The index is bounded at the FIRST piece, before any payload is kept:
            // a rejection must not depend on the payload arriving whole.
            if (offset == 0) {
                if (id >= CAP) {
                    throw SofabException(
                        SofabError.LIMIT_EXCEEDED,
                        "array element index $id above configured limit $CAP",
                    )
                }
                // MESSAGE_SPEC §5.1: every destination slot is initialised to its
                // ELEMENT DEFAULT before the array is applied — "" for a string.
                // Only a gap case ever looks at a slot nothing was written to.
                while (strings.size <= id) strings.add("")
                payload = ByteArray(total)
            }
            data.copyInto(payload, offset, chunkOffset, chunkOffset + chunkLength)
            if (offset + chunkLength == total) {
                strings[id] = payload.decodeToString()
            }
        }

        fun numberAt(i: Int): Long = rows.getOrNull(i)?.firstOrNull() ?: 0L

        fun stringAt(i: Int): String = strings.getOrNull(i) ?: ""
    }

    // --- the cases ---------------------------------------------------------------

    @Test
    fun growthCasesMatchTheirExpectations() {
        // A statically bounded profile declares dynamic_arrays false and states that
        // in its README instead (§7.2 item 8); this port grows, so it runs.
        if (!GROWS_DYNAMIC_ARRAYS) return
        assertTrue(cases.isNotEmpty(), "no sequence_growth block: §7.2 item 8 has no corpus to run")

        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val structElements = case["element_type"]!!.jsonPrimitive.content == "struct"
            val message = build(case)
            val dest = GrowthDest(structElements)
            val stream = IStream()
            val expect = case["expect"]!!.jsonObject

            when (val outcome = expect["outcome"]!!.jsonPrimitive.content) {
                "complete" -> {
                    stream.feed(message, dest)
                    assertEquals(
                        resolve(expect, "length", "length_from_cap", "$name: expect"),
                        dest.length,
                        "$name: container length",
                    )
                    // A gap below the cap holds the element default, and neither
                    // shortens nor shifts the array (§5.1).
                    expect["default_ids"]?.jsonArray?.forEach {
                        val id = it.jsonPrimitive.int
                        assertTrue(id < dest.length, "$name: default id $id past length ${dest.length}")
                        if (structElements) {
                            assertEquals(0L, dest.numberAt(id), "$name: element $id")
                        } else {
                            assertEquals("", dest.stringAt(id), "$name: element $id")
                        }
                    }
                }
                "limit_exceeded" -> {
                    // A policy rejection, not INVALID: the same bytes decode under a
                    // looser cap (§6.2.1, §6.3).
                    val thrown = assertFailsWith<SofabException>("$name: want a rejection") {
                        stream.feed(message, dest)
                    }
                    assertEquals(SofabError.LIMIT_EXCEEDED, thrown.error, "$name: error category")

                    // The bound is applied BEFORE the container is extended, so the
                    // length never passes what legitimately arrived — and the
                    // rejection is terminal, so an element delivered after it does
                    // not land either.
                    expect["max_length"]?.let {
                        val max = it.jsonPrimitive.int
                        assertTrue(
                            dest.length <= max,
                            "$name: container length ${dest.length}, want at most $max — " +
                                "extended toward the rejected index",
                        )
                    }
                    if (expect["terminal"]?.jsonPrimitive?.boolean == true) {
                        // Terminal, but not folded into the wire-conformance outcome
                        // (§6.3): these bytes are well-formed, so the status is
                        // neither INVALID nor COMPLETE.
                        assertTrue(stream.status != DecodeStatus.INVALID, "$name: status is not INVALID")
                        assertTrue(stream.status != DecodeStatus.COMPLETE, "$name: status is not COMPLETE")
                    }
                }
                else -> error("$name: unknown expected outcome $outcome")
            }
        }
    }

    /**
     * The block is the one place a full-format port still honours `requires`: the
     * tag says how the port ALLOCATES, not what it can parse, so a statically
     * bounded build must skip these cases even though it runs every vector.
     */
    @Test
    fun everyCaseIsGatedOnDynamicArrays() {
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val requires = case["requires"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("dynamic_arrays" in requires, "$name does not carry the dynamic_arrays tag")
        }
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the
     * block does not fail this port, while a block that SHRANK — or a case kind that
     * vanished — is caught.
     */
    @Test
    fun theBlockCarriesEveryCaseKind() {
        assertTrue(cases.size >= 8, "sequence_growth carries ${cases.size} cases, want at least 8")

        val groups = cases.map { it["group"]!!.jsonPrimitive.content }.toSet()
        val kinds = cases.map { it["element_type"]!!.jsonPrimitive.content }.toSet()
        val outcomes = cases.map { it["expect"]!!.jsonObject["outcome"]!!.jsonPrimitive.content }.toSet()

        for (g in listOf("growth/index", "growth/gap", "growth/reject", "growth/length")) {
            assertTrue(g in groups, "no case in group $g")
        }
        // Both element kinds are mandatory: a string element reaches the destination
        // through the leaf path and a struct element through the sequence path, and a
        // port can get one right and the other wrong.
        for (k in listOf("string", "struct")) assertTrue(k in kinds, "no case with element_type $k")
        for (o in listOf("complete", "limit_exceeded")) assertTrue(o in outcomes, "no case expecting $o")
    }
}
