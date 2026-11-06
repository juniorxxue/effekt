# Previously rejected examples: fixes and remaining limits

For a single overview of all benchmark families, contracts and commands, start
with [BENCHMARKS.md](BENCHMARKS.md). This report explains the individual repairs.

Checked on 2026-11-06. The scope here is every existing
`examples/atm/**/*.effekt` file, not the unported rows of the paper benchmark table.
The initial fresh run found 18 accepted files, 12 blocked positive examples,
and six intentional negatives. That repair increment had 28 accepted files, two unsupported examples and six
negatives. After the ARM migration, the current inventory, including the six inference examples, has **58 accepted
files, three unsupported examples, and 22 intentional negatives**.
Of the ten repairs, seven use added language support and three also narrow their
generic contracts to explicit answer-type parameters. The distinction is
recorded below; their original generic interfaces are not all implemented.

The [machine results](results/examples.json) include every file, its SHA-256,
actual diagnostic and expected category. Reproduce them with:

```sh
python3 docs/atm/check_examples.py
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests'
```

The runner exits nonzero on an unexpected outcome, including a missing solver,
parse error or an arbitrary type error in an UNSAT benchmark. The one protocol
negative has its specific type-error diagnostic checked. The unsupported
entries remain limitations, never counted as successful negative tests.

## Ten repaired examples

| Example | Change | Checked runtime witness |
| --- | --- | --- |
| [`capability-argument`](../../examples/atm/capability-argument.effekt) | A fixed-region `def local` invokes the enclosing handler | `false` |
| [`capability-polymorphic`](../../examples/atm/capability-polymorphic.effekt) | The capability abstraction returns a `fun local[c]`, callable at that capability's region | `true` |
| [`effectful-callback`](../../examples/atm/effectful-callback.effekt) | Local callback parameter and local higher-order helper | `false` |
| [`polymorphic-callback`](../../examples/atm/polymorphic-callback.effekt) | Capability-polymorphic helper with a callback at the instantiated capability's region | `false` |
| [`stored-resumption`](../../examples/atm/stored-resumption.effekt) | Expose the actual continuation as a compatible local arrow, return it, and call it at its ambient region | `42` |
| [`return-effects`](../../examples/atm/return-effects.effekt) | Explicit `return effect` checks the return body against the known effectful answer contract | `false` |
| [`effect-polymorphic`](../../examples/atm/effect-polymorphic.effekt) | Replace opaque `E` with value-type parameters `Initial`/`Final` in `(Initial => Final)`; use local callbacks | `false` |
| [`repeatable-effect`](../../examples/atm/repeatable-effect.effekt) | Replace `E: repeatable` with `(Answer => Answer)`; both callback invocations remain | `88` |
| [`composed-effects`](../../examples/atm/composed-effects.effekt) | Replace `F: after(E)` with `(Middle => Final)` followed by `(Initial => Middle)`; retain both effects/resumptions | `200` |
| [`arm/state-easy-SAT`](../../examples/atm/arm/state-easy-SAT.effekt) | Local recursive counter and local state-passing functions; scope the answer alias at the ambient region of `test` | `8`, also `0`, `1`, `17` |

The originals are preserved byte-for-byte in
[`history/rejected-before-local/`](history/rejected-before-local/).
`AtmLocalFunctionTests` executes each original closed program in the independent
source interpreter and compares its witness with the repaired version.
`AtmLocalFunctionTests` and `AtmArmTests` also check the repaired programs through
direct Lifted/Chez and legacy Effekt/JavaScript, each with and without its
optimization pass. These are execution comparisons, not a general equivalence
proof for the changed generic interfaces.

`state-easy` retains the universal property: for every initial state `>= 0`,
the result is nonnegative. The recursive counter, lookup/update handler and
function-valued answers remain. A strengthened false postcondition `>= 1`
is rejected. There is no `state-easy-UNSAT.ml` in the pinned upstream inventory;
the strengthened-contract regression is not counted as an upstream benchmark.
That increment yielded 11 ARM ports. The subsequent migration covers all 52;
see [BENCHMARKS.md](BENCHMARKS.md) for every family and adaptation.

## What the added support means

`local (T) => C` denotes one fixed call region. In synthesis, `fun local`/`def local`
default to the lexical region; in checking, an expected local arrow supplies
the region. `local[c]` explicitly selects an in-scope capability's region and
must agree with an expected arrow. The
checker implements the declarative `T_Abs`/`T_App` premises: check the body at
that region and enforce the same region at application. Core retains the region;
the runtime function has no abstract call-evidence parameter. Operations in the
body still reconstruct evidence and use the existing partial `atc` algorithm.

This is an explicit frontend extension exposing declarative functions. It is
not a claim that algorithmic `A_Abs` infers them. Ordinary functions still use
fresh caller regions, residual obligations and reconstructed call evidence.
Local functions cannot be silently promoted to ordinary functions, invoked
beneath an extra delimiter or exported with a local handler region in their
type. Returned continuations keep their exact ambient call region.

Similarly, `return effect` is an explicit bridge to declarative `T_HRet`, with
the known return contract providing the inherited final answer. Default return
clauses keep the previous empty inherited mode. The explicit clause is checked
outside the exited handler; it cannot use that handler's capability. See
[FIDELITY.md](FIDELITY.md) for both rule distinctions.

The last three callback annotations in the table quantify over **value types
as pure answer endpoints**, not arbitrary computation types or opaque effects.
Pure callbacks may be embedded through checked `S_CPureImpure`. Repeated
callbacks must have composable endpoints; negative tests reject duplicating
an answer-changing callback and inconsistent composition parameters. Full
opaque-effect polymorphism and the old `repeatable`/`after` bounds remain
unsupported. The original generic APIs have therefore been narrowed, while
their closed examples and effectful executions have been retained.

## Unsupported examples

| Example | Remaining blocker | What an honest fix requires |
| --- | --- | --- |
| [`callback-under-handler`](../../examples/atm/callback-under-handler.effekt) | The closure is defined outside a later caller-owned delimiter and called beneath it. Its ordinary-function body reaches symbolic-distance ATC inference. A local arrow cannot be called at both regions. | A checked caller/ATC-indexed computation contract, with explicit ATC abstraction/instantiation and the relevant target kinds/codomain family. Moving the closure into the inner handler or restoring `replaceAnswer` would avoid the intended abstraction rather than implement it. |
| [`multiple-capabilities`](../../examples/atm/multiple-capabilities.effekt) | The old `capfun here` assumes an unknown relative handler tail. Current capability bounds do not establish a concrete relative distance between the two abstract handlers for ATC inference. | Checked relative-region/distance/evidence/ATC binders or sufficient explicit annotations, supported by a declarative target checker. Inlining the two-handler helper would lose the intended reusable abstraction. |

These programs remain in place. Their comments describe the intended prototype
behavior, not a guarantee from the current checker. Symbolic-distance ATC
inference is partial in the supplied paper as well; its rejection is not fixed
by treating the unknown distance as zero.

There is one additional restriction on the new forms: ordinary functions cannot
synthesize local-function results indexed by their fresh abstract caller region,
because Core does not yet retain Definition 17's caller-indexed codomain family.
The checker rejects that case explicitly. A stable local result annotation is
supported and tested. General computation/ATC-kind quantification, polymorphic
operation signatures and region-polymorphic datatypes are still missing. Closed
pure function fields at the top region are now implemented.

The inference suite additionally contains
[`infer/polymorphic-outer-call`](../../examples/atm/infer/polymorphic-outer-call.effekt).
It isolates the symbolic-distance limitation: reconstruction yields a residual
witness, then ATC inference is undefined. It is an unsupported example, not a
successful contract-negative test. Its partial evidence result is observed by
`Checker.inspect` and reported separately in the inference table.

## Current negative inventory

Twenty-one ARM files fail with `ContractFailure`, preserving their false upstream
assertions. `file-protocol-invalid.effekt` remains the separate protocol type error:
it requires `Open` while receiving `Closed`. All names and diagnostics are in
[the machine report](results/examples.json).

`distribution-UNSAT.effekt` accepts because the pinned upstream file has exactly the
same program and assertion as its SAT counterpart, apart from an unsat comment.
The manifests retain that label discrepancy; it is not counted as a successful
negative. The remaining 29 paper-only rows are unported, not rejected examples.
See [PAPER-COVERAGE.md](PAPER-COVERAGE.md) and [ARM.md](ARM.md).

## Validation

See [VALIDATION.md](VALIDATION.md) for the complete suite results. Regressions
cover exact-region invocation, local escape, resumption storage, answer-effect
composition, invalid handler returns, local-region alpha-renaming in capability
contracts, and a malformed Core local lambda. These tests and SMT checks are
not a mechanized soundness or compiler-preservation proof.
