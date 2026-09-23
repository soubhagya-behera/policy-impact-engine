package com.soubhagya.policyimpactengine.user.web;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
			JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitFilter rateLimitFilter)
			throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(authenticationEntryPoint(objectMapper))
						.accessDeniedHandler(accessDeniedHandler(objectMapper)));
		return http.build();
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
