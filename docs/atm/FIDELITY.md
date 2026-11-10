# Paper fidelity: repairs and remaining work

Updated 2026-11-10; the earlier rule audit used `popl27-paper152.pdf` and
`popl27-paper152-supplemental.pdf`, on Effekt v0.3.0.

The implementation is a tested subset of the algorithm, not a full transcription
of the paper language or an implementation covered automatically by its theorem.
This document distinguishes repaired acceptance bugs, surface conveniences, and
work that is still missing. This rule audit uses the supplied paper snapshots;
the maintained supplement now documents the Effekt implementation and its
current benchmark results.
For the suite outline and current per-family coverage, use
[BENCHMARKS.md](BENCHMARKS.md).

## Repaired checker discrepancies

| Audit item | Repair | Regression |
| --- | --- | --- |
| `A_Do` synthesized its argument instead of checking it | Operation arguments use `checkValue` against the operation domain, just like ordinary application | `operation-checked-lambda.effekt`: an inline identity function is accepted and returns 42 |
| Mixed pure/impure guarded join/meet lost the returned-value binder | Both initial answers are renamed into a fresh binder; the pure-to-impure premise has the appropriate returned-value type and branch assumption | `mixed-guard-dependent.effekt`, its mirrored branch order, and a weakened-precondition rejection |
| Extra capture-set subtyping rejected a recursive function under an unused handler | Captures no longer participate in function/scheme subtyping, type equality or free-region checking; the region still participates | `recursive-extra-capture.effekt` returns 42; a different region remains unequal |
| Type arguments had to be closed | Type arguments are resolved in the current context; instantiated types retain their region identities and remain subject to handler escape checks | A polymorphic identity can accept a local capability inside its handler; returning it out of that handler is rejected |
| Capability instantiation required identical signatures | `operationsSubtype` implements `S_Cap`: contravariant argument, covariant full dependent computation, including answer effects; capability-family parameters use the dual direction | A broader argument/stronger result is accepted; stronger preconditions and weaker postconditions are rejected |
| A capability binder did not scope over its operation signatures | Signature resolution introduces the fresh region first; equality also alpha-renames that region in signatures | A quantified operation's function parameter can refer to the bound region |
| Lambda domains were mandatory even in checking mode | `fun (x) { ... }` obtains its domain and codomain from an expected arrow | Ordinary checking machinery is exercised through operation arguments and handler returns; a wrong result is rejected |
| Handler result annotations were always mandatory | `try { ... } with h { ... }` is checked against an available expected computation type | Direct checking and a handler inside a region-polymorphic function; residuals are retained for abstraction |
| Unit refinements and universal formulas were missing | Added the singleton Unit logical sort/literal and scoped, sorted `forall` formulas; SMT uses a singleton datatype and quantifiers | Valid/invalid formulas, sort errors, binder shadowing and alpha-equivalence |

The three original reproduction files are in `examples/atm/fidelity/`.
`AtmFidelityTests` runs accepted executable cases against the independent source
interpreter and direct Chez-Lift with cleanup both enabled and disabled. Negative
cases must fail checking. The pre-fix run demonstrated that all three original
regressions failed; the repaired tests pass.

## Core validation strengthened, but not completed

`Core.validate` now tracks lexical value bindings and capability contracts. It
checks erased variable types, literal types, abstraction contracts, residual
telescopes, type-application results and freshness of region/evidence binders.
An operation's witness must end at its receiver's actual region, including when
the receiver is a capability alias. Checking only depth was insufficient to
detect a changed receiver.

Mutation tests now reject an operation redirected to another in-scope handler,
an unbound value and a changed base type on a bound variable. Earlier tests for
wrong regions, ATC depth, Lifted evidence and handler structure remain.

This is still **not a complete independent declarative checker**. Refinement
entailments, dependent computation subtyping and the ATC hole obligation remain
trusted results of `Checker`. In particular, successful structural validation is
not evidence for all of Theorem 3's typing premises.

## Implemented surface conveniences

The frontend retains the region-free Effekt-like surface. Explicit regions, evidence, and ATCs are also available through the checked
annotation forms described in [ANNOTATIONS.md](ANNOTATIONS.md). Explicit domains/results
remain necessary where neither bidirectional checking nor the partial annotation
pass below supplies them. Annotated function recursion is supported; general
`fix x. V` is not implemented.

Automatic closing of local refinement names at let boundaries remains in place;
its redesign is deferred. `closeLet`
projects names existentially in positive positions and dually in negative
positions, then verifies the generated contract by subtyping. This permits
ordinary local bindings without asking users to write every escape-free
annotation. The generated `Ascribe` is visible in elaboration. It is an
annotation-generation pass, not a replacement for the paper's escape conditions.

The selected backend is historical Lifted/Chez. Its runtime
evidence representation is useful for this implementation. Passing execution
tests establishes behavior of that path; it does not prove the Core-to-Lifted
translation correct.

## Named interface declarations: surface signature lookup

Native `interface File { def read(): Int def close(): Unit }` headers and
`with h: File { ... }` implementations are accepted. Parameterized interface
declarations/explicit instantiations and multiple value arguments are also
supported; each implementation still has monomorphic operation signatures.
The checker resolves declarations in the top-level environment and verifies any repeated implementation annotations
against them. Missing/extra/duplicate clauses and wrong arity are rejected.
An absent return clause expands to an identity clause with a fresh binder.
No nominal-interface rule or capability equality rule is added to the calculus;
the existing structural operation signatures and `A_Handle` checks remain.

A result-only declaration does not specify an ATM answer transition. Each
implementation may give an explicit transition or use the contextual convention
below. This is additional surface elaboration, not a new inference rule in the
paper. [SYNTAX-DIFFERENCES.md](SYNTAX-DIFFERENCES.md) records the native language
features that remain unimplemented rather than attributing them to ATM.

## Native syntax elaboration

`Parser`/`Surface` lower nested expression calls to fresh `let` bindings in source
evaluation order. Computed or partial right operands of Boolean short circuit
operators stay under an `If`. Multiple parameters/arguments become nested pairs
and checked projections, with corresponding substitutions in result/answer
refinements. Native datatype/match spelling uses the existing datatype rules.
Ordinary self recursion selects the existing annotated `LetRec`; acyclic
top-level dependencies become local bindings. Native type-parameter/application
spelling uses the existing type abstraction/application machinery.

When `main` omits its result, the checker synthesizes the complete program and
retains the pure-final-computation, capability-escape and residual-obligation
checks. Native blocks use the additional contextual pass described below; full
block/capture or effect-set inference is not claimed.
Empty handlers retain a region and distance-one edge; they are not optimized
away before checking. These conveniences do not change the evidence/ATC rules
or establish an operational correspondence theorem for the compiler.

## Native blocks: contextual specialization

`def file(c: Int) {body: {File} => Int}` is retained as a reusable source
function. `BlockDefine` records its lexical environment and block declaration.
At a `BlockApply`, `Checker.applyBlock` projects the result value type, combines
it with the inherited answer, and checks a specialization of the helper at the
caller's exact region. The helper's value parameters and callback remain
runtime arguments. Free values are saved under fresh names at declaration time
and supplied as environment arguments, preserving lexical shadowing.

When the helper executes `body {f}`, `invokeBlock` checks the supplied interface
and obtains an actual witness from the capability region to the specialization's
caller region using `Evidence.known`. The witness must have concrete distance
`d`. The block is checked as a capability abstraction containing an evidence
abstraction and an exact-region Unit function. Schematically, for caller region
`rho`, operation signatures `ops`, inherited answer `C`, and block result `R`:

```
forall cap f: ops.
  forall e: Ev(f, d, rho).
    local[f] (Unit) => R / (C => C)
```

The callback body is checked with the corresponding bound evidence in `delta`.
The actual block invocation supplies its capability and the checked witness;
`Core.validate` verifies the kind, endpoints, distance, and instantiated type.
For Fig. 1 both callback edges have distance one. The operation distances remain
**1, 0, 1, 0**, and `Evidence.atc` reconstructs the same contexts as for the
inlined companion. The outer helper result is `Int / pure`; the inner result is
`Int / (Int => Int)`. Each callback's default transition preserves the inherited
answer, as with the existing omitted-operation-contract convention.

The comparison with `defn/algorithmic.ott` and
`PLDI27/sections/implementation.otex` has the following boundary:

- `A_Do` and `atc` are unchanged: symbolic distances still reject, and a concrete
  distance must match the number of retained answer layers. The new evidence
  fact is a quantified, checked argument, not a guessed distance.
- The handler is checked by the existing `A_Handle` implementation, including
  its residual and region-escape checks. Residual obligations from generated
  function bindings are propagated to enclosing abstractions and the program
  boundary; none are discarded.
- Exact-region helper/callback arrows use the existing declarative
  `T_Abs`/`T_App` bridge described below, not the printed region-polymorphic
  `A_Abs` rule. Capability and evidence applications remain explicit in Core.
- **The contextual specialization pass is additional surface elaboration.**
  The paper does not provide these native block schemes, their inference rule,
  or a proof of this pass. It does not imply that arbitrary symbolic-distance
  programs now satisfy the printed algorithm. No compiler correspondence or
  soundness theorem is claimed for this extension.

`AtmBlockTests` inspects Core to ensure helper lambdas/calls survive and their
callback operations have not been substituted into the helper bodies. The
independent source interpreter executes native block closures directly; it does
not execute the inferred specialization. Both optimized and unoptimized Chez
are compared with this interpreter and native Effekt on Fig. 1.

Current limits: one block parameter with one capability parameter, statically
named nonrecursive helpers, and one compatible callback answer/region contract
per specialization. Invocation must be at the capability's own region with
known evidence to the caller. Same-region forwarding is supported; forwarding
across a new delimiter is rejected. Headers and free names are checked when a
scheme is defined; its full body is checked separately for each use, not once
as a principal polymorphic library signature. Definitions that never invoke
their block cannot infer its contract. General escaping blocks, higher-rank
capture inference, and arbitrary callback answer transitions remain work.
The direct Lifted backend supports the generated evidence abstraction; the
legacy source emitter still rejects explicit evidence-kind abstractions.

## Omitted handler annotations: additional surface inference

The 2026-11-10 Fig. 1 audit compared `Checker.scala` with the current
`defn/algorithmic.ott` and `PLDI27/sections/implementation.otex` in the paper
repository. The paper explicitly assumes operation signatures are supplied.
`A_Handle` checks against a complete computation type `C_F`; its inherited
mode is `pi(C_F)`. That mode alone does not determine the value result or the
initial answer. There is no handler-synthesis rule in the printed algorithm.

The result-only operation syntax therefore adds a surface convention: for a
known handler computation `C`, an omitted operation transition is `C => C`.
Each resulting clause is checked by the existing clause checker. This does not
infer arbitrary answer-type modification or make operation invocations pure.

An unannotated handler in synthesis now uses an additional, partial annotation
pass. `handlerResultValue`/`projectedValue` project a candidate value type `T`
from declared value types, operation result types, the handled body, and the
return clause, forgetting refinements. They do not produce a typing derivation.
The proposed whole-handler annotation is `T / pure` in empty inherited mode
and `T / (C => C)` under inherited answer `C`. Its final-answer projection
therefore matches the input mode. `inferHandler` then performs the full checks
on the original body and clauses, including operation arguments, evidence,
ATC depths, residuals, return contracts, and scope escape. No supplied annotation
is weakened or retried under a different type. Unsupported projections require
an explicit annotation; this is not principal or complete inference.

For Fig. 1, this generates outer `Int / pure` and inner
`Int / (Int / pure => Int / pure)`. The operation annotations are completed
using those computations before `A_Do` reconstructs depth-zero or depth-one
contexts. `AtmHandlerInferenceTests` also checks a Bool-valued inner handler
under an Int answer, multiple nesting levels, and invalid arguments/clauses.

This is an implementation extension that feeds annotations to the existing
checker, not a claim that the paper already infers those annotations. The
separate return-clause mode bridge below remains relevant, and no mechanized
soundness theorem for these Scala passes is claimed.

## Handler return mode: an explicit rule reconciliation

The supplement's `A_HRet` checks the return body under empty inherited mode
against `C`. Its general `A_SubC` conclusion, however, checks under `pi(C)`.
For impure `C`, these are different modes. Merely renaming an implementation
function would not resolve that discrepancy.

The implementation retains empty inherited mode and uses this explicit bridge:

```
Gamma | delta | rho | empty |- M synthesizes D
Gamma |- D <: C
------------------------------------------------  return-clause bridge
check the return clause against C, retaining M's elaboration
```

For `return V`, value checking supplies the expected value type before producing
the pure computation `D`. This also permits an unannotated lambda in a return
clause. The final subsumption is checked in the return-clause context, not under
facts from the handled body's execution path.

The declarative justification is `T_Sub` after elaboration of `M`; a pure `D`
can meet an impure `C` only when `S_CPureImpure` establishes the required answer
subtyping premise. This is a proposed reconciliation of the printed mode rules,
not a claim that the printed algorithm already contains this bridge. The code
makes the choice explicit in `checkTermInMode`.

The explicit surface form `return effect x => { M }` now opts into checking `M`
against the known return computation `C` under `pi(C)`. This is a second,
deliberate declarative `T_HRet` bridge, not the printed `A_HRet` rule. The default
form remains unchanged. Checking is in the ambient region outside the handled
delimiter, with the returned-value assumption and outer capabilities only.
`return-effects.effekt` exercises an outer operation in this clause. Regressions
reject the wrong answer type and use of the exited handler's capability.

## Explicit fixed-region functions

`local` functions expose the supplement's declarative `T_Abs` and `T_App`
(Figure 7): check the body at a fixed region and require that exact region at
application. `local[c]` names that region through a capability. An omitted
qualifier uses an expected local arrow's region in checking, or the current
lexical region in synthesis. An explicit qualifier must agree with an expected
arrow. The frontend records a separate
`LocalFunction` type and `LocalLambda` Core node. Neither acquires an abstract
call witness, and Lifted gives them zero call-evidence parameters. Operations
inside them use evidence reconstruction and the partial `atc` when annotations
are omitted, or check explicit evidence and ATC arguments.
Local recursion is checked against an explicit contract. Same-region
continuations can be viewed as local arrows with compatible contracts; they
cannot become ordinary region-polymorphic functions.

This is an explicit extension/bridge to the declarative language, **not** a
claim that Figure 10's algorithmic grammar or `A_Abs` already infers local
arrows. Ordinary lambdas still use `A_Abs` unchanged. Handler escape tests and
wrong-region application tests apply to local arrows; capture sets remain
legacy-emitter metadata, not source typing permissions.

Because Definition 17's caller-indexed codomain families are still absent, an
ordinary function cannot synthesize a returned local arrow indexed by its fresh
abstract caller region. Relabeling it as the function's required region would
be wrong. The checker reports this as unsupported; an explicitly stable local
result annotation works. This restriction also covers returned continuations
and returned evidence kinds that mention the implicit caller region.

Ten formerly blocked examples now check; seven are enabled by new forms and
three also replace the former `repeatable`/
`after`/opaque-effect bounds with explicit value-type parameters for pure answer
endpoints. The original closed witnesses are retained and executed, but the
new generic interfaces do not quantify over arbitrary nested computation
answers. See [REJECTED-EXAMPLES.md](REJECTED-EXAMPLES.md) for individual changes
and the retained unannotated negative fixtures. Their annotated counterparts
now execute while preserving the callback and two-capability abstractions.

## Unresolved fidelity and coverage gaps

These omissions are not defended as better algorithms, and are not fixed by the
repairs above:

1. **Definition 17 is not implemented literally.** Core annotations still use
   the algorithmic `Function`, `Continuation` and `CapabilityPoly` constructors,
   alongside the explicit fixed-region `LocalFunction` extension.
   Function nodes retain region/distance/evidence binders, but types are not
   expanded into a separate declarative language containing the full
   `forall r:Reg. forall d:Dist. forall e:Ev[r <=_d rho]. ...` telescope.
   Capability abstraction also remains a surface convenience that binds both
   the region and a capability value. A complete target translation must make
   this elaboration explicit as well.
2. **Polymorphic operation-signature binders remain unsupported.** Explicit
   `Reg`, `Dist`, `Cty`, `Ev`, and `ATC` quantification/application are now checked,
   alongside existing type, predicate, effect and capability forms. This does
   not add quantifiers to individual operation clauses or implement every
   declarative computation rule for opaque effects.
3. **Abstract result projections have a backend restriction.** Direct Lifted
   lowering supports computation variables via value-type parameters, but an
   abstract ATC application used as a computation's result needs a known value
   projection. ATC applications in answer endpoints, including the symbolic
   callbacks, execute. The legacy source emitter rejects the new kind-polymorphic
   forms. See [ANNOTATIONS.md](ANNOTATIONS.md).
4. **Other language coverage remains limited.** General value fixpoints,
   polymorphic datatypes, and unknown refinement-predicate inference are absent.
   Monomorphic data now supports exhaustive matching, dependent fields, closed
   pure top-region function fields, and structurally recursive logical measures.
   The function component of logical data is projected to unit; executable
   equality on function-containing data is forbidden. This extension is not a
   mechanized general datatype theory or automatic induction procedure.
5. **All 52 pinned upstream ARM programs now have ports with adaptations.**
   Thirty-one accept and 21 fail their contracts; one accepted distribution file
   is mislabeled UNSAT upstream. BFS/modulus/shift helper specialization and
   scheduler/reference state passing are disclosed in [BENCHMARKS.md](BENCHMARKS.md).
   In particular, shift's inert recursive reset is omitted; the general recursive
   helper is not implemented. The 59 paper ARM rows remain a different inventory.
6. **No mechanized implementation or backend preservation proof is provided.**
   Regression tests and SMT validity checks are distinct from that proof.

The explicit annotation path now checks dependent kinds, scoped aliases,
evidence endpoints, distance equality, and ATC plugging. Core independently
checks kind applications and symbolic ATC depths; Lifted receives evidence
parameters as runtime arguments. Remaining target work includes the literal
Definition 17 translation and polymorphic operation signatures, with a complete
independent declarative checker.

Symbolic-distance ATC **inference** is a separate matter: rejecting it agrees
with supplemental Section 3.3, page 37. ATC abstraction was identified
there as future work. It is now available explicitly; the unannotated algorithm
and its symbolic-distance rejection remain unchanged.

## Validation

See [VALIDATION.md](VALIDATION.md) for commands, counts and logs. This work changes the ATM
frontend and validation, not the historical Lifted runtime. Documents describe
remaining work explicitly rather than counting it as implementation coverage.
