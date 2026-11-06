# Paper-algorithm migration validation

> **Historical validation stage.** The 96-test run and pending `state-easy` below
> predate the v0.3.0 migration and later repairs. For current behavior and counts,
> see [validation](../VALIDATION.md) and [benchmark overview](../BENCHMARKS.md).

Verified 2026-11-06 in the local Effekt checkout. This records implementation
checks, not a proof of elaboration soundness or a full compiler test run.

## Completed checks

| Check | Result |
| --- | --- |
| `AtmTests` | 44 passed: paper-rule conformance, evidence, scopes, rejection boundaries, native differential execution |
| `AtmParserTests` | 16 passed |
| `AtmRefinementTests` | 27 passed |
| `AtmArmTests` | 7 passed: 3 SAT ports, 3 UNSAT ports, 1 explicit pending-port rejection |
| `AtmAnnotationKeyTests` | 2 passed |
| ATM-related total | 96 passed, 0 failed, 0 ignored |
| `core.TypeInferenceTests`, `core.TransformerTests` | 5 passed |
| `effektJS/compile` | Passed |
| `--elaborate examples/atm/evidence-functions.atm` | Produced bound region/distance/evidence abstractions and applications |
| `--run examples/atm/arm/expectation-SAT.atm` | Printed `600` |
| Pinned ARM input hashes | All 52 matched the local coar checkout |
| Whitespace and `git diff --check` | Passed |

The final run log is `/private/tmp/effekt-paper-migration/verified.log`. The
commands used the source-emitter pipeline at this stage. These execution checks compare both
optimized and unoptimized JavaScript against the independent source interpreter.

## Evidence example

The supplied supplement's page-33 pattern is represented by
`examples/atm/evidence-functions.atm`. In its inferred `both` function, the
elaboration contains:

```
Lambda bodyRegion, callDistance, callEvidence : bodyRegion <= h
  outer(): evidence = callEvidence + handlerEvidence
           distance = callDistance + 1
  inner(): evidence = callEvidence
           distance = callDistance
```

At the actual call under `h`, the caller supplies reflexive evidence and zero
distance. The checked program has no residual obligations at its outer boundary.
No definition-stack length or suffix test is used to construct these witnesses.

## Changed acceptance boundary

The archived prototype suite required acceptance of several rules that are absent
from the paper algorithm. Those requirements were not retained as passing tests.
Tests at this stage explicitly reject symbolic-distance outer-handler calls in lambdas,
continuation escape through ordinary arrows, continuation calls under new
handlers, scoped stack tails, repeatable/after bounds, and undefined final-answer
projections. Direct and repeated resumptions at their exact ambient region,
concrete nested-handler operations, refinement checking and explicit
value-type/predicate polymorphism remain tested.

At this stage, `state-easy-SAT.atm` was a pending port. Its rejection was checked
separately and was not counted as an UNSAT benchmark success. The manifest then
had 6 supported files and 46 pending. The current local-function port is accepted.

## Additional scope regressions

Dependent computation result binders are alpha-renamed at each application;
two calls with different refined results cannot introduce a false equality by
reusing the same logical identity. Handler return clauses receive only the
outer context plus their returned-value binding. Local logical names are closed
by verified subsumption annotations before the let type escapes its scope.
The return-clause parameter type must not mention the handler's closed region.

Evidence validation checks bindings and composition in the intermediate tree.
It is not an independent implementation of the complete declarative type system.
Full grammar coverage (including general ATC/computation kinds and polymorphic
operation signatures), a correspondence proof, and the remaining ARM migration
are separate unfinished work; see the README's explicit boundaries.
