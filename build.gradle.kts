/*
 * SofaBuffers Kotlin Multiplatform corelib.
 *
 * SPDX-License-Identifier: MIT
 */
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.jetbrains.dokka") version "2.2.0"
    `maven-publish`
}

group = "org.sofabuffers"
version = "0.1.0"

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // `Visitor` gets real JVM default methods and no `DefaultImpls` shim:
            // the shim is a compatibility artifact for a binary contract this
            // library has never published, and every call through it would be one
            // more hop on the decoder's hottest edge.
            freeCompilerArgs.add("-jvm-default=no-compatibility")
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            // The shared conformance suite is read straight from assets/ — one copy,
            // the one CORELIB_PLAN §8 requires the repo to carry, never a duplicate
            // under a resources directory that could drift from it.
            systemProperty("sofab.vectors", layout.projectDirectory.file("assets/test_vectors.json").asFile.path)
            // The conformance run states how many vectors and checks it executed
            // (CORELIB_PLAN §7.2); those lines are worthless if only the local
            // console ever sees them, so the test JVM's stdout goes to the build
            // log — and therefore into the CI run's output.
            testLogging { showStandardStreams = true }
        }
    }

    js(IR) {
        nodejs()
        browser {
            // The library is browser-consumable, but its tests need no DOM and the
            // suite already runs on Node — so CI is not made to depend on a headless
            // browser being installed.
            testTask { enabled = false }
        }
    }

    // Targets that cross-compile from a Linux CI host. Apple targets build the
    // same commonMain sources and can be added on a macOS runner.
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            // Runtime dependencies: none. The corelib is the standard library only.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Test-only: parse the shared assets/test_vectors.json conformance
            // suite. Values stay exact — kotlinx-serialization keeps a JSON
            // number as its literal text, so a u64 above 2^53 survives.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
}

// --- benchmark tools (CORELIB_PLAN §10, BENCH_SPEC) -------------------------
//
// The three tools live in the JVM source set next to the code they measure, as
// they do in the other ports: `bench` (throughput), `perf` (per-op cost) and
// bench/run_callgrind.sh (instructions retired per op, which drives the same
// workloads through the Callgrind entry point). They are excluded from coverage
// and from the API documentation — runnable mains, not library surface.

val benchRuntime: FileCollection = files(
    tasks.named("jvmJar"),
    configurations.named("jvmRuntimeClasspath"),
)

// --- the allocation measurement runs in a JVM of its own --------------------
//
// AllocationTest reads ThreadMXBean.currentThreadAllocatedBytes around a loop and
// asserts an exact figure. That is sound only once the JIT has reached a steady
// state for the measured code, and downstream of the rest of the suite in a shared
// JVM it has not: the compiler queue and OSR timing differ, a few kilobytes of
// non-codec allocation land inside the measured window, and a cold `./gradlew
// build` went red in 2 of 6 runs for a reason unrelated to the change under review
// (#34). Run alone it is the first thing its JVM does, which is the state §6.6.4's
// measurement assumes. So the suite's JVM excludes the class and a task of its own
// runs it — one `check` depends on, so `./gradlew build` still runs the measurement.
// Naming it once keeps the exclusion and the inclusion from drifting apart.
val ALLOCATION_TEST = "org.sofabuffers.sofab.AllocationTest"

// bench/run_callgrind.sh drives the same workloads through a bare `java` command
// (Callgrind must see one JVM per rep count, with nothing of Gradle's in it), so
// it needs the runtime classpath — the JVM jar plus kotlin-stdlib — as a string.
// The JVM test suite reads README.md (ReadmeGeneratedObjectsTest checks that the
// documented calls are the ones the example implements). Gradle does not know
// that, so a README-only edit would leave jvmTest UP-TO-DATE and the check would
// silently not run. Declaring the file an input is what makes it a real gate.
tasks.named<Test>("jvmTest") {
    inputs.file(rootProject.file("README.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    filter { excludeTestsMatching(ALLOCATION_TEST) }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

val jvmAllocationTest = tasks.register<Test>("jvmAllocationTest") {
    group = "verification"
    description = "The §6.6.4 allocation measurement, alone in its own JVM."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnitPlatform()
    filter { includeTestsMatching(ALLOCATION_TEST) }
    testLogging { showStandardStreams = true }
}

tasks.named("check") { dependsOn(jvmAllocationTest) }

tasks.register("benchClasspath") {
    group = "verification"
    description = "Write the benchmark runtime classpath for bench/run_callgrind.sh."
    val cp = benchRuntime
    val out = layout.buildDirectory.file("bench-classpath.txt")
    inputs.files(cp)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.asPath) }
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "BENCH_SPEC throughput table (MB/s over a ~1 s CPU-time loop)."
    mainClass.set("org.sofabuffers.sofab.bench.BenchKt")
    classpath = benchRuntime
    // Shorten the ~1 s loop for a smoke run: ./gradlew bench -Psofab.bench.seconds=0.05
    systemProperty("sofab.bench.seconds", (project.findProperty("sofab.bench.seconds") ?: "1.0"))
}

tasks.register<JavaExec>("perf") {
    group = "verification"
    description = "BENCH_SPEC per-op cost report (cycles/op + throughput)."
    mainClass.set("org.sofabuffers.sofab.bench.PerfKt")
    classpath = benchRuntime
    // Shorten the ~1 s loop for a smoke run: ./gradlew perf -Psofab.bench.seconds=0.05
    systemProperty("sofab.bench.seconds", (project.findProperty("sofab.bench.seconds") ?: "1.0"))
}

kover {
    reports {
        filters {
            excludes {
                // Runnable benchmark mains, not unit-tested library code.
                packages("org.sofabuffers.sofab.bench")
            }
        }
        verify {
            rule {
                // CORELIB_PLAN §7.3: the family's coverage bar.
                bound { minValue.set(90) }
            }
        }
    }
}

dokka {
    // The module name becomes a path segment in the published site, so it is the
    // repository name rather than a prose title: spaces would be escaped into every
    // link on https://sofa-buffers.github.io/corelib-kotlin-mp/.
    moduleName.set("corelib-kotlin-mp")
    dokkaSourceSets.configureEach {
        // The benchmark tools are runnable mains, not public API (CORELIB_PLAN §9.4:
        // the Docs badge is the single entry point to the API reference, and it
        // should show the library).
        perPackageOption {
            matchingRegex.set(""".*\.bench.*""")
            suppress.set(true)
        }
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/sofa-buffers/corelib-kotlin-mp/tree/main/src")
            remoteLineSuffix.set("#L")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("SofaBuffers CoreLib (Kotlin Multiplatform)")
            description.set(
                "Dependency-free, allocation-light, streaming Kotlin Multiplatform " +
                    "implementation of the SofaBuffers (sofab) serialization format.",
            )
            url.set("https://github.com/sofa-buffers/corelib-kotlin-mp")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}
