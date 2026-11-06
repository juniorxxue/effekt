# Ξ Effekt

## ATM research frontend in this checkout

This working tree contains a separate ATM frontend using `.effekt` files, based on
**Effekt v0.3.0**
(`cd18ad896b9f1c856a9d6002fd0a5733e5cb9a15`). It implements a subset of the paper's
region/evidence algorithm, with explicit fixed-region functions and effectful
handler-return forms. Its default backend is the adapted historical Lifted IR
and Chez. Select the ATM frontend through `effekt.atm.Main`.

For the complete benchmark outline in one file, read
[BENCHMARKS.md](docs/atm/BENCHMARKS.md). Start with the [ATM guide](docs/atm/README.md),
[artifact status](docs/atm/ARTIFACT-STATUS.md), and
[paper-fidelity boundaries](docs/atm/FIDELITY.md). The verified 2026-11-06 snapshot
has 58 accepted examples, 22 intentional negatives and three unsupported examples;
all 52 pinned upstream ARM files have ports, with one documented upstream label discrepancy. See
[example repairs](docs/atm/REJECTED-EXAMPLES.md),
[paper coverage](docs/atm/PAPER-COVERAGE.md), and
[validation](docs/atm/VALIDATION.md) for the distinct inventories and test results.

Run from this checkout with its pinned Kiama submodule, Java/sbt, Z3 and Chez
available. The full differential suite also requires Node:

```sh
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/arm/state-easy-SAT.effekt'
python3 docs/atm/check_examples.py
```

The first command prints `8`. These commands use the local implementation;
the upstream npm package below does not contain this ATM work. Clean-checkout
release packaging and CI validation remain unfinished.

## Upstream Effekt overview

Compared to other languages with effect handlers (and support for polymorphic effects) the Effekt language
aims to be significantly more lightweight in its concepts.


## Disclaimer: Use at your own risk

**Effekt is a research-level language. We are actively working on it and the language (and everything else) is very likely to change.**

Also, Effekt comes with no warranty and there are (probably) many bugs -- If this does not discourage you, feel free to
play with it and give us your feedback :)

## Upstream installation

The following is the historical upstream installation guide. It installs an
upstream release, not this v0.3.0-based research working tree. Use the local
commands above for ATM.

You need to have Java (>= 11) and Node (>= 12) and npm installed.

The recommended way of installing Effekt is by running:

```
npm install -g https://github.com/effekt-lang/effekt/releases/latest/download/effekt.tgz
```

Alternatively, you can download the `effekt.tgz` file from [another release](https://github.com/effekt-lang/effekt/releases) and then run

```
npm install -g effekt.tgz
```

This will make the `effekt` command globally available. You can start the Effekt REPL by entering:

```
> effekt
```

You can find more information about the Effekt language and how to use it on the website (<https://effekt-lang.org>).
