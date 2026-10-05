import type { ComponentPropsWithoutRef } from 'react'
import { cn } from '../../lib/cn'

/**
 * Flat form field. Label, optional hint, and error are wired together with
 * `aria-describedby` / `aria-invalid` so screen readers announce the state.
 */
export interface FieldProps extends ComponentPropsWithoutRef<'input'> {
  label: string
  hint?: string
  /** Server-side validation message for this specific field. */
  errorMessage?: string | undefined
}

let fieldIdCounter = 0
function nextFieldId(prefix: string): string {
  fieldIdCounter += 1
  return `${prefix}-${fieldIdCounter}`
}

export function Field({
  label,
  hint,
  errorMessage,
  className,
  id,
  ...rest
}: FieldProps) {
  const generatedId = nextFieldId('field')
  const inputId = id ?? generatedId
  const hintId = hint ? `${inputId}-hint` : undefined
  const errorId = errorMessage ? `${inputId}-error` : undefined

  return (
    <div className="flex flex-col gap-2">
      <label
        htmlFor={inputId}
        className="type-label text-ink-muted"
      >
        {label}
      </label>

      <input
        id={inputId}
        className={cn(
          'min-h-11 w-full border bg-transparent px-4 py-3',
          'font-body text-base text-ink placeholder:text-ink-ghost',
          'transition-colors duration-150 ease-standard',
          'focus:border-accent-soft focus:outline-none',
          errorMessage ? 'border-accent-soft' : 'border-line-strong',
          className,
        )}
        aria-invalid={errorMessage ? true : undefined}
        aria-describedby={cn(hintId, errorId) || undefined}
        {...rest}
      />

      {hint ? (
        <p id={hintId} className="font-body text-sm text-ink-ghost">
          {hint}
        </p>
      ) : null}

      {errorMessage ? (
        <p id={errorId} className="font-body text-sm text-accent-soft">
          {errorMessage}
        </p>
      ) : null}
    </div>
  )
}
