package com.soubhagya.policyimpactengine.common.error;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.soubhagya.policyimpactengine.monitoring.application.PolicyFetchClaimRejectedException;
import com.soubhagya.policyimpactengine.policy.application.PolicyArchivedException;
import com.soubhagya.policyimpactengine.policy.fetch.PolicyFetchException;
import com.soubhagya.policyimpactengine.user.AuthenticationRequiredException;
import com.soubhagya.policyimpactengine.user.DuplicateEmailException;
import com.soubhagya.policyimpactengine.user.InvalidCredentialsException;
import com.soubhagya.policyimpactengine.user.InvalidRefreshTokenException;

/**
 * Global RFC 7807 error handling. All error responses use
 * {@code application/problem+json}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(NoSuchElementException.class)
	public ProblemDetail handleNotFound(NoSuchElementException ex) {
		return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
	}

	@ExceptionHandler(DuplicateEmailException.class)
	public ProblemDetail handleConflict(DuplicateEmailException ex) {
		return problem(HttpStatus.CONFLICT, "Already registered", ex.getMessage());
	}

	@ExceptionHandler({ InvalidCredentialsException.class, AuthenticationRequiredException.class,
			InvalidRefreshTokenException.class,
			com.soubhagya.policyimpactengine.user.InvalidGoogleIdentityException.class })
	public ProblemDetail handleUnauthenticated(RuntimeException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Unauthenticated", ex.getMessage());
	}

	/**
	 * Phase 18-B — anonymous Google login colliding with an existing local
	 * email (see DECISIONS.md ADR-037). Generic conflict with no oracle:
	 * nothing is merged and no subject is written.
	 */
	@ExceptionHandler(com.soubhagya.policyimpactengine.user.GoogleLinkRequiredException.class)
	public ProblemDetail handleGoogleLinkRequired(RuntimeException ex) {
		return problem(HttpStatus.CONFLICT, "Already registered", ex.getMessage());
	}

	/**
	 * Phase 16-B/2 — manual-check state conflicts (see DECISIONS.md
	 * ADR-034): a lost attempt claim (another check already in flight)
	 * and a check refused on an archived policy both surface as 409
	 * with their existing deterministic messages.
	 */
	@ExceptionHandler({ PolicyFetchClaimRejectedException.class, PolicyArchivedException.class })
	public ProblemDetail handleCheckConflict(RuntimeException ex) {
		return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
	}

	/**
	 * Phase 16-B/2 — manual-check fetch failures (see DECISIONS.md
	 * ADR-034): any fetch-layer observation failure surfaces as 502
	 * after the existing FAILED attempt recording and reschedule. The
	 * focused mapping covers the operational failure class; only the
	 * fetch layer throws this type, so no other endpoint's behavior
	 * changes.
	 */
	@ExceptionHandler(PolicyFetchException.class)
	public ProblemDetail handleBadGateway(PolicyFetchException ex) {
		return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", ex.getMessage());
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
		Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
				.collect(Collectors.toMap(
						FieldError::getField,
						FieldError::getDefaultMessage,
						(existing, duplicate) -> existing));
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(
			TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Malformed request",
				"Invalid value for '%s'".formatted(ex.getPropertyName()));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	private ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

}
