#!/usr/bin/env python3
"""Prove gate rejection in disposable copies; never mutate the working tree."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'target/gate-evidence'
EVIDENCE.mkdir(parents=True, exist_ok=True)
PROBE = Path('src/main/java/org/syskata/api/scaffold/ToolingProbe.java')
TEST = Path('src/test/java/org/syskata/api/scaffold/ToolingProbeTest.java')
summary = []


def run(case, directory, goals, expected=None):
    command = [str(directory / 'mvnw'), '-B', '-ntp']
    if os.environ.get('GATE_MAVEN_REPO'):
        command.append('-Dmaven.repo.local=' + os.environ['GATE_MAVEN_REPO'])
    command.extend(goals)
    result = subprocess.run(command, cwd=directory, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=600)
    (EVIDENCE / (case + '.log')).write_text(result.stdout)
    passed = (result.returncode == 0 if expected is None else
              result.returncode != 0 and all(x.lower() in result.stdout.lower() for x in expected))
    summary.append({'case': case, 'expected': expected or 'success',
                    'exit_code': result.returncode, 'passed': passed})
    (EVIDENCE / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(case + ': ' + ('PASS' if passed else 'FAIL'), flush=True)
    if not passed:
        raise SystemExit('Unexpected outcome; inspect target/gate-evidence/' + case + '.log')


with tempfile.TemporaryDirectory(prefix='syskata-gates-') as tmp:
    directory = Path(tmp) / 'api'
    directory.mkdir()
    for name in ['pom.xml', 'mvnw', '.mvn', 'config', 'src']:
        source, destination = ROOT / name, directory / name
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)
    original = (directory / PROBE).read_text()
    original_test = (directory / TEST).read_text()
    run('baseline', directory, ['clean', 'verify'])

    (directory / PROBE).write_text(original.replace('return "ready";', 'return missingSymbol;'))
    run('compiler', directory, ['compile'], ['COMPILATION ERROR', 'missingSymbol'])
    (directory / PROBE).write_text(original)

    (directory / TEST).write_text(original_test.replace('assertEquals("waiting"', 'assertEquals("wrong"'))
    run('test-assertion', directory, ['test'], ['Failures: 1'])
    (directory / TEST).write_text(original_test)

    # Each test covers a different branch. Both isolated runs must fail the
    # combined gate; this also detects loss of either instrumentation path.
    for test in ['ToolingProbeTest', 'ToolingProbeQuarkusTest']:
        run('coverage-' + test, directory, ['clean', 'verify', '-Dtest=' + test],
            ['coverage checks have not been met', 'branches covered ratio'])

    # Add an entirely untested class, proving the denominator includes code
    # never loaded by tests, rather than only instrumented/covered classes.
    uncovered = directory / PROBE.parent / 'UncoveredProbe.java'
    uncovered.write_text('package org.syskata.api.scaffold;\nclass UncoveredProbe {\n'
                         + ''.join('  int value' + str(i) + '() { return ' + str(i) + '; }\n'
                                   for i in range(30)) + '}\n')
    run('line-coverage', directory, ['clean', 'verify'],
        ['coverage checks have not been met', 'lines covered ratio'])
    uncovered.unlink()

    (directory / PROBE).write_text(original.replace('if (ready)', 'int discardedCount = 1;\n    if (ready)'))
    run('pmd', directory, ['pmd:check'], ['UnusedLocalVariable'])
    (directory / PROBE).write_text(original)

    duplicate = 'package org.syskata.api.scaffold;\nclass Duplicate%s {\n int calculate(int value) {\n'
    body = ''.join('value += ' + str(i) + ';\n' for i in range(35)) + 'return value;\n}\n}\n'
    duplicate_paths = [directory / PROBE.parent / ('Duplicate' + suffix + '.java')
                       for suffix in ['One', 'Two']]
    for path, suffix in zip(duplicate_paths, ['One', 'Two']):
        path.write_text(duplicate % suffix + body)
    run('cpd', directory, ['pmd:cpd-check'], ['duplication'])
    for path in duplicate_paths:
        path.unlink()

    (directory / PROBE).write_text(original.replace('describe(', 'Describe('))
    run('checkstyle', directory, ['checkstyle:check'], ['MethodName'])
    (directory / PROBE).write_text(original.replace('if (ready) {', 'if(ready){'))
    run('format', directory, ['spotless:check'], ['format violations'])
    run('format-fix', directory, ['spotless:apply'])
    if (directory / PROBE).read_text() != original:
        raise SystemExit('Formatter did not restore the expected source')
    run('corrected', directory, ['clean', 'verify'])
