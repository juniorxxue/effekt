# Native Effekt and the ATM frontend: syntax differences

Updated 2026-11-12 against the **native Effekt v0.3.0 frontend in this checkout**
(base commit `cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`). The native compiler
remains present and unchanged. This compares its syntax with the separate ATM
frontend, rather than with the latest upstream language.

## Restored native forms

| Form | Current ATM support |
| --- | --- |
| `interface File { def read(): Int def close(): Unit }` | Named declarations, inherited clause value signatures, and checked repeated annotations. |
| `interface Reader[A] { def read(): A }`; `with h: Reader[Int]` | Interface type parameters and explicit instantiation. Declarations are resolved in their own scope before instantiation. |
| `def op(x: Int, y: Int): Int` | Multiple function/lambda/operation parameters and arguments elaborate to nested pairs. Missing clause types come from the interface; mixed explicit/omitted annotations are checked. |
| `resume(readInt(c))`, `f(g())`, `f()(x)`, calls in arithmetic/constructors | Expressions elaborate to fresh `let` bindings in left-to-right order, with each computation evaluated once. |
| `false && call()`, `true || call()` | Computed right operands remain under conditional branches. Partial primitive operands such as division also remain conditional. |
| `def main() = ...` | The existing synthesis checker infers its result; the closed program must still have a pure final computation and no escaping capability or residual obligation. |
| Several top-level functions/values; forward references | Acyclic definitions are ordered by their lexical dependencies and elaborated to existing local definitions. Mutual recursive groups remain unsupported. |
| `type Handle = Int` | Top-level aliases are checked and available to interface/function declarations. Local aliases also accept newline separators. |
| `def identity[A](x: A): A = x`; `identity[Int](42)` | Native function type-parameter and explicit type-argument syntax elaborates to the existing type abstractions/applications. |
| `def sum(n: Int): Int = ... sum(n - 1) ...` | Lexically free self references select the existing recursive-function checker; no `rec` marker is required. Recursive result annotations remain necessary. |
| `type Choice { Yes(n: Int); No() }` | Native datatype spelling; newline constructor separators. The existing `data` spelling remains an alias. |
| `x match { case Yes(n) => n; case No() => 0 }` | Native postfix match spelling and unbraced, multiline case bodies. Wildcard constructor fields such as `case Pair(_, x)` are supported. |
| Single-expression function/clause bodies, `if (c) a else b`, `try expression with ...` | Braces are optional in these positions. Computed conditions and return expressions are accepted. |
| Omitted identity handler returns; empty interfaces/handlers | Identity returns are inserted hygienically. Empty handlers retain their real delimiter and therefore still affect evidence distance. |
| Zero arguments and newline-separated ordinary statements | Supported; semicolons remain accepted. |
| Names ending in `?`, `!`, `$` | Accepted. Primitive spellings `add`, `not`, `fst`, `snd` also work as operation member names. |

The [reusable Fig. 1 example](../../examples/atm/file-reusable.effekt) uses
caller-indexed result families, a curried helper returning an explicit
fixed-region callback consumer, computation parameters, capability/evidence
abstractions, handler/operation contracts, and ATCs. It is checked once as a polymorphic
value and reused at two computation types. The old contextual specialization
pass has been removed. The [inlined companion](../../examples/atm/file-nested.effekt)
remains a comparison fixture. The explicit source requires the ATM frontend.

Name an ordinary function's implicit caller after its value parameters:
`fun (x: T)[R: Reg] { ... }`, `def f(x: T)[R: Reg]: C = ...`, or
`(T)[R: Reg] => C` in a type. This is ATM surface notation for the existing
`A_Abs` binder, not native Effekt syntax. Its name scopes over the result/body
and subsequent call-ATC kinds, not the ordinary domain. Calls still supply the
region implicitly. Handler labels and `top` refer to existing regions.
`here` is removed as a keyword and predefined region name. The
[rule-boundary table](CALLER-INDEXED.md) separates this naming convention from
explicit ATC abstraction and the declarative fixed-region bridge. The
callback-domain extension remains removed.

## Remaining gaps and their reasons

| Native feature | Remaining work |
| --- | --- |
| Native block parameters, block literals/invocation, and capture inference | Unsupported. The former contextual specialization checked a helper separately at each use; it was removed. Caller-indexed functions with explicit capability, computation, evidence and call-ATC parameters support reusable helpers checked once. General block scheme inference remains work. |
| Chained `with` clauses | Native Effekt binds the capabilities under **one shared handler region** (`Typer.scala`, `core/Transformer.scala`). Rewriting these as several nested ATM handlers would add delimiters and change evidence distances. Correct capability-group elaboration is still needed. |
| `effect Ask(): Int`, unnamed handlers and single-operation handler shorthand | Declaration and handler desugaring remain missing. These are not inherent ATM restrictions. |
| `do read()` and inferred capability arguments | Native operation/name resolution and implicit capability passing are not integrated; use explicit receivers. This also affects reusable helpers with native effect annotations. |
| Polymorphic operations, omitted generic instantiations | Parameterized **interfaces** now work, but operations cannot declare their own type parameters. Function/interface type arguments remain explicit; native type-argument inference is not implemented. |
| Mutual recursion and polymorphic recursive functions | Need recursive groups/schemes beyond the existing unary `LetRec`. Ordinary monomorphic self recursion works. |
| Generic datatypes, records, lists, nested/default/guarded patterns | The datatype checker currently supports monomorphic data and exhaustive flat constructor cases. These forms require further data/pattern elaboration and coverage checking. |
| Native interface names as general block/capability types; `new File { ... }` | Named interfaces currently supply handler signatures. General native object/block typing is not integrated. |
| `module`, `import`, namespaces, qualified names, `extern`, standard libraries | Module resolution, library contracts and external-call lowering remain missing. Fig. 1 still uses mocks. |
| Strings, characters, floating point and interpolation | Literal/runtime/library support remains missing. Int execution is exact; it is not claimed to reproduce native fixed-width arithmetic. |
| `var`, assignment, `while`, native `region`, `box`/`unbox` | State/control/capture elaboration and typing/runtime support remain missing. |
| Declaration placement and minor grammar forms | Data/interface/alias declarations must precede functions/values (and measures precede them too). Top-level type aliases resolve in declaration order and are not yet expanded in datatype fields. Local data/interfaces, block aliases (`def f = ...`), default `else`, and a fully native keyword/name policy remain incomplete. |

There is no ATM reason to ban the remaining purely syntactic forms. The table
distinguishes missing frontend work from features requiring additional typing
or runtime integration; it does not invent historical reasons for omissions.

Multiple parameter domains are represented by ordinary pairs, not dependent
telescopes. Result/answer refinements can refer to all parameters; a parameter
type depending on an earlier parameter still needs additional elaboration.
As in the preexisting Unit shorthand, the unary core representation does not
retain a distinction between multiple arguments and an explicitly packed pair.

## Intentional ATM meanings and extensions

- Capabilities are passed with `fun (c: { def op(): T / (C1 => C2); }) { value }`
  and `f(h)`. Their dependent arrow is `(c: { ... }) => T`; the structural
  contract binds the abstract region using the existing capability rules.
  Answer effects use `fun [E: Eff]` and `f[pure]`. The prototype `capfun` and
  `effectfun` keywords and brace calls remain compatibility aliases only.
  Capability-abstraction bodies remain values; effectful work goes in returned
  ordinary or fixed-region functions. This does not implement native block
  inference or general named-interface value types.

- `T / (C1 => C2)` describes answer-type modification. Native `/ Effects`
  describes effect sets; these cannot simply be relabeled as one another.
- ATM `resume()` means `resume(())`. Native Effekt expects the Unit argument;
  Fig. 1 uses the common spelling `resume(())`.
- Explicit `return x => ...` can transform a handler result, and `return effect`
  exposes the separately documented declarative bridge.
- Refinements, measures, explicit regions/distances/evidence/ATCs, and exact-region
  `(T) =>[r] C` contracts remain ATM additions; the `local` keyword is removed. `fun [R: Reg, D: Dist, ...]` introduces
  kinded parameters; bare applications such as `f[h, 1, ...]` select argument
  kinds from the function's type. Grouped and multiline chained arguments work.
  The old `typefun` and tagged argument spellings remain compatible.
- Default `C => C` operation answers and partial handler-result synthesis remain
  extra surface inference checked by the existing algorithm. Generic interface
  instantiation produces ordinary monomorphic operation signatures; it does not
  add nominal capability types or polymorphic-operation rules.
- Legacy `with h: C { ... }` computation annotations remain compatible. A declared
  interface name selects the native interface form; use a trailing `: C` when
  supplying both an interface and a computation annotation.

## Source and validation

Native grammar: [`RecursiveDescent.scala`](../../effekt/shared/src/main/scala/effekt/RecursiveDescent.scala)
and [`Lexer.scala`](../../effekt/shared/src/main/scala/effekt/Lexer.scala).
Native handler grouping: [`Typer.scala`](../../effekt/shared/src/main/scala/effekt/Typer.scala)
and [`core/Transformer.scala`](../../effekt/shared/src/main/scala/effekt/core/Transformer.scala).
ATM changes: [`Parser.scala`](../../effekt/shared/src/main/scala/effekt/atm/Parser.scala),
[`Surface.scala`](../../effekt/shared/src/main/scala/effekt/atm/Surface.scala),
and [`Checker.scala`](../../effekt/shared/src/main/scala/effekt/atm/Checker.scala).

`AtmNativeSyntaxTests` covers evaluation order, short circuiting, native declarations,
generics, recursion, wildcard fields, and rejected type/escape errors. Accepted
cases run through the reference interpreter and optimized/unoptimized Chez.
`AtmExplicitFileTests` checks one generic helper/handler body in Core, two
computation instantiations, symbolic callback evidence/ATCs,
and rejection of invalid unused helper definitions and invalid annotations.
Accepted cases run through the independent source interpreter and optimized/
unoptimized direct Chez. The explicit reusable example returns **42**; its
descriptor-sensitive variant returns **735**. It no longer has the same source
as the native Effekt/JS comparison from the historical block experiment.

```
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'
```

Current and historical results, including removal of the specialization tests,
are recorded in [VALIDATION.md](VALIDATION.md). The legacy source emitter does
not support explicit evidence-kind abstraction and remains tested with the
inlined companion.

These are compiler and execution checks, not a mechanized correspondence or
soundness theorem. [FIDELITY.md](FIDELITY.md) records the algorithm boundary.

Caller-indexed result families implement Definition 17. Explicit call-ATC
abstraction and fixed-region checking are separate extensions to the printed
algorithm, documented in [CALLER-INDEXED.md](CALLER-INDEXED.md). These are ATM
annotation forms, not native Effekt syntax or implicit ATC inference.
