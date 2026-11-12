# Named caller regions and caller-indexed results

Updated 2026-11-12 after naming caller-region binders and removing `here`. Ordinary
functions implement the caller-indexed **result** family of Definition 17.
The reusable Fig. 1 now uses currying and an explicitly fixed-region result.
The paper sources are unchanged. The implementation tests below are not a
mechanized proof of this compiler or of its remaining extensions.

## Boundary with the paper

The reference sources are `defn/algorithmic.ott` (`A_AbsChk`, `A_AbsSyn`,
`A_App`, `A_AppF`) and `defn/algorithmic_appendix.otex` (Definition 17 and
“Incompleteness”) in the lexical-effects repository.

| Feature | Relation to the printed algorithm |
| --- | --- |
| Ordinary caller-indexed result families | Implements `A_Abs`/`A_App` and Definition 17: the domain is fixed before the fresh caller is introduced; the result is instantiated at each call. |
| Explicit call-ATC abstraction | Extension. The appendix leaves ATC abstraction across ordinary functions to future work. Callers supply contexts; no symbolic-depth ATC inference is added. |
| Explicit fixed-region arrows with ordinary `fun` | Declarative `T_Abs`/`T_App` bridge, outside printed ordinary `A_Abs`. A function is checked and callable at exactly its annotated region. Fig. 1 uses this bridge for the second curried function. |
| Explicit `Reg`/`Dist`/`Ev`/`ATC`/`Cty` annotations | Surface access to declarative typelikes with kind, endpoint, depth, and plugging checks. Explicit evidence parameters can supply reconstruction edges. These checking paths are not claimed to be printed reconstruction rules. |
| Named caller binder `[R: Reg]` after value parameters | Surface name for the existing `A_Abs` caller parameter, supplied implicitly by `A_App`. It introduces no runtime region, new quantifier, or caller-dependent domain. Handler labels and `top` name existing regions. |
| Omitted handler annotations, identity operation answers, refinement closing | Additional partial annotation generation and mode reconciliation, described in [FIDELITY.md](FIDELITY.md). Not part of the printed inference algorithm. |
| Caller-dependent ordinary argument domains | Removed. Neither the implicit caller nor the post-parameter call-ATC binders scope over that domain. |

Named interfaces, zero-argument operations, sequencing, and pairs are surface
elaboration conveniences. Native block/capture inference is still unsupported.
The internal family representation and direct Lifted lowering are not a literal
implementation of every declarative target construct in Definition 17, and
Core validation is not a second complete declarative/refinement checker.

## Ordinary functions: fixed domain, caller-indexed result

An ordinary arrow has a required scope `rho`, a caller-independent domain `T`,
and a result family `C(r)`. Definition 17 translates it conceptually as:

```
forall r: Reg. forall d: Dist. forall e: Ev(r,d,rho).
  (x: T) ->[r] C(r)
```

`T` can mention regions and typelike parameters already in scope. It cannot
mention this arrow's fresh `r`, `d`, or call-ATC parameters. The domain is
resolved before these binders are introduced. `Checker.abstraction` then checks
the body once at a fresh region, retaining ordinary residual reconstruction,
minimum-scope selection, and discharge. `A_App` checks the actual argument
against the same `T`, reconstructs the caller-to-required-scope witness, and
opens only the result family. No application revisits the function body.

Subtyping, alpha equality, free-region analysis, and Core annotation validation
respect this domain/result scope boundary. Returned evidence kinds, fixed-region
arrows, and nested result families can retain the caller binder. In particular,
a nested fixed arrow's domain can mention the **enclosing** caller: it is part
of that enclosing ordinary function's result, not its argument domain.
`FunctionTypes` centralizes result and call-context opening; resolved identities
are generative. Escaping free handler regions remain forbidden.

## Explicit names for the existing caller parameter

Name an ordinary function's caller after its value parameter list:

```effekt
val make = fun ()[R: Reg] {
  fun [E: Ev(R, 0, R)] { 42 }
};
make()[refl(top)]
```

The same binder is available on declarations and ordinary arrow types:

```effekt
def make(u: Unit)[R: Reg]: forall E: Ev(R, 0, R). (Int) = {
  fun [E: Ev(R, 0, R)] { 42 }
}
val make: ((Unit)[R: Reg] => forall E: Ev(R, 0, R). (Int)) =
  fun (u)[S: Reg] { fun [E: Ev(S, 0, S)] { 42 } };
```

`R` names the fresh static caller variable already introduced by `A_Abs`.
It does not allocate a handler or a runtime region. `A_App` supplies the actual
current region implicitly: a call inside handler `h` instantiates this binder
with `h`'s region. Calls do not add a region argument. The function definition
is checked once, under a fresh identity independent of the written name.
Expected-type checking and alpha equality allow different binder spellings.

The binder scopes over later call-ATC kinds, the result annotation, and the body.
It does **not** scope over the ordinary value-argument domain. If an outer region
has the same name, the domain uses that outer binding; the body/result use the
new caller. Nested binders have distinct identities, and a binder is not visible
in following declarations. At most one caller binder may appear, before call
ATCs; duplicate names and mixing a caller binder with a fixed-region contract
are rejected. The ambient name `top` cannot be rebound by a caller binder.

These two forms are different:

```text
fun (x: T)[R: Reg] { ... }   // names the implicit caller; invoke as f(x)
fun [R: Reg](x: T) { ... }   // explicit typelike parameter; invoke as f[h](x)
```

The binder may be omitted if its name is not needed. For example, `fun () { 42 }`
still has the ordinary implicit caller telescope. Existing handler names should
be used directly when referring to those handlers: `Ev(h,0,h)`, `refl(h)`, and
`at [h]`. Fixed-region functions continue to name an existing region; their
contracts introduce no caller binder. `region R = h;` is a lexical alias for an
existing region, not a declaration of a fresh caller.

`here` has been removed from the keyword set, region resolution, and the old
scoped-capability syntax. It now behaves like any other identifier: an unbound
`here` is rejected, while a user-declared value or region named `here` is legal.
Bare `refl` continues to denote reflexivity at the checking region; it has a
separate internal representation and does not resolve a hidden `here` name.

## Explicit call ATCs: remaining extension

An ordinary function can declare ATCs after its value parameter list:

```effekt
val callback = fun ()[K: ATC(dist(path(h)))]:
    Int / (K[Int] => K[Bool]) {
  h.ask[path(h); K]()
};
callback[[]]()
```

Its callback type is:

```effekt
(Unit)[K: ATC(dist(path(h)))] => Int / (K[Int] => K[Bool]) at {h}
```

The call-ATC telescope sits inside the implicit caller region/distance/evidence
telescope. Its binders scope over subsequent call-ATC kinds, the result, and the
body, **not the ordinary argument domain**. An ATC parameter bound outside the
ordinary function, using `fun [K: ATC(D)] { ... }`, can still occur in its domain;
that is an already-scoped explicit parameter.

`dist(proof)` projects the distance of a checked witness; it does not guess a
numeric depth. A call supplies ATCs explicitly and checks their depths against
its actual call evidence. The definition is checked under rigid ATCs and generic
call evidence. Its required scope is the minimum of the lexically available
`path(r)` targets in the declared ATC kinds, excluding the function's own named
caller, or the lexical scope if none occur. The body cannot add an
undischarged obligation from its fresh caller. Other residuals propagate outward.
This annotation-directed path is an extension, not the printed ordinary `A_Abs`
procedure. An explicit `Ev(a,d,b)` parameter contributes a reconstruction edge
when `a` has no outgoing edge already; otherwise its witness remains available
explicitly. All explicit evidence applications check endpoints and distances.

ATCs are erased before execution; call evidence remains a runtime argument.
Symbolic-distance ATC inference remains unsupported. Recursive call-ATC telescopes
and standalone partial ATC applications remain unsupported. Abstract ATCs in
answer endpoints execute, while an abstract computation-result projection still
needs a known backend value representation.

## Curried Fig. 1

[`file-reusable.effekt`](../../examples/atm/file-reusable.effekt) keeps
`interface File`, `read(): Int`, and `close(): Unit`. Schematically, with `B(r)`
standing for the explicit callback contract containing `Ev(f,1,r)`:

```text
file[C] : Int ->rho ((B(r) ->fixed[r] C) / pure)
```

The source structure is:

```effekt
val file = fun [C: Cty] (c: Int)[R: Reg] {
  fun (body: /* B(R), written out in the example */) at [R]: C {
    // Install handler h and invoke body(h)[step(h)][[]]().
  }
};
file[Int](7)(/* outer callback */)
// Within that callback:
file[A](35)(/* inner callback */)
```

The ordinary helper's domain is `Int`. Its result contains a fixed-region
function whose argument depends on the outer caller. The second application
must occur at the same region as the first; these immediate curried calls do.
The helper is checked once, without specialization. The inner fixed function
installs `h`, supplies `step(h)` to the callback's evidence binder, and supplies
`[]` for its call ATC.

`R` in `B(R)` and `at [R]` is explicitly bound by the outer helper. The
later `h` names its newly installed handler, a different region. The outer
callback declares `fun ()[S: Reg, K: ATC(dist(path(a)))]: ...`; its inner
callback uses `Ev(b,1,S)`. `S` is the outer callback's abstract caller, not
necessarily capability `a`'s region. The separate `PA` witness connects
`S` to `a`. The inner callback uses
`path(b) + EB + PA` and `J[layer(Int, K[Int], K)]` for its outer operation.

The mock-backed program checks at `Int / pure` and returns `42`. Tests verify
one generic helper/handler body and two computation instantiations; all callback
operation certificates remain symbolic. Invalid unused definitions, incorrect
ATCs/evidence, and the former uncurried caller-dependent contract are rejected.
See [VALIDATION.md](VALIDATION.md) for current commands and results.

## Fixed-region contracts without `local`

The removed `local` keyword is not restored. In a handler `h`'s body, either
spelling can express an exact-region function:

```effekt
val f: ((Int) =>[h] Int) = fun (x) { x };
fun (x: Int) at [h]: Int { x }
```

These forms use `FixedFunction`/`FixedLambda`. They check at exactly the contract's
region, with no implicit call-evidence parameter, and cannot be promoted to an
ordinary caller-polymorphic arrow. Plain `fun` can be checked against an expected
fixed-region arrow; an explicit `at` region must agree with that contract.
Annotated fixed recursion remains at its enclosing lexical region. Stored
resumptions, ARM ports, and the second curried function in Fig. 1 use this
explicit declarative bridge. It is not printed ordinary `A_Abs` inference.
