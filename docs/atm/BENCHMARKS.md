# Benchmark overview

Snapshot: **2026-11-06**, ATM on Effekt v0.3.0. This is the single-file outline
of all benchmarks, their contracts, source adaptations, limitations and commands.
The archived paper's performance evaluation has not been reproduced. The current
supplement lists all 52 programs with numbered adaptation notes and measured
typechecking times.

## Counts and meaning

| Inventory | Total | Current status |
| --- | --- | --- |
| Local `.effekt` examples | 83 | 58 accepted, 21 contract negatives, 1 protocol negative, 3 unsupported |
| Nested-handler inference examples | 6 | 5 infer evidence/ATCs and run to `true`; 1 reconstructs residual evidence but has undefined ATC inference |
| Pinned coar ARM inputs | 52 OCaml files | All 52 have ports: 31 accepted, 21 contract rejections |
| Archived submission inventory | 81 rows: 59 ARM + 22 Lexa | 52 mapped upstream ports; 29 archived rows unported |

The 41 previously pending upstream files now have translations. These inventories
overlap: the 52 ARM ports are part of the 83 examples. Universal input/output
contracts are checked; concrete `main` results are separate runtime witnesses.
Rejected ARM files must fail with `ContractFailure`, not unsupported syntax or
an unavailable solver. The three unsupported local examples are not counted as
negative successes. `Int` uses mathematical integers, not OCaml overflow.

**Upstream label discrepancy:** `distribution-SAT.ml` and `distribution-UNSAT.ml`
at the pinned commit have the same executable code and assertion. The only
addition to the latter is `(*unsat*)`. Both ports preserve that bounded-weight
assertion and accept. Thus **51 results match the original filename labels**;
one known mismatch is recorded separately in both inventories and reports.
This accepted upstream port does not validate the paper's distribution UNSAT row.

Mapping an upstream program does not establish equivalence to its Lexa translation.
In particular, seven ARM variants and all 22 Lexa programs remain outside this
migration. Several helper APIs are specialized, as detailed below.

## ARM families and checked properties

Every source file is linked by name in [the 52-file manifest](arm-manifest.json),
with its pinned upstream hash, port hash, original label and adaptation notes.
All ports are in [examples/atm/arm](../../examples/atm/arm).

| Family | Files | Universal property / false variant | Runtime witness |
| --- | ---: | --- | --- |
| `expectation` | 2 | `p*x+(100-p)*y`; negative swaps weights | `600` |
| `choose-sum` | 2 | Sum of four differences is `0`; negative claims `x+y` | `0` |
| `choose-max` | 2 | Under ordered inputs, maximum difference is `v-x`; negative claims `u-y` | `2` |
| `safe-div-1` | 2 | Zero divisor returns `None`; negative claims otherwise | `true` |
| `safe-div-2` | 2 | Nonzero divisor returns exactly `Some(n/m)`; negative negates equality | `true` |
| `state-easy` | 1 | Nonnegative initial state gives a nonnegative result | `8` |
| `state` | 2 | Counter result equals every nonnegative initial state; negative claims inequality | `8` |
| `amb-*` | 6 | Empty search fails; a supplied matching first/second element succeeds; simplified Boolean search finds the match. Negatives claim all nonempty searches fail or an arbitrary first element must match | `true` |
| `bfs`, `bfs-simpl` | 3 | For `n` in `{2,3}`, breadth-first square-root search returns `Success(n)`; negative claims failure. Simplified search returns `a` or `-a` | `true` |
| `choose-all` | 2 | Ordered results `[0,x-y,y-x,0]`; negative swaps middle entries | `true` |
| `deferred-*` | 3 | For an arbitrary thunk returning `r`, forcing returns `r`; negative claims `r+1`. Integer variant creates its thunk internally | `true` |
| `distribution` | 2 | For `0<=p<=100`, every output probability is between 0 and 100; both upstream assertions are identical | `true` |
| `io-read-*` | 4 | Zero reads succeeds; one read from a nonempty input succeeds; positive reads from empty input fail. Negative claims zero reads fails | `true` |
| `io-write-*` | 4 | Empty/nonempty input produces empty/nonempty output, paired with unit; negatives reverse each property | `true` |
| `queue-*` | 3 | Append 42, dequeue twice: empty initial queue yields `None`, nonempty initial queue yields `Some`; negative flips the empty case | `true` |
| `round-robin` | 2 | Final counter is at least its arbitrary initial value; negative claims equality | `true` (`8` becomes `12`) |
| `modulus` | 2 | The two supplied sequences give equal maximum queried indices; negative claims inequality; callback preconditions are retained | `true` |
| `select` | 2 | First-success collector for `[x,-x]` returns `Success([x])`; negative negates equality | `true` |
| `shift` | 2 | Three resumed calls give `8*n+7`; negative claims `4*n+3` | `true` (`3` becomes `31`) |
| `transaction` | 2 | Result is not 2 and is either 0 or 42; negative claims 2 | `true` (both commit and abort exercised) |
| `yield` | 2 | Arbitrary tree of zero leaves becomes a tree of one leaves; negative requires leaf values of 2 | `true` |

There is no upstream `state-easy-UNSAT.ml`. A separate regression rejects
`result >= 1` for arbitrary nonnegative input. The paper's nested state example
with result exactly 3 is a different, still unported program.

## Adaptations and limits of the ports

All ports use lexical operations, explicit contracts, monomorphic data, and unary
functions for curried arguments. Handler helpers are checked at their benchmark
instances; their general OCaml APIs are not automatically inferred. Operation
preconditions/invariants are annotations, not inferred unknown predicates.
Executable recursion is checked for partial correctness, without a termination proof.

| Families | Material adaptation |
| --- | --- |
| `amb`, `io-read`, `io-write`, `bfs`, `state-UNSAT` | Extra operation arguments carry annotation context: head/tail, remaining read count, remaining write tail, expected branch results, or the current counter. The upstream `state-SAT` already has its context arguments; `state-UNSAT` does not. Original branch computations and resumptions remain |
| `state`, `queue`, `select`, `deferred`, `yield` | Exact-region local arrows type state answers and stored continuations; a local-arrow alias can anchor an enclosing call region |
| `deferred`, `yield` | Functions/continuations are stored in real datatype fields and invoked later; no eager thunk evaluation or precomputed iterator result |
| `distribution` | Higher-order `map`/`foldRight` now invoke supplied callbacks. Their contracts and list datatype remain instantiated to this benchmark; recursive scaling, insertion, duplicate merging and integer division remain |
| `safe-div` | Encode `MyExn` as an aborting lexical operation, inline `optionize`, and omit the unperformed polymorphic `Dummy` operation; native exceptions and the general helper interface are not ported |
| `bfs` | Specialized to each benchmark's single Select. Thread the private queue through `runNext`; move failure dispatch from the return clause to the caller of the dequeued continuation. Keep actual continuation closures and FIFO true/false jobs |
| `round-robin` | Replace private queue/counter references with explicit scheduler state. Reify Increment/Yield/Spawn, install child handlers separately, and enqueue actual continuations. The scheduler performs every increment and FIFO dispatch |
| `transaction` | Replace the private reference with the committed handler result: commit returns final state; exception returns original zero. Retain both Boolean branches and all updates/lookups |
| `modulus` | Specialize `mu` to the benchmark's `f`; still call each supplied sequence through Call, multiply by zero, and track the maximum index |
| `shift` | Omit the inert recursive reset around `f`: this benchmark's `f` and resumed suffix perform no further Shift. Keep the Shift operation and three actual resumption calls. **General recursive shift/reset remains unported** |

The supplement uses the following numbered adaptation notes, attached as
superscripts to program names and repeated in section 3.3. Multiple notes can
apply to the same port; BFS has both 1 and 6.

| Marker | Families | Adaptation |
| --- | --- | --- |
| 1 | `bfs`, `bfs-simpl` | Single-choice specialization and explicit continuation queue |
| 2 | `modulus` | Specialize the higher-order helper to the supplied functional |
| 3 | `shift` | Omit an inert nested reset in this benchmark |
| 4 | `round-robin` | Explicit scheduler state and reified suspended steps |
| 5 | `transaction` | Return the committed state instead of updating a private reference |
| 6 | `amb`, `io-read`, `io-write`, `bfs`, `state-UNSAT` | Add operation arguments carrying annotation context |
| 7 | `safe-div` | Exception encoding and specialized helper interface |
| 8 | `distribution` | Monomorphic data and callback contracts |

The 20 unmarked programs are structurally close benchmark instances, subject to
the common limitations above. Markers describe adaptations, not equivalence
proofs. The manifest retains the original principal-class audit metadata;
[the inference report](results/arm-inference.json) records the current,
possibly overlapping numbered notes. Cross-backend tests compare executions
of the same port, not the original OCaml program against its translation.
The [rebuilt supplement](../../output/pdf/atm-supplement.pdf), section 3,
describes the Effekt implementation and lists all programs with **Time (ms)**
and numbered adaptation markers. The two side-by-side blocks share the same
program/time format; there are no evidence/ATC inference columns. Its current table replaces
the old submission table; the 81-row inventory remains archived provenance.

The separate Core inspection confirms reconstructed evidence and operation ATCs
in all 31 successful elaborations. All those ATCs are the hole context; the
`evidence-two-delimiters.effekt` regression exercises a nonzero-depth ATC.
The other 21 programs fail their contracts before a complete elaboration is
produced. These facts are recorded in the inspection report, not presented as
per-program inference columns. Refinement contracts, recursive invariants, and
answer endpoints remain annotated. The distribution label discrepancy has a
separate dagger marker.

These adaptations are part of the artifact, not claims of general mutable
references, fully polymorphic datatypes, or arbitrary callbacks beneath new
handlers. The BFS, scheduler, modulus and shift translations need these caveats
when used to compare source languages or annotation burden.

New shared support comprises exhaustive flat constructor matching, total logical
measures with structural recursion, dependent/refined constructor fields, and
closed pure function fields at the top region. Measures are checked definitions,
not assumed axioms; recursive calls must decrease on a direct recursive field.
They do not provide automatic induction or invariant inference. Functions are
projected to unit in logical datatype views; runtime equality on any datatype
containing functions is rejected. General region-polymorphic datatype fields,
computation/ATC/evidence/distance kinds and polymorphic operation signatures
remain incomplete. See [ARM.md](ARM.md) for syntax and [FIDELITY.md](FIDELITY.md)
for the paper boundary.

## Nested-handler inference examples

These six programs are maintained under [examples/atm/infer](../../examples/atm/infer).
They supplement the ARM ports, whose inferred ATCs are all the hole context.
The five successful examples have operations at evidence distances and ATC depths
0, 1, and 2. The supplement reports typechecking time, evidence inference, and
ATC inference in a dedicated table for this suite; the ARM table keeps its
program/time columns.

| Program | Purpose |
| --- | --- |
| [active-three-counts.effekt](../../examples/atm/infer/active-three-counts.effekt) | Three active handlers, with evidence/ATC depths 0, 1, and 2 and observable clause results. |
| [active-three-multishot.effekt](../../examples/atm/infer/active-three-multishot.effekt) | Two outer resumptions restore the two intervening handlers. |
| [nested-residuals.effekt](../../examples/atm/infer/nested-residuals.effekt) | Abstract helper-call evidence composes with three local handler edges; callers instantiate distances 0 and 1. |
| [mixed-answer-layers.effekt](../../examples/atm/infer/mixed-answer-layers.effekt) | An outer operation infers a Bool layer around an Int layer, preserving both initial answers. |
| [shadowed-capabilities.effekt](../../examples/atm/infer/shadowed-capabilities.effekt) | Aliases retain the identities of outer handlers despite repeated source name h. |
| [polymorphic-outer-call.effekt](../../examples/atm/infer/polymorphic-outer-call.effekt) | Residual evidence is reconstructed from an abstract caller region, but its symbolic distance prevents ATC inference. |

`polymorphic-outer-call` is rejected as `Unsupported`, not a false contract.
Evidence reconstruction succeeds with an undischarged residual witness, while
ATC inference is undefined. The table uses a qualified evidence checkmark and
an ATC cross; the evidence result does not claim a complete elaboration.
The checker observation records both stages directly.

Eleven focused regressions check complete evidence and ATC certificates, residual
abstraction/discharge, and shadowed handler identity. Two tests construct invalid
variants with an incompatible ATC hole type and too few answer layers. All five
positive programs run to `true` in the interpreter and both Chez optimization
modes. Their Boolean results are runtime witnesses, not statically established
refinements of `main`. The [manifest](infer-manifest.json) and
[inference report](results/infer-inference.json) record hashes and certificates.

Use `python3 docs/atm/benchmark_arm.py inspect --suite infer`, then
`render --suite infer --omit-timing` before collecting timings. The timing command
is `python3 docs/atm/benchmark_arm.py time --suite infer --warmups 2 --repetitions 5`;
`render --suite infer` fills the table. Both suites use the timing method below.
The six-program run has per-program medians of **0.14–1.50 ms**; the
unsupported row measures time to report the symbolic-distance limitation.
[Raw samples](results/infer-timing.json) include the environment and source hashes.

## Paper-only rows and motivating examples

The seven additional ARM rows in the paper, all unported, are:

- `amb-3-UNSAT.lx`, `bfs-simpl-UNSAT.lx`, `deferred-2-UNSAT.lx`, `io-read-2-UNSAT.lx`.
- `io-read-3-UNSAT.lx`, `queue-2-UNSAT.lx`, `state-easy-UNSAT.lx`.

All 22 paper Lexa programs are unported:

- `ackermann.lx`, `bezout.lx`, `catalan.lx`, `countdown.lx`, `fibonacci_recursive.lx`.
- `golomb.lx`, `handler_sieve.lx`, `hofstadterq.lx`, `interruptible_iterator.lx`, `iterator.lx`.
- `karatsuba.lx`, `latticepath.lx`, `listiter.lx`, `logger.lx`, `nqueens.lx`.
- `palindrome_partition.lx`, `product_early.lx`, `resume_nontail.lx`, `resume_nontail_2.lx`, `tree_explore.lx`.
- `triples.lx`, `two_threads_ackermann.lx`.

The seven motivating-example groups are separate from the archived table's 81 rows:

| Group | Current coverage |
| --- | --- |
| Lexical selection | Illustration of lexical dispatch; the paper's two-file capability example is not fully mapped |
| ATM introduction | Illustration of an answer-type change, not a full transcription |
| Nested lexical ATM | Regression for composed evidence and depth-two ATC; exact paper program remains to be mapped |
| File management | Partial token protocol; complete nested state-passing handlers remain to be ported |
| Polymorphic close helper | Unported; needs the full kinded/evidence/ATC interface and polymorphic operation contracts |
| Nested state refinements | Unported; preserve both handlers and establish result exactly `3` |
| GUI interactor | Unported; nested file/GUI state handlers and invalid ordering cases remain |

## The other 25 local examples

Together with the 52 ARM files and six inference examples above, this lists
every current `.effekt` file.
These small examples exercise implementation features and regressions; they are
not additional migrated paper benchmarks.

| File | Purpose / status |
| --- | --- |
| [`callback-under-handler.effekt`](../../examples/atm/callback-under-handler.effekt) | **Unsupported:** callback invoked beneath a later caller-owned delimiter needs a caller/ATC-indexed contract |
| [`capability-argument.effekt`](../../examples/atm/capability-argument.effekt) | Accepted: preserve capability identity and its operation contract through a local function |
| [`capability-polymorphic.effekt`](../../examples/atm/capability-polymorphic.effekt) | Accepted: instantiate one capability-polymorphic helper at different handlers |
| [`composed-effects.effekt`](../../examples/atm/composed-effects.effekt) | Accepted with narrowed annotations: explicit composable answer endpoints |
| [`effect-polymorphic.effekt`](../../examples/atm/effect-polymorphic.effekt) | Accepted with narrowed annotations: explicit initial/final answer value-type parameters |
| [`effectful-callback.effekt`](../../examples/atm/effectful-callback.effekt) | Accepted: a local callback with an answer-changing contract |
| [`evidence-functions.effekt`](../../examples/atm/evidence-functions.effekt) | Accepted: defer calls and reroute residual obligations through one call witness |
| [`evidence-two-delimiters.effekt`](../../examples/atm/evidence-two-delimiters.effekt) | Accepted: composed runtime evidence and a depth-two ATC |
| [`fidelity/mixed-guard-dependent.effekt`](../../examples/atm/fidelity/mixed-guard-dependent.effekt) | Accepted: dependent returned-value assumptions in mixed pure/impure joins |
| [`fidelity/operation-checked-lambda.effekt`](../../examples/atm/fidelity/operation-checked-lambda.effekt) | Accepted: check an inline lambda against an operation parameter type |
| [`fidelity/recursive-extra-capture.effekt`](../../examples/atm/fidelity/recursive-extra-capture.effekt) | Accepted: ordinary recursion under an unused handler without extra capture-set subtyping |
| [`file-protocol-invalid.effekt`](../../examples/atm/file-protocol-invalid.effekt) | **Intentional negative:** read after close requires `Open` but receives `Closed` |
| [`file-protocol.effekt`](../../examples/atm/file-protocol.effekt) | Accepted: answer-state token protocol; no operating-system file is opened |
| [`higher-order.effekt`](../../examples/atm/higher-order.effekt) | Accepted: ordinary higher-order function application |
| [`int-to-bool.effekt`](../../examples/atm/int-to-bool.effekt) | Accepted: an `Int` continuation and `Bool` handler answer |
| [`large-integer.effekt`](../../examples/atm/large-integer.effekt) | Accepted: exact integer arithmetic beyond JavaScript Number precision |
| [`multiple-capabilities.effekt`](../../examples/atm/multiple-capabilities.effekt) | **Unsupported:** relative-region/evidence/ATC constraints between two abstract handlers |
| [`multishot.effekt`](../../examples/atm/multishot.effekt) | Accepted: invoke one captured continuation more than once |
| [`nested-outer.effekt`](../../examples/atm/nested-outer.effekt) | Accepted: invoke an outer handler across an inner delimiter |
| [`polymorphic-callback.effekt`](../../examples/atm/polymorphic-callback.effekt) | Accepted: callback contract at an abstract capability region |
| [`refinement-polymorphic.effekt`](../../examples/atm/refinement-polymorphic.effekt) | Accepted: explicit rigid value-type and refinement-predicate polymorphism |
| [`repeatable-effect.effekt`](../../examples/atm/repeatable-effect.effekt) | Accepted with narrowed annotations: repeated callbacks with equal answer endpoints |
| [`return-clause.effekt`](../../examples/atm/return-clause.effekt) | Accepted: nonidentity return clause on normal completion and resumption |
| [`return-effects.effekt`](../../examples/atm/return-effects.effekt) | Accepted: the return clause invokes an outer capability after the inner delimiter exits; `return effect` is a compatibility spelling |
| [`stored-resumption.effekt`](../../examples/atm/stored-resumption.effekt) | Accepted: return and later invoke a resumption through its exact-region local arrow |

The earlier local-function increment repaired ten examples: seven through language
support and three through narrower generic annotations. The two earlier unsupported
examples remain, alongside the explicit symbolic-distance limitation in `infer`. The current negatives are 21 ARM contracts and the invalid
file protocol. `distribution-UNSAT.effekt` is accepted for the source-label reason
above; it is not a successful negative test.

Fixed-region functions are explicit declarative forms, not rules silently added
to ordinary `A_Abs` inference. Return clauses now follow the revised `A_HRet`,
with `return effect` retained as a compatibility spelling. Operations still use
the paper's residual/evidence machinery and partial ATC inference. Full declarative target
checking and Definition 17's type translation remain unfinished.

## Run the benchmarks

Run from this checkout with its pinned Kiama submodule, Java/sbt, Z3 and Chez.
The full differential tests also require Node. The recorded environment uses
Java 27, sbt 1.9.7, Z3 5.1.0 and Chez 10.4.1. `EFFEKT_ATM_Z3` and
`EFFEKT_ATM_CHEZ` select nondefault executables. Release/CI packaging is unfinished.

```sh
# Check all 83 local examples against their separate expected categories.
python3 docs/atm/check_examples.py

# Check available paper ports and retain all unported rows in the JSON report.
python3 docs/atm/paper_coverage.py --run --output /tmp/atm-paper-coverage.json

# Run one supported benchmark; expected output: 8.
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/state-easy-SAT.effekt'

# Differential execution and negative regressions.
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests'
```

Both Python runners build once by default. A zero exit code means the inventoried
expectations match, not that all programs are accepted. Missing solver support
cannot count as an UNSAT success. Source hashes and compiler fingerprints are
recorded in the reports. These checking times include process startup and are
not a performance comparison with the archived submission.

To validate the programs and generate the supplement's timing table:

```sh
# Inspect checked Core certificates without measuring time, then render a draft.
python3 docs/atm/benchmark_arm.py inspect
python3 docs/atm/benchmark_arm.py render --omit-timing

# After reviewing the draft, measure checking in a warmed JVM and render again.
python3 docs/atm/benchmark_arm.py time --warmups 2 --repetitions 5
python3 docs/atm/benchmark_arm.py render
```

The runner compiles and exports the test classpath automatically. An existing
fresh export can be supplied with `--classpath-file PATH`. The timing mode parses
all programs before measurement and times only `Checker.check`, including
refinement solving with a fresh checker cache. It excludes parsing, JVM startup,
Core inspection and backend execution. It runs two full warm-up rounds followed
by five measured rounds in deterministically shuffled order; each table entry
is the median in milliseconds. These are per-program checking costs, including
time to reject false contracts, not a comparison with historical Lexa timings.
Raw samples and environment details are written to `results/arm-timing.json`;
the generated table is `results/arm-table.tex`. The 2026-11-06 run on an Apple M1
Pro (16 GiB, macOS 27.0.1, OpenJDK 27, Z3 5.1.0) produced per-program medians
from **27.8 to 381.2 ms**, with all 52 outcomes stable across the seven rounds.
The supplement PDF was updated and reviewed before this timing run began.

The implicit-resumption migration passed **271 ATM tests** and Scala.js
compilation, followed by **81 integration tests** against a concurrent checker
update; the preceding historical Core/Lifted run passed **35 tests**.
The [validation record](VALIDATION.md) distinguishes these runs. Accepted ARM witnesses were compared
against an independent source interpreter and both direct Lifted/Chez and legacy
Effekt/JavaScript, with and without optimization. Tests and SMT checks establish
implementation behavior, not a mechanized soundness or translation theorem.

## Provenance and maintenance

The upstream input is `hiroshi-unno/coar` at
`d293ee6759bd285f4a496b727f1df85e70f30f3e`, directory
`benchmarks/OCaml/popl24/arm`. The archived paper inventory comes from the supplied
`popl27-paper152-supplemental.pdf`, Table 1. The candidate Lexa source snapshot
has 21 files differing from its recorded HEAD and has not been established as
the submitted artifact. In particular, its `safe-div-2` assertions differ from
upstream; these ATM ports retain upstream's exact equality to `Some(n/m)`.
No historical paper timings are reused.

This outline is maintained with [arm-manifest.json](arm-manifest.json),
[paper-coverage.json](paper-coverage.json),
[example results](results/examples.json), and
[paper-port results](results/paper-coverage.json). Those files retain hashes,
per-row provenance and diagnostics. The source/code details are in
[ARM.md](ARM.md), [REJECTED-EXAMPLES.md](REJECTED-EXAMPLES.md),
[FIDELITY.md](FIDELITY.md), and [VALIDATION.md](VALIDATION.md).
