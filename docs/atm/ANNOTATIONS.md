# Explicit ATM annotations

The ATM frontend accepts explicit regions, distances, evidence, computation
types, and answer-type contexts (ATCs). They are checked before elaboration;
unannotated ATC inference remains partial. Explicit call-ATC abstraction is
the extension described below. The entry point is
`effekt.atm.Main`, separately from native Effekt source checking.

## Binders and applications

`fun [R: Reg, D: Dist, E: Ev(R, D, h), K: ATC(D)] { value }`
introduces a left-to-right telescope. Each kind can refer to earlier parameters
and enclosing bindings. The corresponding type is written with nested
`forall R: Reg. (body)` quantifiers. Plain value-type parameters use `fun [A]`;
value parameters can follow directly, as in `fun [A](x: A): A { x }`.
The older `typefun` spelling remains an alias. `effectfun`, `predfun`, and
`capfun` forms remain available.

Arguments need no kind prefix: the next quantified parameter determines how
each argument is checked. Both grouped and chained brackets are accepted:

```effekt
callback[inner, 1, step(inner), Int / (Int => [])]()
callback[inner][1][step(inner)][Int / (Int => [])]()
```

These instantiate a `Reg`, a `Dist`, the corresponding `Ev`, and `ATC(1)` in
order. Names are resolved in their lexical scope; evidence endpoints and
context depths are still checked. `fun` and bare applications elaborate to
the same checked abstractions/applications as the older tagged forms.

| Kind | Argument syntax | Meaning |
| --- | --- | --- |
| `Reg` | `[top]`, `[h]`, `[R]` | A lexical region; `top` denotes the program's ambient region |
| `Dist` | `[1]`, `[D + 1]` | A nonnegative distance; sums are equal modulo associativity, commutativity, and zero |
| `Ev(R, D, S)` | `[E]` | Evidence from exactly `R` to `S`, at distance `D` |
| `ATC(D)` | `[K]`, `[Int / (Int => [])]` | An answer context with exactly `D` layers |
| `Cty` | `[Int / (Int => Bool)]` | A complete computation type, including its answer effect |

A bound computation variable can be written `C` in a computation-type position
(the older `cty C` spelling is also accepted). Plugging a computation into
an ATC is written `K[Int]` or `K[C]`. `(T) =>[R] C` selects an explicit call region
for a function type; ordinary `fun` can check against that contract. A callback
can declare its result as
`Int / (K[Int] => K[Bool])` and be instantiated at different handler depths.

## Evidence and contexts

An operation accepts evidence followed by an answer type context. Either
annotation may be omitted:

```effekt
h.ask[E; K]() // both annotations
h.ask[E]()    // evidence only, when E has kind Ev(...)
h.ask[K]()    // answer type context only, when K has kind ATC(...)
h.ask()      // reconstruct both
```

A single annotation name is classified by its kind in the current scope,
including kind parameters and lexical aliases. Its spelling does not determine
its meaning. Concrete expressions can also be used alone, for example
`h.ask[refl]()` or `h.ask[[]]()`. Omit the entire bracket when neither
annotation is supplied.
The older tagged form `h.ask[evidence E; atc K]()` remains accepted for archived
sources. General applications also accept the older prefixes (`region`,
`distance`, `evidence`, `atc`, `cty`, and `type`). Bare effect and predicate
arguments are selected by their quantified parameters in the same way.

The evidence's source must be the current region and its target must be the
receiver's region. The ATC's depth must equal the evidence distance. The checker
plugs the operation's initial and final answers into that context and checks the
result against any inherited answer. An explicit ATC also permits synthesis
without an inherited answer. If an annotation is omitted, its existing
reconstruction procedure is used; symbolic-depth ATC inference remains partial.

Evidence consists of `refl` (or `refl(R)`), `step(h)`, a bound evidence variable,
or composition `E + F`. `step(h)` uses the in-scope edge from handler `h` to its
parent. Composition checks the intermediate region, not just the total depth.
`path(h)` requests ordinary evidence reconstruction from the current region to
`h`; it retains any residual obligations. A distance numeral alone is never
evidence for crossing a handler.

Contexts consist of `[]`, a bound context, a layer `T / (C => K)`, or composition
`K[J]`. A dependent layer `(x: T) / (C => K)` binds `x` only in `C`. The alternative
`layer(T, C, K)` accepts complex value types without ambiguous parentheses.
Context application substitutes the entire computation, including nested answer
effects and refinement predicates.

Handler labels already name regions: in `try { ... } with h: ...`, use `h`
in region arguments, `Ev(h,0,h)`, `refl(h)`, or `at [h]`. Use `top` for the
closed program's ambient region. To refer to an ordinary function's caller,
explicitly bind its name: `fun (x: T)[R: Reg]: C { ... }` or
`def f(x: T)[R: Reg]: C = ...`. Ordinary arrow types use `(T)[R: Reg] => C`.
The binder scopes over the result/body and subsequent call-ATC kinds, not `T`.
It names the implicit `A_Abs` parameter; ordinary calls still supply it
implicitly. Pre-parameter `fun [R: Reg] ...` remains explicit type abstraction.
`here` has no predefined meaning and is no longer a keyword. See
[CALLER-INDEXED.md](CALLER-INDEXED.md) for scoping and Fig. 1.

Lexical aliases use `region R = h;`, `distance D = 1;`,
`evidence E = step(inner);`, `atc K = Int / (Int => []);`, and `cty C = Int;`.
Aliases are resolved at their definition and preserve generative region identities.

## Execution and validation

- [`polymorphic-outer-call.effekt`](../../examples/atm/annotations/polymorphic-outer-call.effekt)
  supplies the symbolic callback's ATC at its call site; caller evidence is reconstructed.
- [`multiple-capabilities.effekt`](../../examples/atm/annotations/multiple-capabilities.effekt)
  keeps both capability parameters abstract and supplies their relative evidence.
- `AtmPolymorphicSyntaxTests` checks `fun` binders and bare arguments against
  the tagged program's Core, grouped/chained application, scoped aliases,
  legacy prefix words used as names, computation/effect/predicate arguments,
  dependent ATC scope, and rejection of invalid kinds, evidence, and depths.
- `AtmExplicitAnnotationTests` covers depths 0/1/2, composed symbolic contexts,
  computation polymorphism, dependent-layer scope, alpha-renaming, shadowing,
  invalid annotations, and independent Core certificate validation.

Run either example with
`sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/annotations/polymorphic-outer-call.effekt'`.
The tests compare the independent source interpreter with direct Chez-Lift,
with optimization both enabled and disabled. Evidence parameters become real
Lifted evidence arguments. Region, distance, and ATC parameters are erased;
computation parameters retain their value projection as a Lifted type parameter.

The legacy Effekt/JavaScript emitter rejects the new kind-polymorphic forms
explicitly. Direct Lifted lowering currently requires a known value projection
when an abstract ATC application occurs as the **result of a computation**, as
opposed to an answer endpoint. Such programs may check but report `Unsupported`
at lowering. This does not affect the annotated callbacks above. Polymorphic
operation-signature binders and a literal Definition 17 translation of implicit
function types remain separate work. Core rechecks scoped kinds, evidence, and
ATC certificates; it is not a second complete refinement checker or a preservation
proof.

## ATCs under the implicit caller telescope

`fun ()[R: Reg, K: ATC(dist(path(h)))]: C { ... }` names the caller `R`
and binds `K` inside the function's
implicit caller region, distance and evidence binders. `K` scopes over the
result and body, not the ordinary argument domain. Already-bound outer ATC
parameters can still appear in domains. Supply it at application
as `callback[[]]()` or `callback[layer(Int, Int, [])]()`, with the depth checked
against the actual call evidence. These functions are checked once; they are
not specialized. Explicit call-ATC abstraction extends the printed algorithm.
The [rule-boundary document](CALLER-INDEXED.md) distinguishes it from ordinary
caller-indexed results and the declarative fixed-region bridge used by curried
Fig. 1. Caller-dependent ordinary domains have been removed.
