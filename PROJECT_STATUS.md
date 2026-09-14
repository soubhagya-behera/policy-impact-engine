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

Not yet implemented:
- SSRF protection (DNS/IP validation, private/loopback/link-local blocking, redirect revalidation)
- Response-size limits
- Jsoup / HTML extraction
- Text normalization
- SHA-256 / SimHash
- PolicyVersion / versioning
- Diff / classification / concepts / impact / recommendations / scheduler / notifications

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

Remaining Phase 2 scope will be built in later slices (SSRF, size limits, Jsoup, extraction, normalization).

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

Phase 2 — Policy Fetching is IN PROGRESS.

## Next Action

The next implementation task is the SSRF/redirect-security slice of Phase 2,
as scoped in ARCHITECTURE.md §27 and §31.
Do not begin Phase 2B without explicit instruction.
