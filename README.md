<p align="center"><img src="assets/sofabuffers_logo.png" alt="SofaBuffers" height="140"></p>

# SofaBuffers

<b>Structured Objects For Anyone</b><br>
<i>... so optimized, feels amazing.</i>

[Would you like to know more?](https://github.com/sofa-buffers)

## SofaBuffers Kotlin Multiplatform library

[![CI](https://github.com/sofa-buffers/corelib-kotlin-mp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sofa-buffers/corelib-kotlin-mp/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-kotlin-mp%2Fbadges%2Fcoverage.json)](https://github.com/sofa-buffers/corelib-kotlin-mp/actions/workflows/ci.yml)
[![Branches](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-kotlin-mp%2Fbadges%2Fbranches.json)](https://github.com/sofa-buffers/corelib-kotlin-mp/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-dokka-blue)](https://sofa-buffers.github.io/corelib-kotlin-mp/)

[GitHub repository](https://github.com/sofa-buffers/corelib-kotlin-mp)

A **dependency-free**, **allocation-light**, **streaming** implementation of the
SofaBuffers (*sofab*) serialization format for **Kotlin Multiplatform** — one
`commonMain` codec that runs on the JVM, on Node and in the browser, and as a
native binary, with the *same wire bytes* on every one of them.

Like protobuf's `CodedInputStream` / `CodedOutputStream`, this library is the
runtime stream core, driven by **generated code**: a schema-driven generator emits
one class per message plus the `serialize` / `deserialize` pair that calls the
primitives here. Decoding uses the **visitor pattern**, so a generated message is
typically a single `when` over the field id. The wire format is specified
language-neutrally in the
[SofaBuffers documentation](https://github.com/sofa-buffers/documentation).

The hot paths are the ones the reference Java port uses — an eight-byte store per
varint, a cursor advanced over a contiguous buffer, no per-field allocation —
reached from common code through a per-target `expect`/`actual` for the
little-endian word access alone (byte-array `VarHandle`s on the JVM, indexed
shifts elsewhere). Measured in instructions retired per op, it lands **within
about 1 % of `corelib-java`** on the shared workloads, and ahead of it on one
(see [Benchmarks](#benchmarks)).

### Targets and coordinates

| Target | Built here | Tested here |
|---|---|---|
| `jvm` (JVM 17+) | ✅ | ✅ `jvmTest` |
| `js` (IR: Node + browser) | ✅ | ✅ `jsNodeTest` |
| `linuxX64` | ✅ | ✅ `linuxX64Test` |
| `linuxArm64` | ✅ (cross-compiled) | on an arm64 host |

Apple and Windows targets compile the same `commonMain` sources and are one line
each in `build.gradle.kts`; the default target set is what the Linux CI runner
builds and tests.

```kotlin
// settings/build.gradle.kts of the consuming project
dependencies {
    implementation("org.sofabuffers:corelib-kotlin-mp:0.1.0")
}
```

The import namespace is the package `org.sofabuffers.sofab` — the family's fixed
`sofab` namespace under the organisation's group.

### Requirements

- **Kotlin 2.4+** and **Gradle 9.7.1** to build (the wrapper pins both).
- **JDK 17+** for the JVM target and for running the build.
- **Node 18+** for the JS tests (the Kotlin JS plugin provisions its own).
- **valgrind** for `bench/run_callgrind.sh` (the `.devcontainer/` image installs it).

### Dependencies

- **Runtime: none** — the Kotlin standard library and nothing else, on every target.
- **Test-only:** `kotlin-test`, and `kotlinx-serialization-json` to parse the shared
  `assets/test_vectors.json` with exact `u64` precision. Neither reaches the
  published artifacts.

## Why this design

| Goal | How |
|------|-----|
| Streaming **out** | `OStream` writes into a small caller-owned `ByteArray` and hands each full buffer to a `FlushSink`, so a message can exceed the buffer — and RAM. `MIN_OUTPUT_BUFFER` is **1**, and any size at or above it produces byte-identical output. |
| Streaming **in** | `IStream.feed` accepts arbitrarily small chunks; a message may split at any byte boundary, and string / blob payloads arrive in pieces. Malformed bytes throw `SofabException(INVALID_MSG)`; running out of bytes mid-field is **not** an error — `feed` suspends and resumes. `status` afterwards tells a `COMPLETE` message from a truncated `INCOMPLETE` one; it never throws, and there is no finish/finalize step. A rejection is **terminal** and `reset()` is what clears it. |
| Chunking costs only what straddles | Whole fields are decoded by a cursor advanced over the buffer; only the one construct that would run past the end of the supplied bytes goes through the resumable byte-at-a-time machine, and the rest of the chunk goes straight back to the bulk path — inside an array as much as between fields. A boundary in a 200-element array costs one element, not the remainder (`StreamingTest` asserts it). |
| No allocation after construction | State lives in caller-provided arrays plus one small object per direction, whose fixed-size working state — the encoder's `MAX_DEPTH` hold-back run, the decoder's 8-byte scalar landing zone — is sized in the constructor. After that, `write`, `flush` and `feed` allocate nothing at all, and no wire number sizes anything. Scalars stay primitive (`Long` / `Double`) — no boxing on the hot path on JVM and native. |
| Sparse sequence framing, still one pass | `writeSequenceBeginLazy` holds a sequence header back until a child is actually written, so a sequence-typed **field** with no content is omitted rather than framed empty — decided in a single forward pass, with no sub-message buffering. `writeSequenceEnd` drops such a sequence; `writeSequenceEndKeep` forces the frame out where it carries information (a wrapper-array **element**, whose presence gives the array its length). Held-back ids are encoder state, not buffer content, so a one-byte output buffer still produces the one-shot bytes, and the run reaches the full `MAX_DEPTH` (255), sized once when the `OStream` is constructed. |
| Bit-exact floats on every target | `fp32` travels as **raw wire bits**: the decoder calls `Visitor.fp32Bits`, whose default widens to `Float` and calls `fp32`, and `OStream.writeFp32Bits` writes them back. That pair is what makes a signaling NaN round-trip on Kotlin/JS, where every `Float` is a double and passing the value through one sets the quiet bit. A value consumer overrides `fp32` and never sees the difference. |
| One codec, four runtimes | The whole encoder and decoder are `commonMain`. Only the little-endian word access is per-target (`expect`/`actual`), and the same `commonTest` suite runs on JVM, Node and native. |
| No reflection, no runtime codegen | Plain function calls and one interface with default methods. Fits GraalVM native-image, Kotlin/Native and locked-down runtimes alike. |
| Reserve-offset | `OStream(buf, offset)` leaves room at the front for a lower-layer protocol header, saving a copy — and a sink can re-arm that room in every flushed unit. |
| Generated-code friendly | Every `Visitor` member has a default no-op, so a sink overrides only what it needs; unknown fields are skipped by doing nothing. `arrayBulk` lets a consumer hand back a destination array and take a whole integer array without a callback per element. |

## Usage

`SofabException` is a `RuntimeException`: Kotlin has no checked exceptions, so
every failure below is documented rather than declared.

### Serialize

```kotlin
import org.sofabuffers.sofab.OStream

val buf = ByteArray(64)                 // caller owns the buffer
val os = OStream(buf)
os.writeUnsigned(1, 42)
os.writeSigned(2, -7)
os.writeString(3, "hi")
val used = os.bytesUsed                 // bytes written to buf
```

### Serialize stream

Give `OStream` a `FlushSink` and it writes into a small window, handing each full
buffer to the sink and resuming at the buffer's start:

```kotlin
val window = ByteArray(16)              // a tiny buffer is enough
val os = OStream(window, 0, FlushSink { data, off, len -> out.write(data, off, len) })
repeat(1000) { os.writeUnsigned(it, it.toLong()) }
os.flush()                              // push the tail
```

A sink that *takes* the buffer instead of copying out of it — a zero-copy
transport — must hand the encoder a replacement before it returns, and the cursor
then starts at that installation's offset. That is also how a sink reserves
framing-header room in **every** flushed unit:

```kotlin
lateinit var os: OStream
os = OStream(fresh(), 3) { data, off, len ->
    transport.send(data, off, len)      // takes the array
    os.bufferSet(fresh(), 3)            // re-arms 3 header bytes in the next unit
}
```

Returning **without** `bufferSet` means the sink copied: the same buffer stays
active and writing resumes at offset 0.

### Deserialize

Decoding is push-based: implement `Visitor` and override only the field kinds you
care about — every member defaults to a no-op, so unhandled fields are skipped.

```kotlin
import org.sofabuffers.sofab.IStream
import org.sofabuffers.sofab.Visitor

class My : Visitor {
    var a = 0L
    var b = 0L
    override fun unsigned(id: Int, value: Long) { if (id == 1) a = value }
    override fun signed(id: Int, value: Long) { if (id == 2) b = value }
    // fp32Bits/fp32, fp64, string, blob, arrayBegin, sequenceBegin, ... as needed
}

val sink = My()
val input = IStream()
input.feed(buf, 0, used, sink)
check(input.status == DecodeStatus.COMPLETE)
```

`fixlenBegin(id, subtype, total)` announces a string / blob / float field at its
length word, before any payload byte, so a schema `maxlen` can be rejected there
rather than after the payload assembles.

### Deserialize stream

All parse state lives inside `IStream`, so feed it whatever chunks arrive — even
one byte at a time — and it resumes across boundaries. String and blob payloads
arrive as one or more chunks tagged with the field `total` length and the chunk
`offset`, so a payload never has to be held in one piece:

```kotlin
val input = IStream()
val chunk = ByteArray(4096)
while (true) {
    val n = source.read(chunk)
    if (n <= 0) break
    input.feed(chunk, 0, n, object : Visitor {
        override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
            // append data[chunkOffset ..< chunkOffset + chunkLength] to your sink
        }
    })
}
// The caller's framing decides when the input is over; a still-INCOMPLETE status
// at that point is a truncated message, not a decoder error.
```

A fed chunk is **borrowed only for the duration of the call**: the decoder keeps
no view into it, so the array above may be refilled the moment `feed` returns.

### OStream

`OStream(buffer, offset = 0, sink = null)` is the whole encoder surface:
`writeUnsigned` / `writeSigned` / `writeBoolean`, `writeFp32` / `writeFp32Bits` /
`writeFp64`, `writeString` / `writeBlob` / `writeFixlen`, `writeArrayUnsigned` /
`writeArraySigned` / `writeArrayFp32` / `writeArrayFp32Bits` / `writeArrayFp64`
(one overload per element width, so a `ShortArray` costs no widening pass), the
sequence trio, plus `flush()`, `bufferSet(buffer, offset)`, `reset(buffer)` and
`bytesUsed`. Every array writer takes the natural Kotlin primitive array.

### IStream

`IStream()` holds the decode state: `feed(data, visitor)` or
`feed(data, off, len, visitor)`, the `status` property (`COMPLETE` / `INCOMPLETE` /
`INVALID`), and `reset()` to start the next message — which is also the only way
out of a latched `INVALID`.

### Generated objects

The common real use is driving this runtime through **generated code**. The
generator emits one class per message whose whole wire surface is the family's
closed name set — `encode()` and `decode(bytes)` for the one-shot 90 % case,
`serialize(ostream)` and `deserialize(...)` for the streaming pair underneath, and
`decoder()` for the incremental reader:

```kotlin
val person = Person()
person.name = "Ada"
person.age = 36

val bytes = person.encode()                 // one-shot convenience
val back = Person.decode(bytes)             // one-shot convenience

person.serialize(ostream)                   // streaming out, buffer < message

val dec = Person.decoder()                  // streaming in
var st = dec.feed(chunk1)
st = dec.feed(chunk2)                       // COMPLETE / INCOMPLETE / INVALID
val assembled = dec.value                   // built incrementally, never fully buffered
```

Both halves are the same code path — the one-shot pair is a thin wrapper over the
streaming one — so a message that streams and a message that fits in a buffer
produce identical bytes. Generated code is also where the **schema** rules live
that a corelib cannot know: sparse omission against declared defaults,
`maxlen` / `count` / integer-width bounds reported as `INVALID`, the skip of a
field whose wire type contradicts the schema, and UTF-8 validation of a
materialized string via `Utf8.decode`.

## Generated-code support layer

Around every codec call, generated code does the same few things: put an element
at the index its id names, grow an array as elements actually arrive, reassemble a
payload that arrived in pieces, turn validated bytes into a `String`, drain the
flush sink of a message too large to size up front. None of that is
schema-specific — a `count`, a `maxlen` or a capacity is an argument, an element
type an overload — so it lives here instead of being emitted into every generated
package.

| symbol | what it is |
|---|---|
| `Seq.reserveRowBytes` … `reserveRowBooleans`, `Seq.reserveRowList` | place a matrix row at the index its element id names, filling a gap with the empty row rather than shifting every later row down (MESSAGE_SPEC §5.1 / §7.4) |
| `Seq.ensureCap` (one overload per array type) | the array-growth policy: double, stop at the announced count, and never allocate from a count the wire claimed but has not delivered |
| `Seq.ARRAY_INIT_CAP`, `Seq.EMPTY_BYTES` … `EMPTY_BOOLEANS` | the bounded first reservation, and the shared zero-length arrays a field initializer points at |
| `Seq.boolsToBytes` | the one materialization the encode side still needs: `bool` travels as an unsigned `0`/`1`, the one element kind with no `writeArray*` overload of its own |
| `PayloadAcc` | a growable byte sink for the two places the size is not known in advance — reassembling a `string` / `blob` payload split across feeds, and draining an `OStream`'s `FlushSink` as a `FlushSink` itself. A payload that arrives whole never touches its buffer, and the value never depends on where the split fell |
| `Utf8.decode` | validate a byte range and materialize it, in that order — the only order in which invalid UTF-8 can still be rejected (§6.4) |

Kotlin's unsigned arrays are `value` classes over their signed peers rather than
subtypes of a common array interface, so there are eleven of most of these: one
growth rule, eleven element widths. Two things stay with generated code: the
**size** of the encode scratch buffer, and its allocation. All of it is ordinary
public API, usable directly.

## Memory handling

The library never allocates the payload buffer; the API is `ByteArray`-based
throughout, with state in caller-provided arrays plus one small object per
direction.

- **Output buffer (encoding).** The caller owns and sizes it; the encoder writes
  straight in with an advancing cursor and **never grows or reallocates it**. When
  it fills, a `FlushSink` (if set) receives `[0, bytesUsed)` and writing resumes at
  the start of the *same* buffer — so a message can exceed the buffer or RAM; with
  no sink, a full buffer raises `BUFFER_FULL`. The sink's array is reused once the
  call returns, so a sink that keeps the bytes must copy them — **or take the
  buffer** and install a replacement with `bufferSet(buffer, offset)` before
  returning. The **start offset belongs to the installation, not to the buffer**:
  it is consumed by the flush it was made in, so a sink wanting header room in
  *every* unit re-arms it on each flush.
  **`MIN_OUTPUT_BUFFER` is `1`** (`Sofab.MIN_OUTPUT_BUFFER`): the encoder splits
  every atomic unit — no varint, string run or array element has to land
  contiguously — so one usable byte is enough, and any size at or above it produces
  output byte-identical to the one-shot path. **It binds a buffer installed *with* a
  sink**, at construction and at every `bufferSet`, both of which reject
  `buffer.size - offset < MIN_OUTPUT_BUFFER` with `IllegalArgumentException` where
  the buffer is handed over — never partway through a message. A buffer installed
  **without** a sink is subject to no minimum — **including a zero-length one**:
  no flush can occur, so it either holds the message or raises `BUFFER_FULL`, and
  sizing from a generated `MAX_SIZE` stays exact, down to the all-default message
  whose `MAX_SIZE` is `0`.
  **Pass-through is not implemented**: a sink never receives memory other than the
  buffer it was given.
  A field is written with as few stores as its shape allows — a multi-byte varint
  is assembled in a register and stored eight bytes at a time, a one-byte header and
  one-byte value go out as one two-byte store, and a whole `fp32` field as a single
  eight-byte one — so a store may reach past the field it wrote, leaving up to
  **seven scratch bytes** just past the write position. They are never part of the
  message, sit strictly between `bytesUsed` and the end of the buffer, are
  overwritten by the next write, and are never flushed: read back only
  `[0, bytesUsed)`. Bytes before the starting `offset` are never touched, and a
  buffer with fewer than ten bytes free falls back to the byte-at-a-time path, so
  small buffers see no scratch writes at all.
  `writeString` encodes UTF-8 **directly into the buffer**, with no intermediate
  array, and is **always strict**: a Kotlin `String` is a Unicode string type, so
  the only value it can hold that is not well-formed UTF-8 is an unpaired
  surrogate, and such a string is refused with `ARGUMENT` **before** any byte is
  written, never lossily replaced. The byte-container door,
  `writeFixlen(id, data, from, length, FixlenType.STRING)`, validates its range with
  `Utf8.valid` and refuses a malformed payload the same way; `FixlenType.BLOB` is
  the type for opaque bytes and is never validated.
- **Input buffer (decoding).** The caller owns the bytes and must keep them alive
  for the duration of the `feed` call — no longer. `feed` runs a cursor over that
  array; scalars and floats are passed by value (`Long` / `Double` / raw `Int`
  bits), and a `string` or `blob` is **passed through the callback** in pieces, as
  `(id, total, offset, data, chunkOffset, chunkLength)` — the payload's total, this
  piece's offset, and the caller's own buffer. That is the second of the two routes
  a value may take to a caller: the bytes are the caller's own input, nothing the
  decoder produced, and the range is valid **only until the callback returns**, so
  a consumer that keeps the value copies it first — `PayloadAcc` and `Utf8.decode`
  are what generated code copies it with. Nothing the decoder hands out outlives
  the call that delivered it: there is no payload position, no "valid until the
  next feed" value, and `feed` is the only decode surface, so no one-shot entry
  point differs from it.
- **No wire value decides an allocation in the codec.** Not a count, not a length,
  not a chunk boundary. There is **no library-owned accumulator for
  chunk-straddling fields**: a `string` / `blob` split across feeds arrives in
  pieces and is joined in storage the consumer owns, and the only working state the
  codec keeps — the decoder's 8-byte scalar landing zone, the encoder's
  `MAX_DEPTH`-sized hold-back run — is sized from constants of the format when the
  `IStream` / `OStream` is constructed and never afterwards. Reuse via `reset()`
  keeps that state; a codec built per message pays for it once, in its constructor.
- **The static helper layer allocates; the codec does not.** `Seq`, `PayloadAcc`
  and `Utf8` ship in this library for reuse but are not part of the codec: they run
  on the generated layer's behalf, from inside a visitor callback or from a caller,
  and the storage they take belongs to whoever called them. Their buffers are not a
  codec allocation.

| Buffer | Allocated by | Owned by | Must outlive |
|---|---|---|---|
| output `ByteArray` | caller | caller | until `flush()` returns / `bytesUsed` is read |
| flushed range `[0, bytesUsed)` | caller | encoder, reused after the call | the `FlushSink.flush` call (unless the sink takes the buffer) |
| input chunk | caller | caller | the `feed` call |
| string / blob window | caller | caller | the `string` / `blob` callback |

The hot path allocates nothing on either side. That is measured, not only
stated: `AllocationTest` (JVM) counts thread allocation across a complete encode
and a complete decode and holds the count at zero after construction. Kotlin/JS
and Kotlin/Native offer no per-thread allocation counter, so on those targets the
property is **untested** — the codec is one `commonMain` source set, so it is the
same code the JVM measurement covers. On Kotlin/JS a `Long` is additionally a heap
object the runtime creates to represent a value; its size comes from the type, not
from the message.

## Build & test

```bash
./gradlew build          # compile every target, run the full suite on JVM, Node and native
./gradlew jvmTest        # the suite on the JVM only
./gradlew allTests       # every target's tests, aggregated
./gradlew koverHtmlReport koverVerify   # coverage report, and the family's >90% gate
```

The suite is the shared conformance vectors — read straight from
`assets/test_vectors.json`, the copy the repo carries anyway, never a duplicate
that could drift — driven through encode, chunked encode, decode, chunked decode,
skip-ids and roundtrip, plus the malformed-input and truncation tables, the
chunk-lifetime scrub, the taking- and copying-sink handovers, the `arrayBulk`
narrowing rules and the fp32 signaling-NaN round trip.

Most of the suite is in `commonTest`, so it runs unchanged on every target — the JS
and native legs are what prove the portable little-endian path produces the JVM's
bytes. Only the vector- and benchmark-driven tests are JVM-only, because they read
files and drive the tools.

## Benchmarks

Three runnable tools mirror the other ports' tooling — same workloads, same output
grammar, so results are comparable across languages:

```bash
./gradlew perf                 # per-op cost
./gradlew bench                # throughput (MB/s)
bash bench/run_callgrind.sh    # instructions/op (needs valgrind)
```

The workload set is the family's, defined once in
`src/jvmMain/kotlin/org/sofabuffers/sofab/bench/Workloads.kt` and driven by all
three tools: a 1000-element `u64` array, a small mixed `typical` message, an
unbounded **1 MB `blob`** encoded both one-shot and streamed through a 4096-byte
buffer with a flush sink (and decoded from 4096-byte chunks), and a **`composite`**
message holding what the flat datasets never reach — a wrapper array with a header
per element, 320 bytes of non-ASCII UTF-8, nesting at depth 3, a field equal to its
default that the encoder must *not* write, and a two-byte field header. The encoded
sizes are cross-port parity checks, and this port hits all of them: **170** bytes
for the `perf` message, **1,000,005** for the blob, **956** for the composite.
`BenchSpecTest` holds the tools to the spec's datasets, row set and grammar.

- **`perf`** reports thread-CPU-time ns/op (the JVM exposes no portable cycle
  counter, so the `cycles/op` line prints the spec's fallback).
- **`bench`** reports encode / decode throughput in MB/s over a ~1 s CPU-time loop.
- **`run_callgrind.sh`** reports **instructions retired per op** under Callgrind —
  deterministic and independent of clock speed and scheduler, so the numbers compare
  across machines and against the sibling ports. It runs each workload at two rep
  counts and subtracts, which cancels JVM startup, class loading and JIT cost.

**Read the two `blob 1MB` encode rows against each other, not against the rest.**
Five of that message's bytes are metadata and a million are payload, so their
MB/s is this machine's memory bandwidth rather than a statement about the
library. `decode: composite skip-all` shares a JVM with `decode: composite`, so
the visitor call sites inside `IStream` see both sinks and neither row runs
monomorphic; what not-decoding is worth shows up in `Ir/op`, where each workload
gets its own JVM.

Measured figures are not reproduced here — they belong to the cross-language
benchmark arena, which runs every port on one host under one methodology. This
section says how to obtain them, not what they came out as.

The exact workloads, timing rules and output grammar are specified in the
[SofaBuffers documentation](https://github.com/sofa-buffers/documentation).
