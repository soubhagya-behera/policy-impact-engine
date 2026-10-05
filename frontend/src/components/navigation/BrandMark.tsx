/**
 * Product wordmark. Deliberately not the Render logo.
 *
 * `whitespace-nowrap` keeps the full name on a single line at every width,
 * which is what stops it breaking mid-name in the narrow mobile header. The
 * type size is left alone so the wordmark keeps its desktop presence rather
 * than being shrunk to fit.
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <span
      className={
        className ??
        'font-display text-lg leading-none tracking-[-0.02em] text-ink whitespace-nowrap'
      }
    >
      Policy Impact
      <span className="text-accent-soft"> Engine</span>
    </span>
  )
}
