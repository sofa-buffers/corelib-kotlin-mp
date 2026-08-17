/*
 * SofaBuffers Kotlin Multiplatform — fp32 bit-exactness (CORELIB_PLAN §6.5).
 *
 * The JSON test vectors cannot represent NaN, so this is the implementation-level
 * suite §6.5 requires: a signaling, a quiet and a negative fp32 NaN must round-trip
 * bit-for-bit at a scalar position AND at an array position, on EVERY decode
 * surface. Kotlin/JS has no 32-bit float value type, so the raw-bits pair
 * (Visitor.fp32Bits / OStream.writeFp32Bits) is what carries the payload there —
 * and running the same assertions on every target is what keeps that path honest.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Fp32BitExactTest {

    private val patterns = intArrayOf(
        0x7F80_0001.toInt(), // signaling NaN — the payload a widening double destroys
        0x7FC0_0001.toInt(), // quiet NaN with payload
        0xFFC0_0000.toInt(), // negative quiet NaN
        0x7F80_0000.toInt(), // +inf
        0xFF80_0000.toInt(), // -inf
        0x0000_0000, // +0
        0x8000_0000.toInt(), // -0
        0x0000_0001, // smallest subnormal
    )

    /** Captures the raw bits of every fp32 the decoder delivers. */
    private class Bits : Visitor {
        val seen: MutableList<Int> = mutableListOf()
        override fun fp32Bits(id: Int, bits: Int) {
            seen.add(bits)
        }
    }

    private fun feedWhole(wire: ByteArray): List<Int> = Bits().also { IStream().feed(wire, it) }.seen

    private fun feedByteAtATime(wire: ByteArray): List<Int> {
        val v = Bits()
        val input = IStream()
        for (i in wire.indices) input.feed(wire, i, 1, v)
        assertEquals(DecodeStatus.COMPLETE, input.status)
        return v.seen
    }

    @Test
    fun scalarFp32RoundTripsBitForBitOnEveryDecodeSurface() {
        for (bits in patterns) {
            val wire = encode { it.writeFp32Bits(1, bits) }
            assertEquals(listOf(bits), feedWhole(wire), "one feed, ${bits.toString(16)}")
            assertEquals(listOf(bits), feedByteAtATime(wire), "byte at a time, ${bits.toString(16)}")
            // decode -> re-encode reproduces the exact four payload bytes.
            val out = encode { os ->
                IStream().feed(
                    wire,
                    object : Visitor {
                        override fun fp32Bits(id: Int, bits: Int) = os.writeFp32Bits(id, bits)
                    },
                )
            }
            assertContentEquals(wire, out, "re-encode, ${bits.toString(16)}")
        }
    }

    @Test
    fun everyArrayElementRoundTripsBitForBit() {
        val wire = encode(256) { it.writeArrayFp32Bits(2, patterns) }
        assertEquals(patterns.toList(), feedWhole(wire))
        assertEquals(patterns.toList(), feedByteAtATime(wire))
        val out = encode(256) { os ->
            val collected = ArrayList<Int>()
            IStream().feed(wire, object : Visitor {
                override fun fp32Bits(id: Int, bits: Int) {
                    collected.add(bits)
                }
                override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
                    assertEquals(ArrayKind.FP32, kind)
                }
            })
            os.writeArrayFp32Bits(2, collected.toIntArray())
        }
        assertContentEquals(wire, out)
    }

    @Test
    fun theValueHookSeesTheSameFiniteValues() {
        // A consumer that overrides only fp32(value) is served through the default
        // fp32Bits implementation — one forwarding call, same value.
        val values = floatArrayOf(0f, -0f, 1.5f, -3.25e30f, Float.MIN_VALUE)
        val wire = encode(256) { it.writeArrayFp32(3, values) }
        val seen = ArrayList<Float>()
        IStream().feed(wire, object : Visitor {
            override fun fp32(id: Int, value: Float) {
                seen.add(value)
            }
        })
        assertEquals(values.toList(), seen)
    }

    @Test
    fun writeFp32AndWriteFp32BitsAgreeOnFiniteValues() {
        for (v in floatArrayOf(0f, -0f, 3.14159f, Float.MAX_VALUE, Float.MIN_VALUE)) {
            assertContentEquals(
                encode { it.writeFp32(7, v) },
                encode { it.writeFp32Bits(7, v.toRawBits()) },
                "value $v",
            )
        }
    }

    @Test
    fun fp64CarriesItsOwnNaNPayloads() {
        // §6.5: a native double holds all 64 bits verbatim, so no raw path is needed.
        val bits = longArrayOf(
            0x7FF0_0000_0000_0001uL.toLong(), // signaling NaN
            0x7FF8_0000_0000_0001uL.toLong(), // quiet NaN with payload
            0xFFF8_0000_0000_0000uL.toLong(), // negative quiet NaN
        )
        for (b in bits) {
            val wire = encode { it.writeFp64(1, Double.fromBits(b)) }
            var got = 0L
            IStream().feed(wire, object : Visitor {
                override fun fp64(id: Int, value: Double) {
                    got = value.toRawBits()
                }
            })
            assertEquals(b, got, "fp64 ${b.toString(16)}")
        }
    }
}
