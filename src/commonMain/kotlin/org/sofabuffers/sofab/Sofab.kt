/*
 * SofaBuffers Kotlin Multiplatform — library constants.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/**
 * Library-level constants for the SofaBuffers (`sofab`) core.
 *
 * These mirror the normative limits of CORELIB_PLAN §6.2. [API_VERSION] lets
 * callers and the schema-driven code generator verify compatibility at build or
 * run time.
 */
public object Sofab {

    /**
     * SofaBuffers core API version. Callers and the generator check this for
     * compatibility; the current contract is version `1`.
     *
     * This tracks the **wire contract** — normative and identical in every port
     * (CORELIB_PLAN §6.2) — so it moves only when the bytes on the wire change
     * meaning. It is *not* this library's source-compatibility version: a release
     * that renames or removes a Kotlin function bumps the artifact version and
     * leaves `API_VERSION` alone.
     */
    public const val API_VERSION: Int = 1

    /** Largest valid field id, `2^31 - 1` (`INT32_MAX`). */
    public const val ID_MAX: Int = Int.MAX_VALUE

    /**
     * Largest array element count / fixed-length byte count, `2^31 - 1`
     * (`INT32_MAX`).
     */
    public const val ARRAY_MAX: Long = Int.MAX_VALUE.toLong()

    /**
     * Smallest output buffer this port accepts **for streaming** (CORELIB_PLAN
     * §5.1). It is `1`: the encoder splits every atomic unit, so no write has to
     * land contiguously and a single usable byte is enough.
     *
     * **It binds a buffer installed with a [FlushSink]** — at construction and at
     * every mid-stream [OStream.bufferSet], both of which reject
     * `buffer.size - offset < MIN_OUTPUT_BUFFER` with [IllegalArgumentException]
     * where the buffer is handed over, never partway through a message. A buffer
     * installed *without* a sink is subject to no minimum: no flush can occur
     * there, so a caller sizing from a generated `MAX_SIZE` keeps an exact fit.
     *
     * Any size at or above this produces output **byte-identical** to the
     * one-shot path, so sizing a streaming buffer from this constant trades
     * nothing but flush frequency.
     */
    public const val MIN_OUTPUT_BUFFER: Int = 1

    /**
     * Maximum nested-sequence depth (§4.9 / §6.2). An encoder must not open more
     * than `MAX_DEPTH` nested sequences, and a decoder rejects a message that
     * nests deeper with [SofabError.INVALID_MSG], bounding recursion / stack
     * growth.
     */
    public const val MAX_DEPTH: Int = 255
}
