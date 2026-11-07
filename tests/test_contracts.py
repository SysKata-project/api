"""Contract guards; these do not simulate a running API or database."""
import copy
import json
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from validate_contracts import (ROOT, fixture_errors, load_contract, operations,
                                schema_errors, validate_all)


class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = load_contract()
        cls.fixtures = {p.stem: json.loads(p.read_text())
                        for p in (ROOT / 'contracts/fixtures').glob('*.json')}

    def test_document_and_all_fixtures(self):
        validate_all()

    def test_replay_preserves_original_result(self):
        original = self.fixtures['submitAttempt-success']
        replay = self.fixtures['duplicate-submission']
        self.assertEqual(original['request'], replay['request'])
        self.assertEqual(original['response'], replay['response'])

    def test_all_question_variants_reject_trusted_material(self):
        forbidden = ('assessment', 'correct_option_id', 'correct_order',
                     'accepted_commands', 'parameters', 'setup_profile',
                     'environment_profile', 'reveal_markdown', 'feedback', 'hints')
        for kind in ('single_choice', 'command_order', 'command_entry', 'lab_task'):
            question = self.fixtures['question-' + kind]['response']['body']['current_instance']['question']
            for key in forbidden:
                with self.subTest(kind=kind, key=key):
                    leaked = {**question, key: 'must not escape'}
                    self.assertTrue(schema_errors(self.document,
                        {'$ref': '#/components/schemas/Question'}, leaked))

    def test_mutations_require_idempotency_and_authentication(self):
        for _, method, operation in operations(self.document).values():
            self.assertNotEqual(operation.get('security'), [])
            if method != 'get':
                self.assertIn({'$ref': '#/components/parameters/IdempotencyKey'},
                              operation['parameters'])
        callback = operations(self.document)['deliverResult'][2]
        self.assertEqual(callback['security'], [{'WorkerBearer': []}])
        self.assertEqual(self.document['security'], [{'LearnerBearer': []}])

    def test_infrastructure_and_superseded_states_cannot_carry_grades(self):
        for name in ('infrastructure_error', 'expired', 'superseded'):
            fixture = copy.deepcopy(self.fixtures['assessment-' + name])
            fixture['response']['body']['result'] = self.fixtures['submitAttempt-success']['response']['body']
            self.assertTrue(fixture_errors(self.document, fixture))
        failure = copy.deepcopy(self.fixtures['trusted-infrastructure-failure'])
        failure['request']['body']['criteria'] = [{'criterion_id': 'criterion.ready', 'passed': False}]
        self.assertTrue(fixture_errors(self.document, failure))

    def test_assistance_is_retained_on_resume_and_visible_on_success(self):
        resumed = self.fixtures['resumed-assisted-attempt']['response']['body']['current_attempt']
        self.assertTrue(resumed['assistance']['revealed'])
        assisted = self.fixtures['assisted-success']['response']['body']
        self.assertEqual(assisted['evidence'], 'assisted')
        self.assertEqual(assisted['rewards'][0]['xp'], 10)

    def test_unsupported_or_malformed_inputs_fail(self):
        fixture = copy.deepcopy(self.fixtures['submitAttempt-success'])
        for field, value in [('session_revision', 0), ('exercise_instance_id', '../other')]:
            changed = copy.deepcopy(fixture)
            changed['request']['body'][field] = value
            self.assertTrue(fixture_errors(self.document, changed))
        fixture['request']['headers']['Idempotency-Key'] = 'not-a-uuid'
        self.assertTrue(fixture_errors(self.document, fixture))

    def test_accepted_assessment_has_polling_headers(self):
        fixture = self.fixtures['requestAssessment-success']
        self.assertEqual(fixture['response']['status'], 202)
        self.assertEqual(fixture['response']['headers']['Location'],
                         fixture['response']['body']['status_url'])
        changed = copy.deepcopy(fixture)
        del changed['response']['headers']['Location']
        self.assertTrue(fixture_errors(self.document, changed))

    def test_response_state_invariants(self):
        examples = [
            ('getSession-success', lambda body: body.update(current_instance=None)),
            ('getLab-success', lambda body: body.update(expires_at=None)),
            ('assisted-success', lambda body: body.update(evidence='independent')),
            ('mistake', lambda body: body.update(rewards=[{
                'event_id': 'reward-invalid', 'type': 'lesson', 'subject_id': 'lesson.example',
                'xp': 10, 'credited_date': '2026-11-06', 'timezone': 'America/Denver'}])),
            ('getAttempt-success', lambda body: body.update(result=None)),
        ]
        for name, mutate in examples:
            with self.subTest(name=name):
                fixture = copy.deepcopy(self.fixtures[name])
                mutate(fixture['response']['body'])
                self.assertTrue(fixture_errors(self.document, fixture))

    def test_no_remote_schema_resolution(self):
        def walk(value):
            if isinstance(value, dict):
                if '$ref' in value:
                    self.assertTrue(value['$ref'].startswith('#/'))
                for nested in value.values():
                    walk(nested)
            elif isinstance(value, list):
                for nested in value:
                    walk(nested)
        walk(self.document)


if __name__ == '__main__':
    unittest.main()
