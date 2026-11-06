# SysKata API

The application backend for SysKata's daily technical learning experience.

## Intended scope

- Learner profiles and certification path enrollment.
- Lesson progression, attempts, and saved learning progress.
- Practice session orchestration through the lab component.
- Assessment result recording and feedback delivery.
- Review scheduling based on skills needing reinforcement.
- Daily goals, streaks, and achievement rules.

## Component boundaries

The [Flutter app](https://github.com/SysKata-project/app) consumes application contracts. The [labs component](https://github.com/SysKata-project/labs) provisions environments and executes assessments. The [content repository](https://github.com/SysKata-project/content) supplies versioned learning definitions. This service owns application state; lab execution and authored curriculum belong to their respective components.

## Proposed first milestone

Support one learner's lesson attempt from session creation through assessment and saved progress. Define session states, result formats, error handling, and content version references before connecting the components.

## Development and contribution

The backend baseline is Java 25 LTS, Quarkus and PostgreSQL, with self-hosted
Keycloak selected for identity. The current scaffold validates development tools
only; authentication, persistence and application endpoints are not implemented.
The [OpenAPI contract](contracts/openapi.yaml) specifies the learner and internal
assessment APIs.

See [Java development and quality gates](docs/development.md) for setup, rules,
reports and the complete verification command:

```sh
./mvnw -B -ntp clean verify
```

Run the separate [contract validation checks](docs/contract-validation.md) when
changing the OpenAPI document, fixtures or contract validator.

## Implementation design

- [Learning engine design](docs/learning-engine.md)
- [API contract and lifecycle rules](docs/api-contract.md)
- [Contract validation and CI](docs/contract-validation.md)

## Status

Minimal Java/Quarkus tooling scaffold; listed application capabilities are planned.

## MVP implementation tracking

See the [OpenShift MVP tracker](https://github.com/SysKata-project/docs/issues/1) and [cross-repository backlog](https://github.com/SysKata-project/docs/blob/main/plans/mvp-backlog.md).

## License

Copyright 2026 SysKata contributors.

This repository, including its code, documentation, and learning content, is licensed under the [Apache License 2.0](LICENSE).
