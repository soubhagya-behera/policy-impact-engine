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

---

## ADR-014 — Stale IN_PROGRESS Recovery (Phase 2U.2)

**Status:** Accepted

**Context:** Phase 2U serializes concurrent triggers and Phase 2U.1 retries classified failures, but a worker that crashes between claiming an attempt and completing it leaves an `IN_PROGRESS` row that holds the policy's V11 in-flight slot forever, blocking every later check of that policy. ARCHITECTURE.md §24 requires stale-attempt recovery as the last piece of the monitoring reliability work, without weakening the V11 claim invariant, without touching retry/backoff semantics, and without external coordination.

**Decision:**

1. The lease timestamp is the existing `started_at` (claim-time stamp, immutable after claim). No new timestamp column. The stale cutoff is `clock.instant() − monitoring.stale-timeout`; a row is stale iff `status = 'IN_PROGRESS' AND started_at <= cutoff` (exactly-at-threshold counts as stale). `PENDING` rows are never recovered: a committed-but-unclaimed `PENDING` row cannot exist because insert and claim share one transaction.
2. Recovery transitions the existing stale row in place to `FAILED` with `failure_kind = 'TRANSIENT'`, preserving its `attempt_number`. No new row is created, no row is deleted, no new status is introduced. The error message begins with `Stale IN_PROGRESS` so reaped rows stay distinguishable from genuine transient failures while retry math treats them identically. `completed_at` is the recovery time; `duration_ms` is the non-negative whole-observation duration.
3. The recovery UPDATE is conditional — `WHERE id = :attemptId AND status = 'IN_PROGRESS' AND started_at <= :cutoff` — and the affected-row count is the election: 1 wins and the winner reschedules the policy; 0 loses (concurrent completion or concurrent recovery) and does nothing further, especially no `next_check_at` write. No Java synchronization is used; multi-instance safety comes from the database predicate, exactly as the Phase 2U claim.
4. The reschedule reuses the unchanged `RetryPolicy.nextCheckAt(completedAt, TRANSIENT, attemptNumber)` in a separate short transaction (history first, schedule second — the Phase 2U.1 failure-path order). The next check then runs as an ordinary observation through the single shared claim path with attempt number one past the reaped row.
5. Flyway V13 adds exactly one object, the partial index `idx_attempt_stale_inflight ON policy_fetch_attempt (started_at ASC) WHERE (status = 'IN_PROGRESS')`, for oldest-first stale lookup. V1–V12 and the V11 index are untouched.
6. Recovery runs as a separate scheduled sweep (`monitoring.stale-check-interval=PT5M`, `monitoring.stale-batch-size=100`, oldest first, per-row failure isolation), not inside the 24-hour due tick. The sweeper never fetches and never holds a transaction across HTTP. Defaults: `monitoring.stale-timeout=PT30M` — orders of magnitude above legitimate observation latency (bounded HTTP timeouts plus local pipeline stages) and well below the check interval.
7. A late-finishing original worker whose row was already reaped is rejected by the existing single-terminal-transition guard; the terminal transition is deliberately not redesigned in this phase, and the worker performs no second reschedule because the terminal write precedes scheduling in the observation flow.

**Consequences:**

- Crashed policies self-heal within minutes (lease expiry plus one sweep period) instead of stalling forever; every recovery is auditable history carrying its retry-chain position.
- The retry chain neither resets nor over-penalizes: a reaped attempt `n` retries as `n+1` with the backoff for `n`, bounded by `maxAttempts` like any transient chain.
- The accepted trade-off is lease-inherent: if the original worker is merely slow past the timeout (not crashed), its late result is discarded. The 30-minute default makes this practically impossible for legitimate observations.

---

## ADR-015 — In-App Notification Emission (Phase 10A)

**Status:** Accepted

**Context:** The deterministic chain Fetch → … → Recommendation is complete, but nothing consumes its output: no user can learn about any result. ARCHITECTURE.md §25 requires in-app notifications recorded when a check produces meaningful changes and personalized impact, as a record (not a message queue) with read/unread state. Per-user assessment and recommendation creation already run through explicit-`userId` services with no authentication in place (Phase 2P precedent), and policies carry no owner, so automatic observation fan-out is not yet expressible. Phase 10A must therefore deliver service-layer emission without forcing the ownership, REST-feed, or authentication decisions.

**Decision:**

1. A notification is created only from an existing `ImpactAssessment`. Flyway V14 creates exactly one table, `notification` (`id` UUID PK, `assessment_id` UUID NOT NULL FK → `impact_assessment(id)`, `created_at` timestamptz NOT NULL, `read_at` timestamptz NULL, `CHECK (read_at IS NULL OR read_at >= created_at)`), plus exactly one object beyond the table: the `uq_notification_assessment` UNIQUE index on `assessment_id`, which is the idempotency guard. V1–V13 are untouched. No speculative indexes.
2. The emit rule reads the assessment's persisted recommendation rows and creates exactly one notification if and only if at least one row carries a rule code other than `REC-NONE-REQUIRED` (referenced as `FrozenRecommendationRules.RULE_REC_NONE_REQUIRED`, never re-spelled). The rule is stated in persisted rule codes rather than aggregate scores so it stays correct under future rule versions; the closure row by construction never triggers emission. No AI/LLM text generation; notification content is the assessment reference itself.
3. Ownership derives through `Notification → ImpactAssessment → User`. No `user_id` is stored on the row (V3/V4/V7/V8 no-duplicated-owner-FK convention). Every service method takes an explicit `userId`, resolves ownership through the assessment's user first, and treats cross-user access as not-found (existence-leak avoidance, per §27 convention).
4. The `Notification` entity is immutable except the single sanctioned `markRead` transition (`read_at` NULL → timestamp, idempotent no-op on repeat, never moved backwards or reset; Clock-stamped, mirrored by the schema CHECK). The assessment relationship is EAGER.
5. Emission runs in its own short transaction after recommendations commit, invoked through a single narrow fenced edge: `RecommendationService.getOrCreateRecommendations` calls `NotificationService.emitForAssessment(userId, assessmentId)` on every resolved path (existing, created, or race-re-read — which also heals a previously failed emission). Notification never calls back into recommendation application code. The read path (`RecommendationService`) never touches notification state.
6. Idempotency per assessment follows the 2Q/2R pattern: pre-check re-read plus `UNIQUE(assessment_id)`-backed `DataIntegrityViolationException` → re-read convergence, so repeats and concurrent emits yield the single row. No Java synchronization; no HTTP/network in any notification transaction.
7. An emission failure propagates without rolling back the already committed assessment/recommendations; a later emit heals the gap idempotently.
8. The REST feed endpoints, automatic observation fan-out (needs policy ownership), email delivery, and authentication remain explicitly deferred (Phase 10B / Phase 8). No endpoint, filter-chain, scheduler, or pipeline change in Phase 10A.

**Consequences:**

- Actionable impact becomes a durable, user-scoped, auditable record at the per-user completion point, while NONE/LOW outcomes stay silent by rule rather than by threshold duplication.
- The module graph gains one fenced bidirectional touchpoint — recommendation.application → notification.application (emit call) alongside notification.application → recommendation.domain (read-only persisted-row evaluation, no service call, hence no Spring cycle) — documented here instead of worked around with duplicated rule codes or signature changes.
- Until fan-out lands, notifications emit on explicit per-user flows only; the scheduler-driven path that notifies every subscriber of a policy awaits the ownership model.

---

## ADR-016 — Policy Ownership & Automatic Fan-Out (Phase 10B-1)

**Status:** Accepted

**Context:** Phase 10A emits notifications only on explicit per-user flows (`ImpactAssessmentService` / `RecommendationService` with an explicit `userId`); the scheduler observes policies with no user attached, so nothing fans a `NEW_VERSION` out to whoever cares about the policy. ARCHITECTURE.md §25 anticipated "automatic observation fan-out to subscribers", but no ownership model exists yet: policies carry no owner, and authentication (Phase 8) is still deferred, so there is no principal to fan out to. Phase 10B-1 must close that loop at the service layer without forcing the REST-feed, authentication, transfer-authorization, or subscription-model decisions.

**Decision:**

1. Single-owner FK, not subscriptions: Flyway V15 adds exactly one column, `policy.owner_id UUID NULL REFERENCES app_user(id)`. A policy has exactly one owner. There is no `policy_user`, subscription, or watch table and no many-to-many relationship; policy changes are never broadcast to all users — only the single owner receives the personalized fan-out. V1–V14 are untouched, and no speculative indexes are added.
2. Nullable owner transition: existing rows keep `NULL` (unowned). An unowned policy keeps being observed normally but stays silent: no assessment, no recommendation, no notification.
3. Owner assignment semantics: `PolicyService.assignOwner(userId, policyId)` validates that both the policy and the user exist, assigns a null owner, treats the same owner as an idempotent no-op, and rejects a different owner. No transfer authorization is implemented here and no REST endpoint exposes the operation; tightening assignment to authenticated ownership belongs to a future phase (with Phase 8 authentication).
4. Narrow dependency: assignment reads the persisted user through a fenced `policy.application → user.domain` edge (`PolicyService` → `UserRepository`). This mirrors the Phase 2S precedent (ADR-010) of a narrow, one-directional, documented exception to the module direction rule.
5. Scheduler fan-out placement: the scheduler captures the existing observation result and, after the observation's own success/terminal handling, calls the new `NotificationFanOutService`. `PolicyObservationService` itself is not modified, and the observation result and attempt lifecycle are never altered by fan-out.
6. Outcome rule: fan-out proceeds only when the outcome is `NEW_VERSION`, the policy is `ACTIVE`, and `owner_id` is non-null. `FIRST_VERSION`, `UNCHANGED`, failures, skipped/claim collisions, inactive policies, and unowned policies return silently.
7. Version resolution reuses the existing repository query `findByPolicy_IdAndVersionNumber(policyId, versionNumber)`; `PolicyObservationResult` is not modified to carry the version ID.
8. Fan-out flow reuses the existing services without duplicating their rules: `ImpactAssessmentService.getOrCreateAssessment(ownerId, newVersionId)` → `RecommendationService.getOrCreateRecommendations(ownerId, newVersionId)` → the existing Phase 10A notification emission hook. No impact scoring, recommendation-rule, or notification-creation logic is duplicated.
9. Transactions stay short and separate: assessment TX, then recommendation TX (which in turn runs the notification TX). No encompassing fan-out transaction exists, and no HTTP is involved.
10. Idempotency and concurrency come from the existing uniqueness guards — `UNIQUE(user_id, new_version_id)` on the assessment, the Phase 2R recommendation uniqueness, and `UNIQUE(assessment_id)` on the notification — serialized upstream by the V11 observation claim. No Java synchronization and no new infrastructure (no Redis/Kafka/RabbitMQ/ShedLock/advisory locks). Repeated fan-out for the same version converges to the existing records.
11. Failure isolation: fan-out runs after the observation has succeeded. A fan-out failure changes nothing about the observation — no `PolicyFetchAttempt` change, no `next_check_at` change, no `FAILED` marking, no observation retry, no V11/V12/V13 change — and its exception is isolated per policy so the tick continues with the remaining due policies.
12. Accepted healable gap: when fan-out fails, the observation stays successful while its personalized fan-out is missing. The gap heals because every step is get-or-create: a repeated fan-out for the same version (or the next version's fan-out) converges to the missing records idempotently.
13. Exception narrowing: `NotificationNotFoundException extends NoSuchElementException` replaces the `IllegalArgumentException` previously thrown for missing/foreign assessments and notifications (messages unchanged), so those cases map to HTTP 404 when the REST feed lands. No other `NotificationService` behavior changes.
14. Explicitly out of scope (Phase 10B-2 and Phase 8): REST notification endpoints, JWT/authentication/`SecurityFilterChain`, `CurrentUser`/`SecurityContext`/request-supplied user IDs (the owner ID comes from persisted `Policy.owner_id`), policy transfer/admin functionality, subscriptions/watch tables, audit, email, AI, and observability.

**Consequences:**

- Owned policies notify their owner automatically on every scheduled `NEW_VERSION`; unowned policies observe silently until an owner is assigned.
- The fan-out path adds no new tables, no new dependencies, no scheduler cadence change, and no retry/backoff/stale-recovery change; due selection, ordering, one-observation-per-policy-per-tick, the next-check move-guard, claim-skip, and failure isolation are preserved.
- Until authentication lands, ownership is assigned by explicit user ID at the service boundary; user-data isolation continues to rest on explicit-`userId` service calls, not on a principal.
- Phase 10B-2 (authenticated notification REST feed) builds directly on this slice: it adds HTTP delivery of the records created here, without touching the fan-out contract.

---

## ADR-017 — Authentication & Security Foundation (Phase 8A)

**Status:** Accepted

**Context:** Phase 8 requires account registration, BCrypt password hashing, a real stateless `SecurityFilterChain`, JWT access/refresh tokens, and principal-based user-data isolation for future `/me/*` endpoints — but that bundle is too large for one safe slice, and no credential model exists yet (`app_user` holds only `id` + timestamps; ~37 test call sites use `new User()` / `createUser()`). Phase 8A must establish the minimum foundation (credential model + registration + explicit chain) without issuing any token and without breaking existing tests or pipeline behavior.

**Decision:**

1. Email is the single login identifier: `app_user.email VARCHAR(254) NULL` in the Flyway V16 transition, application-normalized (trim + lowercase) on registration, unique via `uq_app_user_email`. No separate username system and no dual keys.
2. Passwords are hashed with `BCryptPasswordEncoder` (default strength); no custom crypto. Raw passwords are never persisted or logged. Policy: minimum 8 characters, maximum 72 UTF-8 bytes (BCrypt truncates beyond 72, so longer input is rejected, never silently truncated); the byte limit is enforced both by DTO validation and by a service-side guard.
3. Nullable credential transition in V16 (`email` and `password_hash VARCHAR(255)` both NULL): existing credential-less rows keep working and `createUser()` / the no-arg JPA constructor are unchanged, so current tests and internal flows do not break. Application validation requires both fields on the register path. DB-level `NOT NULL` tightening belongs to Phase 8B (V17).
4. No roles, status/lockout flags, token/refresh tables, credential tables, OAuth2, or admin system in 8A. Authorization in 8A/8B is "authenticated user"; a role column with exactly one value would be dead weight.
5. Registration only: `AuthRegistrationService.register(email, rawPassword)` (normalize → duplicate check → BCrypt → persist → safe result of id + email) and `POST /api/v1/auth/register` → `201` with `{id, email}`. No login, no refresh, no `/me/*`. Duplicate email raises `DuplicateEmailException`, mapped to HTTP 409 `application/problem+json` in the existing global handler.
6. Minimal stateless chain: CSRF disabled, HTTP Basic / form login / logout disabled, stateless sessions; `POST /api/v1/auth/register` and existing `/api/v1/policies/**` permitted (behavior preservation — policy endpoints carry no per-user data today); everything else defaults to authenticated. Custom entry point / access-denied handler emit `application/problem+json` (`401 "Unauthenticated"`, `403 "Forbidden"`) to preserve the RFC 7807 convention.
7. No JWT library, secret, filter, or principal resolution in 8A and no fake principal. No request-supplied user-ID workaround (`?userId=`, `X-User-Id`, path-variable user IDs are forbidden). Phase 8B introduces access-JWT issuance + validation with `sub` = application User UUID, `POST /api/v1/auth/login`, the JWT filter, and the principal-to-UUID helper that feeds the existing explicit-`userId` service signatures. Phase 8C owns refresh tokens (rotation/revocation), only on demonstrated need.
8. No new production dependency in 8A (BCrypt ships with the existing `spring-boot-starter-security`).

**Consequences:**

- Account creation becomes a durable, tested, credential-safe record while login and all authenticated reads wait for 8B; 10B-2B (notification REST) waits for 8B's principal.
- Legacy credential-less rows remain valid until 8B tightens the schema; login in 8B must reject null-hash rows.
- The transitional open policy endpoints stay open (documented, time-boxed to the ownership-enforcement work in 8B/8C); every newly added endpoint is locked by default.

---

## ADR-018 — JWT Access Authentication + Principal Resolution (Phase 8B)

**Status:** Accepted

**Context:** Phase 8A delivered registration, BCrypt hashing, and an explicit stateless chain, but no login, no token, and no principal — so `/me/*` endpoints (10B-2B) remain unimplementable. Phase 8B must add the minimum real authentication layer (login → short-lived access JWT → per-request validation → UUID principal) without refresh tokens, roles, or any domain change.

**Decision:**

1. JWT library is `com.nimbusds:nimbus-jose-jwt` (10.x, pinned), the sole new dependency. Nimbus is single-artifact, maintained, Java 17 baseline, and JSON-self-contained, so it introduces no Jackson-version clash with Boot 4's Jackson 3. `jjwt` (Jackson 2 alongside Jackson 3) and `spring-boot-starter-oauth2-resource-server` (heavier, OAuth2-named) are rejected.
2. HS256 access tokens with exactly three claims: `sub` (application User UUID string — never email), `iat`, `exp` (`now + security.jwt.access-token-ttl`, default `PT15M`). No roles, authorities, email, PII, or refresh token. Validation verifies signature, algorithm, expiration, and requires a UUID-parseable `sub`; every failure mode collapses to one `JwtInvalidException` with no reason detail.
3. Secret comes from `security.jwt.secret` (environment / Git-ignored local config; placeholder only in the committed template). Blank/missing or < 32 UTF-8-byte secrets fail fast at startup. Tests use a committed test-only secret in `src/test/resources/application.properties`, clearly labeled non-production.
4. Login is `AuthLoginService.login` + `POST /api/v1/auth/login` → `200 {accessToken, tokenType: "Bearer", expiresIn}`. Email is normalized with the existing 8A routine; unknown email, null hash (legacy rows cannot log in), wrong password, and overlong password all yield the identical `InvalidCredentialsException` → HTTP 401 problem+json `"Unauthenticated"` / `"Invalid email or password"`. Unknown-email attempts run a BCrypt `matches` against one static committed dummy hash (not a secret, never per-request generated) to flatten timing; the taken branch is never exposed.
5. Principal is `AuthenticatedUser(UUID)` (null rejected), built only by `JwtAuthenticationFilter` (a `OncePerRequestFilter` reading `Authorization: Bearer`, registered before `UsernamePasswordAuthenticationFilter`). Filter failures clear the context and continue so the existing entry point emits the single standard 401 — the filter never writes its own. `AuthenticatedUsers.requireUserId(Authentication)` is the web-only UUID extractor (missing/anonymous/wrong principal → `AuthenticationRequiredException` → 401). No `SecurityContextHolder` in services, no static current-user state, no client-supplied identity.
6. Chain preserves all 8A behavior and additionally permits only `POST /api/v1/auth/login`. No `/me/*` yet.
7. No V17 migration: `NOT NULL` tightening would break the ~37 credential-less creation sites still used by tests and internal flows, so V1–V16 stay byte-for-byte unchanged and the invariant remains application-enforced. Tightening is re-queued behind internal-creation migration.
8. Refresh storage/rotation/revocation and `/auth/refresh` belong to Phase 8C.

**Consequences:**

- Any registered user can obtain a 15-minute Bearer token; every protected endpoint resolves the same UUID the explicit-`userId` services already accept, so 10B-2B becomes a thin controller slice with no service changes.
- Stolen-token exposure is bounded by the short TTL at the cost of re-login until 8C refresh exists; HS256 rotation means redeploy/restart (asymmetric keys deferred unless a multi-service future needs them).

---

## ADR-019 — Authenticated Policy Ownership Boundary

**Status:** Accepted

**Context:** Phase 10B-1 introduced the single nullable `policy.owner_id` FK and service-level `assignOwner`, but no REST path assigns ownership: `/api/v1/policies/**` stayed publicly permitted (the Phase 8A transitional opening, kept for behavior preservation), registration created ownerless rows, and reads were global. Meanwhile the scheduler fan-out, assessments, recommendations, and notifications are all user-scoped, so the policy boundary is the last unowned link in the loop.

**Decision:**

1. The transitional `/api/v1/policies/**` permitAll is removed. Policy endpoints require authentication; `POST /api/v1/auth/register` and `POST /api/v1/auth/login` stay public and everything else stays behind `anyRequest().authenticated()`.
2. Registration assigns ownership: `POST /api/v1/policies` resolves the owner exclusively from `AuthenticatedUsers.requireUserId(authentication)` and persists `owner_id` with the new row. The request carries no owner identity (`CreatePolicyRequest` gains no `userId`/`ownerId` field); unknown JSON fields are ignored and body/query/header identity is rejected by construction.
3. Reads are owner-scoped at the repository level: `findByIdAndOwner_Id` for detail and `findByOwner_IdOrderByCreatedAtAscIdAsc` for listing (registration order, id tie-break). No Java-side filtering. Cross-user and unknown ids both map to HTTP 404 (`NoSuchElementException`), never 403.
4. Ownership is immutable through REST: `PolicyService.assignOwner` stays as the internal application operation (existing tests and fan-out flows depend on it) but gains no REST exposure; no transfer/admin/role surface is added.
5. No migration: the V15 `owner_id` column already exists; no new index in this slice (per-user policy counts are bounded; index hardening belongs to Phase 13). V1–V16 stay byte-for-byte unchanged.

**Consequences:**

- Every policy created through the API has an owner from birth, so scheduled fan-out reaches a real user without a separate assignment step; legacy ownerless rows (pre-hardening, tests, internal flows) keep observing silently until owned.
- The public policy listing/detail contract ends here: unauthenticated callers receive 401 and authenticated callers see only their own rows — a deliberate break of the transitional behavior, covered by updated chain and isolation tests.
- Transfer authorization remains explicitly out of scope; `assignOwner` still performs no authorization and must not be exposed without a future decision.

---

## ADR-020 — Privacy Preference REST Semantics

**Status:** Accepted

**Context:** The preference domain (explicit 0–5 rows with concept-default fallback, single-row-per-(user, concept) uniqueness, separate upsert and delete operations) predates any HTTP surface. The REST slice must expose inspection and bulk update without redefining resolution or introducing destructive surprises.

**Decision:**

1. `GET /api/v1/me/privacy-preferences` returns the full effective surface: every vocabulary concept in deterministic code order, each with `conceptCode`, `label`, `effectiveSensitivity` (explicit value or concept default, resolved by the existing `EffectiveSensitivityResolver`), and `explicit` (whether the user configured it). No database IDs, credentials, or timestamps.
2. `PUT /api/v1/me/privacy-preferences` takes `{"preferences": {CODE: 0–5}}` with merge semantics: entries present are upserted (existing rows updated, never duplicated); concepts absent from the request are left untouched. There is deliberately no replace/delete-through-PUT; reverting to default stays a service-level delete with no REST exposure in this slice.
3. Unknown concept codes are rejected with HTTP 400 (`IllegalArgumentException` → existing problem mapping), never silently created. Range violations are rejected at the DTO boundary (Bean Validation) and re-guarded in the service.
4. Writes run per-entry in short `REQUIRES_NEW` transactions over the existing find-then-save upsert; a lost-insert race on `UNIQUE(user_id, concept_id)` converges through re-read with no Java synchronization, matching the assessment/recommendation/notification pattern.
5. Identity comes only from `AuthenticatedUsers.requireUserId(authentication)`; the contract carries no user identity field. No migration: the V6 schema already supports everything.

**Consequences:**

- Clients always see the complete preference surface, so defaults are visible without any rows existing; updates are idempotent and safe to repeat or send partially.
- Effective-sensitivity rules stay single-sourced in the resolver; only future assessments consume new values — historical assessments are untouched, consistent with §19.
- A future explicit "reset to default" REST operation would build on the existing service delete, not on PUT semantics.

---

## ADR-021 — Assessment + Recommendation REST Read Surface

**Status:** Accepted

**Context:** The deterministic chain Policy change → ChangeConceptMatch → ChangeImpact → EffectiveSensitivity → ImpactAssessment → Breakdown → Recommendation → Notification is complete and personalized data is generated, but users can only see the notification feed: nothing exposes the assessment or recommendation details behind a notification. The read-side loop must close with authenticated, user-scoped endpoints that reuse the existing entity/repository/service model without redefining scoring, ownership, or notification semantics.

**Decision:**

1. Four endpoints, all under `/api/v1/me` behind the existing Phase 8B authentication (`anyRequest().authenticated()` already protects them; no `SecurityConfig` change): `GET /api/v1/me/impact-assessments`, `GET /api/v1/me/impact-assessments/{assessmentId}`, `GET /api/v1/me/recommendations`, `GET /api/v1/me/recommendations/{recommendationId}`. The path identifiers are the existing domain UUIDs; no new identifier is invented.
2. Identity comes only from `AuthenticatedUsers.requireUserId(authentication)` in thin controllers (`ImpactAssessmentController`, `RecommendationController`); services receive an explicit `UUID userId` and never touch `SecurityContextHolder`. Cross-user or unknown ids map to HTTP 404 via new `ImpactAssessmentNotFoundException` / `RecommendationNotFoundException` (both extend `NoSuchElementException`, reusing the existing 404 problem mapping), never 403; malformed UUIDs reuse the existing 400 mapping; missing/invalid JWTs reuse the existing 401 entry point.
3. Repository-level ownership filtering only: `findByIdAndUser_Id` + `findByUser_IdOrderByCreatedAtDescIdDesc` on `ImpactAssessmentRepository`; `findByIdAndAssessment_User_Id` + `findByAssessment_User_IdOrderByCreatedAtDescIdDesc` on `RecommendationRepository` (ownership derived through Recommendation → ImpactAssessment → User, preserving the V3/V4/V7/V8 no-duplicated-`user_id` convention). Nothing is loaded and filtered in Java. The `IdDesc` tie-break makes newest-first ordering deterministic when timestamps collide. No migration: all four are derived queries.
4. Dedicated response DTOs (no entities exposed): `ImpactAssessmentSummaryResponse` (8 summary fields, no breakdowns), `ImpactAssessmentDetailResponse` (summary + breakdowns in the existing engine ranking order), `ImpactAssessmentBreakdownResponse` (persisted system snapshot + effective sensitivity + personalized snapshot + both rules versions, including the system `rulesVersion` read through the EAGER `changeImpact`), `RecommendationSummaryResponse` (the entity's real fields: `ruleId`, `ruleOrder`, `actionKind`, nullable `conceptCode`), `RecommendationDetailResponse` (row + `policyId`/`versionNumber` navigation context, no other duplicated domain state). Bands/action kinds render as enum names, matching the policy/notification conventions.
5. DTO mapping runs inside the existing services' `@Transactional(readOnly = true)` read methods, so the lazy `newVersion`/`previousVersion`/`policy` associations resolve before the session closes (the `NotificationResponse` pattern). No `EntityGraph`/fetch join in this slice; the per-row lazy-load cost on these bounded, user-scoped reads is accepted, and no relationship is made EAGER.
6. No new domain state: the existing "current/pending set = latest assessment's set" interpretation (ADR-009) is preserved as-is; no status model, no latest-pointer, no migration (V1–V16 unchanged), no scoring/recommendation/notification/fan-out/ownership change.

**Consequences:**

- A client can navigate Notification → `assessmentId` → assessment detail → breakdowns → recommendations using existing IDs, with every number shown read straight from the persisted rows (no second scoring algorithm exists in the read path).
- Pagination and index hardening for these feeds belong to Phase 13, as with the notification feed.
- Any future write operation on assessments or recommendations (none exists today; both are append-only) would need its own decision; this slice is strictly read-only.

