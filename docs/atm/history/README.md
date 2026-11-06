# Historical ATM records

These files describe earlier implementations and validation stages. Their
acceptance claims, test counts and CLI meanings apply to the recorded stage,
not the current checkout. Start with [the current guide](../README.md) or
[the benchmark overview](../BENCHMARKS.md) for current behavior.

| Record | What it preserves |
| --- | --- |
| [Prototype design](prototype-design.md) | The earlier ordered-handler-stack implementation, including scoped tails and `repeatable`/`after` bounds that the current checker does not implement |
| [Prototype tests](AtmPrototypeTests.scala.txt) | Original suite source, retained as a reference rather than compiled as the current test suite |
| [Paper-algorithm validation](paper-algorithm-validation.md) | The initial replacement checker: 96 ATM tests, six supported ARM ports and pending `state-easy` |
| [v0.3.0 migration validation](v0.3.0-migration-validation.md) | The version migration before direct ATM-to-Lifted lowering: 98 ATM tests and source-emitter/JavaScript execution |
| [Original rejected examples](rejected-before-local/) | Ten source snapshots before local-function and explicit-return repairs; used by current regressions for original closed-program witnesses |

The current route is source checking -> typed region/evidence Core -> adapted
historical Lifted -> Chez. `--emit` now writes Scheme; `--emit-effekt` retains
the legacy source emitter. Current test counts and the complete upstream ARM
inventory are recorded in [validation](../VALIDATION.md),
[paper fidelity](../FIDELITY.md) and [example repairs](../REJECTED-EXAMPLES.md).

The `.atm.txt` source snapshots are intentionally unchanged. Their original
generic interfaces and annotations are not all accepted by the current checker.
Runtime witness comparison is not a general equivalence or soundness proof.
Temporary log paths in the old records refer to the original machine and may
not be present in a copied checkout.

Current ATM sources use `.effekt`; historical records retain their original
`.atm` names and commands, and `.atm.txt` snapshots remain unchanged.
