import { useState } from 'preact/hooks'
import { Dialog, today, toast, useData } from './common'
import { getModel } from '../data/handicap'
import { exportFile, type ExportFormat } from '../domain/export'
import type { Round } from '../domain/round'

import { APP_VERSION } from '../version'

const FORMATS: { code: ExportFormat; name: string; help: string }[] = [
  { code: 'CSV_ROUNDS', name: 'CSV – one row per round', help: 'Date, location, bow, class, total, average, kills and wounds…' },
  { code: 'CSV_TARGETS', name: 'CSV – one row per target', help: "Each target's type, points, arrow and hit, with the round's details on every row." },
  { code: 'JSON', name: 'JSON', help: 'Everything, every animal on every target, in the same layout as the server.' },
]

/** Saves the text as a file through the browser's download. */
function save(name: string, type: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a')
  a.href = url; a.download = name
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** Export rounds (2.7.03, desktop): pick CSV per round, CSV per target or JSON, and the file downloads. */
export function ExportDialog({ open, rounds, what, onClose }: { open: boolean; rounds: Round[]; what: string; onClose: () => void }) {
  const [format, setFormat] = useState<ExportFormat>('CSV_ROUNDS')
  const model = useData(getModel)
  return (
    <Dialog open={open} title="Export scores" onClose={onClose}
      actions={<><button onClick={onClose}>Cancel</button>
        <button disabled={!rounds.length} onClick={() => {
          const f = exportFile(format, rounds, { today: today(), nowUtc: new Date().toISOString(), appVersion: APP_VERSION, model })
          save(f.name, f.type, f.text); toast(`Saved ${f.name}`); onClose()
        }}>Export</button></>}>
      <div class="sub">{what}</div>
      {FORMATS.map(m => (
        <label class="row" style="align-items:flex-start;flex-wrap:nowrap">
          <input type="radio" name="exportFormat" style="width:auto;margin-top:4px" checked={format === m.code} onChange={() => setFormat(m.code)} />
          <span><span>{m.name}</span><div class="sub">{m.help}</div></span>
        </label>
      ))}
    </Dialog>
  )
}
