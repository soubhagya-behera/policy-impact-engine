import { LinkButton } from '../../components/ui/Button'
import { ROUTES } from '../../app/routes'

/**
 * Editorial hero for the overview.
 *
 * The purple wash and oversized display type reproduce the reference's hero
 * treatment; the copy and CTAs are Policy Impact Engine content.
 */
export function HeroSection() {
  return (
    <section className="surface-wash -mx-6 border-b border-line px-6 pb-16 pt-4 md:-mx-10 md:px-10 lg:-mx-12 lg:px-12 lg:pb-24">
      <p className="type-label text-accent-soft">Overview</p>
      <h1 className="type-display mt-6 max-w-5xl text-ink">
        Understand what changed.
        <br className="hidden sm:block" /> Know what it means for you.
      </h1>
      <p className="type-body mt-8 max-w-2xl text-ink-muted">
        Track policy changes, see their privacy impact, and know what action to
        take.
      </p>

      <div className="mt-10 flex flex-col gap-3 sm:flex-row">
        <LinkButton to={ROUTES.policies}>Add a policy</LinkButton>
        <LinkButton to={ROUTES.policies} variant="secondary">
          View policies
        </LinkButton>
      </div>
    </section>
  )
}
