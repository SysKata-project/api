#!/usr/bin/env python3
"""Validate actual integration-test responses against the versioned OpenAPI contract."""
import json
from pathlib import Path
from validate_contracts import load_contract, operations, resolve, schema_errors

root = Path(__file__).resolve().parents[1] / 'target/learning-contracts'
document = load_contract()
for name in ('listJourneys', 'enroll', 'getJourney', 'startSession', 'getSession', 'startAttempt', 'requestHint', 'revealAnswer', 'submitAttempt', 'getAttempt', 'getProgress', 'updateTimezone'):
    response = json.loads((root / (name + '.json')).read_text())
    operation = operations(document)[name][2]
    schema = resolve(document, operation['responses']['200'])['content']['application/json']['schema']
    errors = schema_errors(document, schema, response['body'])
    if errors:
        raise SystemExit(name + ': ' + '\n'.join(errors))
    print(name + ': valid runtime response')

covered_labs = set()
for path in sorted((root.parent / 'lab-contracts').glob('*.json')):
    response = json.loads(path.read_text())
    covered_labs.add(response['operation'])
    operation = operations(document)[response['operation']][2]
    schema = resolve(document, operation['responses'][str(response['status'])])['content']['application/json']['schema']
    errors = schema_errors(document, schema, response['body'])
    if errors:
        raise SystemExit(response['operation'] + ': ' + '\n'.join(errors))
    print(response['operation'] + ': valid runtime lab response')

required_labs = {'createLab', 'getLab', 'requestAssessment', 'getAssessment', 'deliverResult', 'getAttempt'}
if not required_labs <= covered_labs:
    raise SystemExit('Missing lab runtime responses: ' + str(required_labs - covered_labs))
