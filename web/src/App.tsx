import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Device, type Kind, type Overdue } from './api'
import './index.css'

const KINDS: Kind[] = ['DEV_KIT', 'CONSOLE', 'HANDHELD', 'ACCESSORY']
const label = (k: string) => k.replace('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())
const toIso = (local: string) => (local ? new Date(local).toISOString() : '')

export default function App() {
  const [kind, setKind] = useState<Kind | ''>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [devices, setDevices] = useState<Device[]>([])
  const [overdue, setOverdue] = useState<Overdue[]>([])
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setError('')
      setDevices(await api.devices(kind || undefined, toIso(from), toIso(to)))
      setOverdue(await api.overdue())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [kind, from, to])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <main>
      <header>
        <h1>Dev Kit Loaner</h1>
        <p>Reserve dev kits and test hardware. Bookings never overlap.</p>
      </header>

      <section className="filters" aria-label="Filters">
        <label>
          Type
          <select value={kind} onChange={(e) => setKind(e.target.value as Kind | '')}>
            <option value="">All</option>
            {KINDS.map((k) => (
              <option key={k} value={k}>{label(k)}</option>
            ))}
          </select>
        </label>
        <label>
          Free from
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          until
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </section>

      {error && <p role="alert" className="error">{error}</p>}

      <section aria-label="Devices" className="grid">
        {devices.length === 0 && <p>No devices match.</p>}
        {devices.map((d) => (
          <DeviceCard key={d.id} device={d} onBooked={load} />
        ))}
      </section>

      <section aria-label="Overdue loans">
        <h2>Overdue loans</h2>
        {overdue.length === 0 ? (
          <p>Nothing overdue.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Device</th><th>Borrower</th><th>Due</th><th>Hours overdue</th></tr>
            </thead>
            <tbody>
              {overdue.map((o) => (
                <tr key={o.reservationId}>
                  <td>{o.assetTag} · {o.deviceName}</td>
                  <td>{o.borrower}</td>
                  <td>{new Date(o.due).toLocaleString()}</td>
                  <td>{o.hoursOverdue}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  )
}

function DeviceCard({ device, onBooked }: { device: Device; onBooked: () => void }) {
  const [borrower, setBorrower] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [message, setMessage] = useState('')
  const active = device.reservations.filter((r) => r.state === 'BOOKED' || r.state === 'CHECKED_OUT')

  async function book(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api.reserve(device.id, borrower, toIso(start), toIso(end))
      setMessage('Booked.')
      onBooked()
    } catch (err) {
      const conflict = err instanceof ApiError && err.status === 409
      setMessage(conflict ? 'Already booked for part of that time. Pick another slot.' : (err as Error).message)
    }
  }

  return (
    <article className="card">
      <h3>{device.name}</h3>
      <p className="meta">{device.assetTag} · {label(device.kind)} · {device.location}</p>
      <p className="meta">{active.length} upcoming booking{active.length === 1 ? '' : 's'}</p>
      <form onSubmit={book} aria-label={`Book ${device.assetTag}`}>
        <input aria-label="Email" type="email" placeholder="you@example.com" required value={borrower}
          onChange={(e) => setBorrower(e.target.value)} />
        <input aria-label="Start" type="datetime-local" required value={start} onChange={(e) => setStart(e.target.value)} />
        <input aria-label="End" type="datetime-local" required value={end} onChange={(e) => setEnd(e.target.value)} />
        <button type="submit">Book</button>
      </form>
      {message && <p role="status">{message}</p>}
    </article>
  )
}
