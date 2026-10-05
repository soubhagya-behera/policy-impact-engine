import { cn } from '../../lib/cn'
import type { RefObject } from 'react'

/**
 * Compact mobile navigation trigger.
 *
 * Extracted verbatim from the application navigation so the public landing
 * header and the authenticated shell share one hamburger — same 44px target,
 * same hairline border, same 150ms/300ms transitions on the measured easings.
 * Hidden from `lg` up, where the full navigation is shown instead.
 */
export function MenuTrigger({
  isOpen,
  onToggle,
  controlsId,
  buttonRef,
  className,
}: {
  isOpen: boolean
  onToggle: () => void
  /** `id` of the panel this control shows and hides. */
  controlsId: string
  buttonRef: RefObject<HTMLButtonElement | null>
  className?: string
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      onClick={onToggle}
      aria-expanded={isOpen}
      aria-controls={controlsId}
      aria-label={isOpen ? 'Close navigation menu' : 'Open navigation menu'}
      className={cn(
        'inline-flex size-11 shrink-0 items-center justify-center border border-line-strong text-ink',
        'transition-colors duration-150 ease-standard hover:border-ink lg:hidden',
        className,
      )}
    >
      <span aria-hidden="true" className="relative block h-3 w-5">
        <span
          className={cn(
            'absolute left-0 block h-px w-full bg-current transition-all duration-300 ease-global',
            isOpen ? 'top-1.5 rotate-45' : 'top-0',
          )}
        />
        <span
          className={cn(
            'absolute left-0 top-1.5 block h-px w-full bg-current transition-opacity duration-150 ease-standard',
            isOpen && 'opacity-0',
          )}
        />
        <span
          className={cn(
            'absolute left-0 block h-px w-full bg-current transition-all duration-300 ease-global',
            isOpen ? 'top-1.5 -rotate-45' : 'top-3',
          )}
        />
      </span>
    </button>
  )
}
