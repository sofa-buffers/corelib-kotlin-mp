package org.sofabuffers.sofab

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * The README's `### Generated objects` surface, as code that compiles and runs —
 * and a check that the README still shows calls this code implements.
 *
 * A hand-written snippet in a Markdown file stops matching the API the moment the
 * API moves, and nothing notices until a reader copies it. [Person] below is a
 * hand-written stand-in for what `sofabgen` emits, built only out of the runtime
 * this repo ships, and the mirror test fails if the README's snippet reaches for
 * something this class does not have. Nothing here asserts what the README
 * *says* — only that the code in it is code that exists, and that it works.
 */
class ReadmeGeneratedObjectsTest {

    /** What `sofabgen --lang kotlin` emits, reduced to the surface §6.1.1 closes. */
    class Person {
        var name: String = ""
        var age: Long = 0

        fun serialize(os: OStream) {
            if (name.isNotEmpty()) os.writeString(1, name)
            if (age != 0L) os.writeUnsigned(2, age)
        }

        fun encode(): ByteArray {
            val buf = ByteArray(MAX_SIZE)
            val os = OStream(buf)
            serialize(os)
            return buf.copyOf(os.bytesUsed)
        }

        companion object {
            const val MAX_SIZE: Int = 64

            fun decode(bytes: ByteArray): Person = decoder().also { it.feed(bytes) }.value

            fun decoder(): PersonDecoder = PersonDecoder()
        }
    }

    /** The incremental reader `decoder()` hands back. */
    class PersonDecoder : Visitor {
        val value: Person = Person()
        private val istream = IStream()
        private val nameBytes = PayloadAcc()

        val status: DecodeStatus get() = istream.status

        fun feed(chunk: ByteArray) {
            istream.feed(chunk, this)
        }

        override fun unsigned(id: Int, value: Long) {
            if (id == 2) this.value.age = value
        }

        override fun string(
            id: Int,
            total: Int,
            offset: Int,
            data: ByteArray,
            chunkOffset: Int,
            chunkLength: Int,
        ) {
            if (id != 1) return
            nameBytes.string(total, offset, data, chunkOffset, chunkLength)?.let {
                value.name = it
            }
        }
    }

    @Test
    fun theGeneratedSurfaceRoundTripsOneShot() {
        val person = Person().apply { name = "Ada"; age = 36 }

        val bytes = person.encode()
        val back = Person.decode(bytes)

        assertEquals("Ada", back.name)
        assertEquals(36L, back.age)
    }

    @Test
    fun theStreamingPairProducesTheOneShotBytes() {
        val person = Person().apply { name = "Ada"; age = 36 }
        val oneShot = person.encode()

        // A window far smaller than the message must yield identical bytes.
        val out = ArrayList<Byte>()
        val os = OStream(ByteArray(4), 0, FlushSink { data, off, len ->
            for (i in off until off + len) out.add(data[i])
        })
        person.serialize(os)
        os.flush()

        assertContentEquals(oneShot, out.toByteArray())
    }

    @Test
    fun theDecoderAssemblesFromOneByteChunks() {
        val bytes = Person().apply { name = "Ada"; age = 36 }.encode()

        val dec = Person.decoder()
        for (b in bytes) dec.feed(byteArrayOf(b))

        assertEquals(DecodeStatus.COMPLETE, dec.status)
        assertEquals("Ada", dec.value.name)
        assertEquals(36L, dec.value.age)
    }

    @Test
    fun theReadmeSnippetCallsOnlyWhatThisClassImplements() {
        val readme = File("README.md")
        assertTrue(readme.exists(), "run the tests from the repository root")
        val block = kotlinBlockOf(readme.readText(), "### Generated objects")

        // Every member the snippet reaches for must exist on the stand-in above.
        val members = listOf("encode()", "decode(", "serialize(", "decoder()", "feed(", ".value")
        val self = File(SELF_PATH).readText()
        for (m in members) {
            assertTrue(block.contains(m), "the README snippet no longer shows `$m`")
            assertTrue(self.contains(m), "this test does not implement `$m`, which the README calls")
        }
    }

    private fun kotlinBlockOf(doc: String, heading: String): String {
        val i = doc.indexOf(heading)
        assertTrue(i > -1, "README has no `$heading` section")
        var rest = doc.substring(i)
        val end = rest.indexOf("\n## ", heading.length)
        if (end >= 0) rest = rest.substring(0, end)
        val s = rest.indexOf("```kotlin")
        assertTrue(s > -1, "`$heading` has no ```kotlin example")
        rest = rest.substring(s + "```kotlin".length)
        val e = rest.indexOf("```")
        assertTrue(e > -1, "unterminated code fence")
        return rest.substring(0, e)
    }

    private companion object {
        const val SELF_PATH =
            "src/jvmTest/kotlin/org/sofabuffers/sofab/ReadmeGeneratedObjectsTest.kt"
    }
}
