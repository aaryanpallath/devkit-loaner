export type Kind = 'DEV_KIT' | 'CONSOLE' | 'HANDHELD' | 'ACCESSORY'
export type State = 'BOOKED' | 'CHECKED_OUT' | 'RETURNED' | 'CANCELLED'

export interface Reservation {
  id: string
  borrower: string
  start: string
  end: string
  state: State
}

export interface Device {
  id: string
  assetTag: string
  name: string
  kind: Kind
  location: string
  reservations: Reservation[]
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init })
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
  reserve(deviceId: string, borrower: string, start: string, end: string) {
    return request<Reservation>(`/api/devices/${deviceId}/reservations`, {
      method: 'POST',
      body: JSON.stringify({ borrower, start, end }),
    })
  },
  overdue() {
    return request<Overdue[]>('/api/reservations/overdue')
  },
}
