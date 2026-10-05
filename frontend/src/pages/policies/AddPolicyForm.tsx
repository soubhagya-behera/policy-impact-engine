import { useState } from 'react'
import type { FormEvent } from 'react'
import { isApiError, toErrorMessage } from '../../api/errors'
import { createPolicy } from '../../api/policies'
import { Button } from '../../components/ui/Button'
import { Field } from '../../components/ui/Field'

/**
 * Add-policy form.
 *
 * Posts to `POST /api/v1/policies` — the real endpoint, with the backend's own
 * constraints (name <= 255, url <= 2048) enforced here for immediate feedback.
 * On success the parent refreshes the list; the form never invents a policy.
 */
export function AddPolicyForm({ onCreated }: { onCreated(): void }) {
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFormError(null)
    setFieldErrors({})
    setIsSubmitting(true)

    try {
      await createPolicy({ name: name.trim(), url: url.trim() })
      setName('')
      setUrl('')
      onCreated()
    } catch (error) {
      if (isApiError(error)) setFieldErrors(error.fieldErrors)
      setFormError(toErrorMessage(error))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <form onSubmit={(event) => void onSubmit(event)} noValidate>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
        <Field
          label="Policy name"
          name="name"
          required
          maxLength={255}
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Acceptable Use Policy"
          errorMessage={fieldErrors['name']}
        />
        <Field
          label="Policy URL"
          name="url"
          type="url"
          required
          maxLength={2048}
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com/policy"
          hint="The engine checks this URL for changes and assesses the impact of each new version."
          errorMessage={fieldErrors['url']}
        />
      </div>

      {formError ? (
        <div
          role="alert"
          className="mt-6 border border-accent-soft/40 bg-surface px-4 py-3 font-body text-base text-accent-soft"
        >
          {formError}
        </div>
      ) : null}

      <div className="mt-6">
        <Button type="submit" isLoading={isSubmitting}>
          {isSubmitting ? 'Adding policy…' : 'Add policy'}
        </Button>
      </div>
    </form>
  )
}
