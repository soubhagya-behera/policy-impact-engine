# Policy Impact Engine — Development Guide

This guide defines the engineering rules, quality expectations, and development workflow for the Policy Impact Engine. It applies to every contribution, regardless of who makes it.

The architecture is documented in [ARCHITECTURE.md](ARCHITECTURE.md), the current implementation status in [PROJECT_STATUS.md](PROJECT_STATUS.md), and architectural decisions in [DECISIONS.md](DECISIONS.md).

## Project Overview

| | |
| --- | --- |
| Stack | Java 17, Spring Boot 4.1.1, Maven |
| Database | PostgreSQL (database name: `policypulse`) |
| Architecture | Modular monolith, one Maven module, one deployable |
| Base package | `com.soubhagya.policyimpactengine` |
| Main class | `PolicyImpactEngineApplication` |

## Engineering Rules

1. Use a modular monolith. Do not split the application into microservices without explicit approval.
2. Do not introduce Kafka, RabbitMQ, Redis, AWS, Kubernetes, or similar infrastructure unless explicitly approved.
3. The deterministic analysis engine must work without AI. No LLM, paid API, or external AI service may be a dependency of the core pipeline.
4. Implement one phase at a time, as defined in ARCHITECTURE.md §31.
5. Implement one vertical slice at a time. A slice is complete only when it is runnable end to end.
6. Every phase must include appropriate tests.
7. Do not proceed to the next phase while the current phase is failing.
8. Run the relevant Maven tests after implementation (`./mvnw test`).
9. Keep controllers thin: parse input, delegate to a service, map the result to a response DTO.
10. Never expose JPA entities directly from REST APIs.
11. Use DTOs for every request and response body.
12. Validate all external input at the boundary.
13. Treat user-supplied URLs as untrusted input.
14. Implement SSRF protection before any external URL fetching is implemented.
15. Never log credentials, passwords, JWT secrets, or sensitive user data.
16. `application.properties` is local-only and Git-ignored. It must never be committed.
17. `application-example.properties` is the GitHub-safe configuration template. It contains placeholders only.
18. Database schema changes must use Flyway once Flyway is introduced (planned for Phase 1).
19. Do not manually modify the production-style database schema after Flyway is introduced. All schema evolution goes through versioned migrations.
20. Do not create empty placeholder classes for future features. Code exists only when the phase that needs it is implemented.
21. Keep deterministic engines (diff, concept matching, impact scoring, recommendations) as plain Java classes where practical, so they can be unit-tested without a Spring context.
22. Maintain strict user-data isolation. Every user-owned query filters by the authenticated principal.
23. Architectural changes must be recorded in DECISIONS.md before implementation.
24. Before changing an implementation, review ARCHITECTURE.md and PROJECT_STATUS.md to understand the approved design and current state.
25. After completing a phase, update PROJECT_STATUS.md.
26. Do not commit or push unless explicitly instructed.

## General Engineering Expectations

- **Meaningful naming.** Names state intent; no abbreviations that require context to decode.
- **Small, focused classes.** One responsibility per class; long methods are a signal to decompose.
- **Clear boundaries.** Package rules from ARCHITECTURE.md §7 are binding; no reaching into another module's internals.
- **Explicit validation.** Invalid input fails fast at the boundary with a precise error.
- **Predictable error handling.** RFC 7807 problem responses; no swallowed exceptions.
- **Testability.** Determinism, pure functions, and injected dependencies are design requirements, not afterthoughts.
- **No unnecessary abstraction.** Introduce an interface when there is a second implementation or a tested boundary, not by default.
- **No speculative infrastructure.** Nothing is built for a scale or feature that does not exist yet.
- **No dead code.** Unused classes, methods, and configuration are removed, not commented out.
- **No fake implementations.** No stubs pretending to be features, no hardcoded outputs standing in for real logic.
- **No overstated status.** Planned features are never described as implemented, in code comments, documentation, or API responses.

## Development Workflow

All implementation work follows the same workflow, in this order:

```
READ → UNDERSTAND → PLAN → IMPLEMENT ONLY REQUESTED SCOPE → TEST → REPORT → STOP
```

1. **READ.** Review ARCHITECTURE.md, DECISIONS.md, and PROJECT_STATUS.md, plus the code affected by the task.
2. **UNDERSTAND.** Confirm the task maps to the approved architecture and the current phase. If it does not, surface the discrepancy before writing code.
3. **PLAN.** State the slice to be built, the files involved, and the tests required before touching code.
4. **IMPLEMENT ONLY REQUESTED SCOPE.** Build the requested slice and nothing else. No placeholder classes, no speculative infrastructure, no dependency additions outside the phase's plan.
5. **TEST.** Run the relevant Maven tests (`./mvnw test`). Do not proceed while anything is failing.
6. **REPORT.** Summarize what was implemented, what was tested, and any deviations from the architecture.
7. **STOP.** End the task after reporting. New work requires a new, explicit request.

After a phase is completed and verified, update PROJECT_STATUS.md (see rule 25) and record any architectural decisions made along the way in DECISIONS.md.

## Build and Test

```bash
./mvnw test       # run the full test suite
./mvnw verify     # run the full lifecycle including integration checks
```

