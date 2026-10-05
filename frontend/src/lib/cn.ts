/** Class-name joiner that drops falsy entries. Keeps `clsx` out of the bundle. */
export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(' ')
}
