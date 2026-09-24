package com.soubhagya.policyimpactengine.user.web;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;

import com.soubhagya.policyimpactengine.common.ratelimit.web.RateLimitFilter;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase 8A — minimal stateless security foundation (see DECISIONS.md
 * ADR-017).
 *
 * <p>Stateless REST posture: CSRF, HTTP Basic, form login, and logout
 * are disabled and no server-side session is ever created.
 * {@code POST /api/v1/auth/register} and
 * {@code POST /api/v1/auth/login} stay permitted; everything else
 * defaults to authenticated so every endpoint added after this slice
 * is locked unless explicitly opened.
 *
 * <p>Phase 8B adds Bearer-token authentication before authorization:
 * {@link JwtAuthenticationFilter} publishes the {@link AuthenticatedUser}
 * principal. Authenticated policy hardening requires authentication
 * for {@code /api/v1/policies/**} (owner-scoped reads, owner-assigned
 * registration; see DECISIONS.md ADR-019).
 * No refresh tokens or roles (see DECISIONS.md ADR-018). Security
 * failures use {@code application/problem+json} to match the RFC 7807
 * convention.
 *
 * <p>Phase 13-C throttles HTTP requests after authentication:
 * {@link RateLimitFilter} enforces per-tier token buckets (auth,
 * explanation, general API, anonymous) and rejects over-limit callers
 * with 429 {@code application/problem+json} plus {@code Retry-After}
 * (see DECISIONS.md ADR-025).
 *
 * <p>Phase 13-D hardens HTTP transport (see DECISIONS.md ADR-027):
 * explicit security headers with locked literal values (HSTS only on
 * secure requests via a custom writer, since Spring's default writer
 * emits spaces around {@code ;}); deny-by-default CORS for
 * {@code /api/**} only; {@code OPTIONS /api/**} permitted so browser
 * CORS preflights are not rejected by authentication while actual
 * requests stay JWT-gated; actuator ({@code /actuator/health},
 * {@code /actuator/info}) stays authenticated by default.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/** Locked Phase 13-D header literals (see DECISIONS.md ADR-027). */
	static final String CONTENT_SECURITY_POLICY = "default-src 'none'";
	static final String PERMISSIONS_POLICY = "geolocation=(), microphone=(), camera=()";
	static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
			JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitFilter rateLimitFilter,
			CorsConfigurationSource corsConfigurationSource)
			throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(headers -> headers
						.contentTypeOptions(Customizer.withDefaults())
						.frameOptions(frame -> frame.deny())
						.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
						.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
						.httpStrictTransportSecurity(hsts -> hsts.disable())
						.crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
						.crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN))
						.addHeaderWriter(new StrictTransportSecurityWriter())
						.permissionsPolicy(permissions -> permissions.policy(PERMISSIONS_POLICY)))
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(authenticationEntryPoint(objectMapper))
						.accessDeniedHandler(accessDeniedHandler(objectMapper)));
		return http.build();
	}

	/**
	 * Phase 13-D — emits the locked spaceless HSTS literal on secure
	 * requests only (see DECISIONS.md ADR-027). Spring's default HSTS
	 * writer (disabled above) emits {@code max-age=31536000 ;
	 * includeSubDomains} with spaces, which would fail the exact-string
	 * contract; plain-HTTP requests correctly receive no HSTS header.
	 */
	private static final class StrictTransportSecurityWriter implements HeaderWriter {

		@Override
		public void writeHeaders(HttpServletRequest request,
				jakarta.servlet.http.HttpServletResponse response) {
			if (request.isSecure()) {
				response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
			}
		}
	}

	private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
		return (request, response, ex) -> {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(
					HttpStatus.UNAUTHORIZED, "Authentication is required");
			problem.setTitle("Unauthenticated");
			writeProblem(response, objectMapper, problem, HttpStatus.UNAUTHORIZED);
		};
	}

	private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, ex) -> {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(
					HttpStatus.FORBIDDEN, "Access is denied");
			problem.setTitle("Forbidden");
			writeProblem(response, objectMapper, problem, HttpStatus.FORBIDDEN);
		};
	}

	private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper,
			ProblemDetail problem, HttpStatus status) throws IOException {
		response.setStatus(status.value());
		response.setContentType("application/problem+json");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
