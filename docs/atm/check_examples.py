#!/usr/bin/env python3
"""Check every existing .effekt example; keep unsupported cases separate from negatives."""
import argparse
import collections
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys

from paper_coverage import ROOT, DOCS, compiler_digest, digest


ARM = json.loads((DOCS / 'arm-manifest.json').read_text())['benchmarks']
NEGATIVES = {Path(e['port']).relative_to('examples/atm').as_posix() for e in ARM
             if e['status'] == 'ported' and e.get('reviewed_expected', e['expected']) == 'reject'}
TYPE_NEGATIVES = {'file-protocol-invalid.effekt': 'A_Do: operation requires answer Open / pure, found Closed / pure'}
UNSUPPORTED = {'callback-under-handler.effekt', 'multiple-capabilities.effekt', 'infer/polymorphic-outer-call.effekt'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path,
                        help='Reuse an exported, freshly compiled JVM classpath instead of building')
    parser.add_argument('--output', type=Path, default=DOCS / 'results/examples.json')
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    build_command = ['sbt', '-batch', 'effektJVM/compile', 'export effektJVM/Compile/fullClasspath']
    try:
        if args.classpath_file:
            classpath = args.classpath_file.read_text().strip()
        else:
            build = subprocess.run(build_command, cwd=ROOT, capture_output=True, text=True, timeout=900)
            log = args.output.with_suffix('.build.log')
            log.write_text(build.stdout + build.stderr)
            if build.returncode:
                raise RuntimeError(f'Build failed; see {log}')
            candidates = [line.strip() for line in build.stdout.splitlines()
                          if line.startswith('/') and all(Path(p).exists() for p in line.strip().split(os.pathsep))]
            if not candidates:
                raise RuntimeError(f'No exported classpath; see {log}')
            classpath = candidates[-1]
        directory = ROOT / 'examples/atm'
        paths = sorted(directory.rglob('*.effekt'))
        names = {p.relative_to(directory).as_posix() for p in paths}
        if not (NEGATIVES | TYPE_NEGATIVES.keys() | UNSUPPORTED) <= names:
            raise RuntimeError('A named negative or unsupported fixture is missing')
        rows = []
        for path in paths:
            name = path.relative_to(directory).as_posix()
            expected = ('contract_rejection' if name in NEGATIVES else
                        'type_rejection' if name in TYPE_NEGATIVES else
                        'unsupported' if name in UNSUPPORTED else 'accepted')
            row = dict(file=path.relative_to(ROOT).as_posix(), sha256=digest(path), expected=expected)
            try:
                checked = subprocess.run(['java', '-cp', classpath, 'effekt.atm.Main', '--check-json', str(path)],
                                         cwd=ROOT, capture_output=True, text=True, timeout=60)
                check = json.loads(checked.stdout)
                if check['status'] not in ('accepted', 'rejected') or checked.returncode != (0 if check['status'] == 'accepted' else 1):
                    raise ValueError('Inconsistent checker exit status')
                outcome = ('accepted' if check['status'] == 'accepted' else
                           'contract_rejection' if check['kind'] == 'ContractFailure' else
                           'type_rejection' if check['kind'] == 'TypeError' and check.get('message') == TYPE_NEGATIVES.get(name) else
                           'unsupported' if check['kind'] == 'Unsupported' else check['kind'])
                row.update(check=check, outcome=outcome, matches_inventory=outcome == expected)
            except (ValueError, KeyError, subprocess.TimeoutExpired) as error:
                row.update(outcome='runner_error', message=str(error), matches_inventory=False)
            rows.append(row)
            print(f"{name}: {row['outcome']}", flush=True)
        report = dict(schema_version=1, created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      compiler_sha256=compiler_digest(), build_command=None if args.classpath_file else build_command,
                      classpath_supplied=bool(args.classpath_file),
                      scope='All existing examples/atm/**/*.effekt files. Unsupported entries are limitations, not successful negative tests. Runtime checks are separate.',
                      counts=dict(collections.Counter(r['outcome'] for r in rows)),
                      inventory_mismatches=sum(not r['matches_inventory'] for r in rows), results=rows)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report['counts'], sort_keys=True))
        return 1 if report['inventory_mismatches'] else 0
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'Example check failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
