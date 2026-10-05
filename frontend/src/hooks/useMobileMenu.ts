import { useCallback, useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'

/**
 * Open/closed state for a mobile navigation panel, plus the body-scroll lock
 * and Escape handling that must accompany it.
 *
 * Shared by the application navigation and the public landing navigation so
 * both behave identically — a single implementation means the accessibility
 * guarantees cannot drift apart between the two headers.
 *
 * The scroll lock matters for more than tidiness: without it, a touch drag on
 * a long page scrolls the content behind the open panel.
 */
export function useMobileMenu(isDesktop: boolean) {
  const [isOpen, setIsOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)

  // Growing into the desktop breakpoint hides the panel, so close it and
  // release the scroll lock rather than leaving stale state behind.
  useEffect(() => {
    if (isDesktop) setIsOpen(false)
  }, [isDesktop])

  useEffect(() => {
    if (!isOpen) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [isOpen])

  // Escape closes the menu and restores focus to its trigger, so keyboard
  // users are returned to where they were rather than to the top of the page.
  useEffect(() => {
    if (!isOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setIsOpen(false)
      triggerRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [isOpen])

  const toggle = useCallback(() => setIsOpen((open) => !open), [])
  const close = useCallback(() => setIsOpen(false), [])

  return { isOpen, setIsOpen, toggle, close, triggerRef }
}

/** Shape returned by {@link useMobileMenu}, for prop typing. */
export type MobileMenu = ReturnType<typeof useMobileMenu>
export type MenuTriggerRef = RefObject<HTMLButtonElement | null>
