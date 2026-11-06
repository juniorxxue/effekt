# Direct ATM Core to historical Lifted

Status snapshot: **2026-11-06**. The default route on `codex/atm-v0.3.0` is:

```
Effekt-like .effekt source
  -> Checker: algorithm subset + explicit local-function/return forms
              residual obligations, evidence reconstruction, partial ATC
  -> Core: separate syntax with computation types, regions and explicit witnesses
  -> LiftedLowering: actual effekt.lifted.ModuleDecl
  -> generator.chez.TransformerLift
  -> historical libraries/chez/lift/effekt.ss runtime
```

There is no intermediate `.effekt` source, native name resolution/type checking,
or native lift inference. `Lowering.scala` is the retained legacy source emitter,
available through `--emit-effekt` and differential tests. It is not called by
`DirectBackend`.

## Preserving the paper algorithm

The direct lowering records each `A_Do` operation's instantiated signature,
inferred ATC and computation type. It does not replace evidence reconstruction
or the partial ATC algorithm. Explicit local functions check operations at a
fixed region, avoiding a symbolic caller distance where that region is known;
ordinary functions retain the symbolic-distance inference limitation.

`Core.elaborate` converts the checked tree into a distinct syntax with no embedded
source terms. Every computation retains its full type and ambient region.
Ordinary lambdas bind a fresh body region and a call witness. Capability
abstractions bind their residual evidence telescope. Ordinary function and
capability applications explicitly supply reconstructed witnesses. Local
functions and continuations require their exact call region and take no call
witness. A closed program must have no remaining residual obligations.
Handler edges have distance one.

`Core.validate` checks value/capability binding scope and erased variable types,
abstraction contracts, residual telescopes, type-instantiation results,
witness scope and composition, function-call endpoints,
instantiated residual endpoints, continuation regions, handler edges, and the
operation receiver's identity as well as the depth/plugging/outer layers of ATC
certificates. Refinement subtyping, including
the ATC hole obligation, remains the responsibility of `Checker`. This validator
is an implementation invariant, not an independent complete declarative checker.
Core also still uses the algorithmic function/continuation type constructors
alongside the explicit `LocalFunction` extension;
the full Definition 17 type translation is outstanding. See
[FIDELITY.md](FIDELITY.md) for the precise boundary.

## Lowering map

| ATM Core | Historical Lifted |
| --- | --- |
| Ordinary function abstraction | Boxed `BlockLit` with an `EvidenceParam` and value parameter |
| Explicit fixed-region local function | Boxed `BlockLit` with a value parameter and zero call-evidence parameters |
| Local function application | Plain `App`, checked at exactly the declared region |
| Caller-region application | `App` with the reconstructed `Evidence` argument |
| Reflexive witness | `Here`, the empty lift list |
| Evidence variable | `Lift.Var` referencing the bound evidence parameter |
| Evidence composition | Concatenated lift lists, preserving inner-to-outer order |
| Residual-bound capability abstraction | One `EvidenceParam` per bound obligation plus a capability `BlockParam` |
| Capability instantiation | Evidence arguments followed by the capability block |
| Type abstraction/application | Lifted block type parameters and type arguments |
| Predicate/effect proof abstraction | Runtime erasure after source checking; its value body remains |
| Operation invocation | Capability `Member` application with actual evidence |
| Handler | New `Stmt.Handle` with body, implementations, explicit return clause and final runtime type |
| Handler operation | Historical `Shift` with its evidence and typed resumption binder |
| Source continuation call | Plain call through an adapter supplying `Here` to the historical resumption |

The continuation adapter implements the distinction between region-polymorphic
ordinary functions and the paper's region-monomorphic `A_KApp` rule. It does not
make a continuation callable beneath an arbitrary extra delimiter.
A continuation may be stored and returned under a compatible local arrow at
the same region. Local Core lambdas validate their body region and contract
before lowering to a boxed block with zero call-evidence parameters.

`return effect` changes the source checking mode explicitly. Both default and
explicit effectful return clauses lower to the same `Handle.onReturn` structure;
there is no separate runtime effect-return construct.

Refinements and answer transitions stay in Core. `LiftedLowering.Program` retains
the Core and a table relating generated statements to their full computation
types and regions. Runtime Lifted types erase refinements and answer effects;
their results are checked against the erased Core annotations. Type parameters,
capability interfaces, boxed function types and evidence argument kinds remain
in the actual Lifted tree. Regions/distances are static; witnesses survive as
runtime lifting functions. An ATC is used to establish a valid answer transition,
not re-inferred by the backend.

## Why the handler extension is necessary

The old `Try` assumes an identity return clause and synthesizes the handler
result from its body. That is insufficient for a body locally returning `Int`
whose operation resumes an `Int` continuation and then returns `Bool`.
The new form is:

```scala
Handle(body: BlockLit, handler: List[Implementation],
       onReturn: BlockLit, result: ValueType)
```

`body` receives the handler edge evidence and capability blocks. `onReturn`
receives the body's local result. The node's `result` is the handler's final
runtime type. `Shift` already allows a clause result to differ from its
resumption's result, so it is reused.

The Chez translation follows the existing `Try` translation, replacing its
identity normal-return continuation with `onReturn`. Normal return and each
resumption run that clause after leaving the delimiter; aborting operations
bypass it. No finite answer sums, answer tags or tag-failure branches are emitted.
The historical `Try` translation and runtime are unchanged.

## Validation and supported backend

`LiftedValidation` checks the emitted subset independently of Lifted's `.tpe`
synthesis: bound value/block/type/evidence variables, application arity and
argument kinds, alpha-equivalent polymorphic types, constructor/field/method
types, operation implementation signatures and return-clause inputs.
Its checks do not establish full ATM typing after erasure. In particular, a
`Handle` result annotation alone cannot prove answer transitions; the checked
Core and its translation certificates supply that information.

The initial target is **Chez-Lift**. It preserves evidence and supports the
polymorphic boxed values already accepted by the ATM frontend. Its exact integer
arithmetic matches the refinement model; division/remainder truncate toward zero.
ML's evidence monomorphization and generator explicitly reject `Handle`; ATM
support for ML is not implemented.

The frontend is still the subset documented in the README: no general symbolic
ATC inference and no full paper grammar. All 52 upstream ARM files have ports,
with the explicit specializations and label discrepancy in the benchmark outline.
The local-function and explicit-return
bridges extend the source subset as documented in [FIDELITY.md](FIDELITY.md).
These execution and validation tests are not a formal correspondence/soundness
proof. [BENCHMARKS.md](BENCHMARKS.md) gives the complete benchmark outline and
separates supported ports, intentional negatives and missing implementations.

## Inspect and run

```
sbt 'effektJVM/runMain effekt.atm.Main --elaborate examples/atm/evidence-functions.effekt'
sbt 'effektJVM/runMain effekt.atm.Main --lifted examples/atm/int-to-bool.effekt'
sbt 'effektJVM/runMain effekt.atm.Main --emit examples/atm/int-to-bool.effekt /tmp/int-to-bool.ss'
chez --script /tmp/int-to-bool.ss
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/expectation-SAT.effekt'
```

`--emit` includes the historical runtime so the resulting Scheme file is
standalone. `--run` uses `chez`, overridable with `EFFEKT_ATM_CHEZ`, and reports
runtime errors or a 30-second execution timeout without falling back to another
backend. `EFFEKT_ATM_RUNTIME` selects the runtime file when outside the checkout.
See [VALIDATION.md](VALIDATION.md) for observed results and commands.

Datatype constructor values now lower to native Lifted data declarations and
`Make`. Structural equality uses checked Lifted `Match` clauses; it cannot use
Chez `equal?` on opaque constructor records. Source matches also lower directly
to Lifted `Match`, and function-valued constructor fields use boxed functions.
Logical measures are erased. Equality of data containing functions is rejected. See [ARTIFACT-STATUS.md](ARTIFACT-STATUS.md).
