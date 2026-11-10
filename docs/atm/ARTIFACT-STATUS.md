# Artifact readiness after the upstream ARM migration

**2026-11-09 annotation update:** the frontend now checks explicit region,
distance, evidence, computation-type and ATC binders/arguments. The annotated
outer-call and two-capability examples execute through direct Chez-Lift; the
old unannotated fixtures remain negative inference/extension tests. See
[ANNOTATIONS.md](ANNOTATIONS.md) and [VALIDATION.md](VALIDATION.md). Benchmark
measurements below describe the archived 2026-11-06 snapshot and have not been
retimed for this change.

Status snapshot: **2026-11-06**. Start with the [usage guide](README.md) for
commands and the documentation map, or [BENCHMARKS.md](BENCHMARKS.md) for the
single-file benchmark outline.

All 52 pinned upstream ARM files now have ports, including the 41 previously
pending files. This is still not a replacement artifact for all claims of the
paper: 29 paper-only rows, full target typing/Definition 17 translation, and
performance reproduction remain unfinished. Evidence reconstruction and partial
ATC inference remain in place. [BENCHMARKS.md](BENCHMARKS.md) gives the contracts,
helper specializations and state-passing adaptations in one file.

## Coverage that can be inspected and rerun

[PAPER-COVERAGE.md](PAPER-COVERAGE.md) preserves the migration map for all
**81 rows** of the archived submission table and seven motivating-example groups.
The maintained supplement now reports the Effekt implementation and its 52 ARM
ports. The archived map's source of truth is
[paper-coverage.json](paper-coverage.json). The inventory distinguishes:

- 59 paper ARM rows, including 30 SAT and 29 UNSAT variants;
- 22 paper Lexa programs;
- the separately pinned 52 OCaml files in coar;
- 52 upstream ports (31 accept, 21 fail contracts), and 29 paper-only rows without ports;
- one known upstream label discrepancy: distribution UNSAT duplicates the SAT
  assertion and accepts, so it does not validate the corresponding paper UNSAT row.

“Upstream port” means the original coar assertion is retained, with the explicit
adaptations in [ARM.md](ARM.md). It does not establish equivalence to the
corresponding Lexa translation or reproduce the paper's evaluation. The existing
file-token, answer-change, and evidence examples are marked as illustrations or
partial implementations of the paper's complete programs.

All 81 candidate Lexa source files were located and hashed in the recorded audit.
Their repository HEAD was `15b9073094cde063012e0e3b931728de104e9754`;
**21 files differed from HEAD** in that snapshot.
The manifest records each path, hash, tracked status and whether it differs.
Neither these files nor the separate local result CSV have been established to
be the submitted evaluation snapshot. No historical timing is copied into the
new results. The archived supplement filename is `popl27-paper152-supplemental.pdf`
(the earlier attachment path used `pldi27`); both current PDFs are hashed.

An additional source discrepancy matters: the candidate Lexa `safe-div-2-SAT`
checks a disjunction permitting `None`, whereas upstream requires exactly
`Some(n/m)` when `m != 0`. Its UNSAT variant also changes the assertion to a
wrong quotient. The new Effekt ports preserve upstream's exact equality test
and its true/false assertions. This is recorded as a provenance caveat, not
silently counted as a reproduction of those Lexa files.

## ARM language increment

Added exhaustive constructor matching, total structurally recursive logical
measures, dependent/refined constructor fields, closed pure function-valued
fields at the top region, and local-arrow alias anchors. Pattern matching lowers
to native Lifted; function fields store actual thunk/resumption closures.
[ARM.md](ARM.md) documents syntax and logical projection limits.

All 52 ports are checked against universal function contracts and classified
diagnostics. Accepted witnesses are compared with the source interpreter and
both backends, optimized and unoptimized. The label discrepancy is retained
separately from genuine contract rejections. BFS/modulus/shift are helper
specializations; round-robin/transaction use explicit state passing. General
references and recursive shift/reset APIs are not claimed.

Datatype type/region parameters, function fields inside pairs, effectful
function fields, automatic induction and refinement invariant inference remain
unsupported. These extensions do not constitute the paper's complete kind system.

## Local-function increment

Ten previously blocked examples now check and execute: seven through added
language support and three through narrower answer-type annotations. Explicit `local` arrows
and local recursion implement fixed-region functions; same-region resumptions
can be stored under those arrows. Explicit `return effect` permits checked
effects in return clauses. Both are declarative bridges, distinguished from the
printed inference rules in [FIDELITY.md](FIDELITY.md). Three old effect-bound
examples use explicit pure answer endpoints, with their original closed
runtime witnesses retained. General opaque-effect polymorphism is not added.

In the archived 2026-11-06 inventory of 83 `.effekt` files, 58 are accepted, 22 are intentional negatives,
and three remain unsupported. These counts include examples beyond the paper
inventory and must not be added to its benchmark coverage. The new runner checks
every existing file and fails on unexpected errors:

```sh
python3 docs/atm/check_examples.py
```

The [saved example results](results/examples.json) contain source hashes,
diagnostic categories and compiler fingerprint. Unsupported examples are never
counted as successful negative tests.

The original two unsupported files are `callback-under-handler.effekt` (a
caller/ATC-indexed callback contract) and `multiple-capabilities.effekt` (relative
region/evidence/ATC constraints between abstract handlers). The old opaque
effect bounds remain unsupported even though their three closed examples now
have explicit answer-type annotations. All cases are listed in
[REJECTED-EXAMPLES.md](REJECTED-EXAMPLES.md).

## Reproduction

Prerequisites: this Effekt checkout and pinned Kiama submodule, Java/sbt, Z3,
Node (legacy differential tests), and Chez. Set `EFFEKT_ATM_Z3` and
`EFFEKT_ATM_CHEZ` if their executables are not `z3` and `chez`. The current run
uses Java 27, sbt 1.9.7, Z3 5.1.0, and Chez 10.4.1; a clean release environment
and CI pinning remain separate work.

Validate inventory and port hashes without compiling or executing programs:

```sh
python3 docs/atm/paper_coverage.py
python3 docs/atm/check_arm_manifest.py /path/to/coar
# Optional: verify the locally identified reference snapshots and PDFs too.
python3 docs/atm/paper_coverage.py --lexa /path/to/lexa --papers /path/to/papers
```

Build once and generate a JSON result for every paper row, keeping unported rows
explicit (the runner exits nonzero if any supported port fails its expectation):

```sh
python3 docs/atm/paper_coverage.py --run --output /tmp/atm-coverage.json
```

The runner starts a fresh JVM for each port and records elapsed checking time,
source hashes, compiler fingerprint and Java version. These are reproduction
measurements, not a controlled performance comparison. It checks acceptance;
run the suites below for execution and transformation validation:

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' \
  'effektJVM/testOnly effekt.core.* effekt.lifted.mono.MonomorphizationTests' \
  'effektJS/compile'
```

See [VALIDATION.md](VALIDATION.md) and the recorded
[coverage run](results/paper-coverage.json). SMT checks and differential execution
remain distinct from a type-soundness or compiler-preservation proof.

## Remaining priorities

1. **Complete the Definition 17 translation and polymorphic operation signatures.**
   Explicit region/distance/evidence/ATC/computation parameters now have checked
   kinds and Core certificates. The implicit algorithm remains partial;
   arbitrary abstract ATC result projections still require backend support.
   See [FIDELITY.md](FIDELITY.md).
2. **Port the complete motivating programs with their original properties.**
   The reusable mock-backed Fig. 1 now checks with native blocks and contextual
   specialization; see [FIDELITY.md](FIDELITY.md#native-blocks-contextual-specialization).
   Remaining work includes the polymorphic close protocol, nested state handlers
   with result exactly 3, and the file/GUI protocol. The current
   nested state example still needs polymorphic operation contracts and its
   exact relational invariant; local state-passing functions are now available.
3. **Port the remaining 29 paper-only rows and audit translation equivalence.**
   The 52 pinned upstream ports are complete as benchmark instances. Audit the
   paper's starred `bfs-simpl`/`modulus` adaptations, the distribution label
   discrepancy, and the scheduler/shift specializations before claiming the
   paper's source-language or annotation-burden comparison.
4. **Freeze a reproducible release.** Select the paper-source snapshot, commit
   the implementation, pin/install solver and backend dependencies, and test a
   clean checkout in CI. Existing working-tree success does not establish this.
5. **Improve independent checking and diagnostics.** Extend declarative checking
   beyond current structural certificates, add source spans and obligation traces,
   and extend negative/differential tests as new rules become implemented. The
   JSON categories added here are a first step, not a full diagnostic redesign.

The inference suite adds `infer/polymorphic-outer-call.effekt` as an explicit
symbolic-distance ATC limitation: residual evidence is reconstructed, but ATC
inference is undefined. This brought the archived inventory's unsupported file count to three; these
original negative fixtures remain unchanged.
