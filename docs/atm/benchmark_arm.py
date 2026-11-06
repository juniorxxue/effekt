#!/usr/bin/env python3
"""Inspect inference, measure typechecking, or render the ARM/inference paper tables.

Run inspect and review its table before running time. Both use an exported
effektJVM/Test/fullClasspath. Rendering without a timing report shows 'pending'.
"""
import argparse
import collections
import datetime
import hashlib
import json
import os
import platform
import statistics
import subprocess
from pathlib import Path

from paper_coverage import compiler_digest

ROOT = Path(__file__).resolve().parents[2]
DOCS = Path(__file__).resolve().parent
RESULTS = DOCS / 'results'
INFERENCE = RESULTS / 'arm-inference.json'
TIMING = RESULTS / 'arm-timing.json'
RUNNER = ROOT / 'effekt/jvm/src/test/scala/effekt/AtmBenchmark.scala'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def notes(name):
    """Numbers are shared with the numbered paragraphs in supplement section 3.3."""
    result = []
    for prefix, number in [('bfs-', 1), ('modulus-', 2), ('shift-', 3),
                           ('round-robin-', 4), ('transaction-', 5)]:
        if name.startswith(prefix):
            result.append(number)
    if name.startswith(('amb-', 'io-read-', 'io-write-', 'bfs-')) or name == 'state-UNSAT.ml':
        result.append(6)
    if name.startswith('safe-div-'):
        result.append(7)
    if name.startswith('distribution-'):
        result.append(8)
    return result


def manifest(suite='arm'):
    entries = json.loads((DOCS / f'{suite}-manifest.json').read_text())['benchmarks']
    if suite == 'arm':
        assert len(entries) == 52
    else:
        assert {e['port'] for e in entries} == {
            p.relative_to(ROOT).as_posix() for p in (ROOT / 'examples/atm/infer').glob('*.effekt')}
    for e in entries:
        assert digest(ROOT / e['port']) == e['port_sha256'], e['port']
    return sorted(entries, key=lambda e: e['port'])


def validate_report(report, entries):
    assert report['compiler_sha256'] == compiler_digest(), 'Compiler changed; rerun inspection/measurement'
    assert report['runner_sha256'] == digest(RUNNER), 'Benchmark runner changed'
    rows = {r['file']: r for r in report['results']}
    assert set(rows) == {e['port'] for e in entries}
    for e in entries:
        row = rows[e['port']]
        assert row['source_sha256'] == e['port_sha256']
        expected = e.get('diagnostic_kind', 'accepted' if e.get('reviewed_expected', e['expected']) == 'accept' else 'ContractFailure')
        assert row['outcome'] == expected, (e['port'], row['outcome'])
    return rows


def query(command):
    result = subprocess.run(command, text=True, capture_output=True)
    return result.stdout.strip() if result.returncode == 0 else None


def run(args, entries):
    compiler_hash, runner_hash = compiler_digest(), digest(RUNNER)
    if args.action == 'time':
        validate_report(json.loads(INFERENCE.read_text()), entries)
    if args.classpath_file:
        cp = args.classpath_file.read_text().strip()
    else:
        build = subprocess.run(['sbt', '-batch', 'effektJVM/Test/compile',
                                'export effektJVM/Test/fullClasspath'],
                               cwd=ROOT, text=True, capture_output=True, timeout=900)
        log = RESULTS / f'{args.suite}-benchmark.build.log'
        log.write_text(build.stdout + build.stderr)
        if build.returncode:
            raise RuntimeError(f'Build failed; see {log}')
        candidates = [line.strip() for line in build.stdout.splitlines()
                      if line.startswith('/') and all(Path(p).exists() for p in line.strip().split(os.pathsep))]
        if not candidates:
            raise RuntimeError(f'No exported test classpath; see {log}')
        cp = candidates[-1]
    extra = [str(args.warmups), str(args.repetitions)] if args.action == 'time' else []
    command = ['java', '-cp', cp, 'effekt.AtmBenchmark', args.action, *extra, *[e['port'] for e in entries]]
    # stderr carries round progress; stdout contains one JSON record per source.
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, check=True)
    assert compiler_digest() == compiler_hash and digest(RUNNER) == runner_hash, 'Compiler or runner changed during collection; rerun'
    assert all(digest(ROOT / e['port']) == e['port_sha256'] for e in entries), 'Sources changed during collection; rerun'
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    sources = {e['port']: e for e in entries}
    for row in rows:
        e = sources[row['file']]
        row.update(source_sha256=e['port_sha256'])
        if args.suite == 'arm':
            row['fidelity_notes'] = notes(e['source'])
        if args.action == 'inspect':
            complete = row['outcome'] == 'accepted'
            row['evidence_inferred'] = True if complete and row['evidence_uses'] else None
            row['atcs_inferred'] = True if complete and row['operation_atcs'] else None
            row['inference_status'] = 'complete_elaboration' if complete else 'no_complete_elaboration'
            if row.get('operation_failure'):
                row['evidence_inferred'] = True
                row['atcs_inferred'] = False
                row['inference_status'] = 'operation_evidence_only'
            if args.suite == 'infer':
                if complete:
                    assert row['operation_distances'] == e['operation_distances'], e['port']
                    assert row['atc_depths'] == e['atc_depths'], e['port']
                else:
                    assert row['operation_failure']['distance'] is None and row['operation_failure']['residuals']
                    assert row['evidence_inferred'] and row['atcs_inferred'] is False
        else:
            assert len(row['samples_ms']) == args.repetitions
            row.update(median_ms=statistics.median(row['samples_ms']),
                       min_ms=min(row['samples_ms']), max_ms=max(row['samples_ms']))
    report = dict(schema_version=1, suite=args.suite, created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  compiler_sha256=compiler_hash, runner_sha256=runner_hash,
                  results=rows)
    if args.action == 'inspect':
        report['scope'] = ('Accepted programs: evidence/ATC certificates in validated Core. If an operation fails ATC inference, '
                           'the optional checker observation records the reconstructed witness and residuals for that failed attempt; '
                           'this partial result is not a complete elaboration. Other rejections have no inference-success claim.')
        destination = INFERENCE
    else:
        validate_report(json.loads(INFERENCE.read_text()), entries)
        report.update(warmup_rounds=args.warmups, measured_rounds=args.repetitions,
                      order='deterministic shuffle per round, seed 152 + round',
                      scope='Wall-clock Checker.check on parsed sources, including refinement solving and fresh checker caches; excludes parsing, JVM startup, Core lowering, and backend execution.',
                      environment=dict(os=platform.platform(), architecture=platform.machine(),
                                       cpu=query(['sysctl', '-n', 'machdep.cpu.brand_string']),
                                       memory_bytes=query(['sysctl', '-n', 'hw.memsize']),
                                       java=subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.strip(),
                                       z3_executable=os.environ.get('EFFEKT_ATM_Z3', 'z3'),
                                       z3=query([os.environ.get('EFFEKT_ATM_Z3', 'z3'), '--version'])))
        destination = TIMING
    validate_report(report, entries)
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(f"Wrote {destination}: {dict(collections.Counter(r['outcome'] for r in rows))}")


def render(entries, output, omit_timing=False):
    inference = validate_report(json.loads(INFERENCE.read_text()), entries)
    timing_report = json.loads(TIMING.read_text()) if TIMING.exists() and not omit_timing else None
    timing = validate_report(timing_report, entries) if timing_report else None
    def cells(e):
        row = inference[e['port']]
        name = Path(e['port']).stem
        markers = list(map(str, row['fidelity_notes']))
        if e['source'] == 'distribution-UNSAT.ml':
            markers.append(r'\dagger')
        superscript = r'$^{' + ','.join(markers) + '}$' if markers else ''
        elapsed = f"{timing[e['port']]['median_ms']:.1f}" if timing else r'\textit{pending}'
        return r'\texttt{' + name + '}' + superscript + f' & {elapsed}'
    lines = ['% Generated by docs/atm/benchmark_arm.py render; do not edit individual rows.',
             r'\begin{table}[p]', r'  \centering',
             r'  \caption{Typechecking time for the 52 ARM ports. Superscripts identify the adaptation notes in Section~\ref{sec:effekt-port-fidelity}.}',
             r'  \label{tab:effekt-results}', r'  \small',
             r'  \begin{tabular}{@{}lr@{\qquad\qquad}lr@{}}', r'    \toprule',
             r'    Program & Time (ms) & Program & Time (ms) \\', r'    \midrule']
    for left, right in zip(entries[:26], entries[26:]):
        lines.append('    ' + cells(left) + ' & ' + cells(right) + r' \\')
    lines += [r'    \bottomrule', r'  \end{tabular}', r'  \par\medskip',
              r'  \begin{minipage}{0.94\textwidth}', r'    \small',
              r'    The common \texttt{.effekt} suffix is omitted. Read down the left block,',
              r'    then down the right block. Timing includes time to reject false contracts.',
              (f"    Times are medians of {timing_report['measured_rounds']} runs after "
               f"{timing_report['warmup_rounds']} full-suite warm-up rounds." + r'\\[0.4em]' if timing
               else r'    Timing measurements are pending.\\[0.4em]'),
              r'    Superscripts 1--8 refer to the same numbered adaptation notes in',
              r'    Section~\ref{sec:effekt-port-fidelity}; multiple markers can apply.',
              r'    Unmarked programs have no additional family-specific caveat.\\[0.4em]',
              r'    $^{\dagger}$The upstream UNSAT-labeled distribution file duplicates its SAT',
              r'    assertion and therefore also checks successfully.',
              r'  \end{minipage}', r'\end{table}', '']
    output.write_text('\n'.join(lines))
    print(f'Wrote {output}; timing {"included" if timing else "pending"}.')


def render_infer(entries, output, omit_timing=False):
    inference = validate_report(json.loads(INFERENCE.read_text()), entries)
    report = json.loads(TIMING.read_text()) if TIMING.exists() and not omit_timing else None
    timing = validate_report(report, entries) if report else None
    lines = ['% Generated by docs/atm/benchmark_arm.py render --suite infer.',
             r'\begin{table}[htbp]', r'  \centering',
             r'  \caption{Nested-handler inference examples in \texttt{examples/atm/infer}.}',
             r'  \label{tab:effekt-inference}', r'  \small',
             r'  \begin{tabular}{@{}lrcc@{}}', r'    \toprule',
             r'    Program & Time (ms) & Evidence inferred & ATCs inferred \\', r'    \midrule']
    for e in entries:
        row = inference[e['port']]
        elapsed = f"{timing[e['port']]['median_ms']:.2f}" if timing else r'\textit{pending}'
        assert row['evidence_inferred'] is True
        partial = row['inference_status'] == 'operation_evidence_only'
        assert partial == (row['atcs_inferred'] is False)
        ev = r'$\checkmark^{\dagger}$' if partial else r'$\checkmark$'
        atc = r'$\times$' if partial else r'$\checkmark$'
        lines.append(r'    \texttt{' + Path(e['port']).stem + '} & ' + elapsed + f' & {ev} & {atc} ' + r'\\')
    lines += [r'    \bottomrule', r'  \end{tabular}', r'  \par\medskip',
              r'  \begin{minipage}{0.94\textwidth}', r'    \small',
              r'    The common \texttt{.effekt} suffix is omitted. A checkmark means that',
              r'    the corresponding inference step succeeds without explicit evidence/ATC arguments;',
              r'    a cross means ATC inference is undefined. Answer-type annotations are supplied.',
              r'    The five successful programs have validated Core certificates at operation',
              r'    evidence distances and ATC depths 0, 1, and 2.\\[0.4em]',
              r'    $^{\dagger}$Evidence reconstruction produces a residual witness with symbolic',
              r'    distance. ATC inference fails before a complete elaboration is produced.',
              r'    The time for this row is time to report that inference limitation.',
              (f"    Times are medians of {report['measured_rounds']} runs after {report['warmup_rounds']} "
               'full-suite warm-up rounds, using the method in Section~' + r'\ref{sec:effekt-benchmark-results}.'
               if timing else r'    Timing measurements are pending.'),
              r'  \end{minipage}', r'\end{table}', '']
    output.write_text('\n'.join(lines))
    print(f'Wrote {output}; timing {"included" if timing else "pending"}.')


def main():
    global INFERENCE, TIMING
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect', 'time', 'render'])
    parser.add_argument('--suite', choices=['arm', 'infer'], default='arm')
    parser.add_argument('--classpath-file', type=Path)
    parser.add_argument('--warmups', type=int, default=2)
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--omit-timing', action='store_true')
    args = parser.parse_args()
    INFERENCE = RESULTS / f'{args.suite}-inference.json'
    TIMING = RESULTS / f'{args.suite}-timing.json'
    entries = manifest(args.suite)
    if args.action == 'render':
        renderer = render if args.suite == 'arm' else render_infer
        renderer(entries, args.output or RESULTS / f'{args.suite}-table.tex', args.omit_timing)
    else:
        run(args, entries)


if __name__ == '__main__':
    main()
