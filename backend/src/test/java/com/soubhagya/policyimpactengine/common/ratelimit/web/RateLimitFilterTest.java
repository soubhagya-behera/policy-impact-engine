package com.soubhagya.policyimpactengine.common.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.soubhagya.policyimpactengine.common.ratelimit.RateLimitProperties;
import com.soubhagya.policyimpactengine.common.ratelimit.RateLimitService;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 13-C — unit tests for {@link RateLimitFilter} (see DECISIONS.md
 * ADR-025). The filter is exercised directly with mock servlet
 * request/response objects: route-to-tier mapping, 429 problem+json
 * shape with {@code Retry-After}, anonymous fallback, disabled
 * pass-through, and non-API pass-through. No Spring context, no
 * database. End-to-end chain behavior (real JWT, real properties)
 * lives in the rate-limit integration tests.
 */
class RateLimitFilterTest {

	@Test
	void nonApiPathsPassThroughWithoutConsumingBudget() throws Exception {
		Harness harness = new Harness(properties(10, 10, 10, 10), true);

		harness.filter().doFilter(request("GET", "/health", null, null),
				harness.response(), harness.chain());

		assertThat(harness.called()).isTrue();
		assertThat(harness.service().trackedKeys()).isZero();
	}

	@Test
	void disabledFlagIsTruePassThrough() throws Exception {
		Harness harness = new Harness(properties(1, 1, 1, 1), false);
		UUID userId = UUID.randomUUID();

		for (int i = 0; i < 5; i++) {
			harness.filter().doFilter(
					request("GET", "/api/v1/me/notifications", userId, "9.9.9.9"),
					harness.response(), harness.chain());
		}

		assertThat(harness.calls()).isEqualTo(5);
		assertThat(harness.service().trackedKeys()).isZero();
		assertThat(harness.lastResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void authenticatedApiRequestsShareOneUserBucket() throws Exception {
		Harness harness = new Harness(properties(10, 1, 10, 10), true);
		UUID userId = UUID.randomUUID();

		harness.filter().doFilter(
				request("GET", "/api/v1/me/notifications", userId, "9.9.9.9"),
				harness.response(), harness.chain());
		assertThat(harness.calls()).isEqualTo(1);

		harness.filter().doFilter(
				request("GET", "/api/v1/me/recommendations", userId, "9.9.9.9"),
				harness.response(), harness.chain());
		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);
		assertThat(harness.lastResponse().getHeader("Retry-After")).isNotBlank();
		assertThat(harness.lastResponse().getContentType())
				.contains("application/problem+json");
		assertThat(harness.lastResponse().getContentAsString())
				.contains("Too Many Requests");
	}

	@Test
	void registerAndLoginHaveSeparateIpBuckets() throws Exception {
		Harness harness = new Harness(properties(1, 10, 10, 10), true);

		harness.filter().doFilter(
				request("POST", "/api/v1/auth/register", null, "203.0.113.7"),
				harness.response(), harness.chain());
		harness.filter().doFilter(
				request("POST", "/api/v1/auth/register", null, "203.0.113.7"),
				harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);

		harness.filter().doFilter(
				request("POST", "/api/v1/auth/login", null, "203.0.113.7"),
				harness.response(), harness.chain());
		harness.filter().doFilter(
				request("POST", "/api/v1/auth/login", null, "203.0.113.7"),
				harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(2);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);

		harness.filter().doFilter(
				request("POST", "/api/v1/auth/register", null, "203.0.113.8"),
				harness.response(), harness.chain());
		assertThat(harness.calls()).isEqualTo(3);
	}

	@Test
	void explanationTierIsSeparateFromGeneralApi() throws Exception {
		Harness harness = new Harness(properties(10, 100, 1, 10), true);
		UUID userId = UUID.randomUUID();
		String path = "/api/v1/me/impact-assessments/" + UUID.randomUUID()
				+ "/explanation";

		harness.filter().doFilter(request("POST", path, userId, "9.9.9.9"),
				harness.response(), harness.chain());
		harness.filter().doFilter(request("POST", path, userId, "9.9.9.9"),
				harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);

		harness.filter().doFilter(
				request("GET", "/api/v1/me/notifications", userId, "9.9.9.9"),
				harness.response(), harness.chain());
		assertThat(harness.calls()).isEqualTo(2);
	}

	@Test
	void anonymousRequestsFallBackToIpBucket() throws Exception {
		Harness harness = new Harness(properties(10, 10, 10, 1), true);

		harness.filter().doFilter(
				request("GET", "/api/v1/me/notifications", null, "203.0.113.9"),
				harness.response(), harness.chain());
		harness.filter().doFilter(
				request("GET", "/api/v1/me/notifications", null, "203.0.113.9"),
				harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void blankRemoteAddressNeverThrows() throws Exception {
		Harness harness = new Harness(properties(1, 10, 10, 10), true);

		harness.filter().doFilter(
				request("POST", "/api/v1/auth/login", null, ""),
				harness.response(), harness.chain());
		harness.filter().doFilter(
				request("POST", "/api/v1/auth/login", null, ""),
				harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);
	}

	@Test
	void genuinePreflightBypassesWithoutConsumingBudget() throws Exception {
		Harness harness = new Harness(properties(10, 1, 10, 1), true);
		UUID userId = UUID.randomUUID();

		for (int i = 0; i < 5; i++) {
			MockHttpServletRequest preflight =
					request("OPTIONS", "/api/v1/policies", userId, "9.9.9.9");
			preflight.addHeader("Origin", "https://app.example.com");
			preflight.addHeader("Access-Control-Request-Method", "GET");
			harness.filter().doFilter(preflight, harness.response(), harness.chain());
		}

		assertThat(harness.calls()).isEqualTo(5);
		assertThat(harness.service().trackedKeys()).isZero();
	}

	@Test
	void arbitraryOptionsWithoutPreflightHeadersKeepsTierBehavior() throws Exception {
		Harness harness = new Harness(properties(10, 1, 10, 10), true);
		UUID userId = UUID.randomUUID();

		harness.filter().doFilter(
				request("GET", "/api/v1/policies", userId, "9.9.9.9"),
				harness.response(), harness.chain());
		assertThat(harness.calls()).isEqualTo(1);

		MockHttpServletRequest optionsWithoutRequestMethod =
				request("OPTIONS", "/api/v1/policies", userId, "9.9.9.9");
		optionsWithoutRequestMethod.addHeader("Origin", "https://app.example.com");
		harness.filter().doFilter(
				optionsWithoutRequestMethod, harness.response(), harness.chain());

		assertThat(harness.calls()).isEqualTo(1);
		assertThat(harness.lastResponse().getStatus()).isEqualTo(429);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	private static RateLimitProperties properties(int auth, int api,
			int explanation, int anonymous) {
		return new RateLimitProperties(true,
				Duration.ofMinutes(1), auth,
				Duration.ofMinutes(1), api,
				Duration.ofMinutes(1), explanation,
				Duration.ofMinutes(1), anonymous,
				1000, Duration.ofMinutes(10),
				Duration.ofMinutes(1), 10);
	}

	private static MockHttpServletRequest request(String method, String uri,
			UUID userId, String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		request.setRemoteAddr(remoteAddr);
		if (userId != null) {
			Authentication authentication = new UsernamePasswordAuthenticationToken(
					new AuthenticatedUser(userId), null, List.of());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		else {
			SecurityContextHolder.clearContext();
		}
		return request;
	}

	/** One filter plus fresh response/chain per request, shared service. */
	private static final class Harness {

		private final RateLimitService service;
		private final RateLimitFilter filter;
		private final AtomicBoolean called = new AtomicBoolean();
		private int calls;
		private MockHttpServletResponse lastResponse;

			Harness(RateLimitProperties properties, boolean enabled) {
			RateLimitProperties effective = enabled ? properties
					: new RateLimitProperties(false,
							Duration.ofMinutes(1), properties.authMaxRequests(),
							Duration.ofMinutes(1), properties.apiMaxRequests(),
							Duration.ofMinutes(1), properties.explanationMaxRequests(),
							Duration.ofMinutes(1), properties.anonymousMaxRequests(),
							1000, Duration.ofMinutes(10),
							Duration.ofMinutes(1), 10);
			this.service = new RateLimitService(effective,
					Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC));
			this.filter = new RateLimitFilter(service, effective, new ObjectMapper());
		}

		RateLimitFilter filter() {
			return filter;
		}

		RateLimitService service() {
			return service;
		}

		MockFilterChain chain() {
			called.set(false);
			return new MockFilterChain() {
				@Override
				public void doFilter(jakarta.servlet.ServletRequest request,
						jakarta.servlet.ServletResponse response) {
					called.set(true);
					calls++;
				}
			};
		}

		MockHttpServletResponse response() {
			lastResponse = new MockHttpServletResponse();
			return lastResponse;
		}

		boolean called() {
			return called.get();
		}

		int calls() {
			return calls;
		}

		MockHttpServletResponse lastResponse() {
			return lastResponse;
		}
	}
}
