# ATM frontend: implementation and usage

Documentation snapshot: **2026-11-10**. Commands below run from the repository root.

This checkout is based on Effekt **v0.3.0**, commit
`cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`, with its pinned Kiama submodule.
The historical `effekt.lifted` representation and the `ml` and `chez-lift`
backends are available. See [MIGRATION-v0.3.0.md](MIGRATION-v0.3.0.md) for the port.

The ATM checker implements a subset of the region/evidence algorithm
from Section 6.1 of the supplied paper and supplemental Section 3 (Figures 9--15).
Explicit `local` functions and `return effect` expose additional declarative
rules through checked surface forms. They are documented separately from the
printed inference rules. The former ordered-handler-stack checker is not used
as a fallback.

The repaired discrepancies, deliberate surface elaborations, and remaining
fidelity gaps are listed individually in [FIDELITY.md](FIDELITY.md). This is not
yet an implementation of the complete paper language or its declarative target.

The surface still uses Effekt-like syntax. Regions, evidence and residual bounds
are internal. The pipeline is:

```
.effekt surface
  -> paper-algorithm subset + explicit declarative forms
     (residual obligations, evidence reconstruction and partial ATC)
  -> typed region/evidence Core
  -> ATM-adapted historical Lifted
  -> historical Chez-Lift backend
```

This is the default execution route. It constructs `effekt.lifted.ModuleDecl`
directly: it does not emit/reparse Effekt source or run native lift inference.
Evidence becomes actual Lifted `EV` parameters and arguments; it is not erased
before reaching the backend. The existing Effekt-source/JavaScript emitter is
retained as `--emit-effekt` and as a differential test target.
See [DIRECT-LIFTED.md](DIRECT-LIFTED.md) for the IR extension and translation.

This remains a separate frontend from native `.effekt`. Tests establish
implementation behavior, not a mechanized correspondence or soundness theorem.
The paper's theorem is not automatically a theorem about this Scala code or its
Effekt lowering.

## Documentation map and current coverage

| Document | Purpose |
| --- | --- |
| [Benchmark overview](BENCHMARKS.md) | Single-file outline of families, contracts, all current inputs, coverage and run commands |
| [Artifact status](ARTIFACT-STATUS.md) | Current implementation and remaining priorities |
| [Explicit annotations](ANNOTATIONS.md) | Region, distance, evidence, computation and ATC binders, arguments and checking |
| [Native syntax differences](SYNTAX-DIFFERENCES.md) | Restored syntax, remaining frontend gaps, and intentional ATM extensions |
| [Paper fidelity](FIDELITY.md) | Rule correspondence, explicit extensions and missing target types/kinds |
| [Direct Lifted backend](DIRECT-LIFTED.md) | Core-to-Lifted translation, evidence and validation boundary |
| [Example repairs](REJECTED-EXAMPLES.md) | Ten repaired examples and current unsupported/negative cases |
| [ARM migration](ARM.md) | Pinned upstream contracts, adaptations and remaining porting work |
| [Archived paper coverage](PAPER-COVERAGE.md) | Migration provenance for the supplied 81-row submission table and seven example groups |
| [Validation](VALIDATION.md) | Recorded results, reproducible commands and what the checks establish |
| [v0.3.0 migration](MIGRATION-v0.3.0.md) | Base revision, preservation and compatibility history |
| [Historical records](history/README.md) | Superseded designs, validation snapshots and original source fixtures |

The six [nested-inference examples](../../examples/atm/infer/README.md) exercise
nonzero-depth ATCs, residual evidence, mixed layers, shadowed capabilities, and
the symbolic-distance ATC limitation.
Their dedicated supplement table records inference and typechecking time.

For benchmark orientation, [BENCHMARKS.md](BENCHMARKS.md) is self-contained.
The archived 2026-11-06 inventory contains **83 `.effekt` files: 58 accepted,
22 intentional negatives and three unsupported**. Seven blocked examples were repaired through added language
support; three more use narrower explicit answer-type annotations. Separately,
**All 52 pinned upstream ARM files** have ports (31 accept, 21 fail their contracts).
One upstream distribution UNSAT label conflicts with its unchanged SAT assertion.
Those ports map to 52 of the archived submission's 81 rows; 29 archived rows
remain unported. The maintained supplement now reports the Effekt-based
52-port evaluation with numbered adaptation notes shared with section 3.3;
measured typechecking times are documented in [BENCHMARKS.md](BENCHMARKS.md). These inventories overlap and must not be added together.

The implicit-resumption migration passed **271 ATM tests** and Scala.js
compilation, followed by **81 integration tests** against a concurrent checker
update. The preceding historical Core/Lifted run passed **35 tests**.
See [VALIDATION.md](VALIDATION.md) for the distinct runs.

## What changed

- `Evidence.scala` implements tagged subregion constraints, residual obligations,
  `A_Refl`, `A_Step`, `A_Defer`, minimum captured regions, distances projected from
  evidence, and partial `atc`. It uses generative region identities, not stack
  indices. A handler introduces a distance-one edge; reflexivity has distance zero.
- Ordinary functions are checked at a fresh body region. Their deferred calls are rerouted
  through an abstract call witness and static paths from the function's required
  region. A function application supplies its caller region, projected distance,
  and reconstructed evidence. It does not lift an answer effect across extra
  caller-owned delimiters.
- Continuations have a distinct internal arrow. `A_KApp*` requires exactly the
  handler clause's ambient region and emits a plain call. A continuation can be
  aliased and called there, but cannot be coerced into an ordinary function,
  returned through an ordinary arrow, or called beneath a different region. It
  can be exposed as an explicit same-region `local` arrow, stored and called there.
- Capability quantification binds a region and a capability value. Its internal
  scheme records residual constraints mentioning that region; instantiation
  reconstructs their evidence. Type and predicate quantification remain explicit.
- `Elaboration.scala` substitutes and validates inference certificates.
  `Core.scala` builds a separate typed syntax retaining regions, evidence binders,
  residual-evidence arguments and operation ATCs. `--elaborate` shows this core;
  `--lifted` shows the actual historical Lifted nodes sent to the backend.
- `LiftedLowering.scala` translates core evidence into Lifted evidence and adds
  an explicit handler return clause. `LiftedValidation.scala` checks target
  variable scope, application types/arity and handler structure before codegen.
- Computation types now retain dependent result binders. Conditional answers use
  guarded joins and meets with the paper's variance. Answer subtyping uses
  `S_CPureImpure` and `S_CImpure`, without the old erased-shape equality restriction.
- Default handler return clauses use empty inherited-answer mode. Explicit
  `return effect x => { ... }` checks against the annotated answer under its
  final-answer mode, as a declarative `T_HRet` bridge. Their logical context
  contains the returned value's type, not the body's internal path assumptions.

The old stack-lifting, scoped-tail and repeatable/after-constraint design is
archived in [history/prototype-design.md](history/prototype-design.md). Its old
tests are preserved in [history/AtmPrototypeTests.scala.txt](history/AtmPrototypeTests.scala.txt)
for comparison, not counted as current passing tests. Ten blocked examples have
now been repaired with explicit local forms or answer-type annotations. Their
original sources are retained in `history/rejected-before-local/`.
[REJECTED-EXAMPLES.md](REJECTED-EXAMPLES.md) separates the unsupported
examples from the six intentional negative tests.

## Surface syntax and annotations

```
def main(): Int = {
  def id(x: Int): Int = { x }
  def use(x: Int) = { id(x) }
  use(42)
}
```

A function with both domain and result annotations elaborates to an annotated
lambda checked by `A_AbsChk`. Its omitted region defaults to the lexical region;
an expected arrow provides the region when checking an explicitly annotated
lambda. Function types with `at {h}` select the handler region (multiple names
require a known minimum region). This notation also records backend captures.

A function with a domain but no result annotation, such as `use` above, uses
`A_AbsSyn`: synthesize the body in empty-answer mode and infer the minimum region
from the captured residuals. Recursive definitions require a result annotation.

In a checking position, the domain can also be omitted: `fun (x) { x }` obtains
its argument type, result type and required region from the expected arrow.
This works for function arguments, operation arguments and handler returns.
Without an expected arrow, the domain annotation is still required.
Likewise, a handler in a checking position can omit its result annotation:
`try { ... } with h { ... }` uses the expected computation type. In synthesis,
a partial surface pass can infer the returned value type `T` from the body and
return clause. It proposes `T / pure` in empty inherited mode, or `T / (C => C)`
under inherited answer `C`, and then checks the complete handler normally.
This annotation-generation step extends the printed algorithm: `A_Handle`
itself remains checking-only. It is not general inference of answer-changing
contracts or refinements. Source abstractions without result contracts and other
unsupported projections need an annotation on the whole handler:
`try { ... } with h { ... }: ComputationType`. Explicit annotations are always
checked as supplied. The legacy spelling
`with h: ComputationType { ... }` remains accepted for archived inputs.
If the name after `:` is a declared interface, `with h: File { ... }` instead
uses native Effekt's named-interface syntax. Prefer trailing computation
annotations to avoid this legacy ambiguity.

Answer transitions use `Int / (Int => Bool)`. Parentheses enclose the whole
transition, including nested computation endpoints, as in
`Unit / (Int / (Int => Bool) => Int / (Int => Bool))`.
Function-valued endpoints remain explicitly grouped:
`Int / (((Int) => Int) => Bool)`. Effect sequences use `(E; F)`; the separator
distinguishes them from answer transitions. Square brackets also delimit typelike instantiation, explicit operation annotations,
and local-region selectors.
The old bracketed answer transitions are accepted for archived regression inputs.
A dependent computation result can
be written `(answer: Int) / ({r: Int | r == answer} => Int)`; `answer` scopes only
over the initial answer. A computation can be explicitly annotated with a trailing
`: ComputationType`.

Operation clauses may omit the answer transition and use an empty argument list:

```effekt
def read(): Int = { resume(42) }
def close(): Unit = { resume() }
```

The result annotation describes the operation's returned value. For a handler
checked against computation type `C`, an omitted transition is inferred as
`C => C`; the clause and its resumption are checked with that explicit contract.
This is contextual inference for answer-preserving clauses, not general inference
of answer-type modification or a claim that the operation invocation is pure.
`()` introduces an inaccessible Unit argument. Explicit `/ (C1 => C2)` contracts
and named arguments remain supported and can be mixed with inferred clauses.
Capability signature annotations still require explicit answer contracts because
they have no enclosing handler computation to supply `C`.

Named interfaces use native Effekt syntax:

```effekt
interface File {
  def read(): Int
  def close(): Unit
}
def main(): Int = {
  try { h.close(); h.read() } with h: File {
    def read() = resume(42)
    def close() = resume(())
  }
}
```

The declaration supplies omitted clause argument/result types. Explicit types
must agree with it, and implementations must provide every operation exactly
once. Declarations are checked at top level, so a handler-local alias cannot
change their meaning. Parameterized declarations such as `interface Reader[A]`
and implementations such as `with h: Reader[Int]` are supported. Functions and
operations can have multiple value parameters. Interfaces specify value
signatures; each implementation still supplies or contextually infers its own
ATM answer contract. Interface names do not introduce nominal capability types
into the paper calculus. See [syntax differences](SYNTAX-DIFFERENCES.md) for
the remaining native interface features.

Native calls such as `resume(readInt(c))`, computations inside arithmetic and
constructors, and multiple arguments elaborate to ordinary `let` bindings and
pairs. Effects execute once in left-to-right order; short-circuited operands stay
inside their branch. Native `type` declarations, postfix `match`, multiline cases
and wildcard fields are accepted. Ordinary recursive `def` selects the existing
recursive checker when its body contains a free self reference.

Multiple top-level helpers/values, acyclic forward references, top-level aliases,
native `def id[A](x: A): A = x` / `id[Int](42)` syntax, and omitted `main` result
annotations are supported. Inferred closed results must still be pure and pass
escape/residual checks. See [syntax differences](SYNTAX-DIFFERENCES.md) for the
remaining native forms and inference limitations.

Omitted handler return clauses elaborate to a fresh identity return clause.
Clause and function bodies can be expressions, and newlines separate ordinary
statements. Local functions can also have empty argument lists. The ATM shortcut
`resume()` denotes `resume(())`; native Effekt requires the latter spelling for
a Unit resumption.

The [reusable Fig. 1 example](../../examples/atm/file-reusable.effekt) uses
one explicitly polymorphic `file` value with region/computation parameters
`[R: Reg, C: Cty]`. Its callbacks declare their capabilities and evidence;
operation calls supply ATCs. The helper is checked once and instantiated at
both nested call sites, with no contextual specialization. `interface File`,
`read(): Int`, and `close(): Unit` remain. The mock-backed program checks at
`Int / pure` and returns 42 through direct Lifted/Chez.
The former paper copies `file_simple_atm.effekt` and
`file_simple_reusable.effekt` are consolidated here.

From the Effekt repository root, run it directly:

```sh
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/file-reusable.effekt'
```

Or compile it to Scheme and execute the generated file with Chez:

```sh
sbt 'effektJVM/runMain effekt.atm.Main --emit examples/atm/file-reusable.effekt /tmp/file-reusable.ss'
chez --script /tmp/file-reusable.ss
```

Native block inference is unsupported. The former per-call specialization
pass was removed; the explicit version uses ordinary quantified values and
the documented exact-region `local[R]` bridge. The
[inlined companion](../../examples/atm/file-nested.effekt) remains for comparison.
See [FIDELITY.md](FIDELITY.md#reusable-helpers-explicit-polymorphism) for the
explicit contract and the audit of remaining extensions beyond the printed
algorithm. This fully annotated ATM source requires the ATM frontend.

Operation clauses implicitly bind their resumption as `resume`, following
Effekt's syntax:

```effekt
def ask(arg: Unit): Int / (Int => Bool) = {
  val n = resume(42);
  zero(n)
}
```

For `T / (C1 => C2)`, `resume` accepts a `T`, its invocation has computation
type `C1`, and the clause is checked against `C2`. Each nested operation clause
binds its own `resume`; an outer resumption can be saved first with
`val outerResume = resume`. Aliasing, storage, and multiple invocations retain
the existing continuation typing and region restrictions. A handler's body and
return clause do not receive its resumption binding. The old explicit
`resume k` suffix remains accepted for archived regression fixtures.

Handler capabilities can be used directly as values: `invoke(h)` passes the
capability bound by `h`, `val saved = h` aliases it, and `f{h}` supplies it to a
capability abstraction. The nearest lexical term binding wins: a local value
can shadow a capability, and an inner handler or capability parameter can
shadow an outer value. The old value spelling `cap(h)` remains accepted for
compatibility; the type annotation `cap(h)` still denotes that capability's type.

`fun [A] { ... }` and `f[Int]` introduce and instantiate value-type
quantification. `predfun [P(Int)] { ... }` and
`f[(n: Int) => n >= 0]` do the same for rigid refinement predicates.
Type arguments may mention capabilities in the current context; handler escape
checks still apply after instantiation. Refinements support Int, Bool, Unit and
logical pairs, including formulas such as `{u: Unit | u == ()}` and
`{v: Int | forall n: Int. n + 0 == n}`. Quantified formula variables are scoped
and sorted; their names are freshened before substitution or SMT serialization.
`capfun (c: { def ask(Unit): Int / (Int => Int); }) { ... }` abstracts a
capability/region, and `f{h}` instantiates it. There is no implicit permission
to call that capability inside a lambda at a symbolic ATC distance.
Capability instantiation uses `S_Cap`: an implementation may accept a broader
operation argument and provide a stronger result/answer contract. Handler region
identity remains exact. Capture sets are legacy emitter metadata, not an extra
subtyping or equality restriction in the paper checker.

The region-free surface also inserts checked subsumption annotations at let
boundaries when selfified refinements contain local names. It projects those
names existentially in covariant positions and dually in contravariant positions,
using the returned-value assumption for dependent initial answers. Each generated
annotation is verified by subtyping and appears as an `Ascribe` node in
`--elaborate`. The result type and initial answer obey the let rule's escape side
conditions; the intermediate final answer remains under the let binder until
composition. This is surface annotation elaboration, not a new evidence or ATC
rule. Supplied annotations are checked as well.

## Explicit local functions

`def local f(x: Int): Int = { x }`, `fun local (x: Int): Int { x }`, and
`local (Int) => Int` select one fixed call region. In synthesis it defaults to
the lexical region; in checking an expected local arrow supplies it.
`fun local[c] ...` and `local[c] (Int) => Int` explicitly select the region of an
in-scope capability `c`, which must agree with an expected arrow.
`def rec local` supports annotated local recursion at the enclosing lexical
region; a region qualifier is not supported on recursive definitions.
An expected local arrow also supplies omitted lambda domain/result annotations.

These forms expose the declarative `T_Abs`/`T_App` rules, rather than passing
through the region-polymorphic algorithmic `A_Abs`. The body is checked at that
exact region, and calls must occur there. Core retains the region and Lifted
uses a plain function call without a call-evidence parameter. A local function
cannot be implicitly promoted to an ordinary arrow or escape its handler.
A continuation can be stored under a compatible local arrow at its ambient
region. No symbolic evidence distance is assumed to be zero.

`return effect x => { ... }` explicitly enables effects in a handler's return
clause using its known result contract. The clause executes outside the exited
handler and cannot invoke that handler's capability. Ordinary `return x =>`
keeps the previous empty-mode rule. Both extensions and their limitations are
explained in [FIDELITY.md](FIDELITY.md).

For repeated/composed callbacks, explicit value-type parameters can describe
pure answer endpoints: `(Answer => Answer)` is repeatable, and
`(Middle => Final)` followed by `(Initial => Middle)` composes to
`(Initial => Final)`. Those three adapted examples retain this restriction. General computation-type
parameters are available separately through the explicit `Cty` annotation forms.

## Explicit evidence and answer contexts

The frontend now exposes `Reg`, `Dist`, `Ev(R, D, S)`, `ATC(D)`, and `Cty`
binders and arguments. `h.op[E; K](v)` checks the supplied evidence's
endpoints and the context's depth before plugging the operation's answers.
Either annotation may be omitted: a single name in `h.op[X](v)` is classified
by its in-scope evidence or ATC kind.
These forms support callbacks with symbolic ATCs, including the annotated
[outer-call](../../examples/atm/annotations/polymorphic-outer-call.effekt) and
[two-capability](../../examples/atm/annotations/multiple-capabilities.effekt)
examples. See [ANNOTATIONS.md](ANNOTATIONS.md) for syntax, scope rules, execution,
and remaining backend restrictions.

## Deliberate boundaries

The paper algorithm is partial. In particular, an operation whose handler is
outside an ordinary region-polymorphic function has a deferred witness with symbolic distance.
`atc` cannot peel a variable number of layers, so an unannotated invocation is
rejected, including inside an unused function. An explicit context and matching
evidence supply a declarative alternative without assigning that distance zero. A function that installs its own handler is unaffected.
The older supplement's page 37 identifies ATC abstraction as future work;
the explicit annotation forms now provide it. The old `replaceAnswer` function-call extension has been removed.

`capfun here`, scoped capability tails, answer-effect sequences, and
`repeatable`/`after` bounds belong to the former prototype and are rejected.
An opaque answer effect cannot be used where the paper's final-answer projection
is undefined. Unrestricted effect quantification can still occur in types, but
it does not enable computation rules with an unknown final-answer projection.

The frontend covers the currently implemented source forms, not every kind/type
constructor in the paper grammar. Polymorphic operation-signature binders, polymorphic datatypes and inference of
unknown refinement predicates are not implemented. Higher-order value types,
explicit type/predicate quantification, annotated recursion, logical pairs and
lexical type aliases are supported. There is no termination proof for recursion.

The direct backend uses Chez exact integers and preserves the historical
lifting-based control implementation. Its ATM handler form allows differing
body, return-clause, resumption and final answer types without answer-tag sums.
Only Chez-Lift supports the new handler form; ML monomorphization rejects it
explicitly. The retained legacy emitter uses finite answer sums and JavaScript
`bigint` for differential testing. Neither backend decides source ATM validity.

## Rule-to-code map

| Paper rule/operation | Implementation |
| --- | --- |
| `A_Refl`, `A_Step`, `A_Defer` | `Evidence.reconstruct` |
| `A_MinSub*` | `Evidence.minimum` |
| `atc` | `Evidence.atc`, `AnswerContext.plug` |
| `A_AbsChk`, `A_AbsSyn` | `Checker.abstraction`, `checkValue`; `Elaboration.substitute` |
| `A_App*` | `Checker.inferTerm` ordinary application |
| `A_KApp*` | Same branch, separate `Type.Continuation`, exact region test |
| Declarative `T_Abs`/`T_App` bridge | Explicit `local` forms, `Checker.localAbstraction`, `Type.LocalFunction`; exact region, no call witness |
| `A_TAbs`, `A_TApp*` (supported kinds) | Value/type/predicate/capability abstractions and instantiations; capability scheme residual bounds |
| `A_Return*`, `A_Let*`, `A_SubC` | `inferTerm`, inherited-answer mode, `checkTerm`, `ascribe` |
| `A_If*`, guarded join/meet | `Checker.guarded` |
| `A_Do` | Evidence reconstruction, dependent substitution, `Evidence.atc` |
| Explicit declarative invocation and kind instantiation | `Annotations`, `Checker.resolveArgument`, `inferOperation`; `Core.validate` checks scoped kinds and symbolic ATC depths |
| `A_Handle`, `A_HRet`, `A_HOp` | Fresh region/edge; residual scope check; separate clause modes and continuation arrow; return-mode bridge explained in FIDELITY.md |
| `S_Fun`, `S_Refine`, `S_CPureImpure`, `S_CImpure` | `Checker.subtype`; sorted SMT verification conditions |
| Declarative `T_HRet` bridge | Explicit `return effect`; expected computation supplies the inherited final answer |
| `S_Cap` | `Checker.operationsSubtype`; contravariant arguments and covariant dependent computation contracts |

## Run and inspect

Use this checkout with its pinned Kiama submodule and Java/sbt. The recorded run
used Java 27, sbt 1.9.7, Z3 5.1.0 and Chez 10.4.1; these are observed versions,
not a tested minimum-version matrix. Node is needed for the legacy JavaScript
differential tests. This is still a working-tree artifact without a validated
clean-checkout release recipe.

Chez Scheme must be available as `chez` for `--run`; `EFFEKT_ATM_CHEZ` can
select another executable (for example `scheme`). `--emit INPUT OUTPUT.ss`
produces a standalone Scheme file containing the historical runtime. Run from
this checkout, or set `EFFEKT_ATM_RUNTIME` to `libraries/chez/lift/effekt.ss`.
The shared Core/Lifted translation also compiles for Scala.js; the process runner
is JVM-only.

Z3 must be available for nontrivial refinement obligations (`EFFEKT_ATM_Z3` can
select the executable). Unknown, timeout, missing executable and solver errors
fail the obligation. Scala.js accepts an injected solver; it has no built-in SMT
process runner.

The separate entry point is `effekt.atm.Main`; native `effekt` flags do not select
this frontend. ATM source files use the `.effekt` extension.

| ATM option | Result |
| --- | --- |
| `--check INPUT` | Check source typing/refinements and print its computation type |
| `--check-json INPUT` | Emit acceptance or a tagged checker diagnostic as JSON; no backend execution |
| `--elaborate INPUT` | Print the separate typed region/evidence Core |
| `--lifted INPUT` | Print the actual adapted historical Lifted IR |
| `--emit INPUT OUTPUT.ss` | Write standalone Scheme with the historical runtime |
| `--emit-effekt INPUT OUTPUT.effekt` | Write legacy Effekt source for comparison |
| `--run INPUT` | Compile through direct Lifted and run Chez |

```sh
sbt 'effektJVM/runMain effekt.atm.Main --elaborate examples/atm/evidence-functions.effekt'
sbt 'effektJVM/runMain effekt.atm.Main --lifted examples/atm/int-to-bool.effekt'
sbt 'effektJVM/runMain effekt.atm.Main --emit examples/atm/int-to-bool.effekt /tmp/int-to-bool.ss'
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/expectation-SAT.effekt'
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' \
    'effektJVM/testOnly effekt.core.* effekt.lifted.mono.MonomorphizationTests' \
    'effektJS/compile'
```

Check all current example expectations and regenerate paper coverage separately:

```sh
python3 docs/atm/check_examples.py
python3 docs/atm/paper_coverage.py --run --output /tmp/atm-paper-coverage.json
```

Each runner builds once by default. `--classpath-file` can reuse an exported,
freshly compiled JVM classpath; it does not verify freshness itself. An exit code
of zero means all inventoried expectations were met, including the explicitly
listed unsupported cases in the example runner. It does not mean every program
was accepted. Tagged failures and unported rows stay separate in the reports.
The historical native suites can be run additionally; their earlier results
are labeled separately in [VALIDATION.md](VALIDATION.md).

See [ARM.md](ARM.md) for the pinned benchmark inventory and the current supported
subset. Test results are recorded in [VALIDATION.md](VALIDATION.md).

## Datatypes and measures

```
data OptionInt { None(); Some(value: Int) }
def main(): {o: OptionInt | o == Some(42)} = { Some(42) }
```

Constructors support monomorphic first-order fields (`Int`, `Bool`, `Unit`,
pairs, and declared data), including mutually recursive declarations with finite
inhabitants. Refinements may mention constructor applications; SMT checks their
sorts, disjointness and injectivity. `==`/`!=` compare such values structurally.
Exhaustive flat pattern matching, structurally recursive logical measures, and
dependent/refined constructor fields are supported. Closed pure ordinary/local
function fields use the top region. Functions are projected to unit for logical
data measures; runtime equality on data containing functions is rejected.
Datatype type parameters, effectful function fields and general region-polymorphic
fields remain unsupported. See [ARM.md](ARM.md) for syntax and examples. Datatype values at the CLI print `<data>`; use equality or
refinements to inspect their contents in the supported surface subset.

`--check-json INPUT` emits a structured acceptance result or tagged diagnostic.
[The coverage runner](paper_coverage.py) uses these categories so a parser error,
unsupported ATC or unavailable solver never counts as an expected UNSAT result.
