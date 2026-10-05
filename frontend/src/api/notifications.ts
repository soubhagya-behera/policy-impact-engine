import { apiRequest } from './client'
import type { Notification, PageParams } from './types'

/** Notification endpoints, mirroring `NotificationController`. */

/** Newest-first feed, windowed by `page`/`size`. */
export function listNotifications(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<Notification[]> {
  return apiRequest<Notification[]>('/api/v1/me/notifications', {
    query: { ...params },
    signal,
  })
}

export function listUnreadNotifications(
  params: PageParams = {},
  signal?: AbortSignal,
): Promise<Notification[]> {
  return apiRequest<Notification[]>('/api/v1/me/notifications/unread', {
    query: { ...params },
    signal,
  })
}

/** Marks one notification read and returns its updated representation. */
export function markNotificationRead(notificationId: string): Promise<Notification> {
  return apiRequest<Notification>(
    `/api/v1/me/notifications/${notificationId}/read`,
    { method: 'POST' },
  )
}
