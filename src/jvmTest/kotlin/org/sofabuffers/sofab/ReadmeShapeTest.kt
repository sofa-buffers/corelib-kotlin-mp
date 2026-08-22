/*
 * SofaBuffers Kotlin Multiplatform — guards README.md against CORELIB_PLAN §9.
 *
 * §9 fixes the README's shape for the whole corelib family: "do not change the
 * section ordering and do not invent new top-level sections; that shared shape
 * is the point." A reader who knows one port's README navigates any other by
 * looking in the same place. Nothing inside the library can notice when that
 * shape drifts — a top-level section that exists in no other port, or a missing
 * badge, is invisible to every codec test — so the check has to read the
 * document. It lives in `jvmTest` because that is the source set that may touch
 * the filesystem; `commonTest` runs on Node and native too, where there is no
 * repository to read.
 *
 * What it enforces:
 *
 *   1. §9.1  the centered header block: logo, `# SofaBuffers`, tagline, org link.
 *   2. §9.2  the badge block that opens the library section carries a CI badge,
 *            a coverage badge and a Docs badge, in that order, before any prose.
 *   3. §9    the `## ` sections are exactly the prescribed list, in order:
 *            `## SofaBuffers Kotlin Multiplatform library`, `## Why this design`,
 *            `## Usage`, `## Memory handling`, `## Build & test`, `## Benchmarks`.
 *   4. §9.4  no API-documentation section at any heading level; the Docs badge is
 *            the only pointer to the generated reference.
 *
 * Checks 1-4 guard the document's *shape*. The rest guard its *content*, which
 * is the half a shrink threatens: a section can keep its heading and lose the
 * fact a reader came for, and nothing inside the library notices that either.
 *
 *   5. §9.5  the Usage chapter still shows each example the plan lists.
 *   6. §6.4  the strict-UTF-8 policy is documented. This port has **no**
 *            `SOFAB_STRICT_UTF8` knob to check for, and correctly so: a Kotlin
 *            `String` is a Unicode string type, and §6.4 makes those "always
 *            strict", lets them "omit it entirely", and obliges only
 *            byte-container targets (C `char[]`, Go `string`, …) to expose the
 *            option. So the knob check is skipped and the always-ON policy §6.4
 *            asks such a port to document is checked instead.
 *   7. §9.6  MIN_OUTPUT_BUFFER is stated *in the memory chapter* — the number a
 *            caller needs before it can size a streaming buffer, in the section
 *            they read to find out who allocates what.
 *   8. §6.1.1 no spelling outside the closed generated-object name set.
 *   9. Every in-document link still resolves to a heading.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ReadmeShapeTest {

    private val readme: String = readmeText()
    private val headings: List<Heading> = parseHeadings(readme)

    // --- §9.1 the generic header block --------------------------------------

    @Test
    fun headerBlockIsThePlansGenericOne() {
        val failures = mutableListOf<String>()
        fun require(present: Boolean, what: String) {
            if (!present) failures += "§9.1 $what missing"
        }
        require(
            readme.contains("""<p align="center"><img src="assets/sofabuffers_logo.png""""),
            "the centered logo block",
        )
        require(readme.lineSequence().any { it == "# SofaBuffers" }, "the '# SofaBuffers' title")
        require(readme.contains("<b>Structured Objects For Anyone</b><br>"), "the tagline")
        require(readme.contains("https://github.com/sofa-buffers"), "the link back to the organization")
        report(failures)
    }

    // --- §9.2 the badge block -----------------------------------------------

    /**
     * §9.2 puts the badges first in the library section, ahead of the GitHub
     * link and the summary: everything between the library heading and the first
     * blank line after it. Extra badges are allowed (this port publishes a branch
     * coverage badge beside the line one); the three the plan names are not, and
     * their relative order is fixed.
     */
    @Test
    fun badgeBlockCarriesCiCoverageAndDocsInOrder() {
        val lines = prose(readme).lines()
        val start = lines.indexOfFirst { it.matches(Regex("""^## SofaBuffers .* library$""")) }
        if (start < 0) fail("§9.2 no '## SofaBuffers <Language> library' section to open with badges")
        val block = lines.drop(start + 1).dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() }
        val labels = block.mapNotNull { Regex("""^\[!\[([^]]*)]""").find(it)?.groupValues?.get(1) }
        if (labels.isEmpty()) fail("§9.2 the library section opens with no badge block")

        val failures = mutableListOf<String>()
        for (want in listOf("CI", "Coverage", "Docs")) {
            if (labels.none { it.equals(want, ignoreCase = true) }) {
                failures += "§9.2 badge block carries no $want badge (it has: ${labels.joinToString(", ")})"
            }
        }
        val ranked = labels.map { it.lowercase() }.filter { it in setOf("ci", "coverage", "docs") }
        if (ranked != listOf("ci", "coverage", "docs")) {
            failures += "§9.2 badges out of order: expected CI, coverage, Docs — got ${ranked.joinToString(", ")}"
        }
        report(failures)
    }

    // --- §9 the top-level sections ------------------------------------------

    /**
     * The section list §9 prescribes, in order: §9.2, §9.3, §9.5, §9.6, §9.7,
     * §9.8. (§9.1 is the header block, whose `# SofaBuffers` is the document
     * title; §9.4 forbids an API-documentation chapter.) Anything else at `## `
     * level is an invented section — demote it to a `###` subsection of the
     * chapter it belongs to instead of adding a row here.
     */
    @Test
    fun topLevelSectionsAreThePrescribedListInOrder() {
        val expected = listOf(
            "SofaBuffers Kotlin Multiplatform library",
            "Why this design",
            "Usage",
            "Memory handling",
            "Build & test",
            "Benchmarks",
        )
        val actual = headings.filter { it.level == 2 }.map { it.text }

        val failures = mutableListOf<String>()
        for (name in actual - expected.toSet()) {
            failures += "invented top-level section \"$name\" — §9: \"do not invent new top-level " +
                "sections\"; demote it to a `###` subsection of the chapter it belongs to"
        }
        for (name in expected - actual.toSet()) {
            failures += "missing top-level section \"$name\" (CORELIB_PLAN §9)"
        }
        if (failures.isEmpty() && actual != expected) {
            failures += "§9 fixes the section order: got ${actual.joinToString(", ")}"
        }
        report(failures)
    }

    // --- §9.4 no API-documentation chapter ----------------------------------

    /**
     * At *any* heading level — demoting such a chapter to `###` would not make
     * it allowed.
     */
    @Test
    fun noApiDocumentationSection() {
        val forbidden = setOf("source documentation", "api reference", "api documentation", "api docs")
        val failures = headings
            .filter { it.text.lowercase() in forbidden }
            .map {
                "§9.4 an API-documentation section \"${it.text}\" exists; " +
                    "the Docs badge is the only pointer"
            }
        report(failures)
    }

    // --- §9.5 the Usage chapter's examples ----------------------------------

    /**
     * §9.5 lists the examples every port must carry, and they are what a reader
     * opens Usage for. Dropping one drops a use case, not prose. The wording is
     * the family's; only the code inside each is per-language.
     */
    @Test
    fun usageShowsEveryExampleThePlanLists() {
        val usage = chapter("Usage")
        val subsections = parseHeadings(usage).filter { it.level == 3 }.map { it.text }
        val failures = mutableListOf<String>()
        for (want in listOf(
            "Serialize",
            "Serialize stream",
            "Deserialize",
            "Deserialize stream",
            "OStream",
            "IStream",
            "Generated objects",
        )) {
            if (want !in subsections) failures += "§9.5 Usage has no '### $want' example"
        }
        // "Concise, runnable examples" — the five use cases the plan spells out as
        // examples must each carry code, not just a paragraph. `OStream` and
        // `IStream` are this port's surface tours and are exempt.
        for (want in listOf("Serialize", "Serialize stream", "Deserialize", "Deserialize stream", "Generated objects")) {
            if (want in subsections && !subsection(usage, want).contains("```kotlin")) {
                failures += "§9.5 the '$want' example has no ```kotlin block"
            }
        }
        report(failures)
    }

    // --- §6.4 the strict-UTF-8 policy ---------------------------------------

    /**
     * See the file header, check 6: this port has no `SOFAB_STRICT_UTF8` option
     * to document, because §6.4 gives Unicode string types none to have. What it
     * does oblige is saying so — "documented as always-ON" — since a caller
     * otherwise cannot tell a missing knob from an unimplemented check.
     */
    @Test
    fun strictUtf8PolicyIsDocumentedAsAlwaysOn() {
        if (readme.contains("SOFAB_STRICT_UTF8")) {
            fail(
                "§6.4 the README names SOFAB_STRICT_UTF8, but a Kotlin String is a Unicode string " +
                    "type: this port has no such option. Either the option now exists (make this " +
                    "test check it like a byte-container port's) or the mention is stale.",
            )
        }
        assertTrue(
            readme.contains("always strict"),
            "§6.4 a Unicode-string port is always strict and must document that it is",
        )
    }

    // --- §9.6 MIN_OUTPUT_BUFFER, in the memory chapter ----------------------

    @Test
    fun minOutputBufferIsStatedInTheMemoryChapter() {
        assertTrue(
            chapter("Memory handling").contains("MIN_OUTPUT_BUFFER"),
            "§9.6 the memory chapter never states MIN_OUTPUT_BUFFER",
        )
    }

    /** The stated value is the one the code exposes, not a number that drifted. */
    @Test
    fun minOutputBufferValueMatchesTheConstant() {
        val memory = chapter("Memory handling")
        assertTrue(
            Regex("""MIN_OUTPUT_BUFFER[`*]* is [`*]*${Sofab.MIN_OUTPUT_BUFFER}\b""").containsMatchIn(memory),
            "§9.6 the memory chapter does not state MIN_OUTPUT_BUFFER as ${Sofab.MIN_OUTPUT_BUFFER}",
        )
    }

    // --- §6.1.1 the closed generated-name set -------------------------------

    /**
     * §6.1.1 closes the generated-object layer to encode / decode / try_decode /
     * serialize / deserialize / decoder, and lists the spellings a port must not
     * invent beside them. Teaching one in the docs sends a reader looking for a
     * surface sofabgen does not emit — as effectively as emitting it would.
     */
    @Test
    fun noNameOutsideTheClosedGeneratedObjectSet() {
        val banned = Regex(
            """\b(marshal|unmarshal|serialize_to|to_bytes|from_bytes|decode_from|decode_into)\b""",
            RegexOption.IGNORE_CASE,
        )
        val failures = readme.lines().withIndex()
            .filter { banned.containsMatchIn(it.value) }
            .map { "§6.1.1 line ${it.index + 1}: a name outside the closed set: ${it.value.trim()}" }
        report(failures)
    }

    // --- in-document links resolve ------------------------------------------

    /**
     * A heading that moves takes its anchor with it. That is the cheapest way for
     * a restructuring to break navigation while breaking nothing a build can see.
     */
    @Test
    fun everyInDocumentLinkResolves() {
        val anchors = headings.map { githubAnchor(it.text) }.toSet()
        val links = Regex("""]\(#([^)]+)\)""").findAll(readme).map { it.groupValues[1] }.toList()
        assertTrue(links.isNotEmpty(), "no in-document links found; the link scan is broken")
        report(links.filterNot { it in anchors }.map { "link to #$it matches no heading" })
    }

    // --- helpers ------------------------------------------------------------

    private data class Heading(val level: Int, val text: String)

    private fun report(failures: List<String>) {
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", prefix = "README.md:\n"))
    }

    /** The document with fenced code blocks blanked out: a `# comment` in a shell snippet is not a heading. */
    private fun prose(text: String): String {
        var fenced = false
        return text.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("```")) {
                fenced = !fenced
                ""
            } else if (fenced) {
                ""
            } else {
                line
            }
        }
    }

    private fun parseHeadings(text: String): List<Heading> =
        prose(text).lines().mapNotNull { line ->
            Regex("""^(#{1,6}) +(.*?)\s*$""").find(line)?.let {
                Heading(it.groupValues[1].length, it.groupValues[2])
            }
        }

    /** The body of one `## ` chapter, up to the next one — fences and all, so an example inside it survives. */
    private fun chapter(title: String): String {
        val body = mutableListOf<String>()
        var fenced = false
        var inside = false
        for (line in readme.lines()) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced && line.startsWith("## ")) {
                if (inside) break
                inside = line == "## $title"
                continue
            }
            if (inside) body += line
        }
        if (!inside) fail("README.md: no '## $title' chapter")
        return body.joinToString("\n")
    }

    /** The body of one `### ` subsection of a chapter, up to the next heading. */
    private fun subsection(chapterBody: String, title: String): String {
        val lines = chapterBody.lines()
        val start = lines.indexOfFirst { it == "### $title" }
        if (start < 0) return ""
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("### ") }
        return (if (end < 0) rest else rest.take(end)).joinToString("\n")
    }

    /** Slugify a heading the way GitHub does: lowercase, punctuation dropped, spaces to hyphens. */
    private fun githubAnchor(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')

    private fun readmeText(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "README.md")
            if (candidate.isFile && File(dir, "build.gradle.kts").isFile) return candidate.readText()
            dir = dir.parentFile
        }
        fail("README.md not found from ${System.getProperty("user.dir")}")
    }
}
