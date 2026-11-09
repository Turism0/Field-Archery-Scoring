import { Bar, go, ukDate, useData } from './common'
import { finished } from '../data/rounds'
import { ROUND_TYPE_NAMES, classLine, roundTotal, targetsLine } from '../domain/round'

/** Finished rounds, newest first; tap for the scorecard. */
export function History() {
  const rounds = useData(finished)
  return (
    <>
      <Bar title="Score history" />
      <main>
        {rounds && !rounds.length && <p class="sub">No finished rounds yet.</p>}
        {rounds?.map(r => (
          <button class="tile" onClick={() => go(`/card/${r.id}`)}>
            <span class="grow">
              <b>{roundTotal(r)} <span class="sub">{r.type === 'WOODSMAN' ? ROUND_TYPE_NAMES.WOODSMAN : ''}</span></b>
              <span class="sub">{ukDate(r.shootDate)} · {classLine(r)} · {targetsLine(r)}{r.locationName ? ` · ${r.locationName}` : ''}</span>
              <span class="sub">{r.pending ? 'Waiting to back up' : r.failed ? "Couldn't back up" : r.groupId ? 'Group' : ''}</span>
            </span>
          </button>
        ))}
      </main>
    </>
  )
}
