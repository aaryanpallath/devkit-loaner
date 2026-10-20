import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'

const device = {
  id: 'd1', assetTag: 'DK-001', name: 'Dev kit 1', kind: 'DEV_KIT', location: 'Redmond B4',
  reservations: [{ id: 'r1', borrower: 'a@example.com', start: '', end: '', state: 'BOOKED' }],
}

function respond(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => {
  fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url.startsWith('/api/devices') && (!init || !init.method)) return respond(200, [device])
    if (url === '/api/reservations/overdue') return respond(200, [])
    if (init?.method === 'POST') return respond(409, { detail: 'device is already reserved for part of that time' })
    return respond(404, {})
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

test('lists devices with their booking count', async () => {
  render(<App />)
  expect(await screen.findByText('Dev kit 1')).toBeInTheDocument()
  expect(screen.getByText('1 upcoming booking')).toBeInTheDocument()
  expect(screen.getByText('Nothing overdue.')).toBeInTheDocument()
})

test('a conflicting booking shows a clear message', async () => {
  render(<App />)
  await screen.findByText('Dev kit 1')
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'me@example.com' } })
  fireEvent.change(screen.getByLabelText('Start'), { target: { value: '2027-06-16T09:00' } })
  fireEvent.change(screen.getByLabelText('End'), { target: { value: '2027-06-17T09:00' } })
  fireEvent.click(screen.getByRole('button', { name: 'Book' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Already booked'))
})

test('the availability filter is sent to the API', async () => {
  render(<App />)
  await screen.findByText('Dev kit 1')
  fireEvent.change(screen.getByLabelText('Free from'), { target: { value: '2027-06-20T09:00' } })
  fireEvent.change(screen.getByLabelText('until'), { target: { value: '2027-06-21T09:00' } })
  await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).includes('from=2027-06-20'))).toBe(true))
})
