# Contract validation

Use Python 3.13 and the pinned dependency set. From the repository root:

```sh
python3 -m venv .venv-contracts
.venv-contracts/bin/python -m pip install -r requirements-contracts.txt
.venv-contracts/bin/python -m unittest discover -s tests -v
.venv-contracts/bin/python scripts/validate_contracts.py
git diff --check
sh scripts/scan-secrets.sh history
```

`API contracts / validate` runs the same tests and standalone validator on pull
requests and pushes. Secret scanning stays separate. This contract-only issue
includes the Java tooling scaffold merged through
[api #9](https://github.com/SysKata-project/api/issues/9). The required
`Java verification` check runs alongside contract validation and secret scanning.
Follow [Java development and quality gates](development.md) for its local
verification command; this Python validator neither replaces nor weakens it.

The OpenAPI specification validator checks document structure and local schema
references. JSON Schema Draft 2020-12 with format checking validates positive and
negative HTTP fixtures against each operation's request, response, path parameters
and documented headers. Every operation needs a successful fixture. Tests also
cover answer-key/assistance leakage for all question types, forbidden client score
and lab-grade injection, malformed commands/IDs/versions/timestamps, missing
idempotency keys, polling headers, replay response equality, and separation of
infrastructure failure from grades.

Each fixture records `operation_id`, synthetic request/response, `valid`, and
optional explanatory notes. Fixtures are independent examples unless `replay_of`
explicitly relates them; error-state fixtures describe a different server
precondition from the happy path. `valid: false` means the validator must reject
at least one request/response component. A valid error response is not a negative
schema fixture. Authentication headers are deliberately omitted: fixture schema
validation does not authenticate real principals. No fixture is sent to a server.

The tests prove contract shape and the stated example invariants, not transaction,
network, identity, evaluator or OpenShift behavior. Contextual checks such as
membership of an option in its issued question, exact worker/assessment binding,
IANA timezone membership, semantic reward rules and state transitions remain
normative in [the contract](api-contract.md) and require implementation tests.

Review dependency updates as a locked set, rerun validation and inspect contract
compatibility. No Java tool choices, coverage threshold or new repository merge
rule is established here.

## Recorded validation

On 2026-11-06 with Python 3.13.0, all 10 contract tests passed; the standalone
validator accepted OpenAPI 3.1.1 with 21 operations and 59 positive/negative
fixtures. Local Markdown links and staged whitespace checks passed. Staged and
full-history secret scans passed. Synthetic fixture idempotency UUIDs use a
recognizable test pattern so they do not resemble credentials; no scanner
exceptions were added. CI results are attached to the pull request.

No running API, Keycloak, PostgreSQL or OpenShift environment was exercised by
these checks. Those runtime validations remain in the linked implementation
issues, as required by the scope of api #1.
