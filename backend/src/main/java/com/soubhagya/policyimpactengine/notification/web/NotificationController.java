package com.soubhagya.policyimpactengine.notification.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.soubhagya.policyimpactengine.notification.application.NotificationService;
import com.soubhagya.policyimpactengine.notification.web.dto.NotificationResponse;
import com.soubhagya.policyimpactengine.user.web.AuthenticatedUsers;

/**
 * Phase 10B-2B — authenticated in-app notification feed. Thin: resolves
 * the user id exclusively from the authenticated principal, delegates to
 * the application service, and returns DTOs.
 *
 * <p>Identity comes only from
 * {@link AuthenticatedUsers#requireUserId(Authentication)} — never from
 * query parameters, bodies, path variables, or headers.
 * {@code notificationId} is only the notification resource identifier.
 * Cross-user access behaves as not-found and never reveals whether the
 * row exists.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

	private final NotificationService service;

	public NotificationController(NotificationService service) {
		if (service == null) {
			throw new IllegalArgumentException("NotificationService must not be null");
		}
		this.service = service;
	}

	@GetMapping
	public List<NotificationResponse> list(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.listNotificationResponses(userId);
	}

	@GetMapping("/unread")
	public List<NotificationResponse> listUnread(Authentication authentication) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.listUnreadNotificationResponses(userId);
	}

	@PostMapping("/{notificationId}/read")
	public NotificationResponse markRead(Authentication authentication,
			@PathVariable UUID notificationId) {
		UUID userId = AuthenticatedUsers.requireUserId(authentication);
		return service.markReadResponse(userId, notificationId);
	}

}
