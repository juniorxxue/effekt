# Archived submission coverage matrix

For the single-file family outline, implemented contracts, current examples and run commands,
start with [BENCHMARKS.md](BENCHMARKS.md). This matrix tracks the supplied, hashed submission PDFs,
not the maintained supplement. The latter now reports the Effekt implementation and 52 ARM ports.

Generated from [paper-coverage.json](paper-coverage.json); update that inventory and run
`python3 docs/atm/paper_coverage.py --render` to regenerate.

Supplement section 4, Table 1, pages 52-53: 59 ARM (30 SAT, 29 UNSAT) plus 22 Lexa. This is not the pinned 52-file coar inventory.

Current mapping: **52 upstream ports**, **0 blocked ports**, **29 unported paper rows**. Upstream ports preserve the pinned coar assertions; equivalence to the archived Lexa translations has not been audited. These counts are not a reproduction of the archived Table 1. **One label discrepancy:** the pinned distribution UNSAT file equals its SAT counterpart apart from a comment; its port accepts and does not validate the paper UNSAT row.

The candidate Lexa sources were read from a local working tree at commit `15b9073094cde063012e0e3b931728de104e9754`. Each path and SHA-256 is in the JSON. 21 reference files differ from that commit. They are not assumed to be the submission snapshot, and no paper timings are reused.

The pinned 52-file upstream inventory remains in [arm-manifest.json](arm-manifest.json). A machine run records unported/unsupported entries separately from contract rejections. See [ARTIFACT-STATUS.md](ARTIFACT-STATUS.md) for reproduction and remaining priorities.

## Paper examples

| Example | Location | Status | Remaining work |
| --- | --- | --- | --- |
| lexical-selection | main section 2.1, Figure 1 | illustration: [nested-outer.effekt](../../examples/atm/nested-outer.effekt) | Audit correspondence to both lexical file capabilities in the paper; current port illustrates lexical dispatch only. |
| atm-introduction | main section 2.2 | illustration: [int-to-bool.effekt](../../examples/atm/int-to-bool.effekt) | Current program illustrates an answer-type change; not a transcription of the paper example. |
| nested-lexical-atm | main section 2.3 | illustration: [evidence-two-delimiters.effekt](../../examples/atm/evidence-two-delimiters.effekt) | Current regression covers composed evidence and a depth-two ATC; map the exact paper program separately. |
| file-management | main section 4.3 | partial: [file-protocol.effekt](../../examples/atm/file-protocol.effekt), [file-protocol-invalid.effekt](../../examples/atm/file-protocol-invalid.effekt) | Existing token protocol omits the full nested state-passing handlers and their operation contracts; fixed-region functions are now supported. |
| polymorphic-close | main section 5.1 | unported | Full Reg/Dist/ATC/Ev/Cty kinded abstraction/application and polymorphic operation contracts are absent; capability polymorphism alone is insufficient. |
| nested-state-refinements | main section 5.2; supplement section 5.2 | unported | Preserve both state handlers and prove result exactly 3; polymorphic operation contracts remain missing. Fixed-region state functions are now available; state-easy is a different, weaker benchmark. |
| gui-interactor | main Figure 6; supplement section 5.1 | unported | Port nested file and GUI state-passing handlers, including rejection of invalid start/stop ordering. |

## Benchmark rows

The star marks the four rows the paper already identifies as adapted translations. A “dirty” reference means its current bytes differ from the recorded Lexa commit.

| Suite | Paper file | Expected | Status / port | Reference | Remaining work or caveat |
| --- | --- | --- | --- | --- | --- |
| ARM | `amb-1-SAT.lx` | accept | upstream_port [amb-1-SAT.effekt](../../examples/atm/arm/amb-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `amb-1-UNSAT.lx` | reject | upstream_port [amb-1-UNSAT.effekt](../../examples/atm/arm/amb-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `amb-2-SAT.lx` | accept | upstream_port [amb-2-SAT.effekt](../../examples/atm/arm/amb-2-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `amb-2-UNSAT.lx` | reject | upstream_port [amb-2-UNSAT.effekt](../../examples/atm/arm/amb-2-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `amb-3-SAT.lx` | accept | upstream_port [amb-3-SAT.effekt](../../examples/atm/arm/amb-3-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `amb-3-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `amb-3-simpl-SAT.lx` | accept | upstream_port [amb-3-simpl-SAT.effekt](../../examples/atm/arm/amb-3-simpl-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `bfs-SAT.lx` | accept | upstream_port [bfs-SAT.effekt](../../examples/atm/arm/bfs-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `bfs-UNSAT.lx` | reject | upstream_port [bfs-UNSAT.effekt](../../examples/atm/arm/bfs-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `bfs-simpl-SAT.lx`* | accept | upstream_port [bfs-simpl-SAT.effekt](../../examples/atm/arm/bfs-simpl-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `bfs-simpl-UNSAT.lx`* | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `choose-all-SAT.lx` | accept | upstream_port [choose-all-SAT.effekt](../../examples/atm/arm/choose-all-SAT.effekt) | matches HEAD | Local Lexa result list is reconstructed explicitly; branch/resumption structure needs an independent equivalence audit. |
| ARM | `choose-all-UNSAT.lx` | reject | upstream_port [choose-all-UNSAT.effekt](../../examples/atm/arm/choose-all-UNSAT.effekt) | matches HEAD | Local Lexa result list is reconstructed explicitly; branch/resumption structure needs an independent equivalence audit. |
| ARM | `choose-max-SAT.lx` | accept | upstream_port [choose-max-SAT.effekt](../../examples/atm/arm/choose-max-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `choose-max-UNSAT.lx` | reject | upstream_port [choose-max-UNSAT.effekt](../../examples/atm/arm/choose-max-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `choose-sum-SAT.lx` | accept | upstream_port [choose-sum-SAT.effekt](../../examples/atm/arm/choose-sum-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `choose-sum-UNSAT.lx` | reject | upstream_port [choose-sum-UNSAT.effekt](../../examples/atm/arm/choose-sum-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `deferred-1-SAT.lx` | accept | upstream_port [deferred-1-SAT.effekt](../../examples/atm/arm/deferred-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `deferred-1-UNSAT.lx` | reject | upstream_port [deferred-1-UNSAT.effekt](../../examples/atm/arm/deferred-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `deferred-2-SAT.lx` | accept | upstream_port [deferred-2-SAT.effekt](../../examples/atm/arm/deferred-2-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `deferred-2-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `distribution-SAT.lx` | accept | upstream_port [distribution-SAT.effekt](../../examples/atm/arm/distribution-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `distribution-UNSAT.lx` | reject | upstream_port [distribution-UNSAT.effekt](../../examples/atm/arm/distribution-UNSAT.effekt) | matches HEAD | Upstream filename says UNSAT, but its program/assertion equals the SAT file. The upstream port accepts; this does not validate the paper UNSAT row. |
| ARM | `expectation-SAT.lx` | accept | upstream_port [expectation-SAT.effekt](../../examples/atm/arm/expectation-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `expectation-UNSAT.lx` | reject | upstream_port [expectation-UNSAT.effekt](../../examples/atm/arm/expectation-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-read-1-SAT.lx` | accept | upstream_port [io-read-1-SAT.effekt](../../examples/atm/arm/io-read-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-read-1-UNSAT.lx` | reject | upstream_port [io-read-1-UNSAT.effekt](../../examples/atm/arm/io-read-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-read-2-SAT.lx` | accept | upstream_port [io-read-2-SAT.effekt](../../examples/atm/arm/io-read-2-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-read-2-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `io-read-3-SAT.lx` | accept | upstream_port [io-read-3-SAT.effekt](../../examples/atm/arm/io-read-3-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-read-3-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `io-write-1-SAT.lx` | accept | upstream_port [io-write-1-SAT.effekt](../../examples/atm/arm/io-write-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-write-1-UNSAT.lx` | reject | upstream_port [io-write-1-UNSAT.effekt](../../examples/atm/arm/io-write-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-write-2-SAT.lx` | accept | upstream_port [io-write-2-SAT.effekt](../../examples/atm/arm/io-write-2-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `io-write-2-UNSAT.lx` | reject | upstream_port [io-write-2-UNSAT.effekt](../../examples/atm/arm/io-write-2-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `modulus-SAT.lx`* | accept | upstream_port [modulus-SAT.effekt](../../examples/atm/arm/modulus-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `modulus-UNSAT.lx`* | reject | upstream_port [modulus-UNSAT.effekt](../../examples/atm/arm/modulus-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `queue-1-SAT.lx` | accept | upstream_port [queue-1-SAT.effekt](../../examples/atm/arm/queue-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `queue-1-UNSAT.lx` | reject | upstream_port [queue-1-UNSAT.effekt](../../examples/atm/arm/queue-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `queue-2-SAT.lx` | accept | upstream_port [queue-2-SAT.effekt](../../examples/atm/arm/queue-2-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `queue-2-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `round-robin-SAT.lx` | accept | upstream_port [round-robin-SAT.effekt](../../examples/atm/arm/round-robin-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `round-robin-UNSAT.lx` | reject | upstream_port [round-robin-UNSAT.effekt](../../examples/atm/arm/round-robin-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `safe-div-1-SAT.lx` | accept | upstream_port [safe-div-1-SAT.effekt](../../examples/atm/arm/safe-div-1-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `safe-div-1-UNSAT.lx` | reject | upstream_port [safe-div-1-UNSAT.effekt](../../examples/atm/arm/safe-div-1-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `safe-div-2-SAT.lx` | accept | upstream_port [safe-div-2-SAT.effekt](../../examples/atm/arm/safe-div-2-SAT.effekt) | matches HEAD | Local Lexa version uses a disjunctive None-or-Some contract; the Effekt port instead targets the exact upstream Some(n/m) property. |
| ARM | `safe-div-2-UNSAT.lx` | reject | upstream_port [safe-div-2-UNSAT.effekt](../../examples/atm/arm/safe-div-2-UNSAT.effekt) | matches HEAD | Local Lexa variant checks None-or-Some(n/m+1); the Effekt port retains the upstream assertion that the exact Some(n/m) equality test is false. |
| ARM | `select-SAT.lx` | accept | upstream_port [select-SAT.effekt](../../examples/atm/arm/select-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `select-UNSAT.lx` | reject | upstream_port [select-UNSAT.effekt](../../examples/atm/arm/select-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `shift-SAT.lx` | accept | upstream_port [shift-SAT.effekt](../../examples/atm/arm/shift-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `shift-UNSAT.lx` | reject | upstream_port [shift-UNSAT.effekt](../../examples/atm/arm/shift-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `state-SAT.lx` | accept | upstream_port [state-SAT.effekt](../../examples/atm/arm/state-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `state-UNSAT.lx` | reject | upstream_port [state-UNSAT.effekt](../../examples/atm/arm/state-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `state-easy-SAT.lx` | accept | upstream_port [state-easy-SAT.effekt](../../examples/atm/arm/state-easy-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `state-easy-UNSAT.lx` | reject | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| ARM | `transaction-SAT.lx` | accept | upstream_port [transaction-SAT.effekt](../../examples/atm/arm/transaction-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `transaction-UNSAT.lx` | reject | upstream_port [transaction-UNSAT.effekt](../../examples/atm/arm/transaction-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `yield-SAT.lx` | accept | upstream_port [yield-SAT.effekt](../../examples/atm/arm/yield-SAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| ARM | `yield-UNSAT.lx` | reject | upstream_port [yield-UNSAT.effekt](../../examples/atm/arm/yield-UNSAT.effekt) | matches HEAD | Paper translation equivalence not audited. |
| Lexa | `ackermann.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `bezout.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `catalan.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `countdown.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `fibonacci_recursive.lx` | accept | unported  | matches HEAD | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `golomb.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `handler_sieve.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `hofstadterq.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `interruptible_iterator.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `iterator.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `karatsuba.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `latticepath.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `listiter.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `logger.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `nqueens.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `palindrome_partition.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `product_early.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `resume_nontail.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `resume_nontail_2.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `tree_explore.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `triples.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
| Lexa | `two_threads_ackermann.lx` | accept | unported  | dirty | Port and audit the complete original handler behavior and contracts; no ATM port exists |
