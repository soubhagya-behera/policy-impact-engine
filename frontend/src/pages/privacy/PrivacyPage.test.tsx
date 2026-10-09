// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PrivacyPreference } from '../../api/types'
import {
  changedPreferences,
  isValidSensitivity,
  PrivacyPage,
  sensitivityChoiceLabel,
} from './PrivacyPage'
import {
  listPrivacyPreferences,
  updatePrivacyPreferences,
} from '../../api/privacy'

vi.mock('../../api/privacy', () => ({
  listPrivacyPreferences: vi.fn(),
  updatePrivacyPreferences: vi.fn(),
}))

const mockedList = vi.mocked(listPrivacyPreferences)
const mockedUpdate = vi.mocked(updatePrivacyPreferences)

const INITIAL_PREFS: PrivacyPreference[] = [
  {
    conceptCode: 'LOCATION',
    label: 'Location',
    effectiveSensitivity: 2,
    explicit: true,
  },
  {
    conceptCode: 'HEALTH',
    label: 'Health',
    effectiveSensitivity: 3,
    explicit: false,
  },
]

const UPDATED_PREFS: PrivacyPreference[] = [
  {
    conceptCode: 'LOCATION',
    label: 'Location',
    effectiveSensitivity: 5,
    explicit: true,
  },
  {
    conceptCode: 'HEALTH',
    label: 'Health',
    effectiveSensitivity: 3,
    explicit: false,
  },
]

beforeEach(() => {
  mockedList.mockReset()
  mockedUpdate.mockReset()
  mockedList.mockResolvedValue(INITIAL_PREFS)
})

afterEach(() => {
  cleanup()
})

function locationSelect(): HTMLElement {
  return screen.getByRole('combobox', { name: 'Sensitivity for Location' })
}

describe('PrivacyPage read mode', () => {
  it('renders values read-only with an Edit action', async () => {
    render(<PrivacyPage />)

    await screen.findByText('Location')
    expect(screen.getByText('Health')).toBeDefined()
    expect(
      screen.getByRole('button', { name: 'Edit' }),
    ).toBeDefined()
    expect(screen.queryByRole('combobox')).toBeNull()
  })
})

describe('PrivacyPage editing', () => {
  it('opens selects seeded with current values and a disabled Save', async () => {
    render(<PrivacyPage />)
    await screen.findByText('Location')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))

    expect(locationSelect()).toHaveProperty('value', '2')
    expect(
      screen.getByRole('combobox', { name: 'Sensitivity for Health' }),
    ).toHaveProperty('value', '3')
    expect(screen.getByRole('button', { name: 'Save' })).toHaveProperty(
      'disabled',
      true,
    )
    expect(
      screen.getByRole('button', { name: 'Cancel' }),
    ).toBeDefined()
  })

  it('saves only changed preferences, then reloads and returns to read mode', async () => {
    mockedUpdate.mockResolvedValueOnce(UPDATED_PREFS)
    render(<PrivacyPage />)
    await screen.findByText('Location')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(locationSelect(), { target: { value: '5' } })

    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toHaveProperty('disabled', false)
    fireEvent.click(save)

    await screen.findByText('Sensitivity by concept')
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(mockedUpdate).toHaveBeenCalledWith({
      preferences: { LOCATION: 5 },
    })
    // Real preferences reloaded from the backend after saving.
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(2))
    await screen.findByText('Location')
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('blocks duplicate saves while a save is in flight', async () => {
    let resolveSave!: (prefs: PrivacyPreference[]) => void
    mockedUpdate.mockImplementationOnce(
      () =>
        new Promise<PrivacyPreference[]>((resolve) => {
          resolveSave = resolve
        }),
    )
    render(<PrivacyPage />)
    await screen.findByText('Location')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(locationSelect(), { target: { value: '5' }})

    const save = screen.getByRole('button', { name: 'Save' })
    fireEvent.click(save)
    fireEvent.click(save)

    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(
      screen.getByRole('button', { name: /saving/i }),
    ).toHaveProperty('disabled', true)

    resolveSave(UPDATED_PREFS)
    await screen.findByText('Location')
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('preserves edits on save failure and retries the same changes', async () => {
    mockedUpdate.mockRejectedValueOnce(new Error('save failed'))
    render(<PrivacyPage />)
    await screen.findByText('Location')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(locationSelect(), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByRole('alert')
    expect(screen.getByText('save failed')).toBeDefined()
    // Still editing with the user's choice intact.
    expect(locationSelect()).toHaveProperty('value', '5')
    expect(screen.getByRole('button', { name: 'Save' })).toHaveProperty(
      'disabled',
      false,
    )

    mockedUpdate.mockResolvedValueOnce(UPDATED_PREFS)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    await screen.findByText('Location')
    expect(mockedUpdate).toHaveBeenCalledTimes(2)
    expect(mockedUpdate).toHaveBeenNthCalledWith(2, {
      preferences: { LOCATION: 5 },
    })
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('discards edits on Cancel', async () => {
    render(<PrivacyPage />)
    await screen.findByText('Location')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(locationSelect(), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('combobox')).toBeNull()
    expect(mockedUpdate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(locationSelect()).toHaveProperty('value', '2')
  })
})

describe('changedPreferences', () => {
  it('returns only values that differ from the loaded surface', () => {
    expect(
      changedPreferences(INITIAL_PREFS, { LOCATION: 2, HEALTH: 3 }),
    ).toEqual({})
    expect(
      changedPreferences(INITIAL_PREFS, { LOCATION: 5, HEALTH: 3 }),
    ).toEqual({ LOCATION: 5 })
  })

  it('excludes invalid values and unknown concept codes', () => {
    expect(
      changedPreferences(INITIAL_PREFS, {
        LOCATION: 6,
        HEALTH: -1,
        UNKNOWN: 4,
      }),
    ).toEqual({})
    expect(
      changedPreferences(INITIAL_PREFS, { LOCATION: 2.5, HEALTH: 0 }),
    ).toEqual({ HEALTH: 0 })
  })
})

describe('isValidSensitivity', () => {
  it('accepts integers 0 through 5 only', () => {
    for (const level of [0, 1, 2, 3, 4, 5]) {
      expect(isValidSensitivity(level)).toBe(true)
    }
    for (const bad of [-1, 6, 2.5, Number.NaN, '3', null, undefined]) {
      expect(isValidSensitivity(bad)).toBe(false)
    }
  })
})

describe('sensitivityChoiceLabel', () => {
  it('describes each level consistently with the read-only scale', () => {
    expect(sensitivityChoiceLabel(0)).toContain('Not sensitive')
    expect(sensitivityChoiceLabel(5)).toContain('High sensitivity')
  })
})
