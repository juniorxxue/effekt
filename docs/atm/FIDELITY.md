# Paper fidelity: repairs and remaining work

Updated 2026-11-06 against `popl27-paper152.pdf` and
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

The frontend retains the region-free Effekt-like surface. The paper's regions and
evidence still belong in elaboration and Core. Explicit domains/results remain
necessary where bidirectional checking supplies no expected type. Annotated
function recursion is supported; general `fix x. V` is not implemented.

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
inside them still use ordinary evidence reconstruction and the partial `atc`.
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
result annotation works. This restriction also covers returned continuations.

Ten formerly blocked examples now check; seven are enabled by new forms and
three also replace the former `repeatable`/
`after`/opaque-effect bounds with explicit value-type parameters for pure answer
endpoints. The original closed witnesses are retained and executed, but the
new generic interfaces do not quantify over arbitrary nested computation
answers. See [REJECTED-EXAMPLES.md](REJECTED-EXAMPLES.md) for individual changes
and the two examples that remain unsupported.

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
2. **The complete typelike/kind language is absent.** General computation-type,
   distance, evidence and ATC quantification/application, and polymorphic
   operation-signature binders remain unsupported. Existing type, predicate,
   effect and capability forms do not constitute the full kind system.
3. **The explicit evidence/ATC annotation escape hatch described in paper
   Section 6.2 is absent.** It needs the target kinds and well-formedness rules;
   it must not be implemented by trusting arbitrary annotations or restoring
   the old `replaceAnswer` approximation.
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

The next architectural change should introduce the full declarative type/kind
representation and its checker, then the Definition 17 translation, followed by
polymorphic operation signatures and explicit ATC/evidence arguments. That order
gives the new forms a checked target instead of accumulating unchecked metadata.
The existing algorithmic types can remain in the frontend for diagnostics.

Symbolic-distance ATC **inference** is a separate matter: rejecting it agrees
with supplemental Section 3.3, page 37. General ATC abstraction is identified
there as future work. This rejection has deliberately not been “repaired” by
changing the algorithm's meaning.

## Validation

See [VALIDATION.md](VALIDATION.md) for commands, counts and logs. This work changes the ATM
frontend and validation, not the historical Lifted runtime. Documents describe
remaining work explicitly rather than counting it as implementation coverage.
