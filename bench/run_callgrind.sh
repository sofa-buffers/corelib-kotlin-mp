#!/usr/bin/env bash
#
# SofaBuffers Kotlin — machine-independent instruction cost.
#
# Reports instructions retired per operation (Ir/op) under Callgrind. Unlike
# wall-clock or cycle counts, instruction counts are deterministic and
# independent of the host's clock speed and scheduler, so the numbers compare
# across machines (and against the C/C++/Rust/Go/Java/Python/TypeScript tools —
# the workloads, ids and values are identical).
#
# The JVM has no native `run_<workload>` symbol to `--toggle-collect` on (the hot
# code is JIT-compiled at runtime), so — as BENCH_SPEC prescribes for JIT and
# interpreted ports — each workload is run at two rep counts (R1, R2) and the
# total instruction counts are subtracted:
#
#     Ir/op = ( Ir(R2) - Ir(R1) ) / ( R2 - R1 )
#
# which cancels *all* fixed cost exactly — JVM startup, class loading, JIT
# compilation and the one-time setup — leaving the pure per-op cost. For the
# subtraction to be clean the two runs must differ *only* in the measured rep
# count, so the JVM is pinned to make everything else identical between runs:
#
#   -XX:-TieredCompilation -XX:-BackgroundCompilation -XX:CompileThreshold=2000
#         one synchronous compile tier reached after 2000 invocations;
#         Callgrind.kt's fixed warmup drives the hot methods to C2 before the
#         measured loop, so no tier transition happens during it.
#   -XX:+UseEpsilonGC -Xms/-Xmx equal
#         no garbage collection and a fully-committed heap, so GC and heap
#         growth add no variable instructions (the bounded run never fills it).
#   -XX:hashCode=2
#         a constant identity hashCode, removing the last startup
#         non-determinism (the default scheme seeds identity hashes from a
#         per-run PRNG).
#
# What survives the subtraction is a small, run-to-run-stable startup jitter; the
# measured rep delta per workload is chosen so that jitter stays a negligible
# fraction of the reported per-op number. Cheap ops (typical) use a large delta;
# the 1000-element array and composite ops carry a big per-op signal already, so a
# smaller delta keeps them fast without losing precision. The two `blob 1MB`
# *encode* rows use a delta of two (BENCH_SPEC: R1=1, R2=3 is enough) — a megabyte
# of copying per op is slow under Callgrind, and the subtraction cancels fixed cost
# just as well at three reps as at three hundred.
#
# `encode: blob 1MB passthrough` is BENCH_SPEC's one optional row and is absent:
# this port implements no pass-through (CORELIB_PLAN §5.1 makes it a MAY), so the
# row is omitted rather than filled with a placeholder.
#
# Prereqs: valgrind, a JDK (with EpsilonGC — OpenJDK 11+), and the Gradle wrapper.
# Usage:   bash bench/run_callgrind.sh
#          WORKLOADS="encode_composite decode_composite" bash bench/run_callgrind.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Per-workload measured rep counts (R1 R2): cheap ops need a large delta so the
# startup jitter is negligible; expensive ops need only a small one.
#
# `decode: blob 1MB` is deliberately *not* in the blob class. A decode hands the
# visitor a window into the input and copies nothing, so its per-op cost is a walk
# over 245 chunks — thousands of instructions, not the million the two encode rows
# pay — and a delta of two ops would sit inside the run-to-run startup jitter and
# can come out negative. It gets the ordinary delta, and is fast at it.
REPS_CHEAP="${REPS_CHEAP:-10000 110000}"
REPS_ARRAY="${REPS_ARRAY:-200 1200}"
REPS_BLOB="${REPS_BLOB:-1 3}"
reps_for() {
    case "$1" in
        encode_blob_oneshot|encode_blob_streaming) echo "$REPS_BLOB";;
        *_u64_array|*_composite|*_composite_skip|decode_blob) echo "$REPS_ARRAY";;
        *)                                        echo "$REPS_CHEAP";;
    esac
}

if ! command -v valgrind >/dev/null 2>&1; then
    echo "error: valgrind not found (needed for instruction counts)." >&2
    echo "       install it, e.g.  apt-get install valgrind" >&2
    exit 1
fi

echo ">> building (./gradlew -q benchClasspath) ..." >&2
./gradlew -q benchClasspath
# The JVM jar plus its runtime dependencies (kotlin-stdlib); written by the
# `benchClasspath` task so this script never has to guess Gradle's layout.
CP="$(cat "$ROOT/build/bench-classpath.txt")"
if [ -z "$CP" ]; then
    echo "error: empty benchmark classpath — did ./gradlew benchClasspath run?" >&2
    exit 1
fi
MAIN="org.sofabuffers.sofab.bench.CallgrindKt"
JVM_FLAGS=(
    -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC
    -Xms1g -Xmx1g
    -XX:-TieredCompilation -XX:-BackgroundCompilation -XX:CompileThreshold=2000
    -XX:hashCode=2
)

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# Order follows BENCH_SPEC's table.
WORKLOADS="${WORKLOADS:-encode_u64_array encode_typical encode_blob_oneshot \
encode_blob_streaming encode_composite decode_u64_array decode_typical \
decode_blob decode_composite decode_composite_skip}"

run_cg() { # $1 workload, $2 reps, $3 tag
    valgrind --tool=callgrind --callgrind-out-file="$OUT/$3.out" \
        java "${JVM_FLAGS[@]}" -cp "$CP" "$MAIN" "$1" "$2" \
        >/dev/null 2>"$OUT/$3.log"
}

ir_of()    { grep -m1 '^summary:' "$OUT/$1.out" 2>/dev/null | awk '{print $2}'; }
bytes_of() { grep -ohE 'bytes=[0-9]+' "$OUT/$1.log" 2>/dev/null | head -1 | cut -d= -f2; }

label() {
    case "$1" in
        encode_u64_array)      echo "encode: u64 array (1000)";;
        encode_typical)        echo "encode: typical message";;
        encode_blob_oneshot)   echo "encode: blob 1MB one-shot";;
        encode_blob_streaming) echo "encode: blob 1MB streaming";;
        encode_composite)      echo "encode: composite";;
        decode_u64_array)      echo "decode: u64 array (1000)";;
        decode_typical)        echo "decode: typical message";;
        decode_blob)           echo "decode: blob 1MB";;
        decode_composite)      echo "decode: composite";;
        decode_composite_skip) echo "decode: composite skip-all";;
    esac
}

echo ">> Measuring instructions/op under Callgrind (two rep counts per workload; this is slow) ..." >&2
echo
echo "==============================================================================="
echo " SofaBuffers Kotlin instruction cost   (Callgrind, Ir/op)"
echo " instructions/op: lower is better. Deterministic & machine-independent."
echo "==============================================================================="
printf "%-26s %16s %9s\n" "Workload" "instr/op" "bytes"
printf "%-26s %16s %9s\n" "--------" "--------" "-----"

for w in $WORKLOADS; do
    read -r r1 r2 <<<"$(reps_for "$w")"
    run_cg "$w" "$r1" "$w.lo"
    run_cg "$w" "$r2" "$w.hi"
    lo="$(ir_of "$w.lo")"; hi="$(ir_of "$w.hi")"
    b="$(bytes_of "$w.hi")"
    iperop="$(awk -v lo="${lo:-0}" -v hi="${hi:-0}" -v ops="$(( r2 - r1 ))" \
        'BEGIN{ if (ops>0) printf "%d", (hi-lo)/ops; else print "-" }')"
    printf "%-26s %16s %9s\n" "$(label "$w")" "${iperop:--}" "${b:--}"
done
echo
echo "Ir = instructions retired (Callgrind). Independent of CPU clock and OS"
echo "scheduling; depends only on the executed code, so it compares across machines."
echo "The blob 1MB rows are read against each other: their gap is the divisible-run"
echo "path of CORELIB_PLAN §5.1, with the JVM's array-copy strategy folded in."
