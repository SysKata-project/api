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

The MVP baseline uses PostgreSQL and a modular API. Backend framework, authentication provider and formal OpenAPI contracts remain to be selected. There are no runnable services yet. Proposals should explain expected behavior and data ownership, including how retries avoid duplicate progress updates. Record cross-component decisions in [docs](https://github.com/SysKata-project/docs).

## Implementation design

- [Learning engine and proposed contracts](docs/learning-engine.md)

## Status

Planning scaffold only; listed capabilities are planned.

## MVP implementation tracking

See the [OpenShift MVP tracker](https://github.com/SysKata-project/docs/issues/1) and [cross-repository backlog](https://github.com/SysKata-project/docs/blob/main/plans/mvp-backlog.md).

## License

Copyright 2026 SysKata contributors.

This repository, including its code, documentation, and learning content, is licensed under the [Apache License 2.0](LICENSE).
