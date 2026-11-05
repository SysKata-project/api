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

The [web app](https://github.com/SysKata-project/web) consumes application contracts. The [labs component](https://github.com/SysKata-project/labs) provisions environments and executes assessments. The [content repository](https://github.com/SysKata-project/content) supplies versioned learning definitions. This service owns application state; lab execution and authored curriculum belong to their respective components.

## Proposed first milestone

Support one learner's lesson attempt from session creation through assessment and saved progress. Define session states, result formats, error handling, and content version references before connecting the components.

## Development and contribution

No backend framework, database, authentication provider, or API contract has been selected. There are no runnable services yet. Proposals should explain expected behavior and data ownership, including how retries avoid duplicate progress updates. Record cross-component decisions in [docs](https://github.com/SysKata-project/docs).

## Status

Planning scaffold only; listed capabilities are planned. No license has been selected yet.
