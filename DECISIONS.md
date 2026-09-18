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

---

## ADR-009 — Code-Defined Recommendation Rules (Phase 2R v1)

**Status:** Accepted (Phase 2R v1 — these are explicitly NEW Phase 2R v1 design decisions, not previously specified behavior)

**Context:** Phase 2R introduces recommendations over the Phase 2Q personalized assessments. The older architecture text (ARCHITECTURE.md §23) described the planned engine generically ("replace pending set atomically") without binding rule semantics. Phase 2R approved a concrete four-rule v1 vocabulary and several design decisions that had no prior specification.

**Decision:**

1. The complete v1 recommendation vocabulary is exactly four rules: `REC-DELETION-RIGHTS-LOST`, `REC-SHARING-OPT-OUT`, `REC-REVIEW-SETTINGS`, `REC-NONE-REQUIRED`.
2. `REC-DELETION-RIGHTS-LOST`: `DELETION_RIGHTS` + `REMOVED`/`MODIFIED` → `EXERCISE_DELETION`; `ADDED` is ignored; the rule is band-less. The current diff engine cannot determine whether a `MODIFIED` clause strengthened or weakened the deletion right, so REMOVED/MODIFIED is treated as the deterministic signal.
3. `REC-SHARING-OPT-OUT`: `THIRD_PARTY_SHARING` or `ADVERTISING` with personalized band MEDIUM/HIGH/CRITICAL → `OPT_OUT_SHARING`. `LOCATION` is intentionally excluded from this rule (it can still receive `REVIEW_SETTINGS` via the catch-all). `ADVERTISING` is intentionally mapped to `OPT_OUT_SHARING` for this v1 recommendation vocabulary.
4. `REC-REVIEW-SETTINGS`: any concept with personalized band MEDIUM/HIGH/CRITICAL → `REVIEW_SETTINGS`; this is the generic actionable catch-all. LOW and NONE never produce it.
5. `REC-NONE-REQUIRED`: if no rule 1–3 fired and the assessment aggregate band is NONE or LOW, exactly one assessment-level `NONE_REQUIRED` recommendation is produced with `concept_code = NULL`. It means "no actionable recommendation was generated for this assessment", not "the policy has no changes".
6. Because `privacy_concept` has no concept category column, explicit concept-code sets are the Phase 2R proxy for "concept category". No category column is added in Phase 2R.
7. Concept-level rules condition on `personalizedBand` (user-specific, consistent with Phase 2Q personalization); the assessment-level `aggregateBand` is used only by the `NONE_REQUIRED` closure rule.
8. Ranking is deterministic: personalized score (descending), then rule order (ascending), then concept code (ascending) as the final tie-break.
9. Recommendations are append-only: no recommendation row is ever mutated or deleted. The **current/pending recommendation set** for a user is the set attached to the user's latest assessment. This is a deliberate Phase 2R interpretation of the older ARCHITECTURE.md §23 "regeneration replaces the pending set atomically" wording: the pipeline never rewrites history; a new assessment supersedes the previous pending set by becoming the latest, leaving prior recommendations intact as history.
10. `recommendation` does NOT store `user_id`: ownership is derived through `recommendation.assessment_id` → `impact_assessment.user_id`. This follows the existing V3/V4/V7 no-duplicated-owner-FK convention (child rows never duplicate the owner's foreign key), avoids redundant ownership facts that two independent foreign keys could desynchronize, and keeps the Phase 2R model minimal. User isolation is enforced at the service boundary by resolving the user's own assessment first (`userId` + `newVersionId`); repository reads are assessment-scoped.

Rules remain code-defined and versioned (`RECOMMENDATION_RULES_VERSION = 1`) and are never externalized to the database.

**Consequences:**

- Rule behavior is reproducible, testable, and auditable; changing rules is a code change that bumps the version, and every persisted recommendation records the version that produced it.
- The pending-set interpretation keeps the append-only philosophy (ADR-003) intact: superseded recommendations remain queryable history rather than being deleted.
- Concept-code sets must be maintained when the vocabulary evolves; a future category column would replace the proxy, which is a versioned rules change.
- Ranking and deduplication are fully deterministic; the engine is a pure function of the assessment breakdowns and the aggregate band.
- Ownership without duplication: because the assessment already binds a user to a version, storing `user_id` again on each recommendation would create a second, unenforced ownership fact; deriving it through the assessment keeps reads and writes to a single source of truth.

