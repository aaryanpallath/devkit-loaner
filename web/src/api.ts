export type Kind = 'DEV_KIT' | 'CONSOLE' | 'HANDHELD' | 'ACCESSORY'
export type State = 'BOOKED' | 'CHECKED_OUT' | 'RETURNED' | 'CANCELLED'

export interface Reservation {
  id: string
  borrower: string
  start: string
  end: string
  state: State
}

/** A booking as the API shows it to the caller: other people's bookings come back as anonymous time slots. */
export interface Slot {
  id: string | null
  start: string
  end: string
  state: State
  mine: boolean
  borrower: string | null
}

export interface Device {
  id: string
  assetTag: string
  name: string
  kind: Kind
  location: string
  reservations: Slot[]
}

export interface Overdue {
  deviceId: string
  assetTag: string
  deviceName: string
  reservationId: string
  borrower: string
  due: string
  hoursOverdue: number
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const TOKEN_KEY = 'loaner.token'

/** In production this token comes from the company SSO sign-in; in development it is pasted from scripts/dev-token.sh. */
export const auth = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (t: string) => sessionStorage.setItem(TOKEN_KEY, t.trim()),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = auth.get()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(path, { ...init, headers })
  const body = res.status === 204 ? null : await res.json()
  if (!res.ok) throw new ApiError(res.status, body?.detail ?? `Request failed (${res.status})`)
  return body as T
}

export const api = {
  devices(kind?: Kind, from?: string, to?: string) {
    const q = new URLSearchParams()
    if (kind) q.set('kind', kind)
    if (from && to) {
      q.set('from', from)
      q.set('to', to)
    }
    return request<Device[]>(`/api/devices?${q}`)
  },
  reserve(deviceId: string, start: string, end: string) {
    return request<Reservation>(`/api/devices/${deviceId}/reservations`, {
      method: 'POST',
      body: JSON.stringify({ start, end }),
    })
  },
  overdue() {
    return request<Overdue[]>('/api/reservations/overdue')
  },
}
