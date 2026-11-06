# Migration to Effekt v0.3.0

This is the **migration record**, not the current feature or benchmark inventory.
Subsequent work added direct Lifted lowering, datatypes, local functions and
explicit effectful return clauses. See [the current guide](README.md),
[benchmark overview](BENCHMARKS.md) and [validation](VALIDATION.md).

The ATM work is now based on `v0.3.0`, commit
`cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`, on branch `codex/atm-v0.3.0`.
Kiama is checked out at the revision recorded by that commit:
`ab6aef78fc4ce99a9ad2db8555718d00b03f7b8a`.

The original work consisted of uncommitted changes on `dev`, at
`03570ede5fff01268c6eb0276c0ea4d4f924c2c4`. It was preserved and reapplied to the
older revision, with compatibility fixes, rather than rebasing upstream commits.
The migrated changes remain uncommitted for review.

## Preservation

All 62 originally changed or untracked files were saved, including the ATM
checker, both platform implementations, tests, examples, documentation and native
compiler fixes. No original file was dropped. Historical prototype code and
unsupported examples remain archived or available as before.

The original state is retained in Git stash
`984dc941f349939b05eb511e6b23f24ec3d9ee94`, with message
`codex: preserve all changes before v0.3.0 migration`. It has not been popped.
The `dev` branch still points to its original commit.

An independent archive, patches, SHA-256 manifest and migration logs are in
`/private/tmp/effekt-v0.3.0-migration-20261106/`. The archive is
`working-changes.tar.gz`; `snapshot.json` records the original file hashes.
The former Kiama revision was `9756d3b2977c44e088ba21fb2d539c7339c24d6c`;
that checkout was clean before switching to the pinned revision.

## Compatibility changes at migration time

- The ATM inference, evidence reconstruction, residual obligations, partial ATC,
  refinements, polymorphism and source parser were carried over unchanged.
- The existing source emitter now uses v0.3.0's `extern pure`/`extern io` syntax
  and opaque extern types. Its JavaScript implementation still uses `bigint`.
  Impossible answer-tag branches explicitly throw an error through an IO extern.
- Generated statement blocks are spliced into statement positions: the older
  parser does not accept a nested bare block in a lambda body, sequence tail or
  match clause. Capture annotations on boxes use `box {captures} ...`; boolean
  negation uses `not`. Boolean conjunction/disjunction use conditionals after
  evaluating their arguments, avoiding an undefined-local failure observed with
  v0.3.0's thunk-based operators and inlining. Pair adapters bind converted
  fields before passing them as arguments.
- The inferred block-parameter capture fix was ported to the older `Typer`.
  The JavaScript test harness now supports the existing regression's request to
  run without optimization.
- The annotation identity fix was ported to v0.3.0's separate `HashKey` and
  `IdKey` representation. Identity keys compare object identity, while structural
  keys keep structural equality. The identity-key implementation is package-visible
  so the deterministic collision regression can exercise it. Both key modes are
  covered by tests.

## Lifted and the compilation boundary

This revision restores `effekt.lifted`, including `LiftInference`, its IR,
pretty-printer and monomorphization, plus the `ml` and `chez-lift` backends.
The `v0.4.0` tree no longer contains `effekt.lifted`.

At the completion of the version migration, the ATM execution route was:

```
ATM surface
  -> paper-algorithm checking and evidence-annotated checked tree
  -> answer packaging and v0.3.0 Effekt source
  -> native Core
  -> JavaScript
```

The subsequent [direct Lifted implementation](DIRECT-LIFTED.md) now replaces
that default route with typed ATM Core -> ATM-adapted Lifted -> Chez-Lift.
`--elaborate` prints our separate region/evidence Core, and `--lifted` prints
the actual Lifted IR. The legacy source emitter remains available for comparison.
Restoring Lifted alone did not establish ATM support; the new handler form and
direct lowering are additional changes after the migration.

See [VALIDATION.md](VALIDATION.md) for current checks on this base. The
[migration-only validation](history/v0.3.0-migration-validation.md) and earlier
[paper-algorithm validation](history/paper-algorithm-validation.md) are preserved
with their original stage-specific counts and acceptance boundaries.
