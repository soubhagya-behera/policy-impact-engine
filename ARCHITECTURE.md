# Policy Impact Engine — Architecture

**Repository:** `policy-impact-engine`
**Stack:** Java 17 · Spring Boot 4.1.1 · Maven · PostgreSQL · Spring Web MVC · Spring Data JPA · Spring Security · Validation · Lombok

This document is the technical source of truth for the architecture of the Policy Impact Engine. It records the approved design: the deterministic analysis pipeline, the target module structure, the planned domain model, the planned API surface, the testing strategy, and the phased implementation roadmap.

Status labels are used throughout to separate what exists from what is planned:

| Label | Meaning |
| --- | --- |
| IMPLEMENTED | Exists in the repository today |
| PLANNED | Approved and scheduled on the implementation roadmap |
| FUTURE | Deliberate extension point; not scheduled |
| PROPOSED | Under discussion; not approved |

**Current implementation status:** the Spring Boot foundation — application bootstrap, PostgreSQL connectivity, build and test baseline — is implemented. Everything described in the pipeline, domain model, and API sections below is PLANNED unless explicitly marked otherwise.

## 1. Project Purpose

Policy Impact Engine is a policy-analysis system that tracks changes in published policies — privacy policies, terms of service, and similar documents — and determines how those changes may affect individual users based on their privacy preferences.

The system maintains a versioned record of every tracked policy, compares successive versions, interprets the differences in terms of a privacy-concept taxonomy, and produces a personalized impact assessment together with a deterministic recommended action.

## 2. Core Differentiator

The project is deliberately not a policy-change tracker. Plain change detection answers "did the policy change?". The differentiating chain answers "what does this change mean for this specific user?":

```
Policy change
  → semantic / clause change
  → privacy concept relationship
  → user privacy profile
  → personalized impact
  → recommended action
```

Example: a policy introduces a clause allowing location data to be shared with third parties for advertising. The system represents that change in terms of concepts such as *Location*, *Third-party sharing*, and *Advertising*, compares those concepts against the user's privacy profile, and yields a personalized impact level (for example HIGH or CRITICAL) followed by a deterministic recommendation.

## 3. Problem Being Solved

Organizations change their policies frequently and quietly. Individual users cannot realistically re-read every policy they have accepted, yet material changes — new data-sharing clauses, altered retention periods, weakened deletion rights — directly affect them.

The engine solves this by:

- registering policies the user cares about and re-checking them on a schedule;
- producing a trustworthy, append-only history of every observed version;
- reducing each policy to comparable, fingerprinted sections;
- classifying what actually changed, in domain terms rather than raw text;
- scoring the impact of those changes against the individual user's privacy sensitivity;
- surfacing a clear, deterministic recommendation for what the user should do.

## 4. High-Level System Workflow

```
 User registers a policy (URL)
        │
        ▼
 Fetch (on demand or by the scheduler)
        │
        ▼
 Normalize → Section → Fingerprint → Diff
        │
        ▼
 Classify → Concept Match
        │
        ▼
 Impact (per user, driven by the user's privacy profile)
        │
        ▼
 Personalized Assessment → Recommendation → Notification
```

Two trigger paths exist — manual checks initiated by the user and scheduled checks performed by the monitoring module — but both feed the same deterministic pipeline. There is exactly one analysis path, never two.

## 5. Deterministic Analysis Pipeline

The core of the system is a fixed, deterministic pipeline. Each stage has a single responsibility and a typed output that becomes the typed input of the next stage. The pipeline runs without any LLM, paid AI API, or external AI service; a future local-model integration may only annotate its output with human-readable explanations (see ADR-002 and ADR-006).

| # | Stage | Responsibility |
| --- | --- | --- |
| 1 | **Fetch** | Retrieve the live policy document over HTTP with SSRF protection, timeouts, and response-size limits |
| 2 | **Normalize** | Strip presentation noise (scripts, styles, markup artifacts, entities, whitespace) into canonical text |
| 3 | **Section** | Divide the normalized document into meaningful sections based on document structure |
| 4 | **Fingerprint** | Compute stable fingerprints (exact and approximate) for the document and its sections |
| 5 | **Diff** | Match sections between versions and detect added, removed, modified, and moved sections |
| 6 | **Classify** | Filter insignificant noise and categorize the remaining changes |
| 7 | **Concept Match** | Relate changes to database-backed privacy concepts with recorded evidence |
| 8 | **Impact** | Score the impact of the matched changes using versioned, table-driven rules |
| 9 | **Personalized Assessment** | Apply the individual user's privacy profile to produce a per-user assessment with a persisted score breakdown |
| 10 | **Recommendation** | Derive a deterministic, ordered, idempotent recommended action |
| 11 | **Notification** | Record an in-app notification so the user learns about the assessment |

Every stage is pure with respect to its inputs: no hidden global state, no clock access, no randomness. Determinism is a tested property, not an aspiration.

## 6. Modular Monolith Architecture

The system is a modular monolith (ADR-001): one Spring Boot application, one deployable artifact, one Maven module, with clear internal module boundaries. There are no microservices, no message broker, and no external cache; those remain FUTURE extension points only.

Module boundaries are expressed as packages with a one-direction dependency rule. Modules interact through the application services and interfaces owned by the module being depended upon; no module reaches into another module's internals.

## 7. Module / Package Architecture

Conceptual target structure (PLANNED — no packages or classes exist yet):

```
com.soubhagya.policyimpactengine
├── common              # configuration, error handling, shared utilities
├── policy
│   ├── domain          # Policy, PolicyVersion, PolicySection, value objects
│   ├── application     # services orchestrating registration, fetching, versioning
│   ├── infrastructure  # HTTP/Jsoup fetcher, SSRF guard, parsers, normalizers
│   └── web             # controllers and DTOs
├── diff                # section matching, PolicyChange, diff engine
├── intelligence        # PrivacyConcept, ChangeConceptMatch, classification rules
├── impact              # UserPrivacyPreference, ImpactAssessment, scoring engine
├── recommendation      # Recommendation and the ordered rule engine
├── user                # User, authentication, authorization
├── monitoring          # scheduler, PolicyFetchAttempt, retry/backoff
├── notification        # Notification feed
├── audit               # AuditEvent, append-only history
└── ai                  # optional explanation adapter (FUTURE, Phase 12)
```

Dependency direction (one way, no cycles):

```
common
  ↓
policy
  ↓
diff
  ↓
intelligence
  ↓
impact
  ↓
recommendation
```

**Monitoring is an orchestrator, not a domain stage.** The monitoring module sits above the pipeline and drives it end to end on a schedule; it never becomes part of the deterministic domain chain. Similarly, `common` is a base layer with no upward dependencies, and `user` provides identity and authentication consumed by the web-facing modules. Downward modules never import upward ones.

## 8. Future Domain Model

The complete domain model is documented here as the target state. **All of the following are PLANNED concepts — none are implemented yet.** Per the vertical-slice principle, entities are created only in the phase that needs them (ARCHITECTURE.md §31); empty placeholders are never created ahead of need.

| Concept | Purpose |
| --- | --- |
| `Policy` | A registered policy being tracked (source URL, display metadata, ownership) |
| `PolicyVersion` | An immutable snapshot of the policy's normalized content at a point in time |
| `PolicySection` | A meaningful section of a version, with its fingerprint |
| `PolicyChange` | A detected difference between two versions (added / removed / modified / moved) |
| `PrivacyConcept` | An extensible, database-backed privacy topic (e.g., Location, Third-party sharing) |
| `ChangeConceptMatch` | The relationship between a change and a concept, with recorded evidence |
| `User` | An account; owner of policies and all personalized data |
| `UserPrivacyPreference` | A user's sensitivity setting for one privacy concept |
| `ImpactAssessment` | A per-user, per-change-set scored impact with a persisted breakdown |
| `Recommendation` | A deterministic recommended action derived from an assessment |
| `PolicyFetchAttempt` | One execution of a policy check (scheduled or manual), with status and outcome |
| `Notification` | An in-app notification informing a user about new impact |
| `AuditEvent` | An immutable record of a significant system event |

## 9. Entity Relationships and Ownership

Intended relationships (PLANNED):

- `Policy` 1—N `PolicyVersion` — a policy accumulates versions over time.
- `PolicyVersion` 1—N `PolicySection` — a version is decomposed into sections.
- `PolicyChange` references exactly two versions (previous and current) and, for modified or moved changes, the involved sections on both sides.
- `PolicyChange` 1—N `ChangeConceptMatch`; each match references one `PrivacyConcept`.
- `User` 1—N `Policy` — users own the policies they register; policy data is isolated per user.
- `User` 1—N `UserPrivacyPreference`; each preference references one `PrivacyConcept`. Defaults for unconfigured concepts are resolved in code from concept definitions, never backfilled into rows.
- `User` 1—N `ImpactAssessment`; each assessment references a user, a policy change set, and records a score breakdown.
- `ImpactAssessment` 1—N `Recommendation` — recommendations derive from an assessment and remain stable until a new change set arrives.
- `Policy` 1—N `PolicyFetchAttempt` — every check, manual or scheduled, is recorded as an attempt.
- `User` 1—N `Notification` and `User` 1—N `AuditEvent` (per user's visible trail; system-level audit records are also possible).

**Ownership by module:** `policy` owns Policy, PolicyVersion, PolicySection; `diff` owns PolicyChange; `intelligence` owns PrivacyConcept, ChangeConceptMatch; `user` owns User; `impact` owns UserPrivacyPreference, ImpactAssessment; `recommendation` owns Recommendation; `monitoring` owns PolicyFetchAttempt; `notification` owns Notification; `audit` owns AuditEvent. Each module owns its tables and their invariants.

## 10. Policy Registration

Registration (PLANNED, Phase 1) creates a `Policy` from a user-supplied URL and display metadata:

- URLs are validated for scheme, host, and reachability constraints at the boundary; they are treated as untrusted input (see §27 for the SSRF requirements that apply before any fetching).
- A policy belongs to exactly one user; duplicate registration of the same URL by the same user is rejected explicitly rather than silently deduplicated.
- Registration alone creates no version; the first snapshot is produced by the first successful fetch (Phase 3).
- All inputs pass Bean Validation on DTOs; failures surface as RFC 7807 problem responses.

## 11. Policy Versioning

Each successful fetch of changed content creates a new `PolicyVersion` (PLANNED, Phase 3):

- **Append-only and immutable** (ADR-003): versions are never edited or deleted; corrections produce new versions.
- Each version stores the normalized content, a monotonically increasing version number per policy, the content hash, timestamps, and the fetch attempt that produced it.
- **Duplicate detection:** if the freshly normalized content hashes identically to the latest version, no new version is created; the attempt is recorded as `SKIPPED_UNCHANGED`. This makes repeated scheduled checks cheap and keeps the version history meaningful.

## 12. Content Hashing

Hashing serves two distinct purposes (PLANNED):

- **SHA-256 exact normalized-content hashing.** The primary mechanism for duplicate detection and exact identity. The hash is computed over the *normalized* content, not raw HTML, so presentation-only noise does not create phantom versions. Hash inputs and normalization rules are pinned by characterization tests so the algorithm cannot drift silently.
- **SimHash for near-duplicate detection.** An approximate fingerprint used where exact equality is too strict: recognizing that a page changed only trivially, and (in the diff engine) matching sections that were modified or moved. SimHash is a similarity signal, never a substitute for the exact hash.
- **ETag / Last-Modified support (FUTURE).** Conditional HTTP requests could avoid re-downloading unchanged documents. This is an optimization reserved for later; correctness never depends on it, because the pipeline must work with servers that do not honor conditional headers.

## 13. Section Extraction

Semantic comparison at whole-document granularity is too coarse: a one-line clause change inside a ten-page policy would register as a full-document rewrite. Policies are therefore normalized and divided into meaningful sections (PLANNED, Phase 4) before any comparison.

- Sections are derived from document structure — headings and comparable structural markers — rather than fixed byte offsets.
- Each section carries its own fingerprint, allowing the diff engine to compare at section granularity.
- Edge cases have defined behavior: a document with no detectable headings falls back to a single whole-document section; empty documents produce an empty section list. These rules are fixed by tests.
- Sectioning quality determines diff quality: imperfect sectioning degrades gracefully (coarser diffs) but never causes the pipeline to fail.

## 14. Diff Engine

The diff engine (PLANNED, Phase 4) compares two versions at section granularity:

1. **Exact matching first.** Sections with identical fingerprints are matched directly — the common case and the cheapest path.
2. **SimHash / fuzzy matching where appropriate.** Sections whose exact fingerprints differ are candidates for modified or moved matches; approximate similarity decides whether they correspond.
3. **Change classification per section pair:**
   - **Added** — present in the new version, no counterpart in the old.
   - **Removed** — present in the old version, no counterpart in the new.
   - **Modified** — matched by position or identity but with different content.
   - **Moved** — same content, different position.
4. **Word-level comparison using LCS where appropriate.** For modified sections, a longest-common-subsequence comparison at word level produces a precise, human-readable view of what changed inside the section — the input the concept-matching stage needs to find the operative clause.

## 15. Meaningful-Change Filtering

Not every textual difference is a policy change. Reformatting, punctuation, legal boilerplate shuffling, or a corrected typo must not produce changes, scores, or notifications. The classification stage (PLANNED) therefore distinguishes:

- **Meaningful changes** — differences that alter obligations, rights, data flows, or scope, which flow on to concept matching and impact scoring.
- **Insignificant noise** — differences that survive normalization but carry no semantic weight (formatting-only, whitespace artifacts, trivial editorial edits).

The boundary is explicit rule-based logic, fixed by tests: given identical inputs, the same changes are always classified as meaningful or ignored. When in doubt, a change is treated as meaningful — false positives are cheaper for the user to dismiss than silently dropped material changes.

## 16. Change Classification

Each meaningful change is categorized (PLANNED) along two axes:

- **Change type:** added, removed, modified, moved (from the diff engine).
- **Substance:** substantive (affects rights, data usage, sharing, retention, user obligations) versus editorial (wording, structure, or clarification without behavioral effect).

Only substantive changes proceed to concept matching and impact scoring. Editorial changes remain visible in the version history and diff API but carry no impact weight. Classification is rule-driven and database-backed taxonomy-aligned, so categories evolve without code changes where possible.

## 17. Privacy Concept Taxonomy

Privacy concepts (PLANNED, Phase 5) are the vocabulary connecting raw changes to personal impact — for example *Location*, *Third-party sharing*, *Advertising*, *Data retention*, *Deletion rights*, *Cookies*, *Children's data*, *Arbitration*.

- **Database-backed, not enum-only.** Concepts are stored rows with metadata (identifier, label, description, default weight, default sensitivity), seeded via Flyway data migrations. New concepts can be added without code changes or redeploys; an enum would hard-code the taxonomy into the build.
- Each concept defines the deterministic matching patterns (keywords and phrases) used by the concept-matching stage.
- The taxonomy is deliberately extensible: refiners can grow or adjust concepts as the system encounters new policy language, keeping matching quality a data problem rather than a deployment problem.

## 18. ChangeConceptMatch

`ChangeConceptMatch` (PLANNED, Phase 5) records *why* a change relates to a concept — not just that it does:

- References the `PolicyChange` and the `PrivacyConcept`.
- Carries **evidence**: the matched text fragments, the pattern or rule that fired, and the position/section the evidence came from.
- Carries a confidence-style category derived deterministically (for example: direct keyword evidence versus indirect phrasing), never a random or model-produced score in the core engine.

This record is the audit trail for impact scoring: given any score, a reviewer can trace exactly which text triggered which concept. Evidence also feeds the explanation layer if the optional LLM integration is enabled.

## 19. User Privacy Profile

A user's privacy profile (PLANNED, Phase 6) expresses how sensitive they are to each concept:

- `UserPrivacyPreference` holds one sensitivity value per (user, concept), typically a 0–5 scale, plus an enabled flag.
- Concepts the user has not configured resolve to sensible defaults derived from the concept definition — resolved in code, never backfilled into the database.
- The profile is edited through a dedicated API (§28) and affects only future assessments; existing assessments are historical facts and are not rewritten retroactively.

## 20. Impact Engine

The Impact Engine (PLANNED, Phase 6) is the deterministic heart of the system. Conceptually it is a pure function:

```
ImpactEngine.assess(
    changes,          // meaningful PolicyChange set
    conceptMatches,   // ChangeConceptMatch records with evidence
    preferences,      // the user's effective privacy profile
    fixedRules        // versioned scoring rules
) → ImpactAssessment
```

The engine must have:

- **no I/O** — it reads nothing from disk or network;
- **no repository access** — it never queries the database; all inputs are passed in;
- **no current-time dependency** — no clock access; timestamps are supplied by callers;
- **no randomness** — the same inputs always produce the identical assessment.

Consequences of this design:

- The engine is unit-testable without a Spring context and without a database.
- Determinism is a tested property: identical inputs must produce identical output, including breakdown ordering.
- Persisted assessments record the rules version used, so historical scores remain explainable even after rules evolve.
- The engine consumes only typed outputs of the diff and concept stages — it never reads raw policy text, HTML, or section records directly.

## 21. Impact Scoring

Scoring is table-driven, not hardcoded arithmetic scattered through code (PLANNED):

```
raw score = Σ over matched changes:
    concept weight
  × user sensitivity
  × change-type multiplier
  × section criticality
```

- **Concept weight** — per-concept constant from the taxonomy (e.g., third-party data sharing weighs more than cookie usage).
- **User sensitivity** — from the user's privacy profile (0–5).
- **Change-type multiplier** — e.g., modified ×1.0, removed ×0.8, added ×0.6, moved ×0.2.
- **Section criticality** — sections that carry operative clauses (e.g., "Data Sharing", "Third Parties") weigh more than informational sections.

The raw score is normalized to a **0–100 range** using a defined, versioned normalization curve. Rules (weights, multipliers, curve) are **versioned**: every persisted assessment records which rules version produced it, so historical scores are never silently invalidated.

The **score breakdown is persisted** with the assessment: every contributing `(change, concept, points)` line is stored so the UI and reviewers can answer "why this score?" without recomputation. Breakdown is a first-class part of `ImpactAssessment`, not a derived afterthought.

## 22. Personalized Assessment

Personalization is where policy changes become *this user's* changes (PLANNED):

- The pipeline computes change-level facts (diff, classification, concept matches) **once per policy update** — they are user-independent.
- Assessment is computed **per user**, applying that user's effective privacy profile to the shared change facts. Two users with different profiles receive different scores and potentially different impact levels from the same change set.
- Impact is expressed on a named scale (e.g., NONE / LOW / MEDIUM / HIGH / CRITICAL) mapped from the normalized score, alongside the numeric score and breakdown.
- Assessments are generated for changes since the user's last acknowledged version, keeping per-user work proportional to what is actually new for them.
- Updating preferences affects future assessments only; historical assessments are immutable records (consistent with ADR-003's philosophy).

## 23. Recommendation Engine

The Recommendation Engine (Phase 2R v1) converts a personalized assessment into deterministic actions:

- **Ordered rules.** Rules are an ordered list of `RecommendationRule` descriptors, code-defined and frozen (`RECOMMENDATION_RULES_VERSION = 1`), never externalized to the database. Each rule conditions on (concept category, change type, impact band) and carries an action kind (`REVIEW_SETTINGS`, `EXERCISE_DELETION`, `OPT_OUT_SHARING`, `NONE_REQUIRED`). In Phase 2R, "concept category" is proxied by explicit concept-code sets (no category column exists). Concept-level rules condition on the user-specific `personalizedBand`; only the `NONE_REQUIRED` closure rule conditions on the assessment `aggregateBand`.
- **Deterministic execution.** Rules are evaluated in declared order; matching rules emit recommendations; output is ranked by personalized score, then rule order, then concept code (final tie-break). No randomness, no model involvement.
- **Deduplication.** Candidates matching the same `(action kind, concept)` collapse to the highest-scoring one (ties fall to lower rule order).
- **Append-only idempotency (Phase 2R v1 interpretation).** Recommendations are persisted once per assessment and are never mutated or deleted. The current/pending recommendation set for a user is the set attached to the user's latest assessment; a new assessment supersedes the previous pending set by becoming the latest, while prior recommendations remain as history. This deliberately reinterprets the earlier "regeneration replaces the pending set atomically" wording to keep the pipeline append-only (see DECISIONS.md ADR-009).
- **Closure.** When no concept-level rule fires and the aggregate band is NONE or LOW, exactly one assessment-level `NONE_REQUIRED` recommendation (null concept) is emitted — meaning "no actionable recommendation was generated for this assessment", not "the policy has no changes".
- **Extensibility.** New rules are declarative additions to one package; the engine's execution semantics never change.


## 24. Scheduled Monitoring

The monitoring module (PLANNED, Phase 9) keeps policy data current without user action. It is an orchestrator above the pipeline, not a pipeline stage. **Observation-attempt recording is IMPLEMENTED (Phase 2S); scheduled triggering is IMPLEMENTED (Phase 2T, single-instance sequential ticks); atomic work claiming is IMPLEMENTED (Phase 2U, V11 partial-unique in-flight guard plus conditional claim, cross-instance); retry with bounded backoff is IMPLEMENTED (Phase 2U.1, deferred through next_check_at, V12 failure classification); stale-IN_PROGRESS recovery is IMPLEMENTED (Phase 2U.2, started_at lease with a scheduled sweeper, in-place FAILED/TRANSIENT transition, backoff reschedule, V13 lookup index; see DECISIONS.md ADR-014).**

- **Scheduled checks.** A Spring `@Scheduled` monitor enqueues work for each active policy whose next check time has elapsed; the default interval is configurable. **Implemented in Phase 2T** as a fixed-delay, single-threaded, sequential tick over ACTIVE policies with `next_check_at <= now` (deterministic next-check/id order, one observation per policy per tick, next check advanced by the configured interval from the tick start — including after failures; see DECISIONS.md ADR-011). Retry with bounded backoff is implemented in Phase 2U.1 (failed observations reschedule through `next_check_at`; see ADR-013); claiming is implemented in Phase 2U; stale-attempt recovery and multi-instance operation remain planned.

- **Scheduled checks.** A Spring `@Scheduled` monitor enqueues work for each active policy whose next check time has elapsed; the default interval is configurable.
- **PolicyFetchAttempt.** Every check — scheduled or manual — is recorded as a `PolicyFetchAttempt` with its trigger, outcome, HTTP status, bytes fetched, duration, error message, and attempt number. **Implemented in Phase 2S** (Flyway V9 `policy_fetch_attempt` table; attempts are history with one sanctioned terminal transition; the only exercised trigger is `MANUAL`; see DECISIONS.md ADR-010).
- **Statuses:** `PENDING`, `IN_PROGRESS`, `SUCCESS`, `FAILED`, `SKIPPED_UNCHANGED`. Transitions are explicit and recorded; an `IN_PROGRESS` row orphaned by a crashed worker is recovered by the Phase 2U.2 sweeper once its `started_at` lease expires (in-place `FAILED`/`TRANSIENT` transition with a `Stale IN_PROGRESS` error message, then a backoff reschedule through `next_check_at`; see DECISIONS.md ADR-014).
- **Retry strategy.** **IMPLEMENTED (Phase 2U.1).** Transient failures (timeouts, connection failures, HTTP 5xx, HTTP 429, DNS resolution failures, persistence/concurrency failures) retry up to a configured maximum, each retry persisted as a new attempt row with an incremented number; permanent failures (invalid or rejected URLs, HTTP 4xx, SSRF policy rejections, oversized responses, deterministic pipeline failures) fail fast without retry.
- **Exponential backoff with jitter.** **IMPLEMENTED (Phase 2U.1).** Failed policies are re-checked on a growing delay (`min(cap, base · multiplier^(n-1))` with equal jitter) represented through `next_check_at` — no retry row is ever held open across the wait — with added randomness to avoid synchronized retry storms across many policies. Exhausted and permanent failures resume the regular check interval.
- **Concurrency control.** **IMPLEMENTED (Phase 2U).** Work is claimed atomically in the database (insert `PENDING`, then `UPDATE ... SET status='IN_PROGRESS' WHERE status='PENDING' ...` in one short transaction), backed by the V11 partial unique index preventing two in-flight attempts for the same policy.
- **Prevention of concurrent fetches for the same policy.** **IMPLEMENTED (Phase 2U).** Manual "check now" requests and the scheduler share the same attempt-claiming path, so both are serialized by the same database constraint. A second trigger while a fetch is in flight is rejected (`PolicyFetchClaimRejectedException` for MANUAL) or skipped for the cycle (SCHEDULED), never run in parallel.

## 25. Notifications

Notifications (Phase 10) close the loop between analysis and attention:

- When a policy check produces new meaningful changes and personalized impact, an in-app notification is recorded for the affected user. **Emission is IMPLEMENTED (Phase 10A, service layer only):** exactly one `Notification` per assessment whose persisted recommendations include a rule code other than `REC-NONE-REQUIRED`, ownership derived through the assessment (no duplicated `user_id`), idempotent per assessment via `UNIQUE(assessment_id)`, read/unread state with an explicit mark-read operation, Flyway V14; see DECISIONS.md ADR-015.
- A notification carries references to the assessment and change set so the client can navigate directly to the details.
- The feed supports read/unread state with an explicit "mark read" action; it is a record, not a message queue — no external broker is involved. **The REST feed endpoints remain PLANNED (Phase 10B, with Phase 8 authentication).**
- Emission is part of the single pipeline completion path; both manual and scheduled checks produce notifications identically. **Automatic observation fan-out to subscribers remains PLANNED (Phase 10B — it needs the policy ownership model); Phase 10A emits on explicit per-user flows.**

## 26. Audit Events

Audit events (PLANNED, Phase 11) provide an immutable record of significant system events:

- **Append-only.** Audit events are written once and never updated or deleted; there are no mutation paths in code.
- Recorded events include policy registration, fetch outcomes, version creation, assessment generation, recommendation changes, preference updates, and authentication-relevant events.
- Each event records what happened, when (supplied time), and which user or system actor triggered it.
- Users see their own audit trail through the API; broader administrative access is deliberately deferred and would require an explicit authorization design.

## 27. Security Architecture

Security spans two distinct concerns: protecting the API and its users, and protecting the system from the URLs users supply.

### Application security (auth lands in Phase 8)

- **Spring Security** with an explicit filter chain. Until Phase 8, a transitional permit-all configuration exists purely so pre-auth phases are testable locally; this is a documented temporary state, not a target.
- **Stateless API authentication.** No server-side sessions; the API is designed for token-based auth from the start.
- **JWT access/refresh tokens (PLANNED, Phase 8).** Short-lived access tokens (~15 min) plus longer-lived refresh tokens; signing secrets supplied exclusively via environment configuration, never committed.
- **BCrypt password hashing.** Passwords are hashed with BCrypt; raw passwords are never persisted or logged.
- **Role/user authorization.** A minimal role model (`USER` initially, with room for `ADMIN`); authorization checked at the service/web boundary.
- **User-data isolation.** Every user-owned query filters by the authenticated principal at the repository/service layer. Cross-user access is verified by dedicated tests.
- **Existence-leak avoidance.** Cross-user resource access returns 404 rather than 403, so responses do not reveal whether a resource with a given identifier exists for another user.
- **CSRF considerations.** CSRF protection is disabled for the stateless token API (no cookie-based sessions); this decision is documented and revisited if cookie-based auth is ever introduced.
- **Secrets handling.** Credentials and secrets live only in the Git-ignored local configuration or environment variables; the committed template contains placeholders only.

### SSRF protection for user-supplied policy URLs (architectural requirement)

SSRF protection **must exist before any external URL fetching is implemented** (Phase 2). The fetch layer enforces:

- **HTTPS-only where appropriate** — user-supplied policy URLs are restricted to https.
- **DNS resolution checks** — the resolved address, not just the hostname, is validated before connecting.
- **Block loopback / private / link-local addresses** — resolved IPs in loopback, private, link-local, and other non-routable ranges are rejected.
- **Revalidate redirects** — every redirect target is re-validated with the same rules; redirects cannot be used to bypass the guard.
- **Connection/read timeouts** — bounded connection and read times so a hostile server cannot hold the worker.
- **Response-size limits** — maximum response size enforced during streaming, not after download.

## 28. REST API Design

All REST APIs are versioned under **`/api/v1`**. The following surface is PLANNED architecture only — none of these endpoints are implemented yet. Each endpoint appears in the phase that delivers it (§31).

| Method | Path | Purpose | Phase |
| --- | --- | --- | --- |
| POST | `/api/v1/policies` | Register a policy | 1 |
| GET | `/api/v1/policies` | List the user's policies | 1 |
| GET | `/api/v1/policies/{id}` | Policy detail | 1 |
| DELETE | `/api/v1/policies/{id}` | Remove a policy | 1 |
| POST | `/api/v1/policies/{id}/check` | Trigger a manual fetch/check | 2–3 |
| GET | `/api/v1/policies/{id}/versions` | Version history | 3 |
| GET | `/api/v1/policies/{id}/versions/{versionId}` | Version snapshot content | 3 |
| GET | `/api/v1/policies/{id}/changes` | Changes for a policy | 4 |
| GET | `/api/v1/policies/{id}/versions/{a}/diff/{b}` | Diff between two versions | 4 |
| GET / PUT | `/api/v1/me/privacy-preferences` | Read / bulk-update the privacy profile | 6 |
| GET | `/api/v1/changes/{changeId}/assessment` | Scored assessment with breakdown | 6 |
| GET | `/api/v1/me/impact-summary` | Pending-impact digest for the user | 6 |
| GET | `/api/v1/me/recommendations` | Current pending recommendations | 7 |
| POST | `/api/v1/auth/register` | Account registration | 8 |
| POST | `/api/v1/auth/login` | Authentication (access + refresh token) | 8 |
| POST | `/api/v1/auth/refresh` | Refresh the access token | 8 |
| GET | `/api/v1/me/notifications` | In-app notification feed | 10 |
| POST | `/api/v1/me/notifications/{id}/read` | Mark a notification read | 10 |
| GET | `/api/v1/me/audit-events` | The user's audit trail | 11 |

API conventions:

- **DTOs only.** Controllers accept and return dedicated request/response DTOs. JPA entities are never exposed directly; this prevents lazy-loading accidents, over-fetching, and accidental schema coupling.
- **Input validation.** All request bodies are validated at the boundary (Bean Validation); invalid input yields a precise problem response, not a 500.
- **Error handling.** Errors follow **RFC 7807** (`application/problem+json`) via a single global exception handler: a stable problem type, a human-readable detail, and an accurate status code.
- **Thin controllers.** Controllers parse input, delegate to an application service, and map the result; no business logic lives in the web layer.
- **User-scoped by default.** Every `me/*` and user-owned resource is resolved against the authenticated principal; identifiers never bypass ownership checks.

## 29. Testing Architecture

The pipeline's determinism is the system's core asset, so testing is built around **fixed fixtures and golden expectations**. Determinism is a tested property: identical inputs must always produce identical outputs, byte for byte.

### Strategy

- **Deterministic unit tests.** Engines (diff, concept matching, impact, recommendations) are plain Java where practical and tested without a Spring context. Majority of the suite; fast and stable.
- **Fixed fixtures.** Realistic sample policy pages (v1 and a v2 with planted changes: one modified, one added, one removed, one moved section) plus edge cases (no headings, empty document, entity/whitespace noise) live under test resources and are checked in.
- **Golden expectations.** Golden normalized text, golden section extraction, golden diffs, golden impact assessments, and golden recommendations are checked in as expected outputs. A test failure against a golden file is a reviewed, deliberate change — never a silent behavior drift.
- **Testcontainers PostgreSQL tests.** Repository and versioning behavior is tested against real PostgreSQL, because immutability, monotonic version numbers, and concurrency claims must be verified against the actual engine. **No H2** — in-memory substitutes cannot validate the behavior that matters.
- **WebMvcTest controller tests.** Slice tests for DTO binding, validation, status codes, and problem+json error handling.
- **Full application context smoke test.** One test boots the entire context to catch wiring and configuration regressions.
- **Frozen Clock.** Time is injected everywhere; tests pin it so timestamps never cause flaky comparisons.
- **End-to-end golden pipeline test.** A stub fetcher (no network) feeds fixture v1 then v2 through the entire pipeline; the resulting version, changes, assessments, and recommendations are compared against golden files. This is the determinism contract of the system.

### Key test areas

| Area | What is pinned |
| --- | --- |
| URL validation | Scheme/host rules, rejected inputs, exact error semantics |
| SSRF protection | Blocked address ranges (loopback/private/link-local), redirect re-validation, timeout and size enforcement |
| Normalization idempotency | `normalize(normalize(x)) == normalize(x)`; equivalence of presentation variants; boundary between noise and content |
| SHA-256 | Literal hash characterization tests — known inputs assert exact hex digests, so algorithm drift fails the build |
| Duplicate detection | Identical content creates no version; changed content creates one; attempt recorded as `SKIPPED_UNCHANGED` |
| Section matching | Same fingerprint → unchanged; same title/different content → modified; position-only differences → moved; unmatched → added/removed |
| Added/removed/modified/moved changes | Golden diff of fixture v1 → v2 yields exactly the planted change set with correct types, references, and ordering |
| Concept matching | Positive/negative pattern cases per concept, multiple concepts per change, deterministic output ordering |
| Impact scoring | Table-driven: seeded profile × golden change set → exact score and exact breakdown; boundary cases (zero sensitivity, no matches) |
| Recommendation rules | Condition → expected recommendation per rule; ranking, deduplication, and idempotency |

## 30. Future Extension Points

The following are deliberate extension points only. **None of them are implemented, and none are scheduled, unless a later phase explicitly requires them.** They are listed so the current architecture does not preclude them.

- **Ollama / local LLM explanation layer** (Phase 12, optional): natural-language explanations of existing assessments; the deterministic engine remains authoritative (ADR-006).
- **Queue/worker architecture:** the pipeline is trigger-agnostic (manual endpoint, scheduler, future webhook); a queue-backed worker is a new trigger, not a redesign. Kafka/RabbitMQ remain out of scope.
- **Redis:** caching and distributed rate limiting, if scale ever justifies it.
- **Distributed scheduling:** ShedLock (JDBC-backed) or PostgreSQL advisory locks if the application runs on multiple instances.
- **Email notifications:** a second delivery channel consuming the same notification records; the in-app feed remains the primary one.
- **Browser extension:** a client consuming the same REST surface.
- **Organization / multi-tenant support:** shared policy subscriptions and team-level privacy profiles; requires an explicit ownership-model decision first.
- **Pluggable fetchers:** a `PolicyContentFetcher`-style interface allows a headless-browser fetcher for JavaScript-rendered pages without touching the pipeline.
- **Pluggable explainers:** an adapter interface lets different explanation backends (or none) sit behind the same advisory contract.
- **Observability:** metrics, tracing, and structured logging via Actuator and standard Spring facilities (Phase 13).
- **Cloud deployment:** containerization and hosting decisions deferred to Phase 13; no cloud dependency exists in the architecture.

## 31. Implementation Phases

Implementation proceeds through the approved roadmap below. Phases are **vertical slices**: each one ends with a runnable, tested, committed increment — not with scaffolding (ADR-007). No phase begins while the previous one is incomplete or failing. A dependency is added only in the phase that needs it.

| Phase | Name | Purpose and scope |
| --- | --- | --- |
| 0 | **Foundation** | Existing Spring Boot 4.1.1 project, PostgreSQL connectivity, Git repository, configuration and security baseline (transitional permit-all chain), clean test run. **Status: COMPLETE.** |
| 1 | **Policy Registration** | Policy entity, repository, service, controller; URL validation; DTOs; global RFC 7807 exception handling; Flyway V1 policy schema; `ddl-auto=validate`; Testcontainers repository tests. *New dependency: Flyway, Testcontainers (test scope).* |
| 2 | **Policy Fetching** | Fetcher interface and HTTP/Jsoup implementation; SSRF protection (mandatory before fetching); timeouts and response-size limits; readable-content extraction; normalization; fetcher tests on fixtures. *New dependency: Jsoup.* |
| 3 | **Policy Versioning** | PolicyVersion; SHA-256 content hashing; duplicate detection; first snapshot; immutable, monotonically numbered versions with Testcontainers tests. |
| 4 | **Diff Engine** | PolicySection; section extraction; fingerprints; version comparison; added/removed/modified/moved detection; PolicyChange; deterministic diff tests with golden fixtures. |
| 5 | **Change Intelligence** | PrivacyConcept (database-backed, seeded); ChangeConceptMatch with evidence; deterministic concept classification; golden tests. |
| 6 | **Personalized Impact** | UserPrivacyPreference; ImpactAssessment; pure scoring engine with persisted breakdown; current-user abstraction; golden assessment tests. |
| 7 | **Recommendations** | Recommendation; ordered deterministic rule engine; deduplication and idempotency; golden recommendation tests. |
| 8 | **Authentication & Security** | User entity; real security filter chain; JWT access/refresh; BCrypt; authorization; strict user-data isolation tests. *New dependency: JWT library (chosen in-phase).* |
| 9 | **Scheduled Monitoring** | PolicyFetchAttempt; scheduler; atomic work claiming; concurrent-fetch prevention per policy; retry with exponential backoff and jitter; automatic checks through the one pipeline. |
| 10 | **Notifications** | Notification entity; in-app feed with read/unread; emission at pipeline completion; tests. |
| 11 | **Audit** | AuditEvent; append-only immutable history; user-visible trail; tests. |
| 12 | **Optional Local AI** | Ollama integration for natural-language explanations only; advisory layer; deterministic engine remains authoritative (ADR-006). Skipped unless explicitly requested. |
| 13 | **Production Hardening** | Rate limiting; observability (Actuator); query indexes via Flyway migrations; Dockerization and deployment decisions. *New dependencies: Actuator; rate-limiting library if needed.* |

Current position: **Phase 0 complete; Phase 1 is next** (see PROJECT_STATUS.md).









