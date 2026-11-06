# Nested-handler inference examples

This suite contains five successful programs and one inference limitation. All
use annotated answer types and omit explicit evidence and ATC arguments. The five
successful programs exercise operation evidence distances and ATC depths **0, 1, 2**
and evaluate to `true` in the source interpreter and both optimized and unoptimized
Lifted/Chez execution. These are runtime witnesses, not refinement proofs of the
Boolean returned by `main`.

| Program | Purpose |
| --- | --- |
| [active-three-counts.effekt](active-three-counts.effekt) | Three active handlers, with evidence/ATC depths 0, 1, and 2 and observable clause results. |
| [active-three-multishot.effekt](active-three-multishot.effekt) | Two outer resumptions restore the two intervening handlers. |
| [nested-residuals.effekt](nested-residuals.effekt) | Abstract helper-call evidence composes with three local handler edges; callers instantiate distances 0 and 1. |
| [mixed-answer-layers.effekt](mixed-answer-layers.effekt) | An outer operation infers a Bool layer around an Int layer, preserving both initial answers. |
| [shadowed-capabilities.effekt](shadowed-capabilities.effekt) | Aliases retain the identities of outer handlers despite repeated source name h. |
| [polymorphic-outer-call.effekt](polymorphic-outer-call.effekt) | **Inference limitation:** a callback captures an outer capability; its abstract caller region gives residual evidence with symbolic distance, so ATC inference is undefined. |

For `polymorphic-outer-call`, evidence reconstruction succeeds **with a residual
obligation**; the checker then reports `Unsupported` at ATC inference. It does
not produce a complete elaboration or run the program. The supplement marks
Evidence with a qualified checkmark and ATCs with a cross. `Checker.inspect`
records the witness/residual and failed ATC attempt directly; it does not infer
these statuses from a diagnostic string or bypass the failure.

`AtmInferTests` checks exact evidence endpoints/composition, complete ATCs and
initial answers, residual abstraction/discharge, and alias identity. It also
constructs two negative variants: an incompatible ATC hole type and too few
answer layers. Those two variants are tests, not additional benchmark files. The symbolic-distance
limitation is the sixth file and has its own table row.

```sh
sbt -batch 'effektJVM/testOnly effekt.AtmInferTests'
sbt 'effektJVM/runMain effekt.atm.Main --elaborate examples/atm/infer/nested-residuals.effekt'
python3 docs/atm/benchmark_arm.py inspect --suite infer
python3 docs/atm/benchmark_arm.py render --suite infer --omit-timing
# After reviewing the document, collect timing last.
python3 docs/atm/benchmark_arm.py time --suite infer --warmups 2 --repetitions 5
python3 docs/atm/benchmark_arm.py render --suite infer
```

The [manifest](../../../docs/atm/infer-manifest.json) pins the source hashes.
Inspection and timing reports are in `docs/atm/results/infer-*.json`.
The supplement has a dedicated inference table; its ARM table remains separate.

Recorded typechecking medians: **0.14–1.50 ms**, including time to report
the unsupported example. Each uses five measured runs after two full-suite
warm-up rounds. See the [raw samples](../../../docs/atm/results/infer-timing.json).
