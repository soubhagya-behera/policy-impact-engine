package com.soubhagya.policyimpactengine.common.ratelimit.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.soubhagya.policyimpactengine.common.ratelimit.RateLimitProperties;
import com.soubhagya.policyimpactengine.common.ratelimit.RateLimitService;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 13-C — HTTP request throttling (see DECISIONS.md ADR-025).
 *
 * <p>Runs after {@link
 * com.soubhagya.policyimpactengine.user.web.JwtAuthenticationFilter},
 * so the trusted principal is available for user keying while
 * unauthenticated requests still reach their 401 unchanged. Route to
 * tier mapping is exact path+method matching: each auth endpoint
 * (own IP-keyed bucket), the explanation endpoint (user key, anonymous fallback),
 * then every other {@code /api/**} route (user key when authenticated,
 * IP key otherwise). Non-API paths pass through untouched.
 *
 * <p>The filter counts requests but never validates input, so 400
 * behavior is preserved. Rejections are written directly as 429
 * {@code application/problem+json} with a {@code Retry-After} header;
 * no exception type and no {@code X-RateLimit-*} headers exist in v1.
 * When {@code rate-limit.enabled} is false this is a true
 * pass-through. Server-side work (scheduler, fan-out, audit) never
 * traverses this filter.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimitService rateLimitService;
	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;

	public RateLimitFilter(RateLimitService rateLimitService,
			RateLimitProperties properties, ObjectMapper objectMapper) {
		if (rateLimitService == null || properties == null || objectMapper == null) {
			throw new IllegalArgumentException("Dependencies must not be null");
		}
		this.rateLimitService = rateLimitService;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!properties.enabled() || !request.getRequestURI().startsWith("/api/")) {
			chain.doFilter(request, response);
			return;
		}
		RateLimitService.Decision decision = decide(request);
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType("application/problem+json");
		response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.TOO_MANY_REQUESTS,
				"Rate limit exceeded, retry after " + decision.retryAfterSeconds()
						+ " seconds");
		problem.setTitle("Too Many Requests");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}

	private RateLimitService.Decision decide(HttpServletRequest request) {
		String path = request.getRequestURI();
		String method = request.getMethod();
		String clientIp = clientIp(request);
		if ("POST".equalsIgnoreCase(method)
				&& "/api/v1/auth/register".equals(path)) {
			return rateLimitService.registerAttempt(clientIp);
		}
		if ("POST".equalsIgnoreCase(method)
				&& "/api/v1/auth/login".equals(path)) {
			return rateLimitService.loginAttempt(clientIp);
		}
		UUID userId = trustedUserId();
		if ("POST".equalsIgnoreCase(method)
				&& path.startsWith("/api/v1/me/impact-assessments/")
				&& path.endsWith("/explanation")) {
			return userId != null
					? rateLimitService.explanationRequest(userId)
					: rateLimitService.anonymousRequest(clientIp);
		}
		if (userId != null) {
			return rateLimitService.apiRequest(userId);
		}
		return rateLimitService.anonymousRequest(clientIp);
	}

	private static String clientIp(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (remote == null || remote.isBlank()) {
			return "unknown";
		}
		return remote;
	}

	/**
	 * Returns the trusted user id, or null when no trusted
	 * {@link AuthenticatedUser} principal is present. Never throws:
	 * anonymous callers fall through to the IP-keyed tiers and keep
	 * their downstream 401.
	 */
	private static UUID trustedUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
			return null;
		}
		return principal.userId();
	}
}
