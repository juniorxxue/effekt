# Native Effekt and the ATM frontend: syntax differences

Updated 2026-11-10 against the **native Effekt v0.3.0 frontend in this checkout**
(base commit `cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`). The native compiler
remains present and unchanged. This compares its syntax with the separate ATM
frontend, rather than with the latest upstream language.

## Restored native forms

| Form | Current ATM support |
| --- | --- |
| `def file(c: Int) {body: {File} => Int}`; `file(c) { {f} => ... }`; `body {f}` | Contextual block inference and checked function specialization. Runtime function/callback calls are retained, with inferred capability/evidence parameters. Same-region block forwarding also works. |
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

The [reusable Fig. 1 example](../../examples/atm/file-reusable.effekt) now uses
the original native block syntax with top-level pure mocks, inferred results,
and `resume(readInt(c))`. It passes both ATM and native Effekt. The
[inlined companion](../../examples/atm/file-nested.effekt) remains a comparison
fixture. Specialization supplies explicit checked block contracts; no helper
or callback body is substituted into the other.

## Remaining gaps and their reasons

| Native feature | Remaining work |
| --- | --- |
| General native block/capture polymorphism | The implemented subset has one block parameter with one capability parameter, a statically named helper, a projected result type, and a compatible identity-answer contract at its invocations. Recursive block schemes, several block/capability parameters, escaping first-class blocks, and forwarding across a new delimiter remain unsupported. Helpers are checked per contextual specialization; this is not once-for-all modular inference. |
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

- `T / (C1 => C2)` describes answer-type modification. Native `/ Effects`
  describes effect sets; these cannot simply be relabeled as one another.
- ATM `resume()` means `resume(())`. Native Effekt expects the Unit argument;
  Fig. 1 uses the common spelling `resume(())`.
- Explicit `return x => ...` can transform a handler result, and `return effect`
  exposes the separately documented declarative bridge.
- Refinements, measures, explicit regions/distances/evidence/ATCs, and exact-region
  `local` arrows remain ATM additions. `fun [R: Reg, D: Dist, ...]` introduces
  kinded parameters; bare applications such as `f[here, 1, ...]` select argument
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
The Fig. 1 tests still compare inferred/explicit evidence and ATC certificates,
including distances 1, 0, 1, 0. The same example body is also tested by native
Effekt/JS, with a printing entry point, independently of the ATM source emitter.

```
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests' 'effektJS/compile'
```

The final reusable-block run passed **410 tests**, and Scala.js compilation
passed; details are in [VALIDATION.md](VALIDATION.md). Fig. 1
returns **42** through direct Chez and native Effekt/JS. The legacy source emitter does not support the generated explicit evidence-kind abstraction; it remains tested with the inlined companion.
An instrumented variant appends tags after each resumption returns; both ATM
and native Effekt return **424231**. This checks nested resumption behavior
in addition to the original arithmetic result.

The subsequent plain-polymorphism change passed a 424-test full run and,
after the final alias-name parser refinement, 101 focused tests plus Scala.js
compilation. Its annotated outer-call example returns **true**; the accepted
polymorphic file example returns **42**. See [VALIDATION.md](VALIDATION.md).

These are compiler and execution checks, not a mechanized correspondence or
soundness theorem. [FIDELITY.md](FIDELITY.md) records the algorithm boundary.
