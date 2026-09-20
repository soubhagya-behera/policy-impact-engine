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

---

## ADR-010 — Observation Attempt Recording (Phase 2S)

**Status:** Accepted

**Context:** ARCHITECTURE.md §24 requires every policy check to be recorded as a `PolicyFetchAttempt`, but no attempt recording existed: observations ran the full pipeline with no trace of the check itself. Attempt rows are also the prerequisite the scheduler phase will claim and retry against, so recording must precede scheduling. The recording call necessarily flows from the existing policy orchestrator into the monitoring module, which touches the one-direction dependency rule (ARCHITECTURE.md §7).

**Decision:**

1. Flyway V9 creates the `policy_fetch_attempt` table only; V1–V8 are untouched. No scheduler state (`next_check_at`, claiming constraints, retry counters) is included.
2. Attempt lifecycle mutability is explicitly sanctioned and narrow: a row is created `IN_PROGRESS` and transitions exactly once to `SUCCESS`, `FAILED`, or `SKIPPED_UNCHANGED`. Only `status`, `completed_at`, `duration_ms`, and `error_message` (plus the write-once fetch payload `http_status`/`bytes_fetched`, set by that same single transition) may change; `policy_id`, `trigger`, `attempt_number`, and `started_at` are strictly write-once. The entity guards the single transition; the schema enforces it with CHECK constraints. This is an intentional, bounded exception to the append-only history pattern, required by §24's explicit status-transition design.
3. A single narrow `policy.application → monitoring.application` recording edge is allowed for Phase 2S: `PolicyObservationService` calls `PolicyFetchAttemptService` to begin and complete attempts. The boundary is strict — monitoring must not call back into policy application code in Phase 2S (the monitoring→policy direction arrives with the scheduler, which will drive the pipeline rather than be called by it). `PolicyFetchAttempt` is owned by the monitoring module and is not moved into policy.
4. `PolicyObservationService.observe()` records trigger `MANUAL`. `SCHEDULED` remains schema/domain vocabulary, unused until the scheduler phase.
5. Outcome mapping: `FIRST_VERSION`/`NEW_VERSION` → `SUCCESS`; `UNCHANGED` → `SKIPPED_UNCHANGED`; any fetch/extraction/normalization/hash/persistence failure → `FAILED` with the cause, then the original exception is rethrown unchanged (no new exception hierarchy).
6. Failing to begin the attempt fails the observation fast; there is no silent unrecorded path.
7. `bytes_fetched` is the documented UTF-8-byte-length approximation of the available response body (`null` when no response exists); exact wire-byte accounting is deferred and must not reshape the fetcher.
8. Timing uses an injected `Clock` (system UTC in production, fixed/sequenced in tests).
9. `PolicyObservationResult` is unchanged: attempt recording is internal orchestration/history, not part of the result contract.

**Consequences:**

- Every check — including failed ones — leaves exactly one terminal attempt row, while version/change history keeps its all-or-nothing atomicity (the terminal `FAILED` update never joins the persistence transaction it reports on).
- The scheduler slice inherits a complete, tested recording foundation: claiming and retry can be added without touching the observation flow.
- The dependency exception is fenced: any monitoring→policy-application call before the scheduler phase would violate this ADR.
- Phase 2T fulfills the fenced direction: the monitoring scheduler now drives `PolicyObservationService` (see ADR-011); the 2S recording edge (policy→monitoring) remains narrowly scoped to attempt recording only.

---

## ADR-011 — Scheduled Observation Triggering (Phase 2T)

**Status:** Accepted

**Context:** Phase 2S records every check but nothing triggers checks automatically. ARCHITECTURE.md §24 requires scheduled re-checks of active policies on a configurable interval, with retry/claiming specified for later. The full §24 bundle (triggering + retry + claiming + stale handling) is too large for one safe slice, so Phase 2T implements triggering only; retry/claiming/stale handling are deferred to an explicit follow-up slice.

**Decision:**

1. Scheduling state lives on the policy: Flyway V10 adds `policy.next_check_at` (timestamptz NOT NULL, backfilled to `now()` so existing policies are immediately eligible, no staggering) plus a `(status, next_check_at)` due-selection index. Scheduling state is not derived from attempt history. V1–V9 are untouched.
2. A single-threaded sequential scheduler (`PolicyObservationScheduler`, Spring `@Scheduled` fixed-delay, no pool): each tick loads ACTIVE policies with `next_check_at <= now` in deterministic (next_check_at, id) order and observes each once through the single shared pipeline with trigger `SCHEDULED`.
3. The observation interval defaults to 24 hours (`monitoring.check-interval=PT24H`), configurable through application configuration; the same `Duration` drives the fixed delay and `next_check_at` advancement. No kill-switch in Phase 2T.
4. Every completed check advances `next_check_at` by exactly the interval from the tick start — `SUCCESS`, `SKIPPED_UNCHANGED`, and `FAILED` alike. No retry, backoff, jitter, or failure classification in Phase 2T; no catch-up for overdue policies (at most one observation per policy per tick).
5. `observe(UUID policyId)` remains the `MANUAL` path and delegates to the new `observe(UUID policyId, PolicyFetchAttemptTrigger trigger)` overload. There is exactly one analysis pipeline; nothing is duplicated into the scheduler.
6. One policy's failure (already recorded as its `FAILED` attempt) never aborts the tick; remaining due policies are still processed.
7. Phase 2T is explicitly single-instance/single-thread. Atomic claiming, the partial-unique in-flight guard, stale-`IN_PROGRESS` reclamation, `attempt_number` chains, and the `PENDING` workflow all belong to the follow-up slice, which also owns the real cross-instance and manual-vs-scheduler concurrency guarantees.

**Consequences:**

- Policies are re-checked automatically on a uniform schedule with full attempt history; failures stay visible and do not stall the schedule.
- A persistently failing policy re-checks every interval until the follow-up slice adds backoff — accepted as bounded and observable behavior.
- The follow-up slice can add claiming/retry without touching the tick structure: due selection, the trigger overload, and attempt recording are already in place.

---

## ADR-012 — Atomic Work Claiming (Phase 2U)

**Status:** Accepted

**Context:** Phase 2T triggers scheduled checks but nothing serializes concurrent triggers for the same policy: two manual calls, two scheduler ticks (across instances), or a manual call racing the tick could fetch the same policy in parallel, duplicating HTTP work and racing version writes. ARCHITECTURE.md §24 requires atomic work claiming backed by a database constraint. Retry/backoff/jitter and stale-attempt recovery are explicitly out of scope and belong to Phase 2U.1/2U.2.

**Decision:**

1. The claim boundary is `PolicyFetchAttempt` — no claim/lock columns on `Policy`, no ShedLock, no advisory locks, no Redis or external locking.
2. Flyway V11 adds exactly one schema object: the partial unique index `uq_policy_fetch_attempt_inflight ON policy_fetch_attempt (policy_id) WHERE (status IN ('PENDING', 'IN_PROGRESS'))`. At most one non-terminal attempt may exist per policy; terminal rows are unaffected history. V1–V10 are untouched. No stale-reaper index is created preemptively.
3. The claim algorithm runs in a single short transaction with no HTTP inside: insert a `PENDING` candidate (`attempt_number = 1`), then run the conditional claim `UPDATE policy_fetch_attempt SET status = 'IN_PROGRESS', started_at = :now WHERE id = :attemptId AND status = 'PENDING'`. Affected rows = 1 wins and runs the unchanged observation pipeline; the loser's insert collides with the V11 index, leaves no second runnable row, and performs no fetch.
4. `PolicyFetchAttemptService.beginAttempt` is the single shared claim path for both triggers; `observe(UUID)` still delegates as `MANUAL` to `observe(UUID, trigger)`. There is exactly one observation pipeline — nothing is duplicated into the scheduler.
5. Collision behavior is explicit rejection, not coalescing: MANUAL propagates `PolicyFetchClaimRejectedException` (a domain/application exception, no fetch, no terminal row for the loser); SCHEDULED catches it in the tick, skips the policy for that cycle, and still advances its `next_check_at` uniformly.
6. Attempts stay append-only: the only lifecycle is `PENDING → IN_PROGRESS → SUCCESS / FAILED / SKIPPED_UNCHANGED`. No terminal row is ever mutated back, and no `FAILED → PENDING` chain exists yet.
7. `next_check_at` semantics are unchanged Phase 2T semantics (uniform advancement on `SUCCESS`, `SKIPPED_UNCHANGED`, and `FAILED` alike, plus on claim-skips); `attempt_number` stays 1; `PolicyFetchException` is untouched and no failure classification exists.
8. Stale `IN_PROGRESS` rows are explicitly accepted until Phase 2U.2: no stale timeout, no reaper, no reclaim, no re-enqueue. No new dependencies of any kind.

**Consequences:**

- Concurrent triggers for the same policy cannot both fetch — at most one reaches HTTP — and the guarantee comes from PostgreSQL, so it holds across application instances with no Java synchronization.
- A JVM crash between claim and terminal update leaves an `IN_PROGRESS` row that blocks later checks for that policy until Phase 2U.2; this limitation is accepted and documented, not silently worked around.
- Phase 2U.1 (retry/backoff/jitter) and Phase 2U.2 (stale recovery) build directly on this foundation without touching the tick structure, the pipeline, or the claim path.

---

## ADR-013 — Retry & Bounded Backoff (Phase 2U.1)

**Status:** Accepted

**Context:** Phase 2U serializes concurrent triggers but every failure — a momentary timeout and a permanently dead URL alike — is recorded as `FAILED` and re-checked only at the regular 24-hour cadence, with `attempt_number` pinned at 1. ARCHITECTURE.md §24 requires transient failures to retry on bounded exponential backoff with jitter while permanent failures fail fast, without weakening the V11 claim invariant and without stale-attempt recovery (Phase 2U.2).

**Decision:**

1. Retries are *deferred through `Policy.next_check_at`*, never created immediately as `PENDING`. Holding a `PENDING` row across a backoff window would pin the V11 slot for minutes to hours, blocking all other triggers; waking held rows would need a promoter — stale-recovery-shaped machinery that is out of scope. A transient failure advances `next_check_at` by the backoff delay, and the retry later runs as an ordinary observation through the single shared claim path.
2. Flyway V12 adds exactly one nullable column, `policy_fetch_attempt.failure_kind VARCHAR(16) CHECK (IN ('TRANSIENT','PERMANENT'))`, set only on `FAILED` rows. Pre-2U.1 `FAILED` rows keep `NULL` and reset the retry chain. V11 and V1–V10 are untouched. A streak counter on `policy` was rejected (second source of truth duplicating history); deriving the streak from trailing-`FAILED` counts with no column was rejected (permanent failures would inflate backoff and blur exhaustion).
3. Classification rides on the throw site: `PolicyFetchException` gains `httpStatus` (null without a response) and `transientFailure`. Transient: timeouts/connection failures, mid-stream IO failures, interrupts (flag preserved), HTTP 5xx, HTTP 429 (`Retry-After` deliberately ignored), DNS resolution failures, empty DNS answers, and all `persistenceService.store()` failures (infra/race causes dominate there; deterministic-bug poison is bounded by `maxAttempts` and stays observable). Permanent: blank/invalid URLs, SSRF policy rejections, HTTP 4xx and 3xx, oversized responses, extraction/normalization/hashing failures, and any foreign runtime exception from the fetcher. The legacy message-only constructors default to permanent with no status.
4. `attempt_number` chains at claim time from committed history: one past the latest `FAILED`/`TRANSIENT` row while retries remain, otherwise 1. Every retry is therefore a new row with an incremented number; any fresh check starts at 1.
5. Backoff is `min(cap, base · multiplier^(n-1))` with equal jitter (`delay/2 + uniform(0, delay/2)`), configured as `monitoring.retry-max-attempts=5`, `monitoring.retry-base-delay=PT5M`, `monitoring.retry-multiplier=2.0`, `monitoring.retry-max-delay=PT6H` (production jitter from a `Random` bean; seeded in tests). Exhausted chains and permanent failures resume `monitoring.check-interval` from the failure time and end the streak.
6. Each `observe()` invocation performs at most one fetch — no retry loop. MANUAL and SCHEDULED share the failure path identically; the one deliberate MANUAL delta is that a MANUAL failure now also reschedules `next_check_at` (previously MANUAL never touched it), so the scheduler performs the retry. The original exception is always rethrown unchanged.
7. Transaction order per failed observation: claim TX → no TX across HTTP/pipeline → terminal TX (`markFailed`, history first) → `next_check_at` TX. If the scheduling write fails, the `FAILED` row still exists and the scheduler's move-guard falls back to interval advancement; the secondary failure then propagates.
8. The scheduler's advancement becomes a move-guard: it writes `tickStart + interval` only when `next_check_at` is still at or before the tick start, preserving backoff (or an interleaved MANUAL reschedule). Success, unchanged, and claim-skip behavior is otherwise identical.

**Consequences:**

- Transient outages self-heal in minutes without operator action, while permanent failures stay cheap and visible; every retry is auditable history, never hidden looping.
- Failure-time (not tick-start) based rescheduling shifts permanent/exhausted advancement by seconds relative to Phase 2T — accepted as more accurate.
- Phase 2U.2 inherits classified history and a free V11 slot discipline: stale recovery only needs to deal with orphaned non-terminal rows, never with backoff state.

