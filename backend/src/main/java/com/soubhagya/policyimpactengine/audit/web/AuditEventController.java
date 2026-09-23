package com.soubhagya.policyimpactengine.audit.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.audit.application.AuditService;
import com.soubhagya.policyimpactengine.audit.web.dto.AuditEventResponse;
import com.soubhagya.policyimpactengine.common.pagination.FeedPagination;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Phase 11D — authenticated audit feed. Thin: resolves the user id
 * exclusively from the authenticated principal, delegates to the
 * application service, and returns DTOs.
 *
 * <p>Identity comes only from
 * {@link AuthenticatedUsers#requireUserId(Authentication)} — never from
 * query parameters, bodies, path variables, or headers. Ownership is
 * enforced at the repository level; cross-user events are never
 * returned. The feed is newest-first, read-only, and emits no audit
 * event itself.
 */
@RestController
@RequestMapping("/api/v1/me/audit-events")
public class AuditEventController {

	private final AuditService service;

	public AuditEventController(AuditService service) {
		if (service == null) {
			throw new IllegalArgumentException("AuditService must not be null");
		}
		this.service = service;
	}

	/**
	 * Phase 13-B — paginated audit feed (ADR-026): bare JSON array,
	 * newest first, windowed by {@code page}/{@code size} (defaults
	 * 0/20, maximum 100). The principal is resolved before pagination
	 * is validated so unauthenticated callers stay 401. Read-only:
	 * listing emits nothing.
	 */
	@GetMapping
	public List<AuditEventResponse> list(Authentication authentication,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		FeedPagination pagination = FeedPagination.of(page, size);
		return service.listAuditEventResponsesPaged(userId, pagination.page(), pagination.size());
	}

}
