# ATM validation record

Latest implementation validation: **2026-11-06**, on Effekt v0.3.0
(`cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`) and its pinned Kiama revision
`ab6aef78fc4ce99a9ad2db8555718d00b03f7b8a`, using Chez Scheme 10.4.1.
These are implementation checks, not a correspondence or soundness proof.
The preceding migration-only results are preserved in
[history/v0.3.0-migration-validation.md](history/v0.3.0-migration-validation.md).
The single-file suite outline is [BENCHMARKS.md](BENCHMARKS.md).

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
