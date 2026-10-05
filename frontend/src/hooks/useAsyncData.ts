import { useCallback, useEffect, useRef, useState } from 'react'
import { toErrorMessage } from '../api/errors'

/**
 * Deliberate async data state.
 *
 * Every panel needs the same four states — idle, loading, error, empty — and
 * getting them right (especially *not* showing a spinner over stale content,
 * and not treating an error as "empty") is easy to get subtly wrong inline.
 * This hook centralises it.
 */
export type AsyncStatus = 'idle' | 'loading' | 'success' | 'error'

export interface AsyncState<T> {
  status: AsyncStatus
  data: T | null
  errorMessage: string | null
  /** True only for the first load, so refreshes do not blank the view. */
  isInitialLoading: boolean
  /** True when a successful load produced an empty collection. */
  isEmpty: boolean
  reload(): void
}

export interface UseAsyncDataOptions {
  /** Skip fetching entirely (e.g. the user is still anonymous). */
  enabled?: boolean
}

export function useAsyncData<T>(
  loader: (signal: AbortSignal) => Promise<T>,
  isEmptyPredicate: (data: T) => boolean = () => false,
  options: UseAsyncDataOptions = {},
): AsyncState<T> {
  const { enabled = true } = options

  const [status, setStatus] = useState<AsyncStatus>('idle')
  const [data, setData] = useState<T | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [reloadNonce, setReloadNonce] = useState(0)
  const hasLoadedRef = useRef(false)

  // Keeps the latest loader without making it a dependency, so an inline
  // arrow function does not retrigger the effect on every render.
  const loaderRef = useRef(loader)
  loaderRef.current = loader
  const emptyPredicateRef = useRef(isEmptyPredicate)
  emptyPredicateRef.current = isEmptyPredicate

  useEffect(() => {
    if (!enabled) {
      setStatus('idle')
      return
    }

    const controller = new AbortController()
    let cancelled = false

    setStatus('loading')
    setErrorMessage(null)

    void (async () => {
      try {
        const result = await loaderRef.current(controller.signal)
        if (cancelled) return
        setData(result)
        setStatus('success')
        hasLoadedRef.current = true
      } catch (error) {
        if (cancelled) return
        // An aborted request is a cancellation, not a failure to report.
        if (error instanceof DOMException && error.name === 'AbortError') return
        setData(null)
        setErrorMessage(toErrorMessage(error))
        setStatus('error')
      }
    })()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [enabled, reloadNonce])

  const reload = useCallback(() => {
    setReloadNonce((value) => value + 1)
  }, [])

  return {
    status,
    data,
    errorMessage,
    isInitialLoading: status === 'loading' && !hasLoadedRef.current,
    isEmpty: status === 'success' && data !== null && emptyPredicateRef.current(data),
    reload,
  }
}
