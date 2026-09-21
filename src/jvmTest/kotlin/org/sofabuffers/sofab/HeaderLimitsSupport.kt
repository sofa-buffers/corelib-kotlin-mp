/*
 * SofaBuffers Kotlin Multiplatform — the leaf shared by the two header-ceiling
 * blocks, `header_limits` and `header_limits_nested` (CORELIB_PLAN §6.2.1, §6.3).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/**
 * ONE leaf for both header-ceiling blocks.
 *
 * [HeaderLimitsTest] feeds a truncated over-ceiling header at the top level;
 * [HeaderLimitsNestedTest] feeds the same kind of header one or two sequence frames
 * deeper. The two blocks are required to differ in **where the field arrives** and in
 * nothing else, so the reading itself — which ceiling applies, where it is compared,
 * what category a breach carries — lives here once and is entered from both. A second
 * implementation for the nested block could make the nested path pass through a
 * comparison the flat path never makes, which is exactly the coverage the nested block
 * exists to deny.
 */

/** The three §6.2.1 receiver caps a case may configure. */
internal val KNOWN_LIMITS: Set<String> = setOf("max_dyn_string_len", "max_dyn_blob_len", "max_dyn_array_count")

/**
 * The schema declares no bound on this field, so the receiver cap is what governs it.
 * The library reads any negative number this way.
 */
internal const val NO_SCHEMA_BOUND: Int = -1

/**
 * No cap stated for this construct.
 *
 * Deliberately *not* a large number: §6.2.1 forbids reading an omitted cap as
 * unlimited, so the library answers `ARGUMENT` for a schema-unbounded field handed no
 * cap. A case bounds exactly one construct, so the other two carry this — and a port
 * that consulted the wrong cap fails loudly with `ARGUMENT` instead of quietly passing.
 */
internal const val NO_CAP_STATED: Int = -1

/** The ceiling lifted, for a negative control only. */
internal const val UNCAPPED: Int = Int.MAX_VALUE

/**
 * Which ceilings a negative control lifts.
 *
 * The distinction is not cosmetic. A receiver cap is a deployment's capacity decision
 * and lifting it is the experiment "did the cap decide this?"; a schema bound is a
 * statement about validity that no receiver reconfigures, so lifting it is a different
 * experiment and the two blocks want different ones. `header_limits` lifts [CAPS] and
 * asserts the schema-bounded case keeps its `INVALID`; `header_limits_nested` is
 * specified to lift [EVERY_CEILING] — the same *kind* the case states — and assert that
 * every rejection changed its answer, because there a second, wholly independent reason
 * to answer `INCOMPLETE` (the frames are still open) makes "the ceiling fired" and
 * "something else rejected an unclosed frame" otherwise indistinguishable.
 */
internal enum class Lift { NOTHING, CAPS, EVERY_CEILING }

/**
 * The two ceilings, as one case states them. Exactly one is ever in play: a
 * schema-bounded field carries [NO_CAP_STATED] for every cap, and a field the schema
 * leaves unbounded carries [NO_SCHEMA_BOUND].
 */
internal class Ceiling(
    val schemaBound: Int,
    val stringCap: Int,
    val blobCap: Int,
    val arrayCap: Int,
)

/**
 * Read the ceiling out of a case, lifting as [lift] says for a negative control.
 *
 * Which entry a case carries is read generically — `limits` names one of three caps and
 * the runner must not assume which — and the two kinds are never configured together:
 * §6.2.1 forbids applying a receiver cap to a field the schema already bounds, so a
 * case carrying both is refused here rather than resolved by picking one.
 */
internal fun ceilingOf(case: JsonObject, lift: Lift = Lift.NOTHING): Ceiling {
    val name = case.name()
    val limits = case["limits"]?.jsonObject
    val schema = case["schema"]?.jsonObject
    require(!(limits != null && schema != null)) {
        "$name carries both limits and schema; §6.2.1 forbids capping a schema-bounded field"
    }
    if (schema != null) {
        require(schema.keys == setOf("maxlen")) { "$name: unknown schema key(s) ${schema.keys}" }
        val bound = if (lift == Lift.EVERY_CEILING) UNCAPPED else schema.int("maxlen")
        return Ceiling(bound, NO_CAP_STATED, NO_CAP_STATED, NO_CAP_STATED)
    }
    requireNotNull(limits) { "$name carries neither limits nor schema" }
    require(limits.size == 1) { "$name states ${limits.size} caps; a case bounds one construct" }
    for (k in limits.keys) require(k in KNOWN_LIMITS) { "$name: unknown receiver cap $k" }
    fun cap(key: String): Int = when {
        limits[key] == null -> NO_CAP_STATED
        lift != Lift.NOTHING -> UNCAPPED
        else -> limits.int(key)
    }
    return Ceiling(
        NO_SCHEMA_BOUND,
        cap("max_dyn_string_len"),
        cap("max_dyn_blob_len"),
        cap("max_dyn_array_count"),
    )
}

/**
 * A visitor that applies the case's ceiling **at the header word**, which is where
 * §6.2.1 puts the enforcement point: "at the count/length header, before the allocation
 * it is meant to prevent".
 *
 * It records what the decoder announced before it decides anything, so a case that
 * rejects still proves the header was read as the case describes.
 *
 * [frames] is the chain of **sequence field ids** the target field is nested in,
 * outermost first, and is empty for the flat block — the whole difference between the
 * two blocks, expressed once. The ceiling is bound to [fieldId] at the innermost depth
 * and to nothing else: a field arriving at any other depth, or under any other id, is
 * left to the decoder to skip, so a runner that wired its cap to the top level caps
 * nothing and the over-ceiling cases answer `INCOMPLETE` and fail.
 */
internal class HeaderDest(
    private val ceiling: Ceiling,
    private val frames: List<Int> = emptyList(),
    private val fieldId: Int = 0,
) : Visitor {
    /** The field-level headers the decoder announced at the target position. */
    val events: MutableList<String> = mutableListOf()

    /** The sequence ids the decoder opened, outermost first — the chain as it arrived. */
    val opened: MutableList<Int> = mutableListOf()

    /**
     * Payload that reached the destination: the bytes of a string/blob, the values of
     * an array. §6.2.1 is "rejected, never clamped", so after a rejection this must
     * still be empty — a read that truncates to the cap, materializes the head and
     * *also* reports the error passes every outcome assertion and fails only here.
     */
    val materialized: MutableList<Byte> = mutableListOf()
    val elements: MutableList<Long> = mutableListOf()

    var subtype: FixlenType? = null
        private set
    var declaredLength: Int = -1
        private set
    var kind: ArrayKind? = null
        private set
    var declaredCount: Int = -1
        private set

    /** The open frame chain, tracked exactly as generated code descends one. */
    private val open: MutableList<Int> = mutableListOf()

    /** At the innermost depth of the chain the case names, and nowhere else. */
    private fun atTarget(id: Int): Boolean = id == fieldId && open == frames

    override fun sequenceBegin(id: Int) {
        open.add(id)
        opened.add(id)
    }

    override fun sequenceEnd() {
        open.removeAt(open.size - 1)
    }

    override fun fixlenBegin(id: Int, subtype: FixlenType, total: Int) {
        if (!atTarget(id)) return
        events.add("fixlen:$id:$subtype:$total")
        this.subtype = subtype
        declaredLength = total
        // The library's own comparison, made at the length word. `string`/`blob` call
        // the same routine, so this is one implementation of the rule applied where
        // §6.2.1 requires it — not a second one (#31).
        when (subtype) {
            FixlenType.STRING -> PayloadAcc.checkStringLength(total, ceiling.schemaBound, ceiling.stringCap)
            FixlenType.BLOB -> PayloadAcc.checkBlobLength(total, ceiling.schemaBound, ceiling.blobCap)
            // fp32/fp64 carry a width the format fixes; there is nothing to bound.
            FixlenType.FP32, FixlenType.FP64 -> Unit
        }
    }

    override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
        if (!atTarget(id)) return
        events.add("array:$id:$kind:$count")
        this.kind = kind
        declaredCount = count
        boundCount(count)
    }

    override fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        if (!atTarget(id)) return
        for (i in 0 until chunkLength) materialized.add(data[chunkOffset + i])
    }

    override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        if (!atTarget(id)) return
        for (i in 0 until chunkLength) materialized.add(data[chunkOffset + i])
    }

    override fun unsigned(id: Int, value: Long) {
        if (atTarget(id)) elements.add(value)
    }

    override fun signed(id: Int, value: Long) {
        if (atTarget(id)) elements.add(value)
    }

    /**
     * A count ahead of its payload, bound exactly as a length is (§6.2.1) — and unlike
     * a wrapper array, which carries no count on the wire and is bound at the element
     * index instead ([Seq], and the `sequence_growth` block).
     *
     * Stated here rather than called out of the library because an array destination is
     * the generated layer's: [Seq.ensureCap] grows one against elements that have
     * actually arrived and never sizes from an announced count, so the count itself is
     * refused by the caller that owns the destination. The categories are the
     * library's, and identical to [PayloadAcc]'s: a declared schema `count` makes an
     * over-count message malformed (MESSAGE_SPEC §7.1); a schema-uncounted array is the
     * receiver cap's, and its breach is the `LIMIT_EXCEEDED` policy category (§6.3) —
     * or `ARGUMENT` where the call stated no cap at all, since §6.2.1 forbids reading
     * an omitted cap as unlimited.
     */
    private fun boundCount(count: Int) {
        if (ceiling.schemaBound >= 0) {
            if (count > ceiling.schemaBound) {
                throw SofabException(
                    SofabError.INVALID_MSG,
                    "element count $count above declared count ${ceiling.schemaBound}",
                )
            }
        } else if (count > ceiling.arrayCap) {
            if (ceiling.arrayCap < 0) {
                throw SofabException(
                    SofabError.ARGUMENT,
                    "max_dyn_array_count not stated (cap ${ceiling.arrayCap}) for count $count",
                )
            }
            throw SofabException(
                SofabError.LIMIT_EXCEEDED,
                "element count $count above configured limit ${ceiling.arrayCap}",
            )
        }
    }
}

/**
 * The bytes a case is fed as: its `chunks` where it has them, otherwise `serialized` in
 * one call. A chunked case must carry the same bytes as the whole string — the verdict
 * is a property of the bytes and not of how they were split (§7.2 item 4), which the
 * case can only test if the two agree.
 */
internal fun chunksOf(case: JsonObject): List<ByteArray> {
    val serialized = case.str("serialized")
    val chunks = case["chunks"]?.jsonArray ?: return listOf(unhex(serialized))
    val parts = chunks.map { it.jsonPrimitive.content }
    assertEquals(serialized, parts.joinToString(""), "${case.name()}: chunks are not the serialized bytes")
    return parts.map { unhex(it) }
}

/**
 * Feed the parts in order and answer the **last** verdict. Every feed before the last
 * must answer `INCOMPLETE`: an earlier verdict would mean the decoder answered on bytes
 * it had not yet seen.
 */
internal fun feedAll(stream: IStream, dest: HeaderDest, parts: List<ByteArray>, name: String): DecodeStatus {
    var last = DecodeStatus.INCOMPLETE
    for ((i, part) in parts.withIndex()) {
        last = stream.feed(part, dest)
        if (i < parts.size - 1) {
            assertEquals(DecodeStatus.INCOMPLETE, last, "$name: chunk $i answered before the case was whole")
        }
    }
    return last
}
