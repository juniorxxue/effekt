#!/usr/bin/env python3
"""Validate, render, and execute the paper coverage inventory (Python stdlib only)."""
import argparse
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DOCS = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(manifest, lexa=None, papers=None):
    entries = manifest['benchmarks']
    assert manifest['schema_version'] == 1
    assert collections.Counter(e['suite'] for e in entries) == {'ARM': 59, 'Lexa': 22}
    assert len({(e['suite'], e['paper_file']) for e in entries}) == 81
    assert collections.Counter(e['expected'] for e in entries if e['suite'] == 'ARM') == {'accept': 30, 'reject': 29}
    upstream = json.loads((DOCS / 'arm-manifest.json').read_text())
    originals = {e['source']: e for e in upstream['benchmarks']}
    assert len(originals) == 52
    assert dict(collections.Counter(e['fidelity_class'] for e in originals.values())) == upstream['fidelity_audit']['counts']
    assert {e['upstream_source'] for e in entries if e['upstream_source']} == set(originals)
    assert {e['upstream_source'] for e in entries if e['status'] == 'upstream_port'} == {e['source'] for e in originals.values() if e['status'] == 'ported'}
    for e in entries:
        assert e['status'] in ('upstream_port', 'blocked', 'unported')
        expected = 'reject' if e['paper_file'].endswith('-UNSAT.lx') else 'accept'
        assert e['expected'] == expected, e['paper_file']
        if e['upstream_source']:
            original = originals[e['upstream_source']]
            assert original['expected'] == expected
            assert (e['status'] == 'upstream_port') == (original['status'] == 'ported')
            if e['status'] == 'upstream_port':
                assert e['port'] == original['port']
                assert e['port_fidelity_class'] == original['fidelity_class']
                assert e.get('reviewed_expected') == original.get('reviewed_expected')
                if e.get('reviewed_expected'):
                    assert e['upstream_source'] == 'distribution-UNSAT.ml' and e['reviewed_expected'] == 'accept'
                    assert e['label_discrepancy'] == original['label_discrepancy']
        if e['port']:
            assert digest(ROOT / e['port']) == e['port_sha256'], f"Port changed: {e['port']}"
        if lexa:
            assert digest(lexa / e['reference']['path']) == e['reference']['sha256'], f"Reference changed: {e['reference']['path']}"
    for example in manifest['examples']:
        for p in example['ports']:
            assert digest(ROOT / p) == example['port_sha256'][p], f'Example changed: {p}'
    if papers:
        for p in manifest['paper_inputs']:
            assert digest(papers / p['name']) == p['sha256'], f"Paper changed: {p['name']}"


def render(manifest):
    entries = manifest['benchmarks']
    counts = collections.Counter(e['status'] for e in entries)
    lines = ['# Archived submission coverage matrix', '',
        'For the single-file family outline, implemented contracts, current examples and run commands,',
        'start with [BENCHMARKS.md](BENCHMARKS.md). This matrix tracks the supplied, hashed submission PDFs,',
        'not the maintained supplement. The latter now reports the Effekt implementation and 52 ARM ports.', '',
        'Generated from [paper-coverage.json](paper-coverage.json); update that inventory and run',
        '`python3 docs/atm/paper_coverage.py --render` to regenerate.', '',
        manifest['inventory'], '',
        f"Current mapping: **{counts['upstream_port']} upstream ports**, **{counts['blocked']} blocked ports**, "
        f"**{counts['unported']} unported paper rows**. Upstream ports preserve the pinned coar assertions; "
        'equivalence to the archived Lexa translations has not been audited. These counts are not a reproduction of the archived Table 1. '
        '**One label discrepancy:** the pinned distribution UNSAT file equals its SAT counterpart apart from a comment; its port accepts and does not validate the paper UNSAT row.', '',
        'The candidate Lexa sources were read from a local working tree at commit ' +
        '`' + manifest['reference_checkout']['head'] + '`. Each path and SHA-256 is in the JSON. ' +
        f"{sum(e['reference']['differs_from_head'] for e in entries)} reference files differ from that commit. "
        'They are not assumed to be the submission snapshot, and no paper timings are reused.', '',
        'The pinned 52-file upstream inventory remains in [arm-manifest.json](arm-manifest.json). '
        'A machine run records unported/unsupported entries separately from contract rejections. '
        'See [ARTIFACT-STATUS.md](ARTIFACT-STATUS.md) for reproduction and remaining priorities.', '',
        '## Paper examples', '', '| Example | Location | Status | Remaining work |', '| --- | --- | --- | --- |']
    for e in manifest['examples']:
        ports = ', '.join(f'[{Path(p).name}](../../{p})' for p in e['ports'])
        lines.append(f"| {e['id']} | {e['paper']} | {e['status']}{(': ' + ports) if ports else ''} | {e['remaining']} |")
    lines += ['', '## Benchmark rows', '',
        'The star marks the four rows the paper already identifies as adapted translations. '
        'A “dirty” reference means its current bytes differ from the recorded Lexa commit.', '',
        '| Suite | Paper file | Expected | Status / port | Reference | Remaining work or caveat |',
        '| --- | --- | --- | --- | --- | --- |']
    for e in entries:
        port = f"[{Path(e['port']).name}](../../{e['port']})" if e['port'] else ''
        caveat = e.get('reference_caveat') or e['remaining'] or 'Paper translation equivalence not audited.'
        star = '*' if e['paper_table_starred'] else ''
        state = 'dirty' if e['reference']['differs_from_head'] else 'matches HEAD'
        lines.append(f"| {e['suite']} | `{e['paper_file']}`{star} | {e['expected']} | {e['status']} {port} | {state} | {caveat} |")
    return '\n'.join(lines) + '\n'


def compiler_digest():
    paths = []
    for directory in ['effekt/shared/src/main', 'effekt/jvm/src/main', 'libraries/chez/lift', 'kiama/shared/src/main', 'kiama/jvm/src/main']:
        paths.extend(p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in ('.scala', '.ss'))
    paths += [ROOT / 'build.sbt', ROOT / 'project/build.properties', ROOT / 'project/plugins.sbt']
    hashes = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(paths) if p.exists()}
    return hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()


def execute(manifest, output, classpath_file):
    output.parent.mkdir(parents=True, exist_ok=True)
    build_command = ['sbt', '-batch', 'effektJVM/compile', 'export effektJVM/Compile/fullClasspath']
    if classpath_file:
        classpath = classpath_file.read_text().strip()
    else:
        build = subprocess.run(build_command, cwd=ROOT, capture_output=True, text=True, timeout=900)
        log = output.with_suffix('.build.log')
        log.write_text(build.stdout + build.stderr)
        if build.returncode:
            raise RuntimeError(f'Build failed; see {log}')
        candidates = [line.strip() for line in build.stdout.splitlines()
                      if line.startswith('/') and all(Path(p).exists() for p in line.strip().split(os.pathsep))]
        if not candidates:
            raise RuntimeError(f'No exported JVM classpath; see {log}')
        classpath = candidates[-1]
    records = []
    for entry in manifest['benchmarks']:
        row = {k: entry[k] for k in ('suite', 'paper_file', 'expected', 'status', 'port')}
        if not entry['port']:
            row.update(outcome='unported', matches_expected=None)
        else:
            command = ['java', '-cp', classpath, 'effekt.atm.Main', '--check-json', entry['port']]
            try:
                result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=60)
                checked = json.loads(result.stdout)
                if checked['status'] not in ('accepted', 'rejected') or result.returncode != (0 if checked['status'] == 'accepted' else 1):
                    raise ValueError('Inconsistent checker exit status')
                row.update(check=checked, port_sha256=entry['port_sha256'])
                outcome = 'accepted' if checked['status'] == 'accepted' else checked['kind']
                correct = ((entry['expected'] == 'accept' and outcome == 'accepted') or
                           (entry['expected'] == 'reject' and outcome == 'ContractFailure'))
                reviewed = entry.get('reviewed_expected', entry['expected'])
                reviewed_correct = ((reviewed == 'accept' and outcome == 'accepted') or
                                    (reviewed == 'reject' and outcome == 'ContractFailure'))
                row.update(outcome=outcome, matches_expected=correct if entry['status'] == 'upstream_port' else None,
                           matches_reviewed_expectation=reviewed_correct if entry['status'] == 'upstream_port' else None)
                if entry.get('label_discrepancy'):
                    row.update(reviewed_expected=reviewed, label_discrepancy=entry['label_discrepancy'])
            except (ValueError, KeyError, subprocess.TimeoutExpired) as error:
                row.update(outcome='runner_error', message=str(error), matches_expected=False if entry['status'] == 'upstream_port' else None)
        records.append(row)
        print(f"{row['paper_file']}: {row['outcome']}", flush=True)
    solver = os.environ.get('EFFEKT_ATM_Z3', 'z3')
    try:
        solver_version = subprocess.run([solver, '--version'], capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        solver_version = str(error)
    report = {'schema_version': 1, 'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'inventory_sha256': digest(DOCS / 'paper-coverage.json'), 'compiler_sha256': compiler_digest(),
              'compiler_digest_scope': 'Main Scala sources, historical Chez lift runtime, Kiama main sources, build.sbt and project build/plugin definitions',
              'effekt_base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'kiama_commit': subprocess.check_output(['git', '-C', 'kiama', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'java_version': subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.strip(),
              'solver_command': solver, 'solver_version': solver_version,
              'build_command': build_command if not classpath_file else None,
              'classpath_supplied': bool(classpath_file),
              'scope': 'Checker acceptance only. Runtime differential tests are in AtmArmTests. Checking times include cold checker/solver startup and are not Table 1 performance comparisons.',
              'results': records}
    report['counts'] = dict(collections.Counter(r['outcome'] for r in records))
    report['validated_upstream_ports'] = sum(r['matches_expected'] is True for r in records)
    report['known_label_discrepancies'] = sum(bool(r.get('label_discrepancy')) for r in records)
    report['validated_reviewed_ports'] = sum(r.get('matches_reviewed_expectation') is True for r in records)
    report['failed_upstream_ports'] = sum(r.get('matches_reviewed_expectation', r['matches_expected']) is False for r in records)
    output.write_text(json.dumps(report, indent=2) + '\n')
    return report['failed_upstream_ports'] == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true', help='Regenerate PAPER-COVERAGE.md after validation')
    parser.add_argument('--run', action='store_true', help='Build and check all available ports; keep unported rows explicit')
    parser.add_argument('--lexa', type=Path, help='Also verify candidate Lexa source hashes')
    parser.add_argument('--papers', type=Path, help='Also verify the two paper PDF hashes')
    parser.add_argument('--classpath-file', type=Path, help='Reuse an exported, freshly compiled JVM classpath instead of building')
    parser.add_argument('--output', type=Path, default=DOCS / 'results/paper-coverage.json')
    args = parser.parse_args()
    manifest = json.loads((DOCS / 'paper-coverage.json').read_text())
    try:
        validate(manifest, args.lexa, args.papers)
        print('Validated 81 benchmark entries and 7 example groups.')
        if args.render:
            (DOCS / 'PAPER-COVERAGE.md').write_text(render(manifest))
        if args.run and not execute(manifest, args.output, args.classpath_file):
            return 1
    except (AssertionError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'Coverage check failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
