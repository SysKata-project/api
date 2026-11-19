#!/usr/bin/env python3
"""Validate actual integration-test responses against the unchanged OpenAPI contract."""
import json
from pathlib import Path
from validate_contracts import load_contract, operations, resolve, schema_errors

root = Path(__file__).resolve().parents[1] / 'target/learning-contracts'
document = load_contract()
for name in ('listJourneys', 'enroll', 'getJourney', 'startSession', 'getSession'):
    response = json.loads((root / (name + '.json')).read_text())
    operation = operations(document)[name][2]
    schema = resolve(document, operation['responses']['200'])['content']['application/json']['schema']
    errors = schema_errors(document, schema, response['body'])
    if errors:
        raise SystemExit(name + ': ' + '\n'.join(errors))
    print(name + ': valid runtime response')
