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
- ChangeConceptMatch immutable JPA entity (intelligence/domain, UUID id, FKs to PolicyChangeRecord and PrivacyConcept, matched_fragment TEXT, pattern_id, match_kind, createdAt; no setters, updatable=false; change eager on concept for deterministic reads, unique on (change_id, concept_id))
- PrivacyConceptRepository (findByCode, findAll) + ChangeConceptMatchRepository (findByChange_IdOrderByConcept_CodeAsc, findByConcept_IdOrderByChange_IdAsc; no matching logic)
- ConceptMatcher abstraction + DeterministicConceptMatcher (pure JDK, CASE_INSENSITIVE|UNICODE_CASE|UNICODE_CHARACTER_CLASS regex, LinkedHashMap ordered concepts, one match per (change, concept) at most, matched_fragment is verbatim Matcher.group() substring of oldText/newText, no DB/HTTP/clock/randomness/LLM/embeddings)
- PolicyObservationPersistenceService integrates concept matching inside the same short transaction as version+diff+changes: version observe → predecessor lookup → diff → persist changes → load concepts → match per change → persist ChangeConceptMatch in concept-code order; concept-match failure rolls back version+changes+matches together; SimHash remains outside transaction, unpersisted, SHA-256 authoritative
- IntelligenceConfiguration exposes DeterministicConceptMatcher as Spring bean (matcher stays Spring-free)
- DeterministicConceptMatcherTest (22 deterministic unit tests: location/third-party/advertising positives, hyphen/space variants, negative case, multi-concept, case-insensitive, punctuation, evidence substring, old/new/both handling, empty/null, deterministic ordering, Unicode, duplicate prevention, plus 4 golden pinning tests) + ConceptMatchRepositoryTest (Testcontainers: V4 applied, 9 seeds, ordered retrieval, unique violation)
- ConceptMatchPersistenceIntegrationTest (Testcontainers PostgreSQL end-to-end: FIRST_VERSION no matches, UNCHANGED no matches, NEW_VERSION persists expected concepts with evidence/deterministic ordering and SimHash still present, repeat UNCHANGED no new matches, matcher failure rollback verifies no partial version/change/match rows) — 300 tests passing, BUILD SUCCESS

Remaining Phase 2 scope will be built in later slices (redirect revalidation and later pipeline stages).

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

Phase 2 remains IN PROGRESS. Remaining Phase 2 work stays separate:
- similarity calibration/near-duplicate policy if actually required
- impact analysis
- personalized assessment
- recommendations
- PolicyFetchAttempt
- scheduling
- notifications

Do not mark Phase 2 complete yet.

## Next Action

The next implementation task is the next Phase 2 slice (later pipeline
stages),
as scoped in ARCHITECTURE.md §31.
Do not begin Phase 2O without explicit instruction.
