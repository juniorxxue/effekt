# ATM implementation experiment

> **Archived prototype.** The feature checklist and commands below describe the
> former ordered-handler-stack checker. Scoped tails and `repeatable`/`after`
> bounds are not current features. See [the current guide](../README.md),
> [benchmark overview](../BENCHMARKS.md) and [archive index](README.md).

This directory records the implementation requested after comparing the supplied
*Answer-Type Modification for Lexical Effect Handlers* paper and supplement with
Effekt 0.82.0 (`03570ede5`). The goal is a working prototype followed by the harder
capability-based typing and integration work. Passing the first examples is not
completion of that larger goal.

## Acceptance criteria and work ledger

- [x] Genuine `Int` to `Bool` answer modification, statically checked and executed.
- [x] Nested handlers with an invocation targeting an outer delimiter.
- [x] A valid answer-state file protocol accepted; invalid ordering rejected.
- [x] Higher-order callback contracts, including an effectful callback.
- [x] Stored and repeated resumptions, with explicit scope restrictions.
- [x] Closed capability-polymorphic helpers with fresh binder identities.
- [x] Scoped capability abstraction, ambient captures and nested ordered parameters.
- [x] Explicit answer-effect abstraction and instantiation for reusable callbacks.
- [x] Explicit repeatability bounds for abstract answer-effect composition.
- [x] Directed composition constraints for different abstract answer effects.
- [x] Effekt-like source syntax, migrated examples and parser diagnostics.
- [x] Compilation and execution through an existing Effekt backend, both with
  and without its optimizations.
- [ ] Capability-based formulation that replaces the prototype's exact ambient
  delimiter restrictions, including higher-order invocation beneath handlers.
- [x] Dependent refinements, explicit type/predicate polymorphism and Z3 checking.
- [x] Annotated recursion, logical pairs and lexical type aliases.
- [ ] General kinded answer-context polymorphism and complete ARM migration.
- [ ] Bidirectional resumption and integration with ordinary Effekt source.
- [x] Tests and documentation stating the precise implemented/proved boundary.

The boxes are updated only from completed checks. There is no soundness theorem
for this implementation yet. The earlier System C ATM development and the
attached paper are references, not proofs of this Scala checker or its lowering.

## Design decisions

### D1: Check ATM before generating ordinary Effekt

`effekt.atm.Checker` checks the ATM source program. The source's answer effects
are distinct from Effekt's ordinary capability requirements. A generated Effekt
program passing the original typer is not the evidence for ATM acceptance.

The textual frontend uses Effekt-like syntax in `.atm` files: `def`, `val`,
brace-delimited blocks, ordinary calls and lexical `try ... with` handlers.
ATM contracts and polymorphism have explicit experimental annotations (D20).
The frontend produces the same source AST checked by the prototype. It remains
a separate entry point from the public `.effekt` parser and typer.

### D2: Preserve the current Core invariant through typed answer packaging

Ordinary Effekt equates the prompt, resumption and clause answer types. The
prototype translates each handler to a generic sum with one constructor for
normal completion and constructors for its possible answer representations.
All answers at that target delimiter have this common sum type. A resumption
wrapper decodes the initial answer representation; a clause encodes its final
answer representation. Source ATM types are never identified with the sum.

Normal completion has its own tag so the source return clause runs outside the
target handler, once per normal completion (including resumed completions).
Operation-clause completion does not run the return clause. Decoders preserve
explicit panic branches for impossible tags. These branches diagnose a broken
checker/lowering invariant; they do not replace static protocol checking.
The generated code uses ordinary Effekt typing, Core, optimizers and backend.
A proof that accepted source programs never enter a panic branch is outstanding.

### D3: Region-free surface; ordered internal context in stage one

The paper's algorithmic source already omits evidence (Section 6); it reconstructs
evidence from regions and retains obligations when reconstruction is unresolved.
Removing evidence syntax therefore does not by itself replace its region-based
typing discipline. The intended deeper change separates two obligations:
capabilities/captures establish that a handler is available, while ATM typing
identifies the ordered answer layer an operation modifies. A capture set alone
does not encode that order or the intervening answer types. A source discipline
without region variables or subregion evidence still needs a justified account
of those answer contexts, especially for higher-order calls. Moving compilation
to Effekt can proceed before that formal redesign is complete.

Source programs name capabilities but contain no region or evidence syntax.
The checker currently keeps an internal list of active handler identities.
Invocation reconstructs the answer context by locating the target capability and
peeling that many layers, following `T_Do`/`atc`. This is **not yet** a fully
region-free replacement of the paper's typing discipline.

Functions with contextual dependencies carry an ambient delimiter signature
internally. Resumptions carry their handler's outer signature and conservatively
capture available outer capabilities. The first prototype required exact
signature equality. Its successor permits caller-owned delimiters above that
signature, as described in D6 below. Closed, noncontextual pure functions may be
called in any context. General answer-context polymorphism is still outstanding.

### D4: Keep effectful subsumption conservative

The initial checker implements pure identity lifting and exact matching of
answer transitions. It does not pad an already effectful computation with new
answer layers. It is less complete than the paper's subtyping/refinement system.
Prior credit-reuse and abort counterexamples in the separate System C ATM work
motivate checking this boundary before expanding subsumption; that proof does
not transfer automatically to this implementation.

### D5: First-order protocol witnesses are not real file I/O

`Open` and `Closed` are distinct token types used as answer states. The protocol
example checks read/close sequencing and the operation clauses. It opens no OS
files and establishes no correspondence with an external file implementation.
Refined parameter-passing file handlers remain part of the later work.

### D6: Lift contextual calls across actual caller-owned delimiters

After the initial 18 tests passed, the first harder change generalizes exact
ambient-context checking. If a function was checked in delimiter context `B`,
its caller may have context `P ++ B`. It cannot remove or reorder `B`. Given
latent effect `CI => CF` and inherited final answer `K[CF]`, the call synthesizes
`K[CI] => K[CF]`. Reconstruction peels exactly `length(P)` answer layers, justified
by the actual active delimiters. It rejects insufficient layers or an unequal
tail. Pure calls use ordinary pure identity lifting.

This is a specialized contextual lifting rule, not effect subsumption and not
permission to pad already effectful terms arbitrarily. It allows an effectful
callback defined outside a handler to run below that handler. It also allows a
stored pure resumption to run below new delimiters. The code preserves the
caller-owned layers structurally rather than identifying capture sets with
ordered answer effects.

The necessary contextual lifting lemma (including nested handlers, functions,
and substitution) has not been proved. This remains a proposed extension tested
on programs, not a corollary of the supplied algorithm's theorem.

### D7: Capability values retain identity

`cap(h)` is both a capability type and a value form. It denotes the specific
handler `h`, including that handler's operation contracts. Aliases and function
arguments retain this identity; an invocation through an alias reconstructs the
answer context for the original handler. These values lower to boxed Effekt
capabilities. Types track their captures, so aliasing cannot permit escape.
`cap(h)` remains a singleton capability type. D10 adds an explicit quantifier
over these identities; it does not identify all capabilities of an interface.

### D8: Check lowering against independent source execution

The test-only `AtmReference` interpreter implements deep lexical handling from
the untyped source AST. It uses fresh runtime handler identities and operation
requests/resumptions; it does not read ATM annotations or generated answer tags.
Accepted fixtures compare its result and manually stated expectations with the
generated JavaScript, with and without optimization. This is differential test
evidence for the lowering, not a soundness proof or a replacement for static
checking.

### D9: Preserve exact source integers

An executed counterexample exposed a representation mismatch:
`9007199254740993 + 2` compiled through native JavaScript `Int` produced
`9007199254740994`, whereas exact arithmetic produces `9007199254740995`.
Effekt's JS arithmetic uses `Number`; its LLVM arithmetic uses machine integers.
The prototype now defines source `Int` as a mathematical integer. Its AST and
reference evaluator use Scala `BigInt`, and the generated Effekt uses a typed
`AtmInteger` extern represented by JavaScript `bigint`. Literal construction,
addition, zero testing and printing are explicit pure externs. No native
Effekt integer arithmetic is changed.

This decision makes future integer refinements meaningful for this backend.
Interop with native Effekt `Int` and other backends needs explicit conversion
contracts or another exact representation; silently erasing refinements across
these representations would be unsound. The regression is `large-integer.atm`.

### D10: Abstract over a capability and its outer delimiter context

`capfun` checks a reusable value once against an abstract capability's full
operation contracts. It is not a source macro checked only after specialization.
`f{cap(h)}` accepts a capability value (including an alias), compares argument,
result and both answer contracts, and substitutes the actual identity into the
result type, its captures and nested computation types. `forall cap` permits
these values in annotated higher-order parameter and result types. Bound names
are compared up to alpha-renaming.

For example, the helper in `polymorphic-callback.atm` is defined outside the
handler and accepts a callback capturing its abstract capability. Its callback
contract retains the `Int` to `Bool` answer transition. Instantiating the helper
does not turn this transition into an ordinary Effekt effect requirement.

The abstraction is checked at delimiter context `[c]` with an abstract outer
tail. At instantiation in context `P ++ [h] ++ B`, it substitutes `c := h` and
the tail `:= B` throughout function context annotations, including inside answer
types. Calling the resulting function additionally preserves `P` using D6.
This is a restricted form of context polymorphism: it permits different outer
contexts and additional inner delimiters but does not quantify over arbitrary
answer-type contexts. Answer-effect quantification is added separately in D15.

To justify the fresh tail, the abstraction's operation signatures are closed,
and its body may capture only context-independent values. It cannot reference
concrete enclosing capabilities or context-pinned functions/resumptions. A pure
function with a pinned context is excluded even when its capture set is empty.
Nested closed capability abstractions bind their own identity and tail;
substitution does not rewrite their bound contexts. D19 adds a separate scoped
form that can retain ambient capabilities and nest several ordered abstract
capabilities. Arbitrary placements between those capabilities still require
more general answer-context polymorphism.

The implementation needs an abstraction/instantiation typing lemma, together
with D6's contextual lifting lemma. Neither has been proved. Executing accepted
programs and rejecting contract mismatches is implementation evidence only.

### D11: Fresh identities and shared target interfaces

The checker separates surface names from generative internal handler identities.
Every handler and capability quantifier gets a fresh identity in its checking
session. Shadowing a name does not change existing aliases, captured functions,
or quantified contracts. Diagnostics display the source name; checking and
substitution use the fresh identity.

The lowering uses native Effekt block parameters for capability abstraction.
Handlers with the same operation names share a generated generic nominal
interface; its type arguments carry each operation's value argument and result
representations. Source answer contracts are still checked before this erasure.
Each handler retains its own answer sum and runtime capability identity. No
casts, specialization of the helper body, or changes to Core typing are used.

### D12: Repair missing native capture metadata, without weakening checking

The new lowering exposed a compiler panic in ordinary Effekt: an inferred boxed
function's explicit block parameter was bound with its type but without its
singleton capture annotation. A later `AnnotateCaptures` lookup failed when the
parameter was used directly. `inferFunctionArgument` now binds both, matching
the existing checked-function-argument path.

The independent regression `examples/pos/boxed_capability_parameter.effekt`
failed before the fix. It covers capability and function block parameters, and
is registered for both optimized and unoptimized JavaScript execution. This is
a native compiler metadata repair, not evidence of native ATM support.

### D13: Preserve quantified answer equality through lowering

Fresh identities revealed that raw Scala equality is too strong for answer
types containing a capability quantifier: alpha-equivalent contracts received
different identities. A regression that performs an operation and resumes with
a quantified answer was rejected for this reason. `TypeEquality` now compares
bound names by binder position while retaining exact free identities, capture
sets and delimiter order. Invocation, answer composition, contract comparison
and answer-tag selection use the same relation.

After successful subsumption, a checked term records the expected contract.
In particular, a normal return of a function with fewer captures must use the
declared answer contract for decoding. Otherwise the checker could accept
capture widening while the decoder selected an impossible-tag branch. The
normal-return capture-widening regression exercises this path through execution.

### D14: Elaborate source function subtyping explicitly

Effekt currently compares boxed function types invariantly, including their
capture sets. The source checker permits function subtyping with contravariant
arguments, covariant results and capture widening. A type annotation alone
cannot bridge that difference. The capture-widening regression first exposed
this as a generated-program rejection by the native typer.

The lowering records source subsumption and emits typed eta adapters at checked
result boundaries, function arguments and operation arguments. An adapter
evaluates its original expression once, converts an input contravariantly, calls
the original function, and converts the result covariantly. Its box explicitly
declares the destination captures. Quantified adapters pass the actual capability
to the original abstraction before adapting the instantiated result. These are
ordinary typed Effekt functions; no cast or native subtyping relaxation is used.

Generated lambdas and decoders also carry explicit target types so inference
does not select a narrower capture contract from the first branch. The tests
cover normal returns, higher-order argument/result variance and quantified
result adaptation. Arguments are bound to locals in evaluation order so adapters
are legal Effekt expressions and do not duplicate their inputs. Coercion
preservation is an additional proof obligation.

### D15: Quantify answer effects independently of capabilities

`forall effect E. (T)` binds a variable of answer-effect kind. `effectfun`
checks a value once with that variable rigid; `f[effect E]` substitutes
`pure`, a concrete transition, or another in-scope answer-effect variable into
the checked type. The body is not rechecked as a macro at each instantiation.
Value return types remain explicit: `Int / E` returns an integer with answer effect `E`.

The example `effect-polymorphic.atm` defines a capability-polymorphic helper
whose body is also answer-effect polymorphic. The same instantiated capability
helper accepts a pure callback and an `Int`-to-`Bool` callback. Capability
identity/captures and latent answer effects remain separate parts of its type.
The checker supports these quantifiers in higher-order parameter types as well
as inferred values, with alpha-equivalence and capture-avoiding substitution
based on fresh internal binder identities.

An opaque effect may propagate through pure computations and alternatives
whose answer effects agree. It cannot be equated with `pure`, assumed to be an
identity transition, composed twice without knowing compatible endpoints, or
lifted across another delimiter without knowing its answer layers. After
instantiation, the existing composition/lifting rules apply. The tests contrast
an invalid universally quantified double invocation with a valid repeated
invocation instantiated at a known identity transition.

Opaque effects can also occur inside operation answer computations. A test
handles a local operation whose answer contracts contain `E`, resumes it, and
executes the abstract-effect callback in the return clause outside that local
delimiter. Thus abstraction is not restricted to a bare forwarding lambda.

### D16: Preserve scope when abstracting effects; erase only after checking

An effect abstraction retains the lexical capability environment and captures
of its body. Effect instantiation preserves capability identities and delimiter
context annotations; it does not make a captured function context-independent.
Capability and effect binders have separate kind environments and separate
alpha-equivalence stacks, so the same surface name can safely name both.

The closed capability abstraction of D10 still starts with a fresh outer
delimiter tail. It cannot import an ambient effect variable, including through
a captured value's type. Later substitution for that variable could otherwise
inject concrete capability/context dependencies into a supposedly closed
abstraction. Put a closed capability abstraction outside the effect abstraction.
D19's scoped capability abstraction can instead retain the effect variable and
its fixed ambient context, supporting the reverse order under that restriction.
General context-polymorphic interchange still needs scoped kinds or constraints.

Answer-effect abstraction and instantiation erase in the ordinary Effekt
lowering: they do not change value representations or introduce runtime effect
dictionaries. Lambda result value types and finite answer representations are
known before erasure. The independent source interpreter keeps abstraction
wrappers to check instantiation behavior, while generated programs execute
through the ordinary backend. This testing does not prove the required effect
substitution, abstraction, erasure or contextual-lifting lemmas.

### D17: Bound abstract effects by repeatability

The optional `repeatable` bound restricts an effect argument to `pure` or an
identity transition `C => C`. Equality checks the entire computation type `C`,
including nested answer effects, captures and delimiter contexts. Equal outer
value types alone do not establish repeatability. The bound is checked when
instantiating a helper, even if the resulting function is never called.

The typing environment records bounds under fresh effect identities. It permits
two sequential occurrences of the same bounded variable `E` to compose to `E`.
Two distinct repeatable variables still need matching answer-state information;
their bounds alone do not justify composition. Shadowing introduces a fresh
bound, and forwarding an abstract argument requires its declared bound to imply
the callee's requirement.

The local justification is by two cases: `pure ; pure = pure`, and
`(C => C) ; (C => C) = (C => C)` by the existing sequencing rule. Likewise,
`pure` can lift to every member of this domain: directly for `pure`, or by
identity lifting for `C => C`. This permits a pure alternative to join an
abstract repeatable effect. The checker uses existing subtyping to find a branch
type and records the required branch coercions for lowering. This argument
justifies these algebraic rules, not whole-language soundness or lowering
preservation.

Quantified subtyping respects the domain of legal instantiations. A helper that
accepts any effect can satisfy an interface restricted to repeatable effects;
the reverse direction is rejected. Body subtyping is checked under the caller's
bound, which also justifies pure lifting inside quantified function types.

Repeatability is about answer transitions, not runtime idempotence. Both calls
to a callback still execute, and their values or ordinary effects may differ.
The example `repeatable-effect.atm` checks one bounded helper once, then uses it
with pure and handled callbacks. Each handled invocation adds one after resuming;
the expected result counts both invocations. D18 extends this with directed
binary composition constraints. General constraint solving, endpoint variables,
and answer-context abstraction remain outstanding.

### D18: Constrain and retain ordered compositions of abstract effects

`effectfun [E] { VALUE }` may contain `effectfun [F: after(E)] { VALUE }`.
The inner bound admits exactly those effect arguments for which `E ; F` is
defined. It is directional. In the paper's sequencing orientation,
`(B => A) ; (C => B) = (C => A)`: `E` is the first executed computation,
and its initial answer must equal the second computation's final answer.
Both endpoints are full computation types. `pure` is a neutral element on
either side; two unrelated repeatable effects need not compose.

The new `(E; F)` syntax retains the result of an abstract composition.
Both its occurrence in an annotation and its inference from a `let` require
evidence in the typing environment that composition is defined. Merely writing
the annotation does not assert compatibility. The checker uses concrete
endpoint equality, the existing repeatability bound, or a declared `after`
bound. Constraints are static; no runtime witness or callback specialization
is introduced. This extends the answer-effect language, not Effekt's ordinary
unordered effect requirements.

Bounds are resolved before introducing their binder, so they can depend on
outer effect variables but cannot refer to themselves. Instantiation substitutes
through dependent bounds as well as latent answer effects, checks the bound
even for an unused result, and normalizes newly concrete compositions. Fresh
binder identities prevent capture. Subtyping checks that every instantiation
permitted by the expected interface satisfies the implementation's constraint;
constraints cannot be silently dropped when passing a helper as an argument.
Capability opening and escape checks also traverse bounds, including capability
dependencies inside their nested answer computations.

Compositions are associated to the right after their definedness is checked.
For longer sequences, `G: after((E; F))` establishes that the whole
prefix may be followed by `G`. It also establishes that each suffix of that
prefix may be followed by `G`, which is needed when checking nested `let`s.
This follows from the partial composition algebra with a neutral `pure`:
defined `(E ; F) ; G` entails defined `F ; G` and `E ; (F ; G)` with the same
result. Bounds for pairs alone do not establish compatibility of an entire
sequence: if `F` is instantiated to `pure`, `E ; F` and `F ; G` reveal nothing
about whether `E ; G` is defined. The checker retains this distinction.

This is a directed constraint language and a conservative implication checker,
not a complete constraint solver. Arbitrary Boolean combinations of constraints,
endpoint quantification and answer-context abstraction remain open. In
particular, a constrained composition is still opaque to delimiter lifting
until instantiated. The local algebraic explanations here are not a proof of
the checker, its substitution implementation, or the lowering.

### D19: Retain ambient capabilities with scoped capability abstraction

`capfun` keeps its existing meaning: it binds a capability and a fresh,
unknown outer context. The new `capfun here (c: { ... }) { VALUE }`
retains the current environment instead. If its surrounding handler context is
`H`, its body is checked once at `[c] ++ H`. It can use ambient capabilities,
values, resumptions and effect variables, subject to the existing rules for
their use. Its type records both `H` and its captured capabilities. The annotated
type is `forall cap here c: { ... }. (TYPE) at {CAPTURES}`;
the `at` clause describes free ambient captures, excluding the bound `c`.

The recorded context and the capture set have different jobs. Captures track
dependencies of the closure, while the context preserves the position used to
check answer effects. An empty capture set therefore does not permit discarding
the context. Subtyping permits restricting a closed abstraction to a scoped
interface, opening the closed abstraction's tail at that interface's context.
It rejects treating a scoped implementation as universally valid at arbitrary
contexts. Within a fixed context, ordinary capture widening remains available,
with the existing typed adapters for invariant boxed target types.

Instantiation requires context `P ++ [h] ++ H`, with exactly the recorded `H`
outside the chosen actual handler. It renames `c` to `h` without appending `H`
again. The extra inner prefix `P` is permitted; a subsequent contextual function
call uses D6 to preserve its answer layers. Reusing one handler for two nested
parameters, reversing their order, or inserting an unaccounted-for handler
between a parameter and its recorded outer context is rejected, even if the
instantiated helper is not called. This restriction is explicit: captures alone
cannot justify the missing answer layers.

Nesting a scoped abstraction inside a closed one supports several abstract
capabilities. Opening the outer closed abstraction substitutes its capability
and outer tail through nested scoped contexts, captures, operation contracts,
answer effects and dependent effect bounds. Nested closed abstractions still
bind an independent tail. Operation contracts of the scoped form are resolved
in the surrounding environment, before its new capability is bound, so they
may refer to outer capabilities. Binder identities remain generative and
comparison accounts for alpha-renaming in dependent contracts.

`multiple-capabilities.atm` checks a helper against two abstract capabilities,
then instantiates it with an outer answer-changing handler and an inner handler.
Its body invokes both. A separate case passes a capability through another
capability's operation contract. The lowering still uses ordinary Effekt block
parameters; the scoped factory's boxed type now includes its actual ambient
captures. There is no specialization, cast, or relaxation of Core typing.

Because the scoped form retains a fixed context, an outer effect abstraction
may enclose it: `forall effect E. (forall cap here c: { ... }. (... E ...))`. Substitution preserves
that context while traversing the operation contracts and result type. This
does not grant the same freedom to a closed capability abstraction with an
unknown outer tail. General answer-context quantification, arbitrary gaps
between abstract capabilities, and a proof of capture/context substitution and
escape prevention remain outstanding.

### D20: Change concrete syntax without changing ATM typing

The prefix frontend is replaced by a hand-written lexer and recursive-descent
parser in the shared JVM/Scala.js source. The new syntax follows Effekt's
block, declaration, application and handler conventions. `fun`, `capfun`,
`effectfun`, answer transitions and explicit return clauses are experimental
forms; choosing these spellings does not extend the native Effekt parser.

The parser still produces `Syntax.Program`, `Term`, `Value`, `Type` and
`Effect`. Local `def` desugars to a value binding of an annotated lambda;
empty argument lists denote unit. No region/evidence arguments, new typing
rules, inferred answer effects or changed handler behavior are introduced.
The checker, reference interpreter and lowering are unchanged by this migration.
Parse diagnostics include a one-based line and column, and incomplete or
trailing source is rejected before checking. The CLI labels both parsing and
typing failures as `ATM error`.

Tests use the new syntax, including quantified types, nested scoped capability
abstractions, effect bounds and rejection witnesses. Fixture mutations match
tokens across formatting and assert that the target fragment exists, avoiding
silently lost regression tests. Parser-specific checks cover grouping,
instantiation order, comments, declaration sugar and malformed input. This is
syntax migration and implementation validation, not a new soundness result.

### D21: Dependent refinements checked before erasure

`{v: Int | v >= 0}` refines a base type. Predicates may mention earlier immutable
value binders; dependent function types use `(x: T) => C`. The logical sorts are
`Int`, `Bool`, and pairs of logical sorts. Operations support argument-dependent
contracts and a result binder scoped over the initial answer:

```text
def choose(arg: Int): (choice: Bool) /
  [{r: Int | r == (if choice then arg else 0)} => Int] resume k = { ... }
```

Here `choice` names the value passed to the resumption. It is not in scope in
the final answer or the operation clause. All resolved value, type, predicate,
effect, and capability binders have fresh identities. Substitution uses these
identities, so source shadowing cannot redirect a contract.

The checker generates first-order verification conditions from singleton value
types, function preconditions, call postconditions and branch conditions. It
checks function bodies under arbitrary arguments, including unused definitions.
Facts from two branches are guarded by their respective conditions. Facts about
a handled body's normal result are available to its return clause; they are not
exported from an aborting handler. The enclosing handler exposes only its checked
result contract. `assert(condition)` must be proved; it is not an assumption.

Subtyping uses implication for base refinements, contravariant function arguments
and covariant results. Answer transitions are contravariant in their initial
answer and covariant in their final answer, subject to the existing equal erased
shape restriction. Refinement implication never permits inventing answer layers.
Structural alpha-equivalence remains separate from SMT implication. Some valid
refined programs are therefore conservatively rejected, particularly around
abstract effect composition and branch joins of different answer contracts.

The JVM implementation invokes Z3 with SMT-LIB generated from a sorted AST.
`unsat` for the negated verification condition accepts an obligation. `sat`
rejects it; missing solver, errors, malformed output, timeout and `unknown` fail
checking. The solver has a three-second query timeout and a five-second process
limit. Set `EFFEKT_ATM_Z3` to an executable path if `z3` is not on `PATH`. The
Scala.js checker compiles but has no built-in solver; refinement obligations
require a caller-supplied `Logic.Solver` or the JVM entry point.

Arithmetic remains arbitrary-precision integer arithmetic in both logic and JS.
Division and remainder truncate toward zero; executed divisors must be proved
nonzero. Logical division by zero has SMT's unspecified total interpretation;
specifications should guard it with a nonzero condition. Boolean value operators
are strict, pure primitives; use `if` for conditional evaluation. Logical
operators have ordinary mathematical meanings. Pairs lower to a native record.
Refinements and predicate abstractions are erased only after successful checking.
Handler answer tags group identical erased representations, so different logical
witness names do not produce different runtime tags.

### D22: Explicit value-type and predicate polymorphism

The source supports higher-rank explicitly annotated type polymorphism:

```text
val identity = typefun [A] { fun (x: A): A { x } };
val n = identity[type Int](42);
// A higher-rank parameter type: forall type A. (((A) => A))
```

Type parameters are rigid while checking the definition. Instantiation traverses
function and answer contracts as well as value types. Type arguments must have
no free capability identity or handler-context dependency; closed function types
and refined types are supported. This is explicit polymorphism, not automatic
Hindley-Milner generalization. Type abstractions lower to native Effekt generic
functions; higher-rank subtyping inserts typed adapters.

Predicate polymorphism uses rigid, sorted, uninterpreted predicates:

```text
val keep = predfun [P(Int)] {
  fun (x: {v: Int | P(v)}): {v: Int | P(v)} { x }
};
keep[pred (n: Int) => n >= 0](42)
// Its quantified type: forall pred P(Int). (({v: Int | P(v)}) => {v: Int | P(v)})
```

A definition must work for every interpretation of `P`. Instantiating it does
not defer or bypass verification of its body. Predicate arity, sorts and lexical
scope are checked. These quantifiers coexist with the existing capability and
answer-effect quantifiers; there is no predicate inference or Horn-clause
invariant synthesis.

### D23: Annotated recursion and lexical aliases for migration

`def rec f(x: T): C = { ... }` checks its body using the declared contract as an
inductive hypothesis. This establishes partial correctness obligations only:
there is no termination checker. A diverging function can satisfy an arbitrary
postcondition, but cannot establish a terminating false result. Recursive
functions conservatively retain the surrounding capability context.

`type Answer = ((Int) => Int);` introduces a lexical, nonrecursive type alias.
It resolves once where declared. Reusing it beneath a handler preserves the
original function-type context instead of resolving the annotation again under
a different delimiter. Aliases and type quantifiers shadow lexically, and aliases
cannot conceal free capabilities or abstract effects inside a closed capability
abstraction. This supports the function-valued answers in the recursive state
port without relaxing context checks.

The pinned ARM migration inventory, original postconditions, adaptations and
remaining features are recorded in [ARM migration](../ARM.md) and the machine-readable
[manifest](../arm-manifest.json). Passing these ports is implementation evidence,
not a soundness theorem or full-suite migration.

### D24: Regression repairs exposed by the extension

Native annotation keys compared identity hash codes instead of object identity.
Distinct syntax nodes with colliding hashes could overwrite each other's typing
annotations. The equality comparison now uses reference identity; a regression
forces collisions rather than relying on a rare runtime occurrence. This is a
native compiler correctness repair, separate from ATM/refinement typing.

Generated recursive calls use direct native function calls, preserving the
optimizer's recursion information. Polymorphic answer decoders call an explicitly
instantiated generic failure helper on impossible tags, avoiding unresolved
capture variables in native bottom coercions. The helper still calls `panic`;
there is no cast or default result. Nested pairs use record field accessors.
Type equality keeps a binder slot for unnamed parameters, so an outer dependency
cannot be confused with a named inner parameter.

## Commands

```sh
sbt 'effektJVM/testOnly effekt.AtmTests effekt.AtmParserTests effekt.AtmRefinementTests effekt.AtmArmTests effekt.AtmAnnotationKeyTests'
sbt 'effektJVM/runMain effekt.atm.Main --check examples/atm/int-to-bool.atm'
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/refinement-polymorphic.atm'
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/int-to-bool.atm'
sbt 'effektJVM/runMain effekt.atm.Main --emit examples/atm/int-to-bool.atm /tmp/atm.effekt'
```

## Surface syntax

A complete program has one entry point. This example resumes an `Int` answer
and turns it into a `Bool`:

```text
def main(): Bool = {
  try {
    h.ask()
  } with h: Bool {
    def ask(arg: Unit): Int / [Int => Bool] resume k = {
      val n = k(42);
      zero(n)
    }
    return x => { x }
  }
}
```

The annotation after `with h:` is the handler's result computation. Each
operation declares its parameter and result types, initial and final answer
computations, and resumption name. These ATM annotations extend the familiar
shape of an Effekt handler; they are not ordinary Effekt effect sets.
The return clause is explicit and runs outside its handler, as in D2.

| Construct | Syntax |
| --- | --- |
| Pure computation type | `Int` or `Int / pure` |
| Answer-changing computation | `Int / [Int => Bool]` |
| Nested answer transition | `Int / [Int / [Int => Int] => Int / [Int => Bool]]` |
| Abstract answer effect | `Int / E` |
| Ordered effect composition | `Int / (E; F)` |
| Function type with captures | `((Unit) => Int / E) at {h}` |
| Local function | `def f(x: Int): Int = { x }` |
| Anonymous function value | `fun (x: Int): Int { x }` |
| Sequential binding | `val x = f(42); ...` |
| Function / operation call | `f(42)` / `h.ask()` |
| Conditional | `if (condition) { ... } else { ... }` |
| Unit / nominal protocol token | `()` / `token(Open)` |
| Capability type and value | `cap(h)` |
| Capability abstraction | `capfun (c: { def ask(): Int / [Int => Bool]; }) { VALUE }` |
| Scoped capability abstraction | `capfun here (c: { ... }) { VALUE }` |
| Capability instantiation | `helper{cap(h)}` |
| Capability-polymorphic type | `forall cap c: { ... }. (TYPE)` |
| Scoped capability-polymorphic type | `forall cap here c: { ... }. (TYPE) at {outer}` |
| Answer-effect abstraction | `effectfun [E] { VALUE }` |
| Bounded effect abstraction | `effectfun [E: repeatable] { VALUE }` |
| Dependent composition bound | `effectfun [F: after(E)] { VALUE }` |
| Answer-effect instantiation | `helper[effect [Int => Bool]]` |
| Effect-polymorphic type | `forall effect E: repeatable. (TYPE)` |

An omitted effect bound means `any`. An effect is `pure`, a bound name,
`[INITIAL => FINAL]`, or a parenthesized sequence such as `(E; F)`.
Surface composition associates to the left; parentheses can preserve another grouping.
A function used as an answer endpoint is grouped, for example
`Int / [((Int) => Int) => Bool]`, so the transition arrow is unambiguous.
Capture annotations attach to a function or scoped capability-polymorphic type;
parenthesize the complete function type before writing `at { ... }`.
`forall` bodies are parenthesized to make the scope of nested captures explicit.

Capability and answer-effect abstractions contain a **value**, usually another
abstraction or a `fun`. They do not execute operations when constructed or
instantiated. Postfix instantiations can be chained before application:
`helper{cap(h)}[effect pure](callback)`.

Blocks end in a computation, optionally followed by a semicolon. A value in
final position returns that value; `return VALUE` is equivalent. Empty blocks
return unit. `val` requires a semicolon; a local `def` may have one after its
body. Functions are unary, and `f()` abbreviates `f(())`. Local functions are
nonrecursive. Arguments and conditions are values; bind a computed argument
first, for example `val x = g(); f(x)`. This preserves the prototype's existing
value/computation distinction without adding evaluation-order elaboration.

`add(a, b)`, `zero(a)` and `not(b)` remain built-in value primitives. `a + b`
and `!b` abbreviate addition and Boolean negation; unary `!` binds more tightly
than left-associative `+`. Negative integer literals are exact. Primitive names
and structural keywords are reserved (`after` is contextual); bound names use ASCII letters, digits and
underscores, with a letter or underscore first. Comments use `//` or nested
`/* ... */`. Whitespace and line breaks do not terminate statements.

All 19 files in `examples/atm` use this syntax, including the intentionally
ill-typed protocol example. S-expressions are no longer accepted by the CLI.

## Current boundary and next implementation work

The small prototype is implemented. The harder work includes contextual
lifting, first-class capability identities, closed and scoped capability
abstraction, and explicit answer-effect polymorphism with repeatability and
directed composition bounds. Nested scoped abstractions support several ordered
capability parameters and dependent operation contracts.
This remains a separate experimental frontend, not a complete native Effekt
extension. It now supports explicit value-type and predicate quantification,
dependent refinements, Z3 verification and annotated recursion. It still lacks
general algebraic datatypes, lists, mutable references, exceptions, refinement
inference, general answer-context polymorphism and bidirectional operations.
Nominal protocol tokens remain distinct from refinements. Compiling the checker
for Scala.js is separate from validating generated programs on the JS backend;
the Scala.js build has no built-in SMT process. See [ARM migration](../ARM.md).

The capability-abstraction checkpoint now permits a higher-order helper to be
checked without naming one concrete handler in its callback contract. The next
typing work includes general answer-context polymorphism, broader constraint
solving, and arbitrary gaps between abstract capabilities. Ambient captures now
retain their concrete context; abstracting over changes to that context remains
open. Current instantiation and call lifting do not equate arbitrary abstract
answer contexts. Integration with the native Effekt parser and typer must preserve this distinction.

Required proof obligations remain: contextual lifting of typing derivations;
capability abstraction/instantiation, capture substitution and escape prevention;
answer-effect abstraction, constraint entailment, kind-preserving substitution and erasure;
preservation of subtyping adapters, result contracts and answer contracts by
the tagged-answer lowering; unreachable decoder failures;
and compatibility with stored, repeated and eventually bidirectional resumption.
The independent interpreter tests these behaviors on concrete examples only.

## Verification log

- Baseline: pinned Kiama submodule initialized; `effektJVM/Test/compile` passed
  before ATM changes (Java 27, sbt 1.11.7, Scala 3.9.0).
- Initial prototype: `effektJVM/testOnly effekt.AtmTests` passed all 18 tests.
  Nine accepted fixtures ran through the JavaScript backend both with and
  without optimization. Further tests cover return clauses, abort, invalid
  composition, scope escape and missing answer layers.
- Contextual lifting extension (D6): all 22 tests passed, including one to four
  added delimiters and a stored resumption called below a new delimiter.
- Capability values (D7): all 26 tests passed, including aliases and function
  arguments. Capability escape and non-capability receivers are rejected.
- Scala.js compilation passed, and the documented `--run int-to-bool.atm`
  command returned `false`.
- Independent source oracle: all 27 ATM tests and five targeted pre-existing
  Core tests passed (32 total).
- Exact-integer representation change: all 29 ATM tests passed, including
  arithmetic beyond 64 bits. `effektJS/compile` passed again. The CLI regression
  now prints the correct `9007199254740995`.
- Whitespace checks passed for all 21 new source, example and documentation files.
- Capability abstraction and fresh identities: 39 ATM tests passed. The native
  capture repair passed 2 regression executions, 17 existing lambda tests,
  2 existing block-parameter tests and 5 Core tests (65 tests total). Scala.js
  compilation passed. This was followed by the quantified-answer regressions
  described in D13 and explicit source subtyping elaboration in D14.
- Final capability-polymorphism run: all 46 ATM tests passed, with zero failures
  or ignored tests. Fixtures and new accepted cases compare the independent source interpreter
  with optimized and unoptimized JavaScript. The new cases include different
  outer delimiter contexts, intervening inner handlers, alias/shadowing safety,
  higher-order quantified parameters, quantified answers/resumptions, nested
  quantifier escape, operation declaration ordering and function subtyping.
  Scala.js compilation passed; the CLI polymorphic example returned `true`.
- Final whitespace checks covered all 26 new ATM/documentation/regression files;
  `git diff --check` also passed for the tracked compiler/test changes.
- Answer-effect polymorphism (D15-D16): all 60 ATM tests passed, with zero
  failures or ignored tests. The added checks cover pure and ATM instantiation
  of one helper, quantified higher-order types, effect-variable shadowing,
  separate capability/effect namespaces, pure sequencing and alternatives,
  opaque effects inside operation answers and resumed return clauses, and
  repetition after instantiation at a known identity transition. Negative tests
  reject unjustified pure lifting, unknown variables, mismatched abstract
  effects, repeated opaque transitions, opaque delimiter lifting and scope
  escape. Scala.js compilation passed; the new CLI example returned `false`.
- Repeatability bounds (D17): all 72 ATM tests passed, with zero failures or
  ignored tests. Added cases exercise bound forwarding, binder shadowing,
  quantified-domain variance, pure lifting and branch joins, distinct abstract
  effects, nested answer states, and rejection of unequal nested effects or
  depths despite equal outer value types. The reference interpreter and both
  optimized and unoptimized generated JavaScript agree on accepted examples.
  Scala.js compilation passed; the repeatable example returned `88`, counting
  both handled invocations. Whitespace checks covered all 29 new files and the
  tracked diff.
- Directed composition (D18): all 88 ATM tests passed, with zero failures or
  ignored tests. The nonidentity composition example returned `200` in the
  reference interpreter, optimized and unoptimized generated JavaScript, and
  the CLI. Further checks cover pure/identity combinations, three-callback
  sequencing, abstract forwarding, dependent-bound alpha-renaming and
  shadowing, higher-order variance, eager rejection of incompatible arguments,
  and dependencies that occur only in a bound. A finite model checks admitted
  implications among 63 two/three-effect expressions against all assignments
  over two answer states plus pure that satisfy the declared prefix bounds;
  it also checks all 25 concrete pairs for acceptance/rejection agreement.
  This is finite implementation evidence, not a proof. Scala.js compilation
  passed. Whitespace checks covered all 31 new files and the tracked diff.
- Scoped capability abstraction (D19): all 102 ATM tests passed, with zero
  failures or ignored tests. The two-capability example invokes both abstract
  capabilities and returns the expected `false` through the reference
  interpreter, optimized and unoptimized generated JavaScript, and the CLI.
  Tests cover opened outer tails, three ordered parameters, additional inner
  handlers at a function call, ambient aliases and abstract effects, dependent
  operation contracts, higher-order alpha-equivalence, and restricting a closed
  helper to a scoped interface. Rejections cover reversed/aliased parameters,
  intervening handlers, mismatched dependent identities, shadowing, scope escape
  despite empty captures, and invalid generalization from scoped to closed.
  Scala.js compilation passed. Whitespace checks covered all 32 new files and
  the tracked diff. These results do not prove the substitution or escape lemmas.

- Effekt-like syntax migration (D20, 2026-10-20): all 102 migrated ATM tests
  and 16 parser tests passed, with zero failures or ignored tests. A separate
  one-time audit compared all 19 fixtures against the original parser and found
  identical complete source ASTs, including the rejected protocol fixture.
  The temporary legacy-parser audit helpers were then removed. A discovered
  ambiguity between transition arrows and function-type arrows is covered by
  a permanent parser regression. Scala.js compilation passed, and the CLI
  two-capability example returned the expected `false`. Whitespace checks passed
  for all 24 files changed by the syntax migration and for the tracked diff.

Earlier reproducible ATM checks (2026-10-20):

```sh
sbt 'effektJVM/testOnly effekt.AtmTests effekt.AtmParserTests' 'effektJS/compile' \
  'effektJVM/runMain effekt.atm.Main --run examples/atm/multiple-capabilities.atm'
```

Earlier native/compiler regression commands (2026-10-19):

```sh
sbt 'effektJVM/testOnly effekt.JavaScriptTests -- --tests *boxed_capability_parameter*' \
  'effektJVM/testOnly effekt.JavaScriptTests -- --tests *lambdas*' \
  'effektJVM/testOnly effekt.JavaScriptTests -- --tests *blockparams*' \
  'effektJVM/testOnly effekt.core.TransformerTests effekt.core.TypeInferenceTests'
```

Earlier result: 118 permanent ATM/parser tests passed, plus the one-time migration
comparison; Scala.js compilation passed; the CLI result matched. The earlier
26 targeted native/compiler tests passed before the subsequent ATM-only changes;
the native compiler repair was unchanged. No complete compiler-wide test suite
or formal proof was run. The larger goal remains incomplete.

### Refinement and polymorphism checkpoint (2026-10-31)

The final run passed **154 ATM-related tests**: 102 existing ATM tests, 16 parser
checks, 27 refinement/polymorphism checks, seven ARM acceptance/rejection checks,
and two native annotation-key regressions. Accepted examples compare the source
interpreter with optimized and unoptimized generated JavaScript. Five targeted
Core tests also passed. Scala.js compilation passed; the JVM CLI returned `8`
for `arm/state-easy-SAT.atm` and `42` for `refinement-polymorphic.atm`.

```sh
sbt 'effektJVM/testOnly effekt.AtmTests effekt.AtmParserTests effekt.AtmRefinementTests effekt.AtmArmTests effekt.AtmAnnotationKeyTests' \
    'effektJVM/testOnly effekt.core.TransformerTests effekt.core.TypeInferenceTests' \
    'effektJS/compile' \
    'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/state-easy-SAT.atm' \
    'effektJVM/runMain effekt.atm.Main --run examples/atm/refinement-polymorphic.atm'
```

The pinned input audit verified all 52 upstream hashes, with seven port files
and 45 explicitly pending files. Whitespace checks covered all 50 ATM source,
test, example and documentation files; the tracked diff check passed. Validation
used Java 27, Scala 3.9.0, sbt 1.11.7 and Z3 5.1.0. This checkpoint completes the
implemented refinement/polymorphism extension and initial migration subset;
complete ARM migration, native frontend integration and formal soundness remain
outstanding. No compiler-wide test suite or soundness proof was run.
