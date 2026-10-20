import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import App from './App'

const device = {
  id: 'd1', assetTag: 'DK-001', name: 'Dev kit 1', kind: 'DEV_KIT', location: 'Redmond B4',
  reservations: [
    { id: null, borrower: null, start: '', end: '', state: 'BOOKED', mine: false },
    { id: 'r2', borrower: 'me@example.com', start: '', end: '', state: 'BOOKED', mine: true },
  ],
}

function respond(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => {
  fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url.startsWith('/api/devices') && (!init || !init.method)) return respond(200, [device])
    if (url === '/api/reservations/overdue') return respond(403, { detail: 'Forbidden' })
    if (init?.method === 'POST') return respond(409, { detail: 'device is already reserved for part of that time' })
    return respond(404, {})
  })
  vi.stubGlobal('fetch', fetchMock)
  sessionStorage.setItem('loaner.token', 'test-token')
})
afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

test('lists devices, marks my bookings, and sends the token', async () => {
  render(<App />)
  expect(await screen.findByText('Dev kit 1')).toBeInTheDocument()
  expect(screen.getByText(/2 upcoming bookings \(1 yours\)/)).toBeInTheDocument()
  const [, init] = fetchMock.mock.calls[0]
  expect(init.headers.Authorization).toBe('Bearer test-token')
})

test('borrowers do not see the overdue panel', async () => {
  render(<App />)
  await screen.findByText('Dev kit 1')
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Overdue loans' })).not.toBeInTheDocument())
})

test('without a token the sign-in screen is shown', async () => {
  sessionStorage.clear()
  render(<App />)
  expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
  expect(fetchMock).not.toHaveBeenCalled()
})

test('a conflicting booking shows a clear message', async () => {
  render(<App />)
  await screen.findByText('Dev kit 1')
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
