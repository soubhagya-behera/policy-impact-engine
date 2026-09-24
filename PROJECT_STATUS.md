# Policy Impact Engine — Project Status

**Project:** Policy Impact Engine
**Repository:** policy-impact-engine

## Stack

- Java 17
- Spring Boot 4.1.1
- Maven
- PostgreSQL
- Spring Web MVC
- Spring Data JPA
- Spring Security
- Validation
- Lombok

## Database

PostgreSQL database: `policypulse`

The database name `policypulse` is retained intentionally. No rename is planned as part of the current phase.

## Completed

- Initial Spring Boot backend created
- PostgreSQL connectivity verified
- GitHub repository created
- Git initialized
- Initial backend committed and pushed
- Project renamed from PolicyPulse to Policy Impact Engine
- Java package renamed to `com.soubhagya.policyimpactengine`
- Main application class renamed to `PolicyImpactEngineApplication`
- `application.properties` Git-ignored (local configuration only)
- `application-example.properties` established as the GitHub-safe configuration template
- Clean Maven tests passing
- Spring Boot application startup verified against PostgreSQL
- Architecture designed and approved (see ARCHITECTURE.md and DECISIONS.md)

## Current Phase

**Phase 0 — Foundation: COMPLETE**

**Phase 1 — Policy Registration
Status: COMPLETE**

Completed:
- Policy persistence foundation
- Policy entity
- Policy repository
- Policy registration DTOs
- URL validation
- Policy service
- Policy controller
- RFC 7807/global error handling
- Final integration/acceptance tests

**Phase 2 — Policy Fetching
Status: IN PROGRESS**

Completed (Phase 2A — Fetcher Foundation):
- PolicyFetcher abstraction (application-level interface)
- FetchResult model (url, statusCode, contentType, body)
- PolicyFetchException (small hierarchy, non-2xx and IO/timeout handling)
- HttpPolicyFetcher (Java HttpClient, explicit connect/request timeouts, Redirect.NEVER, independent of controller/JPA)

Completed (Phase 2B — SSRF Protection):
- SsrfAddressValidator (pure IP-range checks: loopback, private 10/8 172.16/12 192.168/16, link-local, unspecified, multicast, CGNAT, TEST-NET, IPv6 loopback/link-local/ULA fc00::/7, documentation 2001:db8::/32)
- SsrfGuard (DNS resolution via DnsResolver, validates every resolved address, blocks if ANY private/non-public, @Component, TOCTOU limitation documented)
- DnsResolver abstraction (InetAddress::getAllByName in production, mocked in tests for deterministic private-host tests)
- HttpPolicyFetcher integrated with SsrfGuard (validateUrl before HttpClient, retains Redirect.NEVER)

Completed (Phase 2C — Response Size Limits):
- MAX_RESPONSE_BODY_BYTES (1 MiB, byte-counted before UTF-8 decoding, documented in HttpPolicyFetcher)
- Two-layer enforcement: Content-Length pre-check (reject without consuming body) + bounded streaming read via BodyHandlers.ofInputStream() (chunked/length-omitted safe, never unbounded ofString() allocation)
- Oversized responses fail with PolicyFetchException (no partial content, no silent truncation)
- HttpPolicyFetcherSizeLimitTest (7 deterministic local-HttpServer tests: below/at/above limit, Content-Length rejection, chunked rejection, multibyte byte-counting, no-partial-content)

Completed (Phase 2D — HTML Extraction):
- Jsoup 1.23.2 production dependency (current stable, Java 17 compatible)
- PolicyContentExtractor abstraction (single extract method, plain Java, no DB/security/HTTP/user coupling)
- JsoupPolicyContentExtractor (lenient parse, script/style removal, body-only leaf-block lines in document order, entities decoded, null/blank/malformed safe, deterministic; no normalization)
- JsoupPolicyContentExtractorTest (8 deterministic inline-fixture tests, no network)
- HttpPolicyFetcher behavior unchanged (SSRF, Redirect.NEVER, size limit, timeouts, status handling)

Completed (Phase 2E — Text Normalization):
- PolicyTextNormalizer abstraction (single normalize method, plain Java, no Spring/DB/HTTP/security/user/clock/randomness)
- DefaultPolicyTextNormalizer (deterministic/idempotent: null→empty, CRLF/CR→LF, horizontal whitespace collapse, per-line trim, blank-line collapse with paragraph preservation; case/punctuation/wording untouched)
- DefaultPolicyTextNormalizerTest (14 deterministic inline-string tests, no network/DB)
- JsoupPolicyContentExtractor and HttpPolicyFetcher unchanged (no extraction/fetch regressions)

Completed (Phase 2F — Content Hashing):
- PolicyContentHasher abstraction (single hash method, plain Java, no Spring/DB/HTTP/security/user/clock/randomness)
- Sha256PolicyContentHasher (JDK MessageDigest SHA-256, UTF-8 bytes explicitly, lowercase hex, deterministic/stateless/thread-safe, null→hash of empty string)
- Sha256PolicyContentHasherTest (8 deterministic unit tests: empty vector, ASCII vectors, Unicode, determinism, lowercase hex 64-char, different-content divergence, whitespace non-normalization)
- PolicyNormalizationHashingIntegrationTest (2 pipeline tests: noisy formatting → normalizer → canonical → hasher → stable hash; same formatting noise yields same normalized text and same hash; raw-hash divergence; null/empty equivalence)
- DefaultPolicyTextNormalizer, JsoupPolicyContentExtractor and HttpPolicyFetcher unchanged (no extraction/fetch/normalization regressions)

Completed (Phase 2G — Version Persistence & Exact Change Detection):
- Flyway V2 policy_version schema (immutable, append-only; unique constraint on policy_id + version_number; per-policy latest lookup index; V1 untouched)
- PolicyVersion immutable JPA entity (policy/domain, UUID id, parent Policy, per-policy version number, content hash, normalized TEXT content, observed_at; no setters, all columns updatable=false)
- PolicyVersionRepository (latest-version lookup + sequence-order listing only; no update/delete operations)
- PolicyVersionService observe(policyId, normalizedContent, contentHash) (FIRST_VERSION / UNCHANGED / NEW_VERSION; no fetch/extract/normalize/hash inside; database unique constraint as the concurrency guard, retry owned by future orchestration)
- PolicyVersionObservation result record + PolicyVersionObservationOutcome enum
- PolicyVersionRepositoryTest (7 Testcontainers tests) + PolicyVersionServiceTest (14 Testcontainers tests) — 168 tests passing, BUILD SUCCESS

Completed (Phase 2H — Policy Observation Orchestration):
- PolicyObservationService.observe(policyId) (application-level orchestration: load → fetch → extract → normalize → hash → PolicyVersionService.observe; owns no pipeline stage itself)
- PolicyObservationResult record (policyId, outcome, versionNumber, contentHash; no JPA entities exposed)
- PolicyFetchConfiguration (Spring wiring for the stateless extractor/normalizer/hasher; existing components untouched)
- Deliberately NOT @Transactional: policy lookup uses the repository's short read, the HTTP fetch runs with no DB transaction held, PolicyVersionService owns its persistence transaction
- PolicyObservationServiceTest (10 deterministic Mockito unit tests, no network/DB: first/unchanged/changed, not-found without fetch, fetch/extraction/normalization/hashing failure propagation, exact content+hash capture, invocation order)
- PolicyObservationServiceIntegrationTest (Testcontainers PostgreSQL end-to-end: real repos + real version service + real extractor/normalizer/hasher + stub fetcher; v1 created → reformatted same content UNCHANGED → changed wording v2; v1 immutability, hashes, and 1/2 numbering verified) — 179 tests passing, BUILD SUCCESS

Completed (Phase 2I — Deterministic Policy Diff Engine):
- Top-level diff module (com.soubhagya.policyimpactengine.diff, per ARCHITECTURE.md §7): PolicyChangeType (ADDED/REMOVED/MODIFIED), PolicyChange immutable record (null-shape + non-equal MODIFIED validation), PolicyDiffResult immutable ordered record, PolicyDiffEngine abstraction
- LineBasedPolicyDiffEngine (JDK-only classical LCS over normalized-text lines with deterministic delete-on-tie forward backtrack; conservative rule: only a 1-removed + 1-added hunk becomes MODIFIED, all other hunks stay primitive ADDED/REMOVED; null means empty document; no normalization performed; stateless/thread-safe/pure, no Spring/DB/HTTP/user/clock/randomness)
- Not wired into PolicyObservationService (observation pipeline unchanged; version-to-version integration belongs to the next slice)
- LineBasedPolicyDiffEngineTest (21 deterministic inline-string unit tests, no network/DB/Spring) + PolicyDiffGoldenTest (3 tests pinning the V1→V2 privacy-policy update to exactly 3 MODIFIED changes with exact old/new text) — 203 tests passing, BUILD SUCCESS

Completed (Phase 2J — Diff-to-Version Integration):
- PolicyObservationResult gains Optional<PolicyDiffResult> diff (present only for NEW_VERSION; empty for FIRST_VERSION/UNCHANGED; no JPA entities exposed)
- PolicyVersionRepository gains findByPolicy_IdAndVersionNumber (derived query, no migration) for predecessor lookup
- PolicyObservationService coordinates previous (N-1) + new (N) persisted contents through the pure PolicyDiffEngine; diff engine untouched, PolicyVersionService untouched; FIRST_VERSION/UNCHANGED never invoke diff or predecessor lookup
- PolicyDiffConfiguration exposes the stateless engine as a Spring bean (engine class stays Spring-free)
- Still NOT @Transactional: predecessor read runs in its own short read transaction; HTTP fetch never inside a DB transaction
- Diff-failure rule: version persistence stands, the failure propagates, no fake empty diff and no recovery system in this slice
- PolicyObservationServiceDiffTest (8 deterministic Mockito unit tests) + PolicyObservationDiffIntegrationTest (Testcontainers end-to-end with counting diff decorator: v1 FIRST_VERSION no diff/no invocation → reformatted UNCHANGED no diff/no invocation → wording NEW_VERSION v2 with 2 MODIFIED changes, v1 immutable)

Completed (Phase 2K — Persist Policy Changes):
- Flyway V3 policy_change schema (immutable, append-only; FKs to previous/new PolicyVersion; CHECK on change_type + null-shape + change_order; UNIQUE on new_version_id + change_order as the document-order guard and retrieval index; V1/V2 untouched)
- PolicyChangeRecord immutable JPA entity in diff.domain (constructor-fixed, no setters, all columns updatable=false; changeOrder is the zero-based document position; owning policy answered via versions, no duplicated policy_id)
- PolicyChangeRecordRepository (save support + findByNewVersion_IdOrderByChangeOrderAsc only; no diff logic, no update/delete)
- PolicyObservationPersistenceService owns the single short persistence transaction AFTER the fetch: observe version (joins tx) → load predecessor N-1 → pure diff → saveAll+flush changes in order; HTTP fetch stays outside the transaction, the pure diff runs inside with no I/O
- PolicyObservationService is now a thin non-transactional orchestrator: load → fetch → extract → normalize → hash → persistenceService.store
- Atomicity: version N and its change rows commit/rollback together; change-persistence or diff failure propagates with no successful result and no partial version row (retry re-attempts the whole transition)
- Empty diff for NEW_VERSION persists zero rows explicitly (no invented change) and still carries the present empty diff; FIRST_VERSION/UNCHANGED never invoke diff and persist nothing
- PolicyChangeRecordRepositoryTest (Testcontainers repository tests) + PolicyChangePersistenceIntegrationTest (Testcontainers end-to-end v1→v2→v3 with stub fetcher, atomic-rollback and empty-diff cases) plus updated orchestrator/persistence unit tests

Completed (Phase 2L — SimHash / Near-Duplicate Detection):
- diff-module SimHash utility only (PolicySimHash abstraction + DefaultPolicySimHash JDK-only 64-bit Charikar SimHash + SimHashDistance hamming/similarity helpers); plain Java, no Spring/DB/network, not wired into any pipeline
- SHA-256 remains the sole exact-identity mechanism for unchanged detection; SimHash is an additional similarity signal only, never persisted, no migration, no threshold
- Tokenization documented on the implementation: Unicode letter/digit runs ([^\p{L}\p{N}]+ separators), weight 1 per occurrence, case preserved, no stemming/stop-words/semantics; FNV-1a 64-bit token hashes; accumulator ties resolve to 0; null/empty/separator-only input yields 0L
- DefaultPolicySimHashTest (empty/same/repeated/multi-instance/small + privacy fixtures, symmetry, Unicode, punctuation/case behavior, post-normalization equivalence, no-distance-assumption for different content, no-self-normalization, SHA-256-vs-SimHash distinction test) + SimHashDistanceTest (0/1/multi-bit distances, symmetry, similarity formula) — deterministic inline-string unit tests only

Completed (Phase 2M — SimHash Integration as Similarity Signal ONLY):
- SimHashSimilarity immutable value object (previousHash/newHash/hammingDistance/similarity via SimHashDistance, linear 1-distance/64, no threshold/classification, pure JDK, no Spring/DB)
- PolicyObservationResult extended with Optional&lt;SimHashSimilarity&gt; similarity: absent for FIRST_VERSION (no previous version) and UNCHANGED (SimHash not run), present for NEW_VERSION only; never exposes JPA entities, never persisted, no migration/column/table/threshold
- PolicyDiffConfiguration exposes DefaultPolicySimHash as Spring bean (PolicySimHash stays pure Spring-free)
- PolicyObservationPersistenceService integrates SimHash as application-level orchestration: persist version+N-1→N diff→changes in one short transaction (no HTTP), then obtain persisted normalized canonical contents (N-1 and N), fingerprint both via PolicySimHash, Hamming distance + linear similarity via SimHashDistance API; no new formula, no threshold, no classification; SimHash receives normalized content never raw HTML; previous/new pairing verified; version+changes commit/rollback together, SimHash runs outside the transaction so a SimHash failure propagates explicitly with no fake similarity and without rolling back the committed transition; SHA-256 remains authoritative (same hash → UNCHANGED no version/diff/similarity, different hash → NEW_VERSION with diff+similarity)
- PolicySimHash and SimHashDistance remain pure Spring-free; SHA-256, PolicyTextNormalizer, PolicyDiffEngine untouched; no repository access in SimHash, no versioning logic in SimHash
- PolicyObservationSimHashTest (9 deterministic Mockito unit tests: FIRST_VERSION no-similarity/no-invocation, UNCHANGED no-similarity/no-invocation, NEW_VERSION normalized pairing, raw-HTML never sent, correct Hamming/similarity vs SimHashDistance, SHA-256 authority, SimHash failure propagation, deterministic repeat) + PolicySimHashIntegrationTest (Testcontainers PostgreSQL end-to-end with real pipeline and counting delegates: v1 FIRST_VERSION no similarity, reformatted UNCHANGED no similarity, wording NEW_VERSION diff+s similarity, SHA/version/change data unaffected, deterministic values)

## Next Phase

**Phase 2 — Policy Fetching (continued)**

Phase 1 is complete. All Phase 1 vertical slices are implemented and tested:

- Policy entity — DONE
- Policy repository — DONE
- Flyway V1 policy schema — DONE
- `spring.jpa.hibernate.ddl-auto=validate` — DONE
- Testcontainers PostgreSQL repository tests — DONE (foundation slice)
- Policy registration DTOs — DONE
- URL validation — DONE (registration-time syntactic rules; network-level SSRF checks belong to the fetch phase)
- Policy service — DONE (registration + retrieval, mocked unit tests)
- Policy controller — DONE (registration/retrieval endpoints, WebMvcTest slice tests)
- Global exception handling / RFC 7807 — DONE (ProblemDetail, 404/400 mappings, application/problem+json)
- Final integration/acceptance tests — DONE (Testcontainers PostgreSQL end-to-end vertical slice: POST → GET by ID → GET collection, invalid URL and bean validation — application/problem+json, Hibernate validate + Flyway)

Phase 2A — Fetcher Foundation is DONE:
- PolicyFetcher interface — DONE
- Basic HTTP fetch implementation (java.net.http.HttpClient, 5s connect / 10s request, Redirect.NEVER, documented non-SSRF-safe) — DONE
- Fetcher tests with local HttpServer (no external network, no Testcontainers) — DONE (success, body, 404, 500, redirect, timeout, unreachable host, blank URL)

Phase 2B — SSRF Protection is DONE:
- SsrfAddressValidator — DONE (37 address-range tests: 127.0.0.1, 10/8, 172.16/12, 192.168/16, link-local, unspecified, multicast, CGNAT, TEST-NET, IPv6 ::1/fe80/fc00/fd00/ff02/2001:db8, public allowed)
- SsrfGuard — DONE (12 tests: private literals, public literal, IPv6 literals, mock DNS private/public/mixed, TOCTOU documented)
- HttpPolicyFetcher SSRF integration — DONE (strict guard blocks localhost/private before connect; Redirect.NEVER retained)
- HttpPolicyFetcherTest updated — DONE (HTTP-layer tests now use permissive guard to isolate transport; new strict tests verify localhost/private blocked)

Phase 2C — Response Size Limits is DONE:
- Byte-counted 1 MiB limit with Content-Length pre-check + streaming enforcement — DONE
- HttpPolicyFetcherSizeLimitTest (below/at/above limit, Content-Length, chunked, multibyte, no-partial-content) — DONE
- Existing SSRF/HTTP status/timeout behavior preserved — DONE (115 tests passing)

Phase 2D — HTML Extraction is DONE:
- Jsoup 1.23.2 + PolicyContentExtractor/JsoupPolicyContentExtractor — DONE
- JsoupPolicyContentExtractorTest (basic, headings/paragraphs, script/style, links, malformed, empty, entities, realistic fixture) — DONE
- HttpPolicyFetcher behavior unchanged — DONE (123 tests passing)

Phase 2E — Text Normalization is DONE:
- PolicyTextNormalizer/DefaultPolicyTextNormalizer — DONE
- DefaultPolicyTextNormalizerTest (null, empty, whitespace-only, LF/CRLF/CR, trim, space/tab collapse, blank-line collapse, punctuation/case/word-boundary preservation, paragraph separation, idempotence, deterministic fixture) — DONE
- Extraction and fetch behavior unchanged — DONE (137 tests passing)

Phase 2F — Content Hashing is DONE:
- PolicyContentHasher/Sha256PolicyContentHasher — DONE
- Sha256PolicyContentHasherTest (empty/ASCII known vectors, Unicode UTF-8, determinism, lowercase hex, different-content, whitespace non-normalization) — DONE
- PolicyNormalizationHashingIntegrationTest (raw noisy → normalizer → hasher pipeline, formatting-noise stability, raw-hash divergence, null/empty) — DONE
- Extraction, fetch and normalization behavior unchanged — DONE

Phase 2G — Version Persistence & Exact Change Detection is DONE:
- Flyway V2 policy_version schema — DONE
- PolicyVersion immutable entity — DONE
- PolicyVersionRepository (latest lookup + ordered listing) — DONE
- PolicyVersionService observe with FIRST_VERSION/UNCHANGED/NEW_VERSION — DONE
- Unique (policy_id, version_number) concurrency guard — DONE
- Testcontainers repository + service tests — DONE (168 tests passing)

Phase 2H — Policy Observation Orchestration is DONE:
- PolicyObservationService + PolicyObservationResult + pipeline bean wiring — DONE
- Deterministic unit tests (no network/DB) — DONE
- End-to-end Testcontainers integration test (stub fetcher, real pipeline) — DONE

Phase 2I — Deterministic Policy Diff Engine is DONE:
- diff module (PolicyChangeType/PolicyChange/PolicyDiffResult/PolicyDiffEngine) — DONE
- LineBasedPolicyDiffEngine (JDK-only LCS, conservative MODIFIED rule) — DONE
- Deterministic unit tests + golden privacy-policy test — DONE
- Observation pipeline untouched (no diff wiring; integration is the next slice) — DONE

Phase 2J — Diff-to-Version Integration is DONE:
- Optional diff on PolicyObservationResult + predecessor lookup — DONE
- Orchestrator coordinates persisted versions through the pure diff engine — DONE
- Diff engine and version service behavior unchanged — DONE
- Mockito unit tests + Testcontainers end-to-end test — DONE

Phase 2K — Persist Policy Changes is DONE:
- Flyway V3 policy_change schema — DONE
- Immutable PolicyChangeRecord entity + minimal repository — DONE
- Single version-plus-changes transaction after the fetch (fetch never inside a DB transaction) — DONE
- NEW_VERSION diff persisted in deterministic order; FIRST_VERSION/UNCHANGED persist nothing — DONE
- Empty diff persists zero rows explicitly; failures propagate with rollback, no false success — DONE
- Testcontainers repository + end-to-end persistence tests — DONE

Phase 2L — SimHash / Near-Duplicate Detection is DONE:
- Deterministic JDK-only 64-bit SimHash + Hamming-distance/similarity utilities in the diff module — DONE
- SHA-256 untouched as exact identity; SimHash unpersisted, unwired, no thresholds — DONE
- Deterministic inline-string unit tests incl. SHA-256-vs-SimHash distinction test — DONE

Phase 2M — SimHash Integration as Similarity Signal ONLY is DONE:
- SimHashSimilarity value object + PolicyObservationResult similarity field (absent for FIRST/UNCHANGED, present for NEW_VERSION, never persisted) — DONE
- Application orchestration coordinates existing SimHash + SimHashDistance over persisted normalized contents outside the version+changes transaction — DONE
- Deterministic unit tests (9 cases) + Testcontainers PostgreSQL integration test (v1 FIRST no similarity → reformatted UNCHANGED no similarity → wording NEW_VERSION diff+similarity, SHA/version/change unaffected, deterministic) — DONE

Completed (Phase 2N — Privacy Concept Vocabulary & Deterministic Concept Matching):
- Flyway V4 privacy_concept + change_concept_match schema (immutable, append-only; unique code on privacy_concept, unique on change_concept_match (change_id, concept_id) as deterministic dedup guard; V1/V2/V3 untouched)
- privacy_concept seed vocabulary 9 concepts (LOCATION, THIRD_PARTY_SHARING, ADVERTISING, DATA_RETENTION, DELETION_RIGHTS, COOKIES, CHILDREN_DATA, ARBITRATION, LEGAL_BASIS) with label/description/weight/sensitivity
- PrivacyConcept immutable JPA entity (policy/intelligence domain, UUID id, code unique, label, description, defaultWeight, defaultSensitivity, createdAt; no setters, updatable=false)
- ChangeConceptMatch immutable JPA entity (intelligence/domain, UUID id, FKs to PolicyChangeRecord and PrivacyConcept, matched_fragment TEXT, pattern_id, match_kind, createdAt; no setters, updatable=false; change EAGER (changed in 2O for impact traceability) and concept EAGER for deterministic reads, unique on (change_id, concept_id))
- PrivacyConceptRepository (findByCode, findAll) + ChangeConceptMatchRepository (findByChange_IdOrderByConcept_CodeAsc, findByConcept_IdOrderByChange_IdAsc; no matching logic)
- ConceptMatcher abstraction + DeterministicConceptMatcher (pure JDK, CASE_INSENSITIVE|UNICODE_CASE|UNICODE_CHARACTER_CLASS regex, LinkedHashMap ordered concepts, one match per (change, concept) at most, matched_fragment is verbatim Matcher.group() substring of oldText/newText, no DB/HTTP/clock/randomness/LLM/embeddings)
- PolicyObservationPersistenceService integrates concept matching inside the same short transaction as version+diff+changes: version observe → predecessor lookup → diff → persist changes → load concepts → match per change → persist ChangeConceptMatch in concept-code order; concept-match failure rolls back version+changes+matches together; SimHash remains outside transaction, unpersisted, SHA-256 authoritative
- IntelligenceConfiguration exposes DeterministicConceptMatcher as Spring bean (matcher stays Spring-free)
- DeterministicConceptMatcherTest (22 deterministic unit tests: location/third-party/advertising positives, hyphen/space variants, negative case, multi-concept, case-insensitive, punctuation, evidence substring, old/new/both handling, empty/null, deterministic ordering, Unicode, duplicate prevention, plus 4 golden pinning tests) + ConceptMatchRepositoryTest (Testcontainers: V4 applied, 9 seeds, ordered retrieval, unique violation)
- ConceptMatchPersistenceIntegrationTest (Testcontainers PostgreSQL end-to-end: FIRST_VERSION no matches, UNCHANGED no matches, NEW_VERSION persists expected concepts with evidence/deterministic ordering and SimHash still present, repeat UNCHANGED no new matches, matcher failure rollback verifies no partial version/change/match rows) — 300 tests passing, BUILD SUCCESS

Completed (Phase 2O — System-Level Concept Impact Scoring):
- Flyway V5 change_impact schema (immutable, append-only; match_id FK→change_concept_match.id UNIQUE, concept_code/change_type/weight/multiplier/base_score/normalized_score/band/rules_version snapshots; V1-V4 untouched)
- ChangeImpact immutable JPA entity in impact/domain (UUID id, FK to ChangeConceptMatch EAGER, all snapshots updatable=false, no setters, UNIQUE(match_id))
- ImpactBand enum (NONE/LOW/MEDIUM/HIGH/CRITICAL via fixed 0/1-29/30-54/55-79/80-100 thresholds) + ImpactScore/ChangeImpactResult pure value objects
- ChangeImpactRepository (findByMatch_Id, findByMatch_Change_IdOrderByImpactBandDescConceptCodeAsc)
- ImpactScoringEngine abstraction + DeterministicImpactScoringEngine pure JDK (conceptWeight=PrivacyConcept.default_weight, multiplier ADDED 0.6/REMOVED 0.8/MODIFIED 1.0, baseScore=weight×multiplier, normalized=min(100, round(baseScore×10)), band mapping, rulesVersion=1, no DB/HTTP/clock/randomness/LLM, no default_sensitivity or SimHash)
- ImpactConfiguration exposes DeterministicImpactScoringEngine as Spring bean
- PolicyObservationPersistenceService extends same short TransactionTemplate to version+changes+matches+impacts atomically: observe→predecessor→diff→persist changes→match→persist matches→score→persist ChangeImpacts sorted by changeOrder/conceptCode; impact scoring/persistence failure rolls back all four tables; SimHash remains outside TX unchanged (SHA-256 authoritative, no section criticality yet fixed at 1)
- DeterministicImpactScoringEngineTest (20 pure unit tests: weight×multiplier×base/normalized/band for LOCATION/THIRD_PARTY/COOKIES variants, multiplier correctness, LOW/MEDIUM/HIGH/CRITICAL/NONE boundaries, deterministic ordering/repeated, empty/null handling, no user sensitivity, plus golden pinning) + ChangeImpactRepositoryTest (Testcontainers: V5 applied, persist/retrieve, UNIQUE violation, ordering, immutability) + ImpactPersistenceIntegrationTest (Testcontainers: FIRST_VERSION 0 impacts, UNCHANGED 0, NEW_VERSION with LOCATION/THIRD_PARTY/ADVERTISING/DATA_RETENTION exact 80/100/70/60 CRITICAL/HIGH scores and traceable evidence→match→change, zero-changes/zero-matches zero impacts, repeat UNCHANGED no new impacts, orchestrator full flow with SimHash preserved, scorer failure rollback no partial rows) — 333 tests passing, BUILD SUCCESS

Phase 2 remains IN PROGRESS. Remaining Phase 2 work stays separate:
- similarity calibration/near-duplicate policy if actually required
- stale-attempt recovery (Phase 2U.2 — DONE: atomic claiming in Phase 2U, retry/backoff in Phase 2U.1, stale recovery in Phase 2U.2)
- notifications

## Current Status

Phase 1 foundation slice implemented and tested successfully
(Flyway V1 migration, Policy entity, Policy repository,
Testcontainers repository tests passing).

Phase 1B slice implemented and tested successfully
(registration DTOs, deterministic URL validation with unit tests).

Phase 1C slice implemented and tested successfully
(policy service with mocked unit tests).

Phase 1D slice implemented and tested successfully
(policy controller with WebMvcTest slice tests).

Phase 1E slice implemented and tested successfully
(global RFC 7807 error handling with ProblemDetail and WebMvcTest coverage).

Phase 1F slice implemented and tested successfully
(final integration/acceptance tests with Testcontainers PostgreSQL
exercising Controller → Validation → Service → URL Validator → Repository → PostgreSQL,
Flyway + Hibernate validate, 49 tests passing).

Phase 1 — Policy Registration is COMPLETE.

Phase 2A slice implemented and tested successfully
(PolicyFetcher abstraction, HttpPolicyFetcher with explicit timeouts and Redirect.NEVER,
FetchResult + PolicyFetchException, local HttpServer tests — 57 tests passing).

Phase 2B slice implemented and tested successfully
(SsrfAddressValidator + SsrfGuard + DnsResolver, HttpPolicyFetcher SSRF integration with
Redirect.NEVER, deterministic local tests without external DNS/internet — 108 tests passing).

Phase 2C slice implemented and tested successfully
(byte-counted 1 MiB response limit with Content-Length pre-check + bounded
streaming read via BodyHandlers.ofInputStream(), HttpPolicyFetcherSizeLimitTest —
deterministic local tests without external network — 115 tests passing).

Phase 2D slice implemented and tested successfully
(Jsoup 1.23.2, PolicyContentExtractor + JsoupPolicyContentExtractor with
JsoupPolicyContentExtractorTest — deterministic inline-fixture tests without
network, HttpPolicyFetcher untouched — 123 tests passing).

Phase 2E slice implemented and tested successfully
(PolicyTextNormalizer + DefaultPolicyTextNormalizer with
DefaultPolicyTextNormalizerTest — deterministic inline-string tests without
network/DB, extraction and fetch untouched — 137 tests passing).

Phase 2F slice implemented and tested successfully
(PolicyContentHasher + Sha256PolicyContentHasher with
Sha256PolicyContentHasherTest + PolicyNormalizationHashingIntegrationTest —
deterministic unit/integration tests without network/DB,
extraction/fetch/normalization untouched).

Phase 2G slice implemented and tested successfully
(Flyway V2 policy_version schema, immutable PolicyVersion entity,
PolicyVersionRepository latest/ordered lookups, PolicyVersionService
observe with FIRST_VERSION/UNCHANGED/NEW_VERSION exact hash detection,
unique (policy_id, version_number) concurrency guard,
Testcontainers repository + service tests — 168 tests passing).

Phase 2H slice implemented and tested successfully
(PolicyObservationService orchestration fetch → extract → normalize →
hash → observe with PolicyObservationResult, pipeline bean wiring,
Mockito unit tests plus Testcontainers end-to-end test with stub
fetcher proving v1 → UNCHANGED → v2 with v1 immutability).

Phase 2I slice implemented and tested successfully
(top-level diff module with PolicyChangeType/PolicyChange/
PolicyDiffResult/PolicyDiffEngine plus JDK-only LineBasedPolicyDiffEngine
with conservative MODIFIED rule, 21 deterministic unit tests and a
golden privacy-policy test pinning V1→V2 to exactly 3 MODIFIED changes;
observation pipeline untouched).

Phase 2J slice implemented and tested successfully
(orchestrator diffs persisted Version N-1 versus Version N contents
through the untouched pure diff engine with the diff exposed as an
optional application-level result, 8 Mockito unit tests plus a
Testcontainers end-to-end test proving FIRST_VERSION/UNCHANGED carry
no diff and NEW_VERSION carries the expected MODIFIED changes).

Phase 2K slice implemented and tested successfully
(Flyway V3 policy_change schema, immutable PolicyChangeRecord entity
with minimal ordered repository, single version-plus-changes
transaction owned by PolicyObservationPersistenceService with the fetch
outside the transaction, thin non-transactional PolicyObservationService
orchestrator, NEW_VERSION changes persisted in deterministic document
order with FIRST_VERSION/UNCHANGED persisting nothing, empty diff
persisting zero rows explicitly, failures propagating with rollback and
no false success, Testcontainers repository + end-to-end persistence
tests).

Phase 2L slice implemented and tested successfully
(deterministic JDK-only 64-bit SimHash utility plus Hamming-distance
and similarity helpers in the diff module as plain Java with no Spring,
database, or pipeline wiring; SHA-256 unchanged as the exact-identity
mechanism; deterministic inline-string unit tests including the
SHA-256-vs-SimHash distinction demonstration with no threshold).

Phase 2M slice implemented and tested successfully
(SimHashSimilarity value object + PolicyObservationResult similarity field
absent for FIRST/UNCHANGED, present for NEW_VERSION, never persisted;
orchestration outside the version+changes transaction; deterministic unit
tests plus Testcontainers integration test).

Phase 2N slice implemented and tested successfully
(Flyway V4 privacy_concept + change_concept_match with 9 seeded concepts
LOCATION/THIRD_PARTY_SHARING/ADVERTISING/DATA_RETENTION/DELETION_RIGHTS/
COOKIES/CHILDREN_DATA/ARBITRATION/LEGAL_BASIS, immutable entities,
minimal repositories, pure DeterministicConceptMatcher with ordered
regex keyword/phrase matching and verbatim evidence, same-transaction
version+changes+concept-matches persistence with rollback, SimHash
outside TX unchanged, Testcontainers repository + end-to-end tests
— 300 tests passing).

Phase 2O slice implemented and tested successfully
(Flyway V5 change_impact with snapshots concept_code/weight/multiplier/
base/normalized/band/rulesVersion=1, immutable ChangeImpact entity,
ImpactBand/ImpactScore value objects, pure DeterministicImpactScoringEngine
with ADDED 0.6/REMOVED 0.8/MODIFIED 1.0 and normalized=min(100,round×10),
same-transaction version+changes+matches+impacts atomically with
rollback, SimHash outside TX, no user sensitivity/section criticality,
Testcontainers repository + end-to-end tests — 333 tests passing).

Phase 2P slice implemented and tested successfully
(Flyway V6 app_user + user_privacy_preference with
UNIQUE(user_id, concept_id) and SMALLINT sensitivity 0–5, immutable User
identity without authentication, mutable UserPrivacyPreference with
updateSensitivity, user-scoped repositories only, pure
DeterministicEffectiveSensitivityResolver with explicit-preference
override and missing-row fallback to PrivacyConcept.default_sensitivity
including explicit 0, deterministic code ordering, short-transaction
UserService/UserPrivacyPreferenceService with upsert/delete/effective
resolution and no REST endpoints, ChangeImpact untouched and
user-independent, Testcontainers repository + integration tests —
356 tests passing).

Phase 2Q slice implemented and tested successfully
(Flyway V7 impact_assessment + impact_assessment_breakdown with
UNIQUE(user_id, new_version_id), UNIQUE(assessment_id, change_impact_id)
and CHECK(previous_version_id <> new_version_id), immutable
ImpactAssessment + ImpactAssessmentBreakdown snapshots of system
normalized/band plus effective sensitivity and personalized
normalized/band with PERSONALIZATION_RULES_VERSION=1, pure
PersonalizedImpactScoringEngine +
DeterministicPersonalizedImpactScoringEngine with
personalized = min(100, round(base × effective / 3.0, 2) × 10) and MAX
aggregate, sensitivity resolved only via EffectiveSensitivityResolver
(explicit 0 distinguishes from a missing preference), idempotent
getOrCreateAssessment per (user, new version) via a short
TransactionTemplate write with UNIQUE-backed
DataIntegrityViolationException re-read, readOnly list/query APIs,
no REST/auth/DTOs, diff/matching/ChangeImpact untouched,
Testcontainers repository + integration tests — 380 tests passing).

Phase 2R slice implemented and tested successfully
(Flyway V8 recommendation table referencing impact_assessment only —
ownership is derived through assessment_id → impact_assessment.user_id
with no duplicated user_id, following the V3/V4/V7
no-duplicated-owner-FK convention — with partial UNIQUE indexes — (assessment_id, action_kind, concept_code)
WHERE concept_code IS NOT NULL for (actionKind, conceptCode) dedup and
(assessment_id) WHERE concept_code IS NULL for exactly one closure row —
plus CHECK(rule_order >= 1), action-kind/band/score CHECKs and
chk_recommendation_concept_code enforcing concept_code IS NULL iff
action_kind = 'NONE_REQUIRED'; immutable append-only Recommendation entity,
assessment-scoped RecommendationRepository (user isolation enforced through
the user's assessment), frozen code-defined rule descriptors
with RECOMMENDATION_RULES_VERSION = 1 and explicit concept-code sets as the
concept-category proxy (no category column), pure
DeterministicRecommendationEngine over ConceptItemScore items with
personalizedBand conditions for rules 1–3, aggregateBand only for the
REC-NONE-REQUIRED closure, dedup by (actionKind, conceptCode) keeping the
highest score, ranking by score DESC → rule order ASC → conceptCode ASC,
exactly one assessment-level NONE_REQUIRED with concept_code NULL when no
rule fires and the aggregate band is NONE/LOW; four-rule v1 vocabulary
REC-DELETION-RIGHTS-LOST (DELETION_RIGHTS + REMOVED/MODIFIED, band-less,
ADDED ignored, diff-engine strengthening/weakening limitation documented),
REC-SHARING-OPT-OUT (THIRD_PARTY_SHARING/ADVERTISING at MEDIUM+, LOCATION
excluded, ADVERTISING intentionally mapped), REC-REVIEW-SETTINGS (any
concept at MEDIUM+), REC-NONE-REQUIRED; RecommendationService with separate
short transactions reusing getOrCreateAssessment, UNIQUE-backed
DataIntegrityViolationException re-read, append-only interpretation of the
pending set (recommendations attached to the user's latest assessment),
engine/flush failure rollback leaving the committed assessment intact, no
REST/auth/notifications/scheduler, V1–V7 and ChangeImpact/ImpactAssessment/
EffectiveSensitivityResolver/observation pipeline untouched, Testcontainers
repository + integration tests — 425 tests passing).

Phase 2S slice implemented and tested successfully
(Flyway V9 policy_fetch_attempt table with trigger/status CHECKs,
http_status/bytes/duration ranges, chk_attempt_completed tying terminal
status to completed_at, and a per-policy newest-first index; no scheduler
state; V1–V8 untouched; immutable-history PolicyFetchAttempt entity with
exactly one sanctioned terminal transition guarded in code
PENDING/IN_PROGRESS → SUCCESS/FAILED/SKIPPED_UNCHANGED; minimal
PolicyFetchAttemptRepository with assessment-free newest-first history
query; PolicyFetchAttemptService with separate short transactions and an
injected Clock; MonitoringConfiguration observationClock bean;
PolicyObservationService records trigger MANUAL around the unchanged
pipeline (begin attempt → fetch with no DB transaction → persist →
terminal update: FIRST_VERSION/NEW_VERSION → SUCCESS,
UNCHANGED → SKIPPED_UNCHANGED, any failure → FAILED with the cause and
the original exception rethrown; beginAttempt failure fails fast with no
unrecorded path; bytes_fetched is the documented UTF-8 body-length
approximation; PolicyObservationResult contract unchanged);
narrow approved policy.application → monitoring.application recording
edge with no monitoring→policy callbacks, recorded in ADR-010;
deterministic unit tests with a manually advanced Clock, Testcontainers
repository tests incl. CHECK/FK violations and write-once conventions,
and end-to-end integration tests incl. FAILED-row survival across
version rollback, no-transaction-across-fetch proof, history ordering,
and concurrent observations — 454 tests passing).

Phase 2T slice implemented and tested successfully
(Flyway V10 policy.next_check_at timestamptz NOT NULL backfilled to
now() with a (status, next_check_at) due-selection index; V1–V9
untouched; Policy gains nextCheckAt defaulting new registrations to
immediately due; deterministic due query ACTIVE + next_check_at <= now
ordered by next_check_at ASC, id ASC; PolicyObservationService gains an
observe(policyId, trigger) overload with observe(policyId) delegating
as MANUAL; single-threaded sequential PolicyObservationScheduler on a
fixed-delay 24-hour configurable interval
(monitoring.check-interval=PT24H driving both the tick delay and
advancement, no kill-switch, no pool) observing each due policy once
per tick with SCHEDULED through the single shared pipeline and
advancing next_check_at by exactly the interval from the tick start on
SUCCESS, SKIPPED_UNCHANGED, and FAILED alike with per-policy failure
isolation; no retry/backoff/jitter/claiming/stale handling (deferred to
the follow-up slice); single-instance scope documented in ADR-011;
deterministic scheduler unit tests with a fixed Clock, Testcontainers
due-selection tests incl. exact-now boundary, ARCHIVED exclusion,
unsigned-id tie-break, backfill/NOT NULL/index checks, and end-to-end
 tick tests incl. failure isolation and single-observation-per-tick —
 471 tests passing).

Phase 2U slice implemented and tested successfully
(Flyway V11 partial unique index uq_policy_fetch_attempt_inflight on
policy_fetch_attempt (policy_id) WHERE status IN
('PENDING','IN_PROGRESS') — the database-enforced cross-instance
invariant; V1–V10 untouched; PolicyFetchAttempt gains a pending factory
and the repository gains the single conditional claim update
PENDING → IN_PROGRESS stamping started_at; PolicyFetchAttemptService
beginAttempt is the single shared claim path (insert PENDING with
attempt_number=1, then conditional claim, in one short transaction, no
HTTP inside; uniqueness collisions translate to
PolicyFetchClaimRejectedException); MANUAL and SCHEDULED observations
enter the same claim path through the unchanged observe pipeline —
MANUAL collisions reject explicitly with no fetch and no second row,
SCHEDULED collisions skip the policy for the cycle with uniform
next_check_at advancement; attempts stay append-only
PENDING → IN_PROGRESS → SUCCESS / FAILED / SKIPPED_UNCHANGED with
Phase 2T next_check_at semantics unchanged; no retry/backoff/jitter
(Phase 2U.1), no stale recovery (Phase 2U.2), no new dependencies;
documented in ADR-012; deterministic unit tests plus Testcontainers
concurrency tests incl. MANUAL/MANUAL, SCHEDULED/SCHEDULED and
MANUAL/SCHEDULED races with exactly one fetch, the partial-unique
invariant, exactly-once conditional claim, terminal lifecycle with no
reclaim, independent different-policy claims, and scheduler skip —
483 tests passing).

Phase 2U.1 slice implemented and tested successfully
(Flyway V12 nullable failure_kind on policy_fetch_attempt with
TRANSIENT/PERMANENT CHECK and no backfill; V1–V11 untouched;
PolicyFetchException enriched with httpStatus + transientFailure set at
the throw site per the classification table; RetryPolicy pure component
with monitoring.retry-max-attempts=5, monitoring.retry-base-delay=PT5M,
monitoring.retry-multiplier=2.0, monitoring.retry-max-delay=PT6H and
equal jitter over an injected Random; chained attempt_number derived at
claim time from the latest FAILED/TRANSIENT row; every failure records
its kind then reschedules next_check_at — backoff for transient with
retries remaining, regular interval otherwise — in separate short
transactions with no TX across HTTP, rethrowing the original exception;
each observe() performs at most one fetch with MANUAL and SCHEDULED on
the same path; scheduler advancement is a move-guard preserving
backoff; no stale recovery (Phase 2U.2), no new dependencies;
documented in ADR-013; deterministic RetryPolicy/classification/
failure-path/move-guard unit tests plus Testcontainers retry-chain
tests incl. 1→2→3 backoff, exhaustion reset, permanent reset, NULL
legacy reset, MANUAL single-attempt, V11 slot discipline, and the V12
CHECK).

Phase 2U.2 slice implemented and tested successfully
(Flyway V13 partial stale-lookup index
idx_attempt_stale_inflight on policy_fetch_attempt (started_at)
WHERE status = 'IN_PROGRESS'; V1–V12 untouched, V11 untouched;
StaleAttemptRecovery sweeper on monitoring.stale-check-interval=PT5M
with monitoring.stale-timeout=PT30M lease on started_at and
monitoring.stale-batch-size=100, oldest first, per-row isolation;
stale IN_PROGRESS rows transition in place to FAILED/TRANSIENT with a
Stale IN_PROGRESS error message, attempt_number preserved, completed_at
at recovery time, non-negative duration; conditional recovery UPDATE
WHERE id AND status AND started_at <= cutoff with affected-rows election
and no reschedule on a lost race; reschedule through the unchanged
RetryPolicy.nextCheckAt in a separate short transaction; PENDING rows
never touched; sweeper never fetches and holds no transaction across
HTTP; MANUAL/SCHEDULED claim behavior, terminal-then-schedule ordering,
and 2U.1 retry/backoff formula unchanged; documented in ADR-014;
deterministic unit tests with a fixed Clock plus Testcontainers
recovery tests incl. exact-boundary, batch, multi-policy, V11 slot
release with n → n+1 reclaim, 2U.1 chain/exhaustion, permanent and
legacy-NULL history, scheduler interaction, dual-worker race with
exactly-once recovery, and late-worker rejection).

Phase 2Q = COMPLETE. Phase 2R = COMPLETE. Phase 2S = COMPLETE. Phase 2T = COMPLETE. Phase 2U = COMPLETE. Phase 2U.1 = COMPLETE. Phase 2U.2 = COMPLETE. Phase 2 overall = IN PROGRESS.

Phase 2P notes:
- User introduced without authentication (id + timestamps only; no
email/password/role/JWT; authentication deferred).
- UserPrivacyPreference introduced with sensitivity range 0–5.
- Explicit preference overrides the concept default; missing
preference row falls back to PrivacyConcept.default_sensitivity.
- Explicit 0 is a valid preference and overrides the default.
- ChangeImpact remains user-independent (system-level fact).
- Personalized ImpactAssessment deferred to Phase 2Q (implemented in
Phase 2Q).
- Authentication deferred; no public preference endpoints; service
APIs take userId explicitly for future authentication.

Phase 2 — Policy Fetching is IN PROGRESS.

Phase 2A — COMPLETE
Phase 2B — COMPLETE
Phase 2C — COMPLETE
Phase 2D — COMPLETE
Phase 2E — COMPLETE
Phase 2F — COMPLETE
Phase 2G — COMPLETE
Phase 2H — COMPLETE
Phase 2I — COMPLETE
Phase 2J — COMPLETE
Phase 2K — COMPLETE
Phase 2L — COMPLETE
Phase 2M — COMPLETE
Phase 2N — COMPLETE
Phase 2O — COMPLETE
Phase 2P — COMPLETE
Phase 2Q — COMPLETE
Phase 2R — COMPLETE
Phase 2S — COMPLETE
Phase 2T — COMPLETE
Phase 2U — COMPLETE
Phase 2U.1 — COMPLETE
Phase 2U.2 — COMPLETE

Phase 2 remains IN PROGRESS. Remaining Phase 2 work stays separate:
- similarity calibration/near-duplicate policy if actually required
- notifications (Phase 10A emission DONE; feed REST + automatic fan-out in Phase 10B)

Do not mark Phase 2 complete yet.

Phase 10A slice implemented and tested successfully
(Flyway V14 notification table with assessment_id FK, UNIQUE(assessment_id)
idempotency index and read_at CHECK; V1–V13 untouched; immutable Notification
entity with the single idempotent markRead transition and EAGER assessment,
no duplicated user_id (ownership derived through the assessment); NotificationService
with emitForAssessment/markRead/user-scoped list/unread reads, explicit-userId
ownership checks with cross-user not-found, separate short transactions and an
injected Clock; emit rule from persisted recommendation rows — exactly one
notification iff a rule code other than REC-NONE-REQUIRED exists, NONE-only and
empty sets stay silent; UNIQUE-backed DataIntegrityViolationException → re-read
idempotency with no Java synchronization; narrow fenced RecommendationService
hook after recommendation commit on every resolved path (existing/created/race
re-read, which heals prior emission failures); emission failure propagates
without rolling back committed recommendations; no REST endpoints, no scheduler
or pipeline changes, no auth/filter-chain changes, no new dependencies;
documented in ADR-015; deterministic unit tests with a fixed Clock plus
Testcontainers repository tests incl. FK/UNIQUE/CHECK violations and
newest-first isolation, and end-to-end emission tests incl. hook emission,
NONE silence, idempotent re-emit, concurrent exactly-once emission,
failure-without-rollback with healing, and mark-read/isolation flows).

Phase 10B-1 slice implemented and tested successfully
(Phase 10B-1 = ownership + automatic service-layer fan-out;
Phase 10B-2 = authenticated notification REST feed, NOT implemented):
- Flyway V15 policy.owner_id (single nullable owner FK, no
subscription/watch table, no many-to-many; V1–V14 untouched, no
speculative indexes)
- Policy.owner (EAGER ManyToOne, nullable transition) + PolicyService
assignOwner(userId, policyId) via the narrow fenced
policy.application → user.domain edge (ADR-016): unknown policy/user
rejected, null owner assigned, same owner idempotent no-op,
different owner rejected; no transfer authorization, no REST endpoint
- NotificationFanOutService (service layer only): fans out only when
outcome == NEW_VERSION, policy == ACTIVE, owner_id != null
(FIRST_VERSION/UNCHANGED/FAILED/skipped/inactive/unowned return
silently); version UUID derived through the existing
findByPolicy_IdAndVersionNumber query (PolicyObservationResult
unchanged); eligible flow reuses ImpactAssessmentService
getOrCreateAssessment → RecommendationService
getOrCreateRecommendations → Phase 10A emission hook with no
duplicated scoring/rules/creation logic and no encompassing
transaction (assessment TX → recommendation TX → notification TX)
- Idempotency/concurrency through the existing uniqueness guards
(assessment, Phase 2R recommendations, notification) with V11 claim
serialization upstream; no Java synchronization, no new
infrastructure; concurrent fan-out converges to one set
- Scheduler captures the observation result and calls the fan-out
after success/terminal handling with per-policy exception isolation:
fan-out failure changes no attempt, no next_check_at, no retry, no
V11/V12/V13 behavior, and never aborts the tick (accepted healable
gap, healed by repeat fan-out)
- NotificationNotFoundException extends NoSuchElementException for
missing/foreign assessment/notification cases (messages unchanged);
no other NotificationService behavior change
- No REST notification controller, no JWT/authentication/
SecurityFilterChain, no CurrentUser/SecurityContext, no policy
transfer/admin, no subscription/watch table, no audit/email/AI/
observability, no new dependency, no Redis/Kafka/RabbitMQ/
ShedLock/advisory locks; scheduler cadence and retry/backoff/stale
recovery algorithms unchanged
- Unit tests (ownership, fan-out trigger/silence/version-derivation,
scheduler trigger/isolation) + Testcontainers concurrency proof
(two fan-out calls → one assessment/recommendation set/notification)
+ Testcontainers ownership/fan-out integration (owned NEW_VERSION
full flow, unowned silence, UNCHANGED silence, NONE_REQUIRED
silence, idempotency, V11/retry/stale intact, fan-out failure
isolation with healing); documented in ADR-016; ARCHITECTURE.md
§9/§25/§28 updated.

Phase 10B-2B slice implemented and tested successfully
(Phase 10B-2B = authenticated notification REST feed; no new ADR —
ADR-015/ADR-016/ADR-018 already cover the architecture):
- `NotificationResponse` DTO (exactly `id`, `assessmentId`,
`policyId`, `versionNumber`, `createdAt`, `readAt`, `read`;
no user/credential/score/recommendation/entity material)
- `NotificationController` (`/api/v1/me/notifications`: `GET /`,
`GET /unread`, `POST /{notificationId}/read`; thin —
principal → `requireUserId` → explicit-`userId` service → DTO;
no `SecurityContextHolder`, no client-supplied userId)
- `NotificationService` gains `listNotificationResponses`,
`listUnreadNotificationResponses`, and `markReadResponse`,
each delegating to the existing entity methods and mapping
via `NotificationResponse.from(...)` inside the existing
transactions (lazy `newVersion`/`policy` safe; no
`EntityGraph`/fetch join; per-row lazy-load cost accepted,
pagination/index hardening deferred to Phase 13)
- Existing `listNotifications`/`listUnreadNotifications`/
`markRead` signatures and behavior preserved (ownership,
`NotificationNotFoundException` → 404, idempotent mark-read)
- Security unchanged (`SecurityConfig` untouched,
`anyRequest().authenticated()` already protects `/me/**`;
missing/invalid/expired/wrong-signature JWT → 401
`Unauthenticated`; foreign/unknown notification → 404
`Resource not found`, never 403; malformed UUID → 400
`Malformed request`)
- No migration (V1–V16 byte-for-byte unchanged), no
auth/JWT/scheduler/scoring/recommendation/fan-out changes
- `NotificationControllerTest` (slice: delegation, 7-field
shape, 404/400, client-`userId` override rejection) +
`NotificationFeedIntegrationTest` (filters-enabled
Testcontainers: isolation, ordering, unread filtering,
idempotency, 401/404/400, field allowlist); documented in
ARCHITECTURE.md §25/§28/§31.
Phase 8 (authentication & security) IN PROGRESS — Phase 8A scope (see DECISIONS.md ADR-017):

Phase 8A slice implemented and tested successfully
(Flyway V16 `app_user.email VARCHAR(254) NULL` +
`password_hash VARCHAR(255) NULL` + unique email index;
V1–V15 untouched; `User` gains nullable email/passwordHash with
no-arg JPA constructor and `createUser()` unchanged;
`BCryptPasswordEncoder` bean, no custom crypto, no new dependency;
`AuthRegistrationService.register` normalizes email trim+lowercase,
rejects duplicates via `DuplicateEmailException` → HTTP 409
problem+json, enforces 8-char minimum / 72 UTF-8-byte maximum
(DTO + service-side guard, never silently truncated), BCrypt-hashes
before persistence, never persists/logs raw passwords;
`POST /api/v1/auth/register` → 201 `{id,email}` only;
minimal stateless `SecurityFilterChain` — CSRF/Basic/form/logout
disabled, register + `/api/v1/policies/**` permitted transitionally,
everything else authenticated by default, problem+json 401
"Unauthenticated" / 403 "Forbidden" entry points; no JWT/login/
refresh/roles/admin/OAuth2/principal, no notification/scheduler/
scoring changes; documented in ADR-017).

Phase 8B slice implemented and tested successfully
(Nimbus JOSE+JWT 10.x HS256 access tokens, claims exactly
`sub` (application User UUID) + `iat` + `exp` with `PT15M`
default TTL; secret from `security.jwt.secret` with fail-fast
blank/missing/<32-byte rejection, placeholder only in the
committed template, test-only secret in
`src/test/resources/application.properties`;
`AuthLoginService.login` reuses 8A email normalization, runs a
static dummy BCrypt hash on unknown emails, rejects null-hash
legacy rows, and yields one uniform 401 `"Unauthenticated"` /
`"Invalid email or password"` for every credential failure;
`POST /api/v1/auth/login` → 200
`{accessToken,tokenType:"Bearer",expiresIn}` with no credential
material; `AuthenticatedUser(UUID)` principal built only by the
Bearer `OncePerRequestFilter` (before
`UsernamePasswordAuthenticationFilter`), filter failures flowing
into the existing entry-point 401; `requireUserId` web-only
helper for future `/me/*` controllers with services unchanged;
chain otherwise identical to 8A plus login permitAll; no V17
(V1–V16 untouched), no refresh/roles/admin/OAuth2, no
notification/scheduler/scoring changes; documented in ADR-018).

Phase 8C (refresh tokens) NOT implemented.

Authenticated policy hardening slice implemented and tested successfully
(owner-scoped policy boundary; see DECISIONS.md ADR-019):
- `POST /api/v1/policies` assigns the authenticated principal as
owner (`owner_id` persisted with the new row; no client-supplied
owner identity accepted)
- `GET /api/v1/policies/{id}` returns only owned policies via
`findByIdAndOwner_Id` (foreign/unknown → 404, never 403)
- `GET /api/v1/policies` returns only owned policies via
`findByOwner_IdOrderByCreatedAtAscIdAsc` (registration order;
repository-level filtering, no pagination)
- `SecurityConfig` transitional `/api/v1/policies/**`
permitAll removed; register/login stay public, everything else
(incl. `/me/**`) authenticated; no roles/authorities
- `PolicyService.assignOwner` preserved unchanged as the internal
operation (existing tests + fan-out flows depend on it); no
transfer/admin REST surface
- `PolicyResponse`/`CreatePolicyRequest` unchanged (no ownerId
field); validation, RFC 7807 errors, and 201 + Location
semantics preserved
- No migration (V15 column reused; V1–V16 byte-for-byte
unchanged, no new index — hardening deferred to Phase 13);
no fan-out/scheduler/scoring/recommendation/JWT changes
- `PolicyServiceTest` (unit: ownership, 404, scoping) +
`PolicyControllerTest` (slice: delegation, override rejection)
+ `PolicyRegistrationIntegrationTest` (filters-enabled vertical
slice incl. 401s) + new `PolicyOwnershipIntegrationTest`
(isolation, ordering, immutability, injection attempts);
`SecurityFilterChainTest` + `JwtAuthenticationIntegrationTest`
updated for the locked boundary; documented in ARCHITECTURE.md
§9/§27/§28 and ADR-019.

Privacy preference REST slice implemented and tested successfully
(see DECISIONS.md ADR-020):
- `GET /api/v1/me/privacy-preferences` returns the full
effective surface (every concept, code order; `conceptCode`,
`label`, `effectiveSensitivity`, `explicit`) via the existing
resolver; `PUT` accepts `{"preferences":{CODE:0–5}}` with merge
semantics (upsert present, absent untouched; unknown concept →
400, range violations → 400, malformed JSON → 400)
- Identity only from `requireUserId`; per-entry short
`REQUIRES_NEW` transactions with UNIQUE-backed re-read on
lost-insert races, no Java synchronization; no migration
(V6 schema sufficient), no resolver/scoring changes
- `PrivacyPreferenceControllerTest` (slice: delegation,
validation, 400s, override rejection) +
`PrivacyPreferenceApiIntegrationTest` (filters-enabled
Testcontainers: 401s, full surface, isolation, injection,
idempotency, merge, persisted preference proven to flow into
`ImpactAssessmentService` personalized scores); documented in
ARCHITECTURE.md §28 and ADR-020.

Assessment + recommendation REST slice implemented and tested successfully
(see DECISIONS.md ADR-021):
- `GET /api/v1/me/impact-assessments` returns the user's assessment
summaries newest first (`id`, `policyId`, `versionNumber`,
`previousVersionNumber`, `aggregateScore`, `aggregateBand`,
`personalizationRulesVersion`, `createdAt`; no breakdown rows)
via `findByUser_IdOrderByCreatedAtDescIdDesc` (repository-level
ownership filtering, id tie-break for determinism)
- `GET /api/v1/me/impact-assessments/{assessmentId}` returns the
assessment plus its breakdowns in engine ranking order, each with
the persisted system snapshot (`systemNormalized`/`systemBand`/
`systemRulesVersion`), `effectiveSensitivity`, and personalized
snapshot; scores are read, never recomputed
- `GET /api/v1/me/recommendations` returns the user's
recommendations newest first using the entity's real fields
(`ruleId`, `ruleOrder`, `actionKind`, `conceptCode`,
`personalizedNormalized`/`personalizedBand`,
`recommendationRulesVersion`); ownership derived through the
assessment at the repository level
(`findByAssessment_User_IdOrderByCreatedAtDescIdDesc`), no
duplicated `user_id`
- `GET /api/v1/me/recommendations/{recommendationId}` returns the
recommendation with its assessment navigation context (`policyId`,
`versionNumber`)
- Identity only from `requireUserId`; services never touch
`SecurityContextHolder`; cross-user/unknown ids → 404 (never 403),
malformed UUIDs → 400, missing/invalid JWT → 401; DTO mapping
runs inside the existing service read transactions (lazy
`newVersion`/`policy` safe; no `EntityGraph`, no EAGER changes)
- No migration (V1–V16 byte-for-byte unchanged), no scoring/
recommendation/notification/fan-out/ownership changes; the
existing "current/pending set = latest assessment's set"
semantics preserved with no new domain state
- `ImpactAssessmentControllerTest` + `RecommendationControllerTest`
(slice: delegation, shape, 404/400, client-`userId` override
rejection) + `ImpactAssessmentRecommendationApiIntegrationTest`
(filters-enabled Testcontainers: 401s, isolation with cross-user
404s, deterministic ordering, exact persisted-field mapping,
injection resistance, empty states, real pipeline visibility);
documented in ARCHITECTURE.md §25/§28 and ADR-021.

Phase 11A slice implemented and tested successfully
(see DECISIONS.md ADR-022/ADR-023):
- Flyway V17 `audit_event` table (V1–V16 byte-for-byte unchanged):
immutable columns, six-event catalog CHECK, hash length CHECKs,
`UNIQUE(prev_hash)` chain guard, `UNIQUE(event_hash)` duplicate
guard, partial single-genesis index (plain UNIQUE cannot block
twin NULL predecessors), actor feed index; no other indexes
- Immutable append-only `AuditEvent` entity (lazy nullable actor
association, no resource relationships, no setters/mutation) +
frozen six-code `AuditEventType` enum
- Pure Spring-free `AuditChain` (ADR-023 canonical order, NULL →
empty, microsecond UTC timestamps, backslash-then-pipe escaping,
UTF-8 SHA-256 lowercase hex) with the frozen genesis golden
vector pinned byte-for-byte (`6c3fd51f…fcec4`, matched on first
implementation run with no ADR change)
- Small immutable `AuditMetadata` (insertion-ordered scalar-only
compact JSON; floats/nesting/collections rejected)
- `AuditService.append` in dedicated short transactions with
bounded (5) `UNIQUE`-collision re-read retry and full candidate
recomputation; audit failure never rolls back business state;
actor set via unmanaged reference (no SELECT, no new module
edge); existing single application `Clock` bean reused (a second
`Clock` bean would break all bare-`Clock` injection points);
service-level `listAuditEvents` newest-first, no Java filtering
- No 11B verification service, no 11C emission wiring (no
operation emits yet), no 11D REST, no retention purge, no
roles/pagination/observability; no scoring/recommendation/
notification/auth/policy/privacy behavior changes
- `AuditChainTest` (pure unit: order, NULLs, UUID case,
microsecond width, escaping, metadata determinism, UTF-8,
golden vector) + `AuditMetadataTest` + `AuditEventTest` +
`AuditEventRepositoryTest` (Testcontainers: FK/CHECK/UNIQUE/
genesis/head/feed contract) +
`AuditServiceIntegrationTest` (Testcontainers: genesis, chain
linkage, clock default + truncation, 2-thread convergence with
full chain-integrity proof, exhaustion → `AuditAppendException`
with zero rows, unknown-actor FK failure, feed isolation);
documented in ARCHITECTURE.md §26/§31.

Phase 11B slice implemented and tested successfully
(read-only cryptographic chain verification; see DECISIONS.md
ADR-022/ADR-023 — both unchanged):
- Immutable `AuditVerificationResult` (VALID with `verifiedCount`,
including empty chain → VALID(0) as the locked fresh-install
semantic; INVALID with failed event id, optional chain position,
and deterministic `FailureReason`: malformed hash, multiple/
missing genesis, shared predecessor, hash mismatch, broken link,
missing predecessor, unreachable row; no metadata/resource/actor/
timestamp contents)
- `AuditVerificationService.verify()` in `@Transactional(readOnly
= true)`: one bulk scalar read, then a pure O(N)/O(N) walk from
the single genesis using `prev_hash` linkage only (never
timestamps), recomputing every `event_hash` exclusively through
the frozen `AuditChain` with persisted metadata TEXT verbatim
and persisted instants directly; no writes, no
`AuditService.append`, no Clock/EntityManager/SecurityContext/
user loading; failure precedence malformed → genesis structure
→ earliest chain-order failure → off-chain rows with lowest-id
tie-breaking
- `AuditEventRepository.findAllForVerification` narrow scalar
projection (id, event type, actor id, resource type/id,
occurred_at, metadata, prev/event hashes): the lazy actor
association is never initialized and no `User` row is loaded
- A VALID verdict means persisted rows are mutually consistent;
it does not prove completeness against tail truncation
(ADR-023 §9)
- `AuditVerificationServiceTest` (22 pure unit tests: empty →
VALID(0), ADR golden vector, valid 3-chain, metadata/NULL→{}/
event-hash tampering, prev retarget fork, dangling predecessor,
missing/twin genesis, shared fork, detached cycle, scrambled
timestamps, uppercase/short/non-hex hashes, microsecond
truncation, escaping, determinism, precedence, earliest failure)
+ `AuditVerificationIntegrationTest` (Testcontainers: empty DB,
service-written genesis/chain, exact ADR golden-vector row,
metadata/event-hash/prev-hash corruption, deleted predecessor,
injected cycle, twin-genesis DB rejection with chain still
valid, null-actor chain, before/after row-dump read-only proof)
- No 11C emission wiring (no operation emits yet), no 11D REST/
DTO/controller, no retention purge, no scheduled verification,
no anchoring, no migration/schema change, no auth/policy/
scoring/recommendation/notification behavior changes;
documented in ARCHITECTURE.md §26/§31.

Phase 11C slice implemented and tested successfully
(post-commit audit emission wiring; see DECISIONS.md ADR-022/
ADR-023 — both unchanged):
- Six frozen event types wired exactly at successful business
branches, always after the business transaction commits, as a
best-effort witness: `AuthController.register` →
`AUTH_USER_REGISTERED` (actor/resource = new user),
`AuthController.login` → `AUTH_LOGIN_SUCCEEDED`
(actor/resource = user; failures/unknown/malformed silent),
`PolicyController.register` → `POLICY_REGISTERED`
(actor = owner, resource = policy; GETs/observations/failures
silent) — all with `AuditMetadata.empty()` and null occurredAt
- `PolicyService.assignOwner` restructured to a non-transactional
facade over a `TransactionTemplate` core returning whether the
null→user assignment occurred; emits `POLICY_OWNER_ASSIGNED`
(actor = assigned user per ADR-023, resource = policy) only on
`true`; same-owner no-op, different-owner rejection, and unknown
ids stay silent with existing exceptions preserved
- `UserPrivacyPreferenceService` gains an outcome core: one
`PRIVACY_PREFERENCE_UPSERTED` per created/changed preference
with `{"conceptCode","oldSensitivity","newSensitivity"}` (null
old = created), wired through bulk per-entry post-commit
(partial-commit semantics preserved, race retry converges to one
event), single upsert, and unchanged rewrites/absent keys/empty
maps provably silent; `deletePreference` returns whether a row
existed and emits `PRIVACY_PREFERENCE_DELETED` with
`{"conceptCode","oldSensitivity"}` only then, with no REST
exposure (ADR-020 intact)
- Audit failure propagates post-commit without rolling back
business state (proven for registration, owner assignment, and
bulk update); no credential/token/secret/URL/content/email in
any metadata (forbidden-key scans green)
- `AuditEmissionAuthIntegrationTest` (8) +
`AuditEmissionPolicyIntegrationTest` (8, incl. stub-fetcher
FIRST_VERSION/UNCHANGED silence proof) +
`AuditEmissionPreferenceIntegrationTest` (10, incl. 2-thread
no-duplication proof); mixed operations leave
`AuditVerificationService.verify()` VALID
- Existing suites updated minimally: `AuditService` mocks in
auth/policy controller slices, new deps in policy service unit
tests, audit-first cleanup ordering in suites that trigger wired
branches (auth, policy registration/ownership, preferences,
assessment/recommendation, notification feed); the three exempt
oversized tests untouched
- No 11D REST/DTO/controller, no admin/pagination/retention/
anchoring/scheduling, no login-failure auditing, no new event
types, no verification changes, no migration (V1–V17
byte-for-byte unchanged), no new infrastructure;
documented in ARCHITECTURE.md §26/§31.

Phase 11D slice implemented and tested successfully
(authenticated self-scoped audit feed; see DECISIONS.md ADR-022/
ADR-023 — both unchanged):
- `GET /api/v1/me/audit-events` → 200 bare JSON array (`[]`
when empty) behind the existing chain (no `SecurityConfig`
change; missing/invalid JWT → existing 401): principal-only
identity via `AuthenticatedUsers.requireUserId`, thin
`audit.web.AuditEventController` delegating to a new
`AuditService.listAuditEventResponses` that maps inside the
existing read-only transaction over the unchanged
`findByActorUser_IdOrderByOccurredAtDescIdDesc`
(repository-level actor filtering, `occurred_at DESC, id DESC`,
no Java sorting, no linkage ordering for presentation)
- `AuditEventResponse` DTO with exactly eight safe fields (id,
occurredAt, eventType name, resourceType/Id, verbatim metadata
with null-vs-`{}` preserved, exact prev/event hashes); no actor
id, user entity, credentials, tokens, email, content, or
relationships — the lazy actor association is never touched
- Feed is strictly read-only: GET emits nothing (count-proof),
no `append` on the path, no verification/chain/emitter change
- `AuditEventControllerTest` (6 slice tests: delegation, exact
shape incl. nulls, empty array, `?userId` ignored, problem
propagation, 401 without principal, verbatim `from()` mapping)
+ `AuditEventFeedIntegrationTest` (11 filters-enabled tests with
real JWT: 401s, empty feed, bilateral isolation, newest-first,
same-instant id tie-break, eight-field shape, verbatim
metadata/hashes incl. nulls, `?userId` ignored, read-only
proof, all six 11C types observable, leak scan)
- `AuditVerificationService` remains internal with no HTTP
exposure; admin feed, pagination/filtering (Phase 13),
retention, anchoring, scheduling, login-failure auditing, new
event types, export/search remain deferred; no migration, no new
infrastructure; documented in ARCHITECTURE.md §26/§28/§31.

Phase 12 slice implemented and tested successfully
(optional local-AI explanation layer; see DECISIONS.md ADR-024 —
ADR-002/006/022/023 unchanged):
- New `ai` module above the deterministic pipeline (nothing below
imports it): Spring-free `AiExplanationProvider` +
`ExplanationRequest` (frozen allowlist, single-sourced
`promptText()`) + `ExplanationOutcome` + shared `FallbackReason`
+ pure `DeterministicExplanationFallback` + JDK-`HttpClient`
`OllamaExplanationProvider` (`POST {base}/api/chat`,
`stream:false`, temperature 0, 2s connect / 30s request
timeouts, pre-call 4000-char budget, response-size safety cap,
typed failures only, never logs bodies)
- `AiProperties` (`ai.*`, safe default `ai.enabled=false`) +
`AiConfiguration` (fail-fast validation only when enabled;
disabled bean never performs I/O); `AiExplanationService`
(non-transactional orchestration reusing existing user-scoped
assessment/recommendation/policy/concept reads; provider
invoked only when enabled and within budget; authoritative
score/band/actions echoed from persisted facts)
- `POST /api/v1/me/impact-assessments/{id}/explanation` → 200
with `ExplanationResponse` (assessmentId, explanation,
aggregateScore/Band, actionKinds, model, fallback,
fallbackReason); principal-only identity, foreign id → 404,
malformed id → existing 400; ephemeral prose, no
persistence/cache/audit/verification change
- `ExplanationRequestTest` (prompt golden + allowlist scan) +
`DeterministicExplanationFallbackTest` (3 golden shapes) +
`OllamaExplanationProviderTest` (11 local-`HttpServer` tests:
success/request shape, refused/timeout/non-2xx/malformed/
missing/non-textual/empty/oversized/over-budget-no-call) +
`AiExplanationServiceTest` (7 stub-provider tests incl. lying-
prose passthrough, all five failure mappings,
disabled/over-budget short-circuits, 404 propagation) +
`ExplanationControllerTest` (5 slice tests) +
`ExplanationIntegrationTest` (7 filters-enabled tests with stub
provider: happy path, allowlist capture, isolation, 401s,
read-only proof, disabled fallback, leak scan); no real Ollama
or network in `./mvnw test`
- Deferred: streaming, chat, cloud providers, persistence/
caching, background generation, evidence quotes, per-
recommendation endpoints, explanation audit events, Phase 13
rate limiting/Actuator/Docker/pagination; no migration, no new
dependency; documented in ARCHITECTURE.md §7/§12/§28/§30/§31.

## Next Action

Phase 13-A slice implemented and tested successfully
(database/index hardening, evidence-led, index-only):
- Flyway V18 `V18__feed_read_indexes.sql` (V1–V17 byte-for-byte
unchanged): exactly two additive composite covering indexes —
`idx_policy_owner_created ON policy (owner_id, created_at ASC, id ASC)`
for `findByOwner_IdOrderByCreatedAtAscIdAsc` (Seq Scan + Sort →
Index Only Scan) and `idx_assessment_user_created
ON impact_assessment (user_id, created_at DESC, id DESC)` for
`findByUser_IdOrderByCreatedAtDescIdDesc` (Bitmap Heap Scan + Sort →
Index Only Scan, plus a faster build side on the recommendation/
notification feed joins). Plain transactional CREATE INDEX; no
CONCURRENTLY, no denormalized user_id (ADR-009/015/021 preserved),
no repository/service/controller/query change, no ordering change,
no pagination/rate limiting/Actuator/Docker/N+1/API change.
- Deliberately NOT added (EXPLAIN-verified on postgres:16 with a
representative 20-user distribution): recommendation/notification
join-feed indexes (no natural plan change; latent nested-loop path
only under forced costing — revisit with LIMIT costing in 13-B),
audit duplication (`idx_audit_event_actor_time` already Index Only),
point-lookup indexes (already PK/UNIQUE-driven). Existing
`idx_assessment_user` kept (now redundant for the feed path, but
removal would be destructive to V7-era objects).
- `./mvnw.cmd clean test`: 890 tests passing, BUILD SUCCESS
(Flyway validates/applies all 18 migrations in Testcontainers).

Phase 13-B slice implemented and tested successfully
(feed pagination per DECISIONS.md ADR-026; 13-A EXPLAIN follow-up
included, no new migration):
- Six listing endpoints accept `?page=` (default 0) / `?size=`
(default 20, max 100) and return bare JSON arrays with no envelope
and no totalCount: `GET /api/v1/policies`,
`GET /api/v1/me/impact-assessments`,
`GET /api/v1/me/recommendations`,
`GET /api/v1/me/notifications`,
`GET /api/v1/me/notifications/unread`,
`GET /api/v1/me/audit-events`. Out-of-range values → 400
`Invalid request` (rejected, never clamped); malformed/overflowing
numerics → 400 `Malformed request` (type-mismatch message reworded
from "path parameter" to neutral `Invalid value for 'page'` style);
empty pages → `200 []`; principal resolved before pagination
validation so unauthenticated calls stay 401; duplicate params use
Spring first-value binding (ADR-026 corrected accordingly after a
slice test proved last-value was misstated).
- New `common.pagination.FeedPagination` value object
(DEFAULT_PAGE/DEFAULT_SIZE/MAX_SIZE, explicit validation,
PageRequest/Sort construction); controllers validate at the boundary
then delegate to new `*Paged` service methods (existing
`@Transactional(readOnly=true)` + in-transaction DTO mapping
preserved); repositories gain `Pageable` overloads without embedded
`OrderBy` (Sort carries the exact order); all unbounded methods kept
— `AiExplanationService` still reads the complete user-scoped
recommendation set.
- Ordering preserved exactly except the approved notification
refinement: both notification feeds now sort
`createdAt DESC, id DESC` (previously createdAt only), proven by an
equal-timestamp id-DESC page test. All other feeds byte-identical.
- No V19: paginated EXPLAIN re-runs confirm V18 serves every
single-table feed (`Limit → Index Only Scan`); rec/notification
join feeds stay hash-join+sort under LIMIT (an ordering-only trial
index flipped rec page-1 to a fragile sparse-user scan — evidence
against adopting it). New `FeedPaginationIndexTest` guards the V18
index definitions + schema version 18.
- N+1 observations at size=20 (measured, not fixed — 13-F input):
policies ~1 SELECT/page; assessments ~1+2N (41); recommendations ~1
(proxy ids only); notifications ~1+N (21: one policy_version load
per row, policy via uninitialized proxy id); audit ~1. No
EntityGraph/fetch-join/EAGER change in 13-B.
- `./mvnw.cmd clean test`: 951 tests passing (890 pre-13-B + 61 new),
0 failures, 0 errors, BUILD SUCCESS (Flyway validates/applies all
18 migrations in Testcontainers); documented in
ARCHITECTURE.md §28 and ADR-026 (one-word duplicate-param
correction) — no other ADR change; V1–V18 untouched; no
rate limiting/Actuator/Docker/cursor work.

Phase 13-C slice implemented and tested successfully
(application rate limiting per DECISIONS.md ADR-025):
- New `common.ratelimit` module (no upward dependencies): `RateLimitProperties`
(`rate-limit.*` with ADR-025 defaults and fail-fast validation),
`TokenBucket` (pure Spring-free token bucket over the injected `Clock`),
`RateLimitService` (per-key buckets namespaced `tier:value` with idle
eviction plus oldest-drop under `max-tracked-keys`), and
`common.ratelimit.web.RateLimitFilter` (after `JwtAuthenticationFilter`;
auth routes by IP at 10/min, explanation per user at 10/min, general
API per user at 600/min, anonymous per IP at 60/min; 429
`application/problem+json` with `Retry-After`, no `X-RateLimit-*`
headers, `X-Forwarded-For` ignored, `enabled=false` pass-through;
non-API paths untouched; scheduler/fan-out/audit never traverse it).
- `SecurityConfig` registers the filter after the JWT filter; 401/400
behavior preserved (principal and input validation run before/at
their own layers). No new dependency, no migration (V1–V18
untouched), no pipeline/scoring changes.
- Tests: `TokenBucketTest` (capacity/refill/retry math/clock skew),
`RateLimitServiceTest` (tiers, keying, refill, idle+bounded
eviction, 10-thread exactness, validation), `RateLimitFilterTest`
(routing, 429 shape, anonymous/disabled/non-API paths),
`RateLimitApiIntegrationTest` + `RateLimitAuthIntegrationTest`
(filters-enabled, shrunken budgets, per-test IPs for the shared
auth bucket, per-user isolation, tier separation, 401 precedence,
no-credential-leak body check). The shared test profile disables
the limiter (`rate-limit.enabled=false` in test resources) so
unrelated suites never contend for budgets; only the two dedicated
classes re-enable it.
- `./mvnw.cmd clean test`: 983 tests passing (951 pre-13-C + 32 new),
0 failures, 0 errors, BUILD SUCCESS (Flyway validates/applies all
18 migrations in Testcontainers); documented in ADR-025,
ARCHITECTURE.md §27/§28, and `application-example.properties` —
no other ADR change; no Actuator/Docker/N+1/cursor work.

Phase 13-D slice implemented and tested successfully
(observability baseline + HTTP security hardening per DECISIONS.md
ADR-027, written before code):
- Actuator starter only (no Micrometer registries, Prometheus,
tracing, custom indicators, groups, build-info, or management
port): `health` + `info` exposed under `/actuator` on the same
port with `show-details=never` (body is `{"status":"UP"}` only);
both stay authenticated (`anyRequest().authenticated()` covers
them; anonymous callers get the existing 401 `Unauthenticated`
problem); `env`/`metrics`/`beans` stay unexposed (404 even with
credentials). Non-API paths were already outside `RateLimitFilter`.
- Locked security headers in `SecurityConfig` with exact-string
tests on 200/401/404/429 paths: `nosniff`, `DENY`,
`no-referrer`, `default-src 'none'`,
`geolocation=(), microphone=(), camera=()`,
`max-age=31536000; includeSubDomains` (custom secure-only writer;
the default HSTS writer is disabled because it emits spaces),
`same-origin` COOP/CORP. HSTS asserted present on secure requests
and absent on plain HTTP. JWT ordering and RFC 7807 shapes
unchanged.
- Deny-by-default CORS (`common.web.cors.CorsProperties` record
with fail-fast validation + `CorsConfig` source): empty
allowed-origins registers no mappings; when set, exact-origin
matching on `/api/**` only (never `/actuator/**`), methods within
GET/POST/PUT, headers within Authorization/Content-Type,
allow-credentials locked false, max-age default PT1H, no
`X-Forwarded-For` trust. `OPTIONS /api/**` permitted so
preflights are not 401-rejected; actual requests stay JWT-gated
with unchanged user isolation.
- Preflight-only rate-limit bypass in `RateLimitFilter` (genuine
preflight = OPTIONS + `/api/` + non-blank Origin + non-blank
Access-Control-Request-Method; all four required): bypasses
without consuming budget; arbitrary OPTIONS and all other
requests keep 13-C behavior with unchanged budgets.
- Tests: `CorsPropertiesTest` (11 unit), `SecurityHeadersTest`
(5 chain: 200/secure-200/401/404/429),
`ActuatorExposureTest` (6 chain: 401/UP-no-details/info/3×404/
preflight-no-CORS), `CorsPreflightTest` (3 chain, disabled
default), `CorsAllowedOriginTest` (5 chain, enabled origin +
bypass proof), +2 focused `RateLimitFilterTest` cases; 9 slice
tests gain a `@MockitoBean CorsConfigurationSource` for the new
chain wiring (filters stay disabled there). The shared profile
keeps the limiter disabled except the dedicated re-enabled
suites; test resources pin the health/info exposure.
- No migration (V1–V18 untouched), no scoring/pagination/audit/
business-logic change, no Redis/Kafka/Docker/metrics work;
documented in ADR-027, ARCHITECTURE.md §27/§30/§31, and
`application-example.properties` — no other ADR change.
- `./mvnw.cmd clean test`: 1015 tests passing (983 pre-13-D + 32 new),
0 failures, 0 errors, BUILD SUCCESS (Flyway validates/applies all
18 migrations in Testcontainers).

The next implementation task is the next approved Phase 13 slice
(13-E Docker),
as scoped in the approved Phase 13 plan.
Wait for explicit instruction before beginning the next slice.
