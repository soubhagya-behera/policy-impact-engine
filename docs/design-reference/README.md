# Frontend Design Reference

Reference-only artifacts for the Policy Impact Engine frontend. Neither file in this
folder is application code, and neither is imported, bundled, served, or `require`d by
the React application.

## Contents

| File | What it is |
| --- | --- |
| `DESIGN.md` | Measured visual / design-system reference. Colors, typography scale, spacing, layout gaps, motion durations and easing, component style recipes, layout measurements, responsive conditions, and CSS custom properties — sampled from 338 rendered elements at 1536 x 826px. |
| `render-com.html` | Captured Render HTML reference. The full rendered document (markup, inline computed styles, navigation, sections, responsive rules, typography, spacing, assets and interaction patterns) used as ground truth for structure and visual language. |

Both are the **visual source of truth** for the upcoming frontend. Do not replace them
with generic design assumptions.

## Stack

- React + Vite + **JavaScript**
- Tailwind CSS
- **TypeScript is NOT to be introduced.**

## Frontend design rule

The frontend should **reproduce the visual language** measured in these references:

- Near-black foundation (`#0D0D0D` base, dark surfaces throughout)
- Render-inspired editorial SaaS aesthetic
- Roobert / PPNeueMontreal typography direction
- Measured typography scale from `DESIGN.md` (80px/300/-2.4px heading, 64px/300 section
  heading, 20px/400 subheading, 18px/400 body, 16px/400 button)
- Supplied purple / green / blue accent palette (e.g. purple `#8A05FF` / `#D1B8FF`,
  green `#37CD8F` / `#00C89B`, blue `#002C6F` / `#8AD6FF`)
- Large grid-based composition
- Restrained borders (hairline `rgb(39, 39, 39)` rules)
- Sharp / flat primary controls (`border-radius: 0px`, no shadows)
- Smooth 150–300ms interactions (`0.15s` / `0.3s`,
  `cubic-bezier(0.4, 0, 0.2, 1)` and `--global-ease`)
- Responsive behavior based on the supplied breakpoints (376 / 420 / 640 / 768 / 1024 /
  1180 / 1440 / 1600 / 1760 / 1920, plus `max-width: 600px` / `max-width: 1522px` and
  `prefers-reduced-motion`)
- **No generic rounded-card SaaS redesign.**

## Do not copy

The actual product content must be **Policy Impact Engine**. Reproduce the visual
language only — never the source site's identity:

- Render branding or Render logo
- Render-specific marketing copy
- Render navigation labels
- Render product claims
- Render account flows
- Render cookie / Osano UI
- Render-specific assets, unless an asset is purely visual and legally and technically
  appropriate for our own implementation

## Change policy

These references must remain **unchanged** unless the design specification itself is
intentionally updated later. Treat any edit to these files as a deliberate design-system
change, not an incidental cleanup.
