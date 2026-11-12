# ATM validation record

Latest implementation validation: **2026-11-09** (operation annotation syntax below).
The preceding **2026-11-06** validation used Effekt v0.3.0
(`cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`) and its pinned Kiama revision
`ab6aef78fc4ce99a9ad2db8555718d00b03f7b8a`, using Chez Scheme 10.4.1.
These are implementation checks, not a correspondence or soundness proof.
The preceding migration-only results are preserved in
[history/v0.3.0-migration-validation.md](history/v0.3.0-migration-validation.md).
The single-file suite outline is [BENCHMARKS.md](BENCHMARKS.md).

## Operation annotation syntax (2026-11-09)

Operation calls use `h.op[E; K](v)` for both annotations and `h.op[X](v)`
for either one. A lone name is resolved by its scoped kind, including kind
parameters and lexical aliases. Concrete evidence and context expressions are
also accepted singly. The tagged operation syntax remains compatible; tagged
arguments to general kind applications are unchanged. The two annotated examples
and [ANNOTATIONS.md](ANNOTATIONS.md) now use the concise operation syntax.

JVM test compilation and Scala.js compilation passed. All **86 tests** in
`AtmParserTests`, `AtmExplicitAnnotationTests`, `AtmInferTests`, and
`AtmCapabilityNamesTests` passed. These include 11 new operation-syntax tests
covering both annotations, either omission, kinds independent of name spelling,
kind parameters, lexical alias shadowing, nested/composed contexts and evidence,
legacy syntax, and rejection of wrong kinds, unbound names, invalid endpoints,
and incorrect depths. Executable annotation cases agree between the independent
source interpreter and direct Chez with optimization enabled and disabled.

Logs: `/private/tmp/atm-kind-operation-annotations-build.log` and
`/private/tmp/atm-kind-operation-annotations-tests.log`. This was a focused
regression run; the preceding full ATM run remains recorded below. No benchmark
timing was rerun.

## Bare capability names (2026-11-09)

The frontend now accepts `invoke(h)`, `val saved = h`, and `f{h}` directly.
Value bindings and handler/capability bindings shadow one another lexically.
Resolved occurrences retain the existing capability identity, type, capture,
and region checks. The two capability-passing examples now use bare names;
legacy `cap(h)` values and the type annotation `cap(h)` remain accepted.

JVM test compilation and Scala.js compilation passed. All **313 ATM tests**
in 16 suites passed, including **12 new capability-name regressions**. The new
positive cases agree between the independent source interpreter, direct Chez,
and the retained JavaScript backend. Negative cases reject non-capability
shadows and escaping handler capabilities. The existing local-function suite
also executes the migrated examples with optimization enabled and disabled.

Logs: `/private/tmp/atm-bare-capabilities-build.log`,
`/private/tmp/atm-bare-capabilities-focused.log`, and
`/private/tmp/atm-bare-capabilities-all-tests.log`. No benchmark timing was rerun.

## Explicit annotation validation (2026-11-09)

The complete run passed **334 tests**: 300 ATM tests and 34 concrete Core tests.
A subsequent review added one rejection for an evidence kind that refers to an
implicit function's fresh caller region; the final focused run passed all
**28 explicit-annotation tests**. These runs cover **335 distinct tests**.
Both `effektJVM/Test/compile` and `effektJS/compile` passed after that final change.

The new suite checks symbolic ATC/evidence parameters instantiated at depths
0, 1, and 2, composed contexts and witnesses, alpha-renamed kind telescopes,
lexical aliases, pure and impure computation-type arguments, dependent-layer
scope, handler escape, and invalid endpoints/depths. Accepted executable cases
agree between the independent source interpreter and direct Chez-Lift with
optimization on and off. The two-capability helper retains both abstract
capabilities. Core mutation testing independently rejects an incorrect symbolic
ATC certificate. Another test records the remaining lowering restriction for
abstract ATC applications used as computation results.

The full test command used `org.junit.runner.JUnitCore` with every concrete
`Atm*Tests` class and `LambdaLiftingTests`, `OptimizerTests`,
`PatternMatchingTests`, `PolymorphismBoxingTests`, `RenamerTests`, and `SizeTests`
in `effekt.core`, using the freshly compiled JVM test classpath. `CoreTests` is a
shared trait, not a runnable suite. Logs are in
`/private/tmp/effekt-annotations-20261109/{final-tests,final-scope-tests,scope-compile}.log`.
[ANNOTATIONS.md](ANNOTATIONS.md) documents the syntax and backend boundaries.

The PLDI27 implementation paragraph was updated, rebuilt with `omake -j2`, and
visually checked on PDF page 20. Evaluation remains one paragraph at the end of
Section 6.2. The benchmark timing files remain archived measurements from the
preceding compiler snapshot; this change does not claim fresh performance data.

## Commit preparation validation

The compiler commit `74821da99` was validated in an isolated checkout of the
staged files and the pinned Kiama source, without the ARM directory. It passed
**211 ATM tests, 34 core tests, and the boxed-capability JavaScript regression**,
and Scala.js compilation. After adding the ARM/artifact files, **73 tests passed**:
59 ARM tests, three benchmark-reporting tests, and eleven inference tests. The
universal state-property regression now lives in the ARM suite so the compiler
commit can be tested independently. These runs cover **273 distinct ATM tests**.

Checking all **83 committed examples** again produced **58 accepted, 21 contract
rejections, one protocol rejection, and three unsupported cases**, with zero
inventory mismatches. The current [report](results/examples.json) records their
source and compiler hashes. All 52 upstream ARM files match the pinned manifest.
The new untracked `file-polymorphic.effekt` draft is outside this inventory.

A TODO comment added to `polymorphic-outer-call.effekt` after the supplement
measurements changed its file hash without changing the program. After preserving
that comment and removing trailing whitespace, inference inspection and timing
were rerun against the committed source: five complete elaborations, one expected
symbolic-distance ATC limitation, and per-program medians of **0.14–1.50 ms**.
The two warm-up/five measured-round procedure is unchanged. ARM timings were not
rerun. Both current suites pass the source/compiler/runner fingerprint checks.
The [earlier inference reports and table](history/pre-commit-20261106/README.md)
remain available for the previously built supplement PDF; the PDF was not rebuilt
as part of preparing these commits. The current generated table is
[results/infer-table.tex](results/infer-table.tex).

## Implicit resumption syntax

Operation clauses now bind `resume` implicitly, as in Effekt v0.3.0:
`def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); zero(n) }`.
Active examples and source snippets no longer use an explicit `resume k`
suffix. The parser retains that older spelling for archived regression inputs.
The implicit binding uses the existing continuation type and region restrictions.

The focused parser/conformance run passed **70 tests**, including new checks for
unbound or redeclared `resume` and a nested-clause execution test using both the
inner `resume` and a saved outer resumption. The remaining ATM suites passed
**201 tests**, for **271 total**, and Scala.js compilation passed. After a
concurrent checker update, a fresh build passed **81 parser, conformance, and
inference tests**. These runs are distinguished rather than presented as one
unchanging compiler snapshot. Logs are in
`/private/tmp/effekt-implicit-resume-20261106/`.

That validation of **83 examples** reported **58 accepted, 21 contract
rejections, one protocol rejection, and three unsupported cases**, with zero
inventory mismatches. The [report](results/implicit-resume-examples.json) records
the source/compiler hashes at that point, including the concurrently added
unsupported inference example. Coverage manifests also match the migrated source hashes.

## Nested-handler inference suite

Six new examples under `examples/atm/infer` exercise three active nested handlers,
multi-shot resumption, residual evidence inside a region-polymorphic function,
mixed Boolean/integer ATC layers, and capability aliases under handler-name
shadowing, plus a symbolic-distance ATC limitation. **All 77 tests passed** across the eleven inference regressions, three reporting
regressions, and existing conformance/fidelity suites. Scala.js compilation passed.
They check exact evidence paths/endpoints, complete ATCs including initial
answers, residual discharge and instantiation at two caller depths, and handler
identity. All five programs return `true` in the source interpreter and both
Chez optimization modes. Two negative variants fail at the intended ATC checks.
An optional `Checker.inspect` observer records a reconstructed operation witness
and its residuals even if subsequent ATC inference fails. Ordinary checking
keeps the same result; tests compare successful Core output and the failure
diagnostic with and without observation. The typing algorithm and lowering are unchanged.

The [inference report](history/pre-commit-20261106/infer-inference.json) records distances/depths
`[0, 1, 2]` and full ATC structures for the five successful programs. For
`polymorphic-outer-call`, it records a residual witness at symbolic distance
and undefined ATC inference; there is no complete elaboration. The source hashes are
pinned in [infer-manifest.json](infer-manifest.json). The focused test log is
`/private/tmp/effekt-infer-suite-20261106/with-limitation-tests.log`. The supplement was updated
with a separate inference table before collecting timing; measurements were the
last step. Its ARM table retains only program names, times and fidelity notes.

The final 83-file inventory check reports 58 accepted, 21 contract failures, one
protocol failure, and three unsupported examples, with zero mismatches. Both
benchmark suites ran two warm-up rounds and five measured rounds, sequentially
on the recorded Apple M1 Pro machine. Inference-example medians range from
**0.11 to 1.51 ms**, including the unsupported case; refreshed ARM medians
range from **27.8 to 381.2 ms**. All outcomes were stable. The
[inference timing report](history/pre-commit-20261106/infer-timing.json) has 30 raw samples and the
[ARM timing report](results/arm-timing.json) has 260. The earlier ARM samples
remain unchanged under `history/pre-infer-20261106`. The source hashes and compiler fingerprints matched both reports at collection.
The commit-preparation run above refreshes the inference reports after the comment edit.

## Parenthesized answer-transition syntax

Answer transitions now use `T / (C1 => C2)` in active source, documentation,
and displayed computation types. The parser distinguishes transition arrows
from grouped effect sequences, function-valued endpoints, and arrows inside
refinement predicates. Old bracketed transitions remain accepted for the
unchanged archived regression fixtures. The typing rules and lowering are
unchanged.

The migration changed only paired transition delimiters in **74 of the 77
examples**, including all 52 ARM ports. A direct comparison against the saved
pre-migration sources found **identical parsed ASTs for all 77 programs**.
Source hashes in both coverage manifests
were refreshed and the manifests validate. All **21 parser tests passed**,
including five new regressions for nesting, grouping, dependent/refined answers,
effect instantiation, and printed types. Checking all 77 examples retained
**53 accepted, 21 contract rejections, one protocol rejection, and two unsupported
cases**, with zero inventory mismatches. The fresh report is
[answer-syntax-examples.json](results/answer-syntax-examples.json).

The full regression run passed **258 ATM tests across 13 suites**, including all
58 ARM tests and optimized/unoptimized backend comparisons. Scala.js compilation
also passed. The final test reports contain zero failures and errors.

Reproduce the checks with `sbt -batch 'effektJVM/testOnly effekt.Atm*Tests'
'effektJS/compile'` and `python3 docs/atm/check_examples.py`. Logs for this run
are in `/private/tmp/effekt-answer-parens-20261106/`.
The pre-migration inference and timing reports are preserved unchanged in
[history/pre-infer-20261106](history/pre-infer-20261106/README.md). Current reports
are regenerated for the parenthesized sources and the nested-inference suite.

## Supplement table validation

The ARM table lists all 52 ports with typechecking time and no
evidence/ATC inference columns; the nested examples have a separate table. Its numbered adaptation notes match section 3.3 and may
overlap (BFS has both notes 1 and 6). The PDF was rebuilt and visually checked
with every time cell marked `pending`, before starting measurement work. Only
after that update were the measurements below collected and inserted.

`AtmBenchmarkTests` passed **3 tests**: pure programs do not claim to exercise
inference, a contract failure has no complete elaboration, and operation
certificates distinguish hole from nonzero-depth ATCs. The separate inspection
of all 52 ARM programs reports **31 complete elaborations with evidence and
ATCs**, and **21 ContractFailure diagnostics**. Every ARM ATC in those successful
elaborations is the hole; the nonzero-depth test uses
`evidence-two-delimiters.effekt`. The inspection contains no timing code path.
The compiler itself was unchanged by this reporting work.

The [inference report](results/arm-inference.json) records source/compiler/runner
hashes and certificate counts; [the generated table](results/arm-table.tex)
records program names, timings, and numbered adaptation notes. Reproduction commands
and the measurement scope are in [BENCHMARKS.md](BENCHMARKS.md). The focused test
log is `/private/tmp/effekt-benchmark-table-20261106/inspection-build.log`.

The timing run completed **two warm-up rounds and five measured rounds** in one
JVM. All 31 accepted programs and 21 contract negatives retained their expected
outcomes in every round. Per-program medians range from **27.8 to 381.2 ms**.
The machine was an Apple M1 Pro with 16 GiB memory, macOS 27.0.1, OpenJDK 27,
and Z3 5.1.0. Only `Checker.check` is timed, including fresh checker caches and
Z3 invocations; parsing, JVM startup, Core construction and backend execution
are excluded. The [timing report](results/arm-timing.json) contains all 260 raw
samples, hashes and environment information. The log is
`/private/tmp/effekt-infer-suite-20261106/arm-timing.log`. This is a measurement
of the current annotated ports, not a reproduction of archived Lexa timings.

## Benchmark fidelity audit and small repairs

The initial 2026-11-06 audit assigned a principal fidelity class to every pinned ARM
port: 11 substantial adaptations, 15 added-context translations, four exception
encodings, two higher-order monomorphic distribution ports, and 20 structurally
close benchmark instances. Those classes remain manifest metadata; the paper
now uses the overlapping numbered notes above. Neither notation asserts source equivalence.

Both distribution ports now use actual higher-order `map`/`foldRight` callbacks.
Their datatype and contracts remain instantiated to the benchmark. The
`state-UNSAT` source and manifest now identify its context arguments as added,
and the `io-read-1-UNSAT` provenance typo is corrected. Port hashes and reports
were refreshed. All **58 ARM tests passed**, including a new multi-element,
duplicate-merging and empty-list witness in the interpreter and both backends,
with and without optimization. The log is
`/private/tmp/effekt-fidelity-20261106/tests.log`.

All 52 pinned upstream hashes and both inventories validate, with zero inventory
mismatches and the same accepted/rejected/unported counts. The supplement's
section **Effekt Implementation and Benchmark Results** documents the current
pipeline, 52-port results, fidelity limitations, and required support. The old
submission table and timings have been replaced in the maintained supplement.

## Source extension rename

All 77 active sources now use `.effekt`. Their bytes and source hashes are
unchanged. Test paths, filename filters, manifests, commands and documentation
links use the new suffix; historical `.atm.txt` snapshots retain their names.
The native JavaScript check-file generator excludes `examples/atm`, whose files
are handled by `effekt.atm.Main`.

Both inventory reports were regenerated after the rename: 53 accepted, 21
contract negatives, one protocol negative and two unsupported examples; the
paper mapping still records 31 accepted, 21 contract negatives and 29 unported
rows, including the known distribution label discrepancy. There are no inventory
mismatches. All 215 local Markdown links resolve. The six affected ATM suites
passed all 186 tests, including optimized/unoptimized backend execution. The run
is recorded in `/private/tmp/effekt-extension-20261106/tests.log`.

## Recorded results

| Check | Result |
| --- | --- |
| Existing ATM suites | 148 passed: 45 conformance/evidence, 16 parser, 27 refinement, 57 ARM, 3 annotation-key tests |
| `AtmLiftedTests` | 17 passed: direct IR, evidence composition, handler/return behavior, malformed Core/Lifted rejection, exact arithmetic |
| `AtmFidelityTests` | 17 passed: original audit reproductions, variance, scoping, contextual annotations, quantified/Unit refinements and negative cases |
| `AtmDatatypeTests` | 22 passed: constructor/refinement sorting, injectivity/disjointness, negative cases, recursive schemas, Core mutation, solver failure, and execution across both backends |
| `AtmLocalFunctionTests` | 23 passed: repaired examples and original witnesses, local recursion, exact-region/escape checks, continuation storage, answer composition, explicit return checks, capability alpha-renaming and Core mutation |
| `AtmPatternTests` | 9 passed: exhaustive matching, branch refinements, recursion, effects and invalid patterns |
| `AtmMeasureTests` | 8 passed: structural definitions, datatype results, invalid contracts, nondecreasing recursion, binder/arity/sort failures |
| `AtmFunctionDataTests` | 5 passed: dependent function fields, both backends, wrong contracts/regions, forbidden function equality and refined-field enforcement |
| Preceding full ATM run | 249 passed across that full run and focused rerun; see the syntax-migration section above for the latest 258-test run |
| `effekt.core.*`, `effekt.lifted.mono.MonomorphizationTests` | 35 passed |
| `JavaScriptTests` (preceding backend implementation run) | 276 passed; historical compiler/runtime unchanged by the fidelity repairs |
| `ChezSchemeLiftTests` (preceding backend implementation run) | 149 passed with the suite's existing exclusions; not rerun for the frontend-only repairs |
| `effektJS/compile` | Passed with the new shared Core/Lifted implementation |
| `--check expectation-SAT.effekt` | Accepted with refinement contracts |
| `--elaborate evidence-functions.effekt` | Printed typed Core with abstract and composed call witnesses |
| `--elaborate evidence-two-delimiters.effekt` | Printed a distance-two witness and depth-two ATC |
| `--lifted int-to-bool.effekt` | Printed native Lifted `Handle`, `Shift`, real `EV` arguments, `Int` resumption result and `Bool` handler result |
| `--emit int-to-bool.effekt` | Produced standalone Scheme; running outside the checkout printed `false` |
| `--emit-effekt int-to-bool.effekt` | Retained legacy Effekt source emission |
| `--run expectation-SAT.effekt` | Direct Chez-Lift execution printed `600` |
| Missing Chez executable | CLI exited with an error; no source-emitter fallback |
| Paper coverage inventory | All 81 table rows verified against a fresh PDF extraction; all candidate reference hashes verified, with 21 modified Lexa files identified |
| Coverage runner | 31 accepted, 21 contract rejections, 29 unported; 51 match original labels, one documented distribution label discrepancy; all 52 meet reviewed expectations |
| Runner without Z3 (preceding datatype/coverage run) | Zero successes; all 10 then-supported ports fail, process exits 1 |
| Complete example inventory | 58 accepted, 21 contract rejections, 1 protocol type rejection, 3 unsupported; zero inventory mismatches |
| All 21 current ARM contract negatives without Z3 | Every file exits 1 with `VerificationUnavailable`; none counts as an UNSAT success |
| JSON diagnostic smoke checks | Parse errors, unsupported symbolic ATC, and unavailable solver remain distinct |
| `git diff --check` | Passed |

The current ARM migration validation is recorded in
`/private/tmp/effekt-arm-full-20261106/arm-tests.log` (57 ARM tests),
`full-validation.log` (249 ATM tests), and `final-checks.log` (the corrected
five-test function-data suite, 35 historical tests, and Scala.js compilation).
The initial full run passed 248 tests and exposed one wrong diagnostic expectation
in a new test: an invalid stored continuation correctly fails `A_KApp` as a type
error before refinement checking. After correcting that assertion, the focused
suite passed. The aggregate XML reports at that stage contained 249 tests and no failures.
No compiler change was required for that test correction.

The earlier local-function run (182 ATM tests) is preserved in
`/private/tmp/effekt-rejected-examples-20261106/final-validation.log`;
the earlier datatype run (158 tests) in
`/private/tmp/effekt-artifact-coverage-20261106/final-validation.log`.
The saved [coverage report](results/paper-coverage.json) and
[example report](results/examples.json) record source hashes and the compiler
fingerprint. They were regenerated using the freshly compiled JVM classpath.
This is a working-tree validation, not an immutable released artifact.

## Reproduce current checks

Run from the repository root with Java/sbt, Z3, Chez and Node available:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' \
  'effektJVM/testOnly effekt.core.* effekt.lifted.mono.MonomorphizationTests' \
  'effektJS/compile'

python3 docs/atm/check_examples.py
python3 docs/atm/paper_coverage.py --run --output /tmp/atm-paper-coverage.json
```

The two Python runners build once by default. Use `--classpath-file` only for
an exported, freshly compiled JVM classpath. The checker-only reports are
separate from runtime differential execution. An expected unsupported result
does not count as a successful negative benchmark. To update the stored paper
report explicitly:

```sh
python3 docs/atm/paper_coverage.py --run --output docs/atm/results/paper-coverage.json
```

## Earlier checks and logs

The first datatype runtime attempt found that Chez opaque records were compared by
identity, causing the exact `Some(n/m)` equality witness to return false. The
repair generates typed Lifted matches and recursive field comparisons; both
optimization modes now agree with the source interpreter and legacy JS for all
new equality witnesses. The first legacy run also exposed invalid nested-match
block syntax, corrected in the emitter. These regressions are retained in
`AtmDatatypeTests` and `AtmArmTests`.

The preceding fidelity-repair run (132 ATM tests) is recorded in
`/private/tmp/effekt-fidelity-fixes-20261106/final-validation.log`:

```
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' \
  'effektJVM/testOnly effekt.core.* effekt.lifted.mono.MonomorphizationTests' \
  'effektJS/compile'
```

That directory also contains `red.log`, demonstrating the three original
checker failures before the fixes, and `before.tar.gz`, preserving the preceding
working tree. The newly added execution tests compare the source interpreter
with both direct Chez-Lift modes. Existing differential tests still exercise
the legacy JavaScript emitter as well.

Logs and CLI artifacts are in
`/private/tmp/effekt-direct-lifted-20261106/`:

- `verification.log`: 111 ATM tests (the existing 98 plus the first 13 direct
  tests), then all 276 JavaScript and 149 historical Chez-Lift tests.
- `deep-evidence-tests.log`: all 14 direct tests after adding the two-delimiter
  witness-composition regression. Only this test fixture/test was added after
  the broader run; compiler code was unchanged.
- `lifted-tests.log`: Core/Lifted tests and Scala.js compilation.
- `cli.log`, `int-to-bool.ss`, `int-to-bool.effekt`: CLI checks and both emitted
  artifacts. The standalone Scheme was executed from `/private/tmp`.
- `before.tar.gz`: snapshot of the pre-implementation changes.

## What execution checks establish

The cross-backend execution regressions compare the source interpreter,
direct Lifted/Chez execution, and legacy generated Effekt/JavaScript execution.
They exercise both backends with and without their respective optimizations.
For the direct path this means Chez AST cleanup enabled/disabled, not running
the native Effekt Core optimizer on ATM terms. Compile-only, rejection and
solver-failure tests remain distinct from execution tests.

Direct regressions cover differing body/return/handler answer types, multi-shot
resumptions with a nonidentity return clause, aborts bypassing that clause,
ordinary functions installing handlers beneath caller-owned delimiters,
arbitrary-precision negative division/remainder, and an outer operation crossing
two delimiters. The latter executes composed evidence through the historical
runtime and retains a matching depth-two ATC in Core.

Mutation tests reject a moved Core operation, a changed receiver identity,
an unbound value, a changed bound-value base type, a changed ATC depth, free or
missing Lifted evidence, a wrong value argument type, and a wrong return-clause
input type. The generated `int-to-bool` Lifted module has no answer-tag data
constructors: only the ordinary pair declaration and its capability interface.

The native Chez suite was run with a temporary `scheme` symlink to `chez` on
PATH, because v0.3.0's native runner expects that executable name. The direct ATM
runner uses `chez` by default. No global `scheme` alias was installed.

## Scope

The default route is now typed ATM Core -> adapted Lifted -> historical Chez-Lift.
The source emitter is retained for comparison. ML monomorphization explicitly
rejects the added ATM handler form; ML ATM support is not claimed.

The fidelity repairs broaden acceptance where the previous checker was overly
restrictive; see [FIDELITY.md](FIDELITY.md). Symbolic-distance ATC inference is
still rejected, and continuation calls still require their exact
ambient region. Refinement obligations, including subtyping at an ATC hole,
are discharged by the existing checker. Core/Lifted validation checks structural
certificates and runtime types, not the complete declarative type system after
erasure. No mechanized elaboration or backend soundness theorem is claimed.

All 52 pinned upstream ARM inputs now have ports. Their original universal
assertions are retained, with the explicit source adaptations in
[BENCHMARKS.md](BENCHMARKS.md). Thirty-one accept and execute consistently in the
independent source interpreter and both optimized/unoptimized backends; 21 reject
with `ContractFailure`. One accepted file is labeled UNSAT upstream but contains
the same program/assertion as its SAT counterpart. It is recorded as a known
label discrepancy, not an UNSAT success or validation of the paper UNSAT row.

The migration adds exhaustive matches, structural logical measures, refined and
function-valued constructor fields, and local-arrow alias anchors. It does not
provide general mutable references, region-polymorphic datatype fields, or the
general recursive shift/reset API. Helper specialization/state-passing details
matter for any claim about source-language coverage or annotation burden.

The separate current-example inventory contains 83 files:
[results/examples.json](results/examples.json). Twenty-nine paper-only rows are
still unported. General computation/ATC-kind quantification, polymorphic operation
binders and full target typing remain incomplete. See [FIDELITY.md](FIDELITY.md).

The local-function execution tests compare the original closed witnesses
(preserved in `history/rejected-before-local/`) with the repaired programs via
the source interpreter. The repaired programs also run through both backends
with and without optimization. Initial tests found missing legacy-emitter
capture metadata in local callback annotations; recording capabilities reachable
from the fixed call region repaired it without adding capture-set source
subtyping. A separate rejection reproduction exposed missed alpha-renaming of
local arrow regions inside operation answer computations; it is now covered by
a capability-subsumption regression. No historical runtime code changed in
this increment.


## 2026-11-10: named interfaces and Fig. 1 syntax compatibility

Implemented native monomorphic `interface` declarations, named handler
implementations, inherited clause value signatures, optional identity returns,
expression clause/function bodies, newline statement separators and empty local
function parameter lists. The source checker validates declared signatures and
still checks inferred ATM contracts normally. Full coverage and remaining
differences are in [SYNTAX-DIFFERENCES.md](SYNTAX-DIFFERENCES.md).

- `sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'`:
  **365 passed, 0 failed**; Scala.js compilation passed.
- `AtmInterfaceTests` adds 15 tests, including invalid interface implementations,
  declaration scope, dependent binder renaming, reference interpretation and
  optimized/unoptimized Chez execution. One obsolete parser rejection test was
  removed because omitted identity returns are now supported; the missing
  separator diagnostic test now uses two statements on the same line.
- The final `examples/atm/file-nested.effekt` checks at `Int / pure` and returns
  **42** through direct Chez and the legacy emitted-Effekt/JS route.
- Native Effekt/JS independently executes the same body and returns **42**
  after renaming its `main` to `example` and adding a printing native entry point.
  This run does not use the ATM source emitter. It confirms `resume(())`, used
  in the restored example, is the common Unit resumption spelling.
- The Fig. 1 evidence/ATC certificate comparisons remain passing, with operation
  distances **1, 0, 1, 0**. The two repository copies of the example match.

These are compiler and execution regressions, not a mechanized proof of the
frontend, source-to-Core elaboration or backend correspondence.

## 2026-11-10: native expression and declaration syntax

Restored nested expression calls with left-to-right single evaluation, Boolean
short circuiting, multiple parameters/arguments, parameterized interfaces,
native explicit function type arguments, ordinary annotated self recursion,
top-level functions/values and aliases, inferred `main` results, native
`type`/postfix `match`, wildcard constructor fields, empty interfaces/handlers,
and braceless expression forms. See [SYNTAX-DIFFERENCES.md](SYNTAX-DIFFERENCES.md)
for the precise supported subset and remaining gaps.

- `sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'`:
  **388 passed, 0 failed**; Scala.js compilation passed.
- `AtmNativeSyntaxTests` adds 24 regressions for evaluation order, conditional
  effects and partial primitives, declaration scope, multiple arguments,
  explicit generics, recursion, patterns, empty-handler distance and rejection
  of invalid implementations. Execution cases compare reference interpretation
  and optimized/unoptimized direct Chez. One obsolete parser rejection was
  removed because computations are now accepted in argument positions.
- The simplified Fig. 1 has top-level pure mock primitives, `def main() = ...`,
  and `resume(readInt(c))`/`resume(readInt(d))`. It checks at **Int / pure** and
  returns **42** through direct Chez and emitted Effekt/JS.
- Native Effekt/JS independently returns **42** for the same source body with
  a printing entry point. An instrumented variant appends a tag after each
  clause's resumption returns; both ATM and native Effekt return **424231**.
  This exercises nested delimiter/resumption behavior as well as the result.
- Existing Fig. 1 inferred/explicit evidence and ATC certificate tests still
  pass, including distances **1, 0, 1, 0**. The compiler and paper example
  copies are identical.
- Legacy multiline tagged ATM instantiations remain accepted. Generated
  wildcard pattern names also compile through Chez after separating their
  printable identifier from the fresh source binder.

The reusable `file` helper remains inlined: native block/capture polymorphism
and its interaction with ATM region/answer inference are not implemented.
These checks do not establish a mechanized elaboration or backend theorem.

## 2026-11-10: rechecking the reusable Fig. 1 (historical)

This section records the old native-block source, available at
`506d5024e:examples/atm/file-reusable.effekt`; the current file uses explicit
polymorphic parameters. See the 2026-11-12 removal entry below.

Recovered the original `def file(c: Handle) {body: {File} => Int}` form from
`lexical-effects/PLDI27/examples/file_simple.effekt`. The complete mock-backed
comparison source at `506d5024e:examples/atm/file-reusable.effekt` uses `Int` descriptors, top-level
pure mock primitives, inferred results, `resume(readInt(c))`, and nested
`file(7)` / `file(35)` calls. There are no explicit typelike parameters.

The native Effekt v0.3.0 compiler in this checkout was run with its JS backend
and a printing entry point (`main` renamed to `example`, followed by
`def main() = println(example())`). Results:

- Original nested body: **42**.
- Replacing `x + y` with `x * 100 + y`: **735**, distinguishing the descriptors.
- Appending descriptor-specific tags after each resumption returns: **424231**,
  matching the previously tested inlined source. Read tags are 1/2 and close
  tags 3/4 for the outer/inner descriptor respectively.

The ATM `--check-json` run rejects the same reusable source at the block
parameter with `ParseError: Native block parameters require capture/region and
answer-type inference, which is not implemented yet`.

The saved [`file-polymorphic.effekt`](../../examples/atm/file-polymorphic.effekt)
is an **accepted** example and returns **42**. Its `local[f]` callbacks fix the
operation distance to zero, so every operation infers the hole ATC `[]`.
A separate nested-use variant of that helper got past parsing but rejected
with `Unsupported: A_Do: ATC inference requires a concrete evidence distance;
an enclosing handler used inside a function has symbolic distance`. That
historical rejection concerns the separate variant, not the saved file.
The first rejection is a frontend gap; the second demonstrates an additional
inference gap in the tested explicit encoding. Neither is a source type-safety
counterexample. The maintained symbolic-distance failure fixture is
[`infer/polymorphic-outer-call.effekt`](../../examples/atm/infer/polymorphic-outer-call.effekt);
its [explicitly annotated counterpart](../../examples/atm/annotations/polymorphic-outer-call.effekt)
is accepted.

Thus the simplified reusable source passes native Effekt, **not ATM**. The
earlier 388-test result and successful ATM runs concern the inlined Fig. 1;
the saved rank-2 helper only established separate uses at different result
types. Compiler code was not changed by this recheck, and the full suite was
not rerun.

## 2026-11-10: reusable Fig. 1 with inferred native blocks (removed)

This experiment was removed on 2026-11-12. Its source and tests are preserved
in commit `506d5024e`; these results do not describe the current example.

Implemented native block declarations, literals, capability-block invocation,
and same-region forwarding. The executable at that checkpoint was
`506d5024e:examples/atm/file-reusable.effekt`.
The former paper copies `file_simple_atm.effekt` and `file_simple_reusable.effekt`,
and the duplicate under `docs/atm/native/`, have been consolidated into this file.
The paper retains its short typesetting listings. The executable uses
`interface File`, `read(): Int`, `close(): Unit`,
`def file(c: Int) {body: {File} => Int}`, and nested block calls, with no
explicit typelike arguments or handler computation annotations.

- Final `sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'`:
  **410 passed, 0 failed**; Scala.js compilation passed.
- The 22 `AtmBlockTests` cover original source interpretation and optimized/
  unoptimized direct Chez execution, runtime descriptors, lexical captures and
  shadowing, repeated callback invocation, forwarding, generic interfaces,
  non-Int answers, extra delimiters, and rejection of invalid arguments,
  interfaces, clause results and escaping capabilities. Ordinary symbolic-
  distance ATC rejection remains covered.
- A Core structural regression finds two specialized `file` function bindings
  and their applications. Each helper retains its handler and invokes its
  callback parameter; the callback operation bodies are absent from the helper
  bodies. Thus the frontend preserves runtime function/closure calls rather
  than inlining the reusable example.
- The program checks at **Int / pure**. Fresh ATM/Chez and native Effekt/JS
  comparisons agree on **42**; a descriptor-sensitive result is **735**; tags
  appended after resumptions return produce **424231**. Native testing uses
  the same source with a printing entry point.
- Core operation certificates retain distances **1, 0, 1, 0**, with the hole
  at the two zero-distance invocations. Generated evidence arguments are
  validated by the existing elaboration/Core checks.

The paper comparison in `506d5024e:docs/atm/FIDELITY.md`
separates the added contextual specialization pass from the unchanged printed
`A_Do`/`atc` behavior and the preexisting exact-region declarative bridge.
This is not a new soundness or elaboration-correspondence theorem. The direct
backend supports these blocks; the legacy emitted-Effekt backend still lacks
explicit evidence-kind abstractions and is not claimed to pass this source.


## 2026-11-10: plain polymorphic syntax

Historical checkpoint: the `here` spelling shown below has since been removed;
see [named caller regions](CALLER-INDEXED.md) for the current syntax.

Polymorphic abstractions now use `fun [A] { ... }` or
`fun [R: Reg, D: Dist, E: Ev(R,D,h), K: ATC(D)] { ... }`. Value parameters
can follow directly, as in `fun [A](x: A): A { x }`. The previous `typefun`
spelling remains compatible.

General applications accept bare arguments in grouped or chained brackets.
The next quantified parameter selects the argument kind; existing scope,
evidence-endpoint, distance, and ATC checks apply. The maintained annotated
outer call is `callback[here, 1, step(inner), Int / (Int => [])]()`, and its
Core is identical to the tagged version. The two annotated examples and the
accepted result-type-polymorphic file example use the simplified spelling.
This adds no implicit typelike-argument inference or symbolic-distance ATC
inference. Legacy tagged applications remain accepted.

Validation:

- The full `effektJVM/testOnly effekt.Atm*Tests` run passed **424 tests**,
  followed by successful Scala.js compilation.
- A final parser refinement lets legacy prefix words themselves name bare
  arguments, such as `f[distance + 0]` and `f[atc[[]]]`. The final focused run
  of `AtmPolymorphicSyntaxTests`, `AtmExplicitAnnotationTests`, `AtmParserTests`,
  and `AtmNativeSyntaxTests` passed **101 tests**, including **16** syntax
  regressions. Scala.js compilation passed again. The full suite was not
  repeated after this final parser refinement.
- The final CLI runs return **true** for the annotated outer-call example
  and **42** for `file-polymorphic.effekt`. Positive syntax tests compare the
  independent source interpreter with optimized and unoptimized direct Chez.
- The unannotated `infer/polymorphic-outer-call.effekt` still rejects with
  the symbolic-distance ATC-inference diagnostic.

Logs: `/private/tmp/effekt-plain-typelike/final-tests.log` and
`/private/tmp/effekt-plain-typelike/final-syntax-tests.log`.


## 2026-11-11: compiler commit validation

Rechecked the accumulated annotation, native syntax, and block-inference
changes, including the final bare-argument parser refinement and the edited
outer-call example's zero-argument `fun local[R]()` and `def unused()`.

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/annotations/polymorphic-outer-call.effekt'
```

All **426 ATM tests passed**, and Scala.js compilation passed. The CLI runs
returned **42**, **42**, and **true**, respectively. The source snapshot was
checked for concurrent changes before staging. `git diff --check` passed.
The 83-example inventory above remains an archived measurement; there are now
88 example source files, and benchmark timings were not regenerated.

Build/test log: `/private/tmp/effekt-commit-20261111/tests.log`.

## 2026-11-12: remove contextual block specialization

Removed the native-block syntax nodes, deferred helper environments, per-call
body checking, generated specializations, and corresponding reference paths.
The reusable [Fig. 1 source](../../examples/atm/file-reusable.effekt) now has
explicit region/computation parameters, capability/evidence binders, handler
and operation contracts, and ATCs. One polymorphic helper definition is checked
before its uses; unused definitions are also checked. `interface File` and the
zero-argument `read`/`close` signatures remain.

The [fidelity audit](FIDELITY.md#audit-of-other-additional-mechanisms-2026-11-12)
documents the remaining annotation-generation passes and declarative bridges.
It found no remaining compiler branch selecting Fig. 1 by names or constants.
The new helper uses the existing explicit `local[R]` bridge, so this removal
does not establish that every frontend construct is a printed algorithm rule.

Validation:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --check-json examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/annotations/polymorphic-outer-call.effekt'
```

- **420 ATM tests passed, 0 failures/errors**; Scala.js compilation passed.
  The previous 22 specialization tests were replaced by 16 explicit-helper
  tests: 426 - 22 + 16 = 420. The focused 16-test run also passed.
- The reusable example checks at **Int / pure** and returns **42**.
  The other polymorphic file returns **42**, and the annotated outer call
  returns **true**.
- Core contains **one** polymorphic `file` binding and handler body, with
  two computation instantiations. Explicit operation certificates have
  distances **1, 0, 1, 0** and two hole ATCs.
- New regressions compare the independent source interpreter with optimized
  and unoptimized direct Chez. Descriptor-sensitive execution returns **735**;
  changed descriptors and renamed helper/interface also work. Invalid unused
  helper bodies, wrong argument/result contracts, and invalid evidence/ATCs
  reject. The original symbolic-distance inference failure still rejects.
- `git diff --check` passed. No paper rules or benchmark timings were changed.

Logs: `/private/tmp/effekt-remove-specialization/targeted.log` and
`/private/tmp/effekt-remove-specialization/validation.log`.
These checks do not constitute a mechanized compiler correspondence proof.

## 2026-11-12: caller-indexed functions and removal of `local`

Historical checkpoint, superseded by the domain-removal entry below. Ordinary
function types retained caller-indexed families. This checkpoint included the
approved explicit ATC abstraction and an additional caller-dependent domain
extension; the latter has since been removed. Current boundaries are documented in
[CALLER-INDEXED.md](CALLER-INDEXED.md), including the printed algorithm's retained
incompleteness boundary. `local` is removed from syntax. Older declarative ARM
behavior uses explicit fixed-region contracts with ordinary `fun` instead.

Validation on the completed source:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt' \
  'effektJVM/runMain effekt.atm.Main --emit examples/atm/file-reusable.effekt /tmp/effekt-caller-file.ss'
chez --script /tmp/effekt-caller-file.ss
```

- **434 ATM tests passed, zero failures/errors**; Scala.js compilation passed.
  This includes all existing ARM contract/runtime classifications and 14 new
  caller-family/ATC regressions. No existing benchmark was demoted to unsupported.
- The reusable Fig. 1 and result-type-polymorphic file both return **42** through
  the CLI. Independently executing the emitted Scheme also returns **42**.
- Tests compare reference evaluation with optimized/unoptimized direct Chez,
  check one generic helper/handler body and two computation instances, and
  observe one operation-body check for an ATC-polymorphic function invoked at
  two different handler depths.
- Returned evidence kinds, fixed-region function families, nested binders,
  recursive result families, and ATCs in higher-order argument contracts pass.
  Wrong ATC depths, missing arguments, malformed Core call-context certificates,
  invalid unused definitions, and removed `local` forms are rejected.
- The unannotated symbolic-distance ATC inference tests retain their expected
  residual evidence and rejection. No body specialization or name-based Fig. 1
  treatment was added. `git diff --check` passes; paper rules are unchanged.

Log: `/private/tmp/effekt-caller-complete.log`. Earlier validation counts and
benchmark timing reports remain historical snapshots; timings were not rerun
for publication. These are implementation tests, not a mechanized proof of the
new extension.

## 2026-11-12: remove callback-domain extension; curry Fig. 1

Historical checkpoint: its `here` spelling is superseded by the named-caller
entry below. This supersedes the preceding caller-domain checkpoint. Ordinary function
arguments are resolved before the implicit caller and call-ATC binders, and are
not opened or instantiated by application. Subtyping, equality, free-region
analysis, and Core annotation validation use the same scope boundary. A call-ATC
parameter declared after the value parameter list is now rejected in that
ordinary domain; independently quantified outer ATCs remain available.

Fig. 1 now calls `file[Int](7)(callback)` and `file[A](35)(callback)`.
The ordinary helper's domain is `Int`. Its caller-indexed result is a function
with an explicitly fixed-region contract. The definition is still checked once,
with one generic handler body and two computation instantiations. The
[boundary table](CALLER-INDEXED.md) separates this declarative bridge and explicit
ATC abstraction from printed `A_Abs`/`A_App`. At this checkpoint `here` was added
ATM annotation syntax; it has since been removed in favor of explicitly naming
the ordinary function's existing caller binder.

Validation:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --check-json examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt' \
  'effektJVM/runMain effekt.atm.Main --emit examples/atm/file-reusable.effekt /private/tmp/effekt-curried-file.ss'
chez --script /private/tmp/effekt-curried-file.ss
```

- **439 ATM tests passed; zero failures/errors.** Scala.js compilation passed.
  Existing ARM contract and runtime classifications remain unchanged.
- Curried Fig. 1 checks at **`Int / pure`**. Both file examples return **42**
  through the CLI; separately executing the emitted Fig. 1 Scheme returns **42**.
  Fig. 1 regression tests compare reference evaluation with direct Chez execution,
  both optimized and unoptimized, and retain the single-body checks.
- New regressions reject the former uncurried contract, including when unused;
  cover inferred, annotated, expected-type, recursive, and explicit-call-ATC
  function domains; and reject a forged caller-dependent domain at the Core
  boundary. Ordinary domains retaining their lexical region still work at
  different call sites. Handler-label/current-region equivalence is tested in
  a handler body, with their distinction tested beneath a function and delimiter.
- The earlier test accepting call ATCs in higher-order argument domains is now
  a rejection test, matching the removed extension. Symbolic-distance ATC
  inference still rejects; no specialization was added.
- `git diff --check` passed. Paper rules and historical benchmark timings were
  not changed. These are implementation regressions, not a new mechanized
  soundness or compiler-correspondence proof.

Full log: `/private/tmp/effekt-domain-complete.log`; initial focused log:
`/private/tmp/effekt-domain-targeted.log`.

## 2026-11-12: name caller-region binders; remove `here`

Ordinary functions and arrow types can name the existing implicit caller with
`fun (x: T)[R: Reg]`, `def f(x: T)[R: Reg]`, and `(T)[R: Reg] => C`.
The name scopes over the result, body, and subsequent call-ATC kinds, never the
ordinary argument domain. Application supplies the current region implicitly;
this naming syntax adds no quantifier, runtime region, or explicit call argument.
The separate pre-parameter `fun [R: Reg]` form still abstracts an explicitly
supplied typelike argument.

`here` is no longer a keyword, predefined region, or scoped-capability marker.
Maintained examples use handler labels, `top`, or a named caller binder.
Bare `refl` has its own internal representation; it does not depend on a hidden
region alias. Historical validation and archived prototype sources retain their
old spelling as historical records.

Fig. 1 declares `file` with `(c: Int)[R: Reg]`, uses `R` in its returned
fixed-region callback contract, and names the outer callback's caller `S`.
Its curried calls and explicit Cty/ATC arguments are unchanged. The helper is
checked once, with no specialization. The callback-domain extension remains
removed; explicit call-ATC abstraction and the fixed-region declarative bridge
remain separately documented extensions in [CALLER-INDEXED.md](CALLER-INDEXED.md).

Validation commands:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --check-json examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt' \
  'effektJVM/runMain effekt.atm.Main --emit examples/atm/file-reusable.effekt /private/tmp/effekt-named-file.ss'
chez --script /private/tmp/effekt-named-file.ss
```

- **445 ATM tests passed; zero failures/errors.** Scala.js compilation passed.
  Existing ARM contract and runtime classifications remain unchanged.
- Curried Fig. 1 checks as **`Int / pure`** and returns **42** through the CLI
  and separately executed emitted Scheme. The result-type-polymorphic file
  example also returns **42**. Tests compare reference evaluation with optimized
  and unoptimized direct Chez execution.
- Fig. 1 retains one generic helper/handler body and two computation
  instantiations. The general ATC-polymorphic function regression still observes
  one operation-body check for calls at two different handler depths.
- Six added regressions cover alpha-renaming, lexical shadowing, call-ATC kind
  scope, binder escape, malformed/mixed binders, and `here` as an ordinary
  identifier with no predefined region meaning. Existing domain-rejection,
  recursive-result, returned-function, and invalid-unused-definition tests pass.
- The unannotated `multiple-capabilities.effekt` fixture now uses ordinary
  capability abstractions. It still rejects symbolic-distance ATC inference;
  its diagnostic no longer comes from the removed scoped-capability syntax.
- `git diff --check` passed. Paper sources and historical benchmark timings are
  unchanged. These are implementation checks, not a mechanized proof of the
  remaining extensions.

Full log: `/private/tmp/effekt-named-complete.log`.

## 2026-11-12: named interface types for capability parameters

Capability parameters now accept `(f: File) => T` and `fun (f: File)`.
The checker expands the interface's declaration-scoped value signatures and
uses the expected capability type's full ATM contracts. In a consumer parameter
annotation without an expected contract, its enclosing result annotation `C`
supplies `C => C` defaults. Computation parameters and effectful computations
are preserved; ambiguous standalone uses are rejected instead of being
specialized at later call sites.

Both reusable file examples use named parameters and inherited handler
signatures. Fig. 1 retains one checked helper/handler body, two computation
instantiations, and the existing caller-region/evidence/ATC annotations. Its
Core regression also checks that both File operations carry the abstract
computation parameter as their initial and final answer.

Validation:

```sh
sbt -batch 'effektJVM/testOnly effekt.AtmNamedCapabilityTests' \
  'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'
sbt -batch \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/file-polymorphic.effekt'
```

- **485 ATM tests passed; zero failures/errors.** Scala.js compilation passed.
- **24 new regressions** cover contextual and explicit answer contracts,
  parameterized interfaces, recursive consumers, declaration scope and name
  shadowing, dependent signatures, incompatible contracts, ambiguous contexts,
  capability forwarding/escape, and rejection of unresolved names in Core.
  Accepted cases compare reference evaluation with optimized/unoptimized Chez.
- Both file examples return **42** through the CLI. Existing evidence-distance,
  ATC-depth, caller-domain, and invalid-unused-helper regressions pass.
- `git diff --check` passed. Validation used an isolated copy of the shared
  working tree, preserving the existing caller-region and unified-fun changes.

Logs: `/private/tmp/effekt-named-capabilities-final.log` and
`/private/tmp/effekt-named-capabilities-full.log` (CLI runs).
