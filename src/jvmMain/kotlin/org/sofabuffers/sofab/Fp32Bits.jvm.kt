/*
 * SofaBuffers Kotlin/JVM — the raw-bits view of an fp32 array.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/** A `Float` holds every binary32 pattern unchanged here: no view is needed. */
internal actual fun fp32BitsView(a: FloatArray): IntArray? = null
