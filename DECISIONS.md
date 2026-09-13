# Policy Impact Engine — Architecture Decision Records

This file records the architectural decisions of the Policy Impact Engine in Architecture Decision Record (ADR) style. Each decision distinguishes Status, Context, Decision, and Consequences.

Decisions listed here are approved. Proposals that have not been approved are marked explicitly as **PROPOSED** and must not be treated as part of the architecture until their status changes.

---

## ADR-001 — Modular Monolith

**Status:** Accepted

**Context:** The system consists of a deterministic analysis pipeline with several stages, a REST API, scheduled monitoring, and persistence. The immediate scale is a single deployable application; distributing the pipeline across services would add operational cost (inter-service contracts, transport, deployment coordination) before any scale requirement justifies it.

**Decision:** Use a single Spring Boot modular monolith: one application, one deployable, one Maven module, with clear internal module/package boundaries and a one-direction dependency rule (see ARCHITECTURE.md §6–7).

**Consequences:**

- Simple deployment, transactional consistency, and direct in-process calls between modules.
- Module boundaries are enforced by convention (and later by tests) rather than by process separation; discipline is required to prevent boundary erosion.
- Extraction into separate services remains possible later because the module boundaries already exist; this is a FUTURE extension point, not a plan.

---

## ADR-002 — Deterministic Core

**Status:** Accepted

**Context:** The core value of the product is a trustworthy, explainable, per-user impact assessment. Analysis outcomes must be reproducible for the same inputs and must not depend on an external AI service whose behavior can change, incur per-call cost, or be unavailable.

**Decision:** Policy analysis and impact scoring must work without an LLM. The deterministic pipeline (Fetch → Normalize → Section → Fingerprint → Diff → Classify → Concept Match → Impact → Personalized Assessment → Recommendation → Notification) is authoritative. Any AI integration is limited to an advisory explanation layer.

**Consequences:**

- Reproducibility, testability, and predictable behavior; no mandatory paid AI dependency.
- Classification quality is bounded by the deterministic rules and the concept taxonomy; improving nuance means extending rules and concepts, not adjusting a model.
- A local LLM may be added later for explanations only, without changing any persisted assessment.

---

## ADR-003 — Immutable Policy Versions

**Status:** Accepted

**Context:** The product's value depends on historical policy states being trustworthy: impact assessments and recommendations must remain reproducible against the exact policy text that produced them. Mutable version records would silently invalidate past analyses.

**Decision:** Policy versions are append-only and immutable. A new observation of a policy creates a new version record; existing versions and their sections are never updated or deleted.

**Consequences:**

- Full auditability: every assessment points to an exact, stable policy text.
- Storage grows monotonically; the trade-off is accepted at the current scale, and compression or archival is a documented later optimization.
- Corrections happen by adding new versions with better data, never by editing history.

---

## ADR-004 — PostgreSQL

**Status:** Accepted

**Context:** The domain has strong relational requirements: policies, versions, sections, changes, concepts, matches, preferences, assessments, recommendations, fetch attempts, notifications, and audit events are all interrelated and require transactional consistency. Concurrency control for scheduled fetching also relies on database-level guarantees.

**Decision:** PostgreSQL is the primary relational database. The existing database `policypulse` is retained; it is not renamed in the current phase.

**Consequences:**

- Transactional integrity, rich indexing, and reliable concurrency primitives (constraints, atomic updates) are available without additional infrastructure.
- Testing uses real PostgreSQL via Testcontainers rather than an in-memory substitute, because versioning and concurrency behavior must be verified against the actual engine.

---

## ADR-005 — Flyway

**Status:** Accepted

**Context:** A production-style project must not rely on Hibernate schema generation. Schema evolution needs to be explicit, reviewable, and reproducible across environments, and the current configuration deliberately keeps `spring.jpa.hibernate.ddl-auto=none`.

**Decision:** Database schema changes are managed through Flyway versioned migrations, introduced at the start of Phase 1 with the first real schema (V1: policy table). From that point, `ddl-auto` changes to `validate` and remains there.

**Consequences:**

- Every schema change is a versioned, code-reviewed migration; drift between entities and schema fails validation instead of accumulating silently.
- No manual schema edits are permitted after Flyway is introduced.
- Migration files become part of the permanent history, aligned with ADR-003's append-only philosophy.

---

## ADR-006 — Optional Ollama (Local LLM)

**Status:** Accepted (as an optional, advisory layer only)

**Context:** Deterministic assessments can be precise but terse. A locally hosted LLM can translate a persisted assessment into natural-language explanations without sending data to third-party AI services.

**Decision:** A local LLM (Ollama) may be integrated as an optional advisory/explanation layer. It reads existing deterministic results and produces human-readable explanations. It never produces, alters, or overrides scores, classifications, or recommendations, and the deterministic engine remains the source of truth.

**Consequences:**

- Improved explainability without introducing a dependency of the deterministic analysis engine.
- The system functions fully when the LLM is absent, uninstalled, or unreachable; explanations are best-effort.
- Requires a local model runtime at deployment time when enabled; it remains an optional, clearly separated module (Phase 12).

---

## ADR-007 — Incremental Vertical Slices

**Status:** Accepted

**Context:** The full domain model spans more than a dozen concepts. Building all entities up front would produce a large, untested skeleton and delay a runnable system.

**Decision:** Implement one phase at a time and one vertical slice at a time. Each slice is complete, tested, and committed before the next begins. No entity, controller, service, or repository is created merely because the architecture documents it.

**Consequences:**

- Every milestone is runnable, testable, and reviewable; regressions are localized to a single slice.
- The repository never contains non-functional scaffolding, and early phases may refactor when a later slice reveals a better boundary.
- Delivery pace is disciplined by phase scope (ARCHITECTURE.md §31).

---

## ADR-008 — Repository-Level Source of Truth

**Status:** Accepted

**Context:** Development spans multiple sessions and environments. Architecture, decisions, and implementation status must remain available and authoritative independent of any particular tool or session state.

**Decision:** Architecture and current project state are stored in version-controlled Markdown documentation at the repository root: ARCHITECTURE.md (architecture), DEVELOPMENT.md (engineering rules and workflow), PROJECT_STATUS.md (current status), and DECISIONS.md (decision records).

**Consequences:**

- Persistent technical context available to any developer, reviewer, or maintainer without external tooling.
- Documentation must be kept accurate: PROJECT_STATUS.md is updated after each phase, and architectural changes require a DECISIONS.md entry before implementation.
- Documentation drift is a maintenance risk; the update rules in DEVELOPMENT.md are the countermeasure.


