# Count-Min Sketch (Java)

A small, dependency-free implementation of the Count-Min Sketch — the
probabilistic data structure behind heavy-hitter detection, trending topics,
and traffic accounting wherever storing an exact counter per item is too
expensive.

The guarantee is one-sided and therefore easy to reason about: the estimate
for an item is **never below its true count**, and with width
`w = ceil(e / ε)` and depth `d = ceil(ln(1 / δ))` it overshoots by at most
`ε · N` with probability at least `1 − δ`, where `N` is the total number of
items added.

## What it does

- **Frequency estimation** (`CountMinSketch`) — `add` / `estimate` over any
  item type, sized either from error bounds (`ε`, `δ`) or explicit
  width/depth.
- **Conservative update** — an optional mode that only increments the
  counters currently equal to the item's minimum estimate. It can only reduce
  overestimation, at the cost of one extra pass over the `d` rows.
- **Merging** — two sketches with identical dimensions and seeds add
  together, so counts collected on separate machines combine without shipping
  the raw events.
- **Serialization** — `toByteArray` / `fromByteArray` persist a sketch
  (dimensions, seeds, counters) for checkpoints or shipping between services.
- **CLI demo** — pipe tokens in and see true counts next to estimates, which
  makes the one-sided error visible immediately.

## Design decisions

1. **Double hashing from one 64-bit mix, not `d` independent hash
   functions.** Each item's `hashCode` is spread with SplitMix64, then one
   per-row seed derives that row's column. This keeps the code small while
   giving each row an effectively independent column choice.
2. **Conservative update as a construction-time option, not a flag on
   `add`.** Mixing modes inside one sketch would break the error analysis
   and make merged sketches meaningless; the mode belongs to the sketch.
3. **Seeded construction for reproducibility.** Random per-instance seeds
   are the honest default for production, but tests and demos need stable
   numbers — `seeded(...)` gives them without a second code path.
4. **Synchronized methods, no lock striping.** The table is tiny
   (width × depth longs), so a single monitor is cheap and obviously
   correct. For extreme write throughput, shard by item across sketches and
   merge — which the merge API already supports.
5. **No dependencies, no build tool required.** A plain `javac` + `jar`
   script keeps the project runnable anywhere a JDK exists; the only external
   jar is JUnit, used for tests only.

## How to run

Requires JDK 17+.

```bash
./build.sh                                  # compile + build the jar
echo "apple banana apple cherry banana apple" | java -jar out/count-min-sketch.jar
java -jar out/count-min-sketch.jar --conservative --top 5 access.log
```

Options: `--epsilon <e>` (default 0.001), `--delta <d>` (default 0.01),
`--conservative`, `--top <n>`, and an optional input file (stdin otherwise).
Tokens are whitespace-separated.

Example output:

```
sketch: 2719x5 (epsilon=0.0010, delta=0.0100, conservative=false), 6 tokens, 3 distinct
item                                   true     estimate    overcount
apple                                     3            3            0
banana                                    2            2            0
cherry                                    1            1            0
```

## How to run tests

```bash
./build.sh test       # compile + run the JUnit 5 suite
```

The test run uses the JUnit Platform console standalone jar (downloaded
from Maven Central on first use, or set `JUNIT_JAR` to point at one you
already have). The suite covers the no-undercount guarantee, the error
bound on seeded data, conservative-vs-standard accuracy, merging,
serialization round-trips, and argument validation.

## Tech stack

- Java 17+ (built and tested on JDK 21), no runtime dependencies
- JUnit 5 for tests
