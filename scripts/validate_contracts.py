#!/usr/bin/env python3
"""Validate the OpenAPI document and HTTP request/response fixtures, offline."""
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import OpenAPIV31SpecValidator

ROOT = Path(__file__).resolve().parents[1]


def load_contract():
    return yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())


def resolve(document, value):
    if '$ref' not in value:
        return value
    reference = value['$ref']
    if not reference.startswith('#/'):
        raise ValueError('Only local references are permitted')
    target = document
    for part in reference[2:].split('/'):
        target = target[part.replace('~1', '/').replace('~0', '~')]
    return target


def schema_errors(document, schema, value):
    # Embed the components at the schema root for local JSON Schema references.
    validator = Draft202012Validator(
        {**schema, 'components': document['components']},
        format_checker=FormatChecker(),
    )
    return [f'{list(error.path)}: {error.message}'
            for error in validator.iter_errors(value)]


def operations(document):
    return {operation['operationId']: (path, method, operation)
            for path, methods in document['paths'].items()
            for method, operation in methods.items()}


def fixture_errors(document, fixture):
    _, method, operation = operations(document)[fixture['operation_id']]
    errors = []
    request = fixture['request']
    for parameter in operation.get('parameters', []):
        parameter = resolve(document, parameter)
        values = request['headers'] if parameter['in'] == 'header' else request['path_parameters']
        # HTTP header names are case insensitive.
        if parameter['in'] == 'header':
            values = {key.lower(): value for key, value in values.items()}
            name = parameter['name'].lower()
        else:
            name = parameter['name']
        if name not in values:
            if parameter.get('required'):
                errors.append(f'Missing {parameter["in"]} {name}')
        else:
            errors.extend(schema_errors(document, parameter['schema'], values[name]))
    if 'requestBody' in operation:
        if 'body' not in request:
            errors.append('Missing request body')
        else:
            schema = operation['requestBody']['content']['application/json']['schema']
            errors.extend(schema_errors(document, schema, request['body']))
    elif 'body' in request:
        errors.append(f'Unexpected body on {method}')
    response = fixture['response']
    definition = operation['responses'].get(str(response['status']))
    if definition is None:
        errors.append('Undocumented response status')
    else:
        definition = resolve(document, definition)
        schema = definition['content']['application/json']['schema']
        errors.extend(schema_errors(document, schema, response['body']))
        for name, header in definition.get('headers', {}).items():
            if name not in response['headers']:
                errors.append(f'Missing documented response header {name}')
            else:
                errors.extend(schema_errors(document, header['schema'], response['headers'][name]))
    return errors


def validate_all():
    document = load_contract()
    OpenAPIV31SpecValidator(document).validate()
    fixtures = [json.loads(path.read_text()) for path in sorted((ROOT / 'contracts/fixtures').glob('*.json'))]
    for fixture in fixtures:
        errors = fixture_errors(document, fixture)
        if fixture['valid'] == bool(errors):
            raise ValueError(f'{fixture["name"]}: expected valid={fixture["valid"]}; errors={errors}')
    covered = {f['operation_id'] for f in fixtures if f['valid'] and f['response']['status'] < 300}
    if covered != set(operations(document)):
        raise ValueError(f'Missing successful operation fixtures: {set(operations(document)) - covered}')
    print(f'Valid OpenAPI 3.1.1; {len(covered)} operations; {len(fixtures)} positive/negative fixtures')


if __name__ == '__main__':
    validate_all()
