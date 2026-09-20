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

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase 8A — minimal stateless security foundation (see DECISIONS.md
 * ADR-017).
 *
 * <p>Stateless REST posture: CSRF, HTTP Basic, form login, and logout
 * are disabled and no server-side session is ever created.
 * {@code POST /api/v1/auth/register} and the existing
 * {@code /api/v1/policies/**} endpoints stay permitted (behavior
 * preservation — policy endpoints carry no per-user data today);
 * everything else defaults to authenticated so every endpoint added
 * after this slice is locked unless explicitly opened.
 *
 * <p>No JWT mechanism, no principal resolution, and no fake principal
 * in this slice — those belong to Phase 8B. Security failures use
 * {@code application/problem+json} to match the RFC 7807 convention.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper)
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
						.requestMatchers("/api/v1/policies/**").permitAll()
						.anyRequest().authenticated())
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
