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

Not yet implemented:
- Redirect revalidation (redirects remain disabled)
- SimHash / near-duplicate handling (if still planned)
- Policy observation orchestration/integration (scheduler/manual check wiring fetch → extract → normalize → hash → observe)
- Diff / textual version comparison
- Classification / concepts / impact / recommendations / scheduler / notifications

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

Remaining Phase 2 scope will be built in later slices (redirect revalidation, observation orchestration/integration, SimHash if still planned, and later pipeline stages).

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

Phase 2 — Policy Fetching is IN PROGRESS.

Phase 2A — COMPLETE
Phase 2B — COMPLETE
Phase 2C — COMPLETE
Phase 2D — COMPLETE
Phase 2E — COMPLETE
Phase 2F — COMPLETE
Phase 2G — COMPLETE

Phase 2 remains IN PROGRESS. Remaining Phase 2 work stays separate:
- policy observation orchestration/integration (fetch → extract → normalize → hash → observe wiring)
- textual diffing
- SimHash/near-duplicate handling if still planned
- concept matching
- impact analysis
- recommendations
- notification/scheduling

Do not mark Phase 2 complete yet.

## Next Action

The next implementation task is the next Phase 2 slice (observation
orchestration/integration, textual diffing, and later pipeline stages),
as scoped in ARCHITECTURE.md §31.
Do not begin Phase 2H without explicit instruction.
