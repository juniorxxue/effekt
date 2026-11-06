# ARM migration and language support

All **52** pinned coar ARM files now have `.effekt` ports. The 41 previously pending
files were added in this increment. [BENCHMARKS.md](BENCHMARKS.md) is the single-file
outline of every family, original property, material adaptation and remaining
paper-only work. The [manifest](arm-manifest.json) retains source/port hashes.

The checker accepts 31 ports and rejects 21 with `ContractFailure`. One accepted
file is labeled UNSAT upstream: `distribution-UNSAT.ml` differs from its SAT
counterpart only by an unsat comment. Its assertion remains unchanged. Reports
retain the original label and record the reviewed expectation separately.

## Source constructs added for this migration

```effekt
data ListInt { Nil(); Cons(head: Int, tail: ListInt) }
measure length(xs: ListInt): Int {
  case Nil() => 0;
  case Cons(h,t) => 1 + length(t);
}
def main(): {v: Int | v == 2} = {
  def rec size(xs: ListInt): {v: Int | v == length(xs)} = {
    match (xs) {
      case Nil() => { 0 }
      case Cons(h,t) => { val n = size(t); n + 1 }
    }
  }
  size(Cons(1,Cons(2,Nil())))
}
```

Matches must cover each constructor exactly once, with distinct field binders
and correct arity. Branch checking assumes the constructor equality and each
field's checked type. Synthesis closes branch-local logical identities before
joining the result. This is new match support; it does not redesign the existing
ordinary-let closing policy.

Measures are logical definitions only. Their first argument is a datatype;
self-calls must use a direct recursive constructor field. Definitions may call
earlier measures, but mutual/forward recursion, uninterpreted assumptions and
quantifiers inside a measure body are rejected. Z3 receives `define-funs-rec`,
not assumed equations. Result/parameter sorts and all cases are checked.
Measures may return datatypes, take extra parameters, and use tuples. They do
not prove executable termination or synthesize induction lemmas.

```effekt
data Deferred {
  Value(value: Int);
  Thunk(context: Int, thunk: ((Unit) => {v: Int | v == context}))
}
```

Constructor fields may depend on earlier logical fields. Construction checks
those instantiated contracts; matching recovers them with fresh field identities.
Closed pure ordinary/local function fields are supported at the top region;
function fields inside pairs and effectful function fields remain unsupported.
Functions use unit in the logical projection of a datatype, so measures can
inspect tags/data without claiming extensional function equality. Runtime
structural equality is rejected for data containing functions, including
transitively through other data. Function bodies are still checked normally.

A lexical alias to a local arrow may anchor another local arrow/lambda:
`type StateRegion = (local (Unit) => Unit);` followed by `local[StateRegion]`.
It names the alias's existing call region, introduces no new region, and cannot
relax the exact-region continuation rule. The exact `state` port uses this to
relate the counter result to its initial state.

## What ported means here

The universal coar assertions remain; `main` supplies separate executable
witnesses. Source correspondence is documented per family in
[BENCHMARKS.md](BENCHMARKS.md#adaptations-and-limits-of-the-ports) and per source in
the manifest. Explicit context arguments, helper specialization, and scheduler
state passing are material adaptations. In particular, the shift port omits an
inert recursive reset and does not implement the general shift/reset helper.
No native mutable-reference language support is claimed.

All runtime paths still use checked ATM source → typed region/evidence Core →
ATM-adapted historical Lifted → Chez. Legacy Effekt-source/JavaScript is used
only for differential comparison. This is not a change to the native Effekt
parser/type system, nor a reproduction of the paper's Lexa translations/timings.

## Reproduction

```sh
python3 docs/atm/check_arm_manifest.py /path/to/coar
python3 docs/atm/check_examples.py
python3 docs/atm/paper_coverage.py --run
sbt -batch 'effektJVM/testOnly effekt.AtmArmTests effekt.AtmPatternTests effekt.AtmMeasureTests effekt.AtmFunctionDataTests'
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/yield-SAT.effekt'
```

Use Java/sbt, Z3, Chez and Node for full differential testing. Accepted ports run
in the independent source interpreter and optimized/unoptimized direct Chez and
legacy JavaScript backends. Additional witnesses cover BFS's second candidate,
distribution merging, zero state, and a single-leaf iterator. The suite also
checks false contracts before lowering. A missing solver or unsupported feature
cannot count as a successful negative test.

See [VALIDATION.md](VALIDATION.md) for current results and
[FIDELITY.md](FIDELITY.md) for remaining algorithm/type-translation limitations.
