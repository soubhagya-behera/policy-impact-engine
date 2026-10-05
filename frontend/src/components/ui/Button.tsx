import { Link } from 'react-router-dom'
import type { ComponentPropsWithoutRef, ReactNode } from 'react'
import { cn } from '../../lib/cn'

/**
 * Button variants.
 *  - `primary`   inverted white block, the single strongest action
 *  - `secondary` flat outlined block
 *  - `ghost`     text-only, for tertiary actions
 *  - `danger`    outlined destructive action
 *
 * Controls are deliberately flat: no radii, no shadows, hairline borders.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

export interface ButtonProps extends ComponentPropsWithoutRef<'button'> {
  variant?: ButtonVariant
  /** Shows a busy state and blocks interaction. */
  isLoading?: boolean
  fullWidth?: boolean
  children: ReactNode
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    'bg-ink text-base border border-ink hover:bg-ink-secondary active:bg-ink-muted',
  secondary:
    'bg-transparent text-ink border border-line-strong hover:border-ink hover:bg-surface',
  ghost:
    'bg-transparent text-ink-secondary border border-transparent hover:text-ink hover:bg-surface',
  danger:
    'bg-transparent text-accent-soft border border-line-strong hover:border-accent-soft hover:bg-surface',
}

export function Button({
  variant = 'primary',
  isLoading = false,
  fullWidth = false,
  className,
  disabled,
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled ?? isLoading}
      aria-busy={isLoading || undefined}
      className={cn(
        // 44px minimum target keeps mobile taps usable without rounding.
        'inline-flex min-h-11 items-center justify-center gap-2.5 px-6 py-3',
        'font-body text-base leading-6 tracking-[0.01em]',
        'transition-colors duration-150 ease-standard',
        'disabled:cursor-not-allowed disabled:opacity-50',
        VARIANT_CLASSES[variant],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}

export interface LinkButtonProps {
  to: string
  variant?: ButtonVariant
  fullWidth?: boolean
  className?: string
  /** Used by the mobile menus to dismiss the panel on navigation. */
  onClick?: () => void
  children: ReactNode
  'aria-label'?: string
}

const LINK_BASE_CLASSES =
  'inline-flex min-h-11 items-center justify-center gap-2.5 px-6 py-3 ' +
  'font-body text-base leading-6 tracking-[0.01em] transition-colors duration-150 ease-standard'

/**
 * Router link styled as a button. Used instead of `<button>` whenever the
 * control changes location, so it is a real link for keyboard and
 * middle-click users.
 */
export function LinkButton({
  to,
  variant = 'primary',
  fullWidth = false,
  className,
  children,
  ...rest
}: LinkButtonProps) {
  return (
    <Link
      to={to}
      className={cn(
        LINK_BASE_CLASSES,
        VARIANT_CLASSES[variant],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    >
      {children}
    </Link>
  )
}
