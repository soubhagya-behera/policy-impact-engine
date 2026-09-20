package com.soubhagya.policyimpactengine.user.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.soubhagya.policyimpactengine.user.JwtInvalidException;
import com.soubhagya.policyimpactengine.user.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase 8B — Bearer-token authentication (see DECISIONS.md ADR-018).
 *
 * <p>Reads {@code Authorization: Bearer <token>}, validates it through
 * {@link JwtService}, and publishes an {@link AuthenticatedUser}
 * authentication carrying the User UUID. Identity comes only from the
 * verified token subject — never from email, query params, bodies,
 * headers, or path variables.
 *
 * <p>Token failures clear the context and continue: this filter never
 * writes its own 401, so missing, invalid, expired, and malformed
 * tokens all reach the chain's existing entry point and share the one
 * standard unauthenticated response.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		if (jwtService == null) {
			throw new IllegalArgumentException("JwtService must not be null");
		}
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			try {
				UUID userId = jwtService.parseUserId(header.substring("Bearer ".length()));
				UsernamePasswordAuthenticationToken authentication =
						new UsernamePasswordAuthenticationToken(
								new AuthenticatedUser(userId), null, List.of());
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
			catch (JwtInvalidException invalid) {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}
}
