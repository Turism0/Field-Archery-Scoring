// Equipment (3.2.24, Pete): your bows and arrows, and the pickers for a round's bow and arrows (New round, Join, Round
// details). As the app's EquipmentScreens.kt and EquipmentPickers.kt.
import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Select, back, go, useData } from './common'
import { allRounds } from '../data/db'
import { getProfile } from '../data/account'
import { deleteArrows, deleteBow, getEquipment, saveArrows, saveBow, type EquipmentStore } from '../data/equipment'
import { BOW_STYLES } from '../domain/round'
import {
  BOW_TYPES, FLETCH_MATERIALS, MAX_COLOURS, MAX_TEXT, NONE, SPINE_KIND_NAMES, arrowDetail, arrowForm, arrowLabel, arrowName, arrowProblems,
  arrowsFromForm, bowDetail, bowForm, bowFromForm, bowLabel, bowName, bowProblems, chooseEquipment, emptyArrowForm, emptyBowForm, lastUsed,
  parseSpine, showArrows, showBows, type ArrowForm, type BowForm, type BowType, type EquipmentChoice, type FletchMaterial,
} from '../domain/equipment'

/** Your bows and arrows, and your rounds (for the last ones used with a bow style). */
export function useKit(): { kit: EquipmentStore; rounds: Awaited<ReturnType<typeof allRounds>> } | undefined {
  return useData(async () => ({ kit: await getEquipment(), rounds: await allRounds() }))
}

/** The choice for a bow style and what's been picked, starting on the last ones used with it (not [skipRound]). */
export function equipmentChoice(k: ReturnType<typeof useKit>, style: string | null | undefined, bowId: string | null, arrowId: string | null,
  skipRound?: string): EquipmentChoice | null {
  if (!k) return null
  const last = lastUsed(k.rounds.filter(r => r.id !== skipRound && (r.groupRole == null || r.groupRole === 'SCORER')), style)
  return chooseEquipment(k.kit.bows, k.kit.arrows, style, { bowId, arrowId, lastBowId: last.bowId, lastArrowId: last.arrowId })
}

/** The bow and arrows for a round: each only with two or more to pick from ([always]: Round details, with even one). */
export function EquipmentPickers({ choice, onBow, onArrows, always = false }: {
  choice: EquipmentChoice | null; onBow: (id: string) => void; onArrows: (id: string) => void; always?: boolean
}) {
  if (!choice) return null
  const bow = choice.bows.find(b => b.id === choice.bowId)
  const arrows = choice.arrows.find(a => a.id === choice.arrowId)
  return <>
    {(showBows(choice) || (always && choice.bows.length > 0)) && <>
      <Select label="Bow" value={choice.bowId ?? undefined} options={choice.bows.map(b => b.id)} none="None"
        optionLabel={id => bowLabel(choice.bows.find(b => b.id === id)!)} onChange={id => onBow(id ?? NONE)} />
      <div class="sub">{bow ? bowDetail(bow) : 'Your bows for this bow style (Equipment on Home)'}</div>
    </>}
    {(showArrows(choice) || (always && choice.arrows.length > 0)) && <>
      <Select label="Arrows" value={choice.arrowId ?? undefined} options={choice.arrows.map(a => a.id)} none="None"
        optionLabel={id => arrowLabel(choice.arrows.find(a => a.id === id)!)} onChange={id => onArrows(id ?? NONE)} />
      <div class="sub">{arrows ? arrowDetail(arrows) || 'Your arrows for this bow' : 'Your arrows for this bow'}</div>
    </>}
  </>
}

const styleName = (code: string) => BOW_STYLES.find(b => b.code === code)?.name ?? code

// 3.2.29 (Pete): no figures here (arrows used with two bows would mix them); Stats → Analysis → By equipment has them.
function Row({ title, lines, onClick }: { title: string; lines: string[]; onClick: () => void }) {
  return (
    <button class="tile kit" onClick={onClick}>
      <span class="grow">
        <b>{title}</b>
        {lines.filter(Boolean).map(l => <span class="sub">{l}</span>)}
      </span>
    </button>
  )
}

export function EquipmentScreen() {
  const data = useData(async () => ({ kit: await getEquipment() }))
  if (!data) return <Bar title="Equipment" />
  const bows = data.kit.bows.filter(b => !b.deleted).sort((a, b) => bowLabel(a).localeCompare(bowLabel(b)))
  const arrows = data.kit.arrows.filter(a => !a.deleted).sort((a, b) => arrowLabel(a).localeCompare(arrowLabel(b)))
  return (
    <>
      <Bar title="Equipment" />
      <main class="form">
        <div class="sub">New round offers the bows for the bow style you pick, and the arrows for that bow, starting on the ones
          you used last time. With only one, it's used without asking. Only you see your equipment. How you shoot with each bow and
          set of arrows is in Stats → Analysis.</div>
        <Card title="Bows">
          {!bows.length && <div>No bows yet.</div>}
          {bows.map(b => <Row title={bowName(b)} lines={[bowDetail(b), b.bowStyles.length ? b.bowStyles.map(styleName).join(', ') : 'No bow style: not offered for rounds']}
            onClick={() => go(`/bow/${b.id}`)} />)}
          <button class="outlined" style="align-self:flex-start" onClick={() => go('/bow/new')}>＋ Add a bow</button>
        </Card>
        <Card title="Arrows">
          {!arrows.length && <div>No arrows yet.</div>}
          {arrows.map(a => {
            const used = a.bowIds.map(id => data.kit.bows.find(b => b.id === id)).filter(b => !!b).map(b => bowName(b!) + (b!.deleted ? ' (deleted)' : '')).join(', ')
            return <Row title={arrowName(a)} lines={[arrowDetail(a), used ? `With ${used}` : 'No bow: not offered for rounds']} onClick={() => go(`/arrows/${a.id}`)} />
          })}
          <button class="outlined" style="align-self:flex-start" onClick={() => go('/arrows/new')}>＋ Add arrows</button>
        </Card>
      </main>
    </>
  )
}

function Text({ label, value, onChange, number = false, help, max = MAX_TEXT, bad = false, onBlur }: {
  label: string; value: string; onChange: (v: string) => void; number?: boolean; help?: string; max?: number; bad?: boolean; onBlur?: () => void
}) {
  return (
    <Field label={label} help={help}>
      <input value={value} maxLength={max} inputMode={number ? 'decimal' : undefined} autocapitalize={number ? undefined : 'words'}
        aria-invalid={bad} onInput={e => onChange((e.target as HTMLInputElement).value.slice(0, max))} onBlur={onBlur} />
    </Field>
  )
}

function Problems({ list }: { list: string[] }) {
  return <>{list.map(p => <div class="error">{p}</div>)}</>
}

function ConfirmDelete({ open, what, onDelete, onClose }: { open: boolean; what: string; onDelete: () => void; onClose: () => void }) {
  return (
    <Dialog open={open} title={`Delete ${what}?`} onClose={onClose}
      actions={<><button onClick={onClose}>Cancel</button><button class="danger" onClick={onDelete}>Delete</button></>}>
      It's no longer offered for new rounds. Rounds you shot with it keep its name.
    </Dialog>
  )
}

export function BowEdit({ id }: { id: string }) {
  const isNew = id === 'new'
  const [bowId] = useState(() => (isNew ? crypto.randomUUID() : id))
  const [f, setF] = useState<BowForm | null>(null)
  const [tried, setTried] = useState(false)
  const [confirm, setConfirm] = useState(false)
  useEffect(() => {
    getEquipment().then(async e => {
      const b = e.bows.find(x => x.id === id)
      if (b) setF(bowForm(b))
      else {
        // A new bow starts in your main bow style.
        const main = (await getProfile())?.bowStyle
        setF({ ...emptyBowForm(), bowStyles: main ? [main] : [] })
      }
    })
  }, [id])
  if (!f) return <Bar title={isNew ? 'Add a bow' : 'Bow'} />
  const set = (c: Partial<BowForm>) => setF({ ...f, ...c })
  const save = async () => {
    setTried(true)
    const b = bowFromForm(f, bowId, '')
    if (b) { await saveBow(b); back() }
  }
  return (
    <>
      <Bar title={isNew ? 'Add a bow' : 'Bow'}>
        {!isNew && <button onClick={() => setConfirm(true)}>Delete</button>}
        <button onClick={save}>Save</button>
      </Bar>
      <main class="form">
        <Card title="The bow">
          <Select<BowType> label="Type" value={f.type ?? undefined} options={BOW_TYPES.map(t => t.code)} optionLabel={c => BOW_TYPES.find(t => t.code === c)!.name}
            onChange={t => set({ type: t ?? null })} />
          <Text label="Make" value={f.make} onChange={v => set({ make: v })} />
          <Text label="Model (optional)" value={f.model} onChange={v => set({ model: v })} />
          <Text label="Poundage (lb, optional)" value={f.poundage} onChange={v => set({ poundage: v })} number help="At your draw length, e.g. 45 or 47.5" />
        </Card>
        <Card title="Bow styles" sub="The styles you shoot it in. New round offers it for these (none: kept here, not offered).">
          <div class="chips">
            {BOW_STYLES.map(s => (
              <button type="button" class={`chip ${f.bowStyles.includes(s.code) ? 'on' : ''}`} aria-pressed={f.bowStyles.includes(s.code)}
                onClick={() => set({ bowStyles: f.bowStyles.includes(s.code) ? f.bowStyles.filter(x => x !== s.code) : [...f.bowStyles, s.code] })}>
                {s.name} ({s.code})
              </button>
            ))}
          </div>
          {!f.bowStyles.length && <div class="error">⚠ No bow style picked: this bow won't be available when you start a round until you pick one.</div>}
        </Card>
        {tried && <Problems list={bowProblems(f)} />}
      </main>
      <ConfirmDelete open={confirm} what="this bow" onClose={() => setConfirm(false)} onDelete={async () => { setConfirm(false); await deleteBow(bowId); back() }} />
    </>
  )
}

export function ArrowsEdit({ id }: { id: string }) {
  const isNew = id === 'new'
  const [arrowId] = useState(() => (isNew ? crypto.randomUUID() : id))
  const [f, setF] = useState<ArrowForm | null>(null)
  const [tried, setTried] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const kit = useData(getEquipment)
  useEffect(() => {
    getEquipment().then(e => {
      const a = e.arrows.find(x => x.id === id)
      if (a) setF(arrowForm(a))
      else {
        // With only one bow, new arrows are for it.
        const live = e.bows.filter(b => !b.deleted)
        setF({ ...emptyArrowForm(), bowIds: live.length === 1 ? [live[0].id] : [] })
      }
    })
  }, [id])
  if (!f || !kit) return <Bar title={isNew ? 'Add arrows' : 'Arrows'} />
  const set = (c: Partial<ArrowForm>) => setF({ ...f, ...c })
  // The spine is tidied into the usual way of writing it when you leave the box.
  const spine = parseSpine(f.spine)
  const badSpine = !!f.spine.trim() && !spine
  const tidy = () => { if (spine) set({ spine: spine.text }) }
  const save = async () => {
    setTried(true)
    const fixed = spine ? { ...f, spine: spine.text } : f
    const a = arrowsFromForm(fixed, arrowId, '')
    if (a) { await saveArrows(a); back() }
  }
  const bows = kit.bows.filter(b => !b.deleted || f.bowIds.includes(b.id)).sort((a, b) => bowLabel(a).localeCompare(bowLabel(b)))
  return (
    <>
      <Bar title={isNew ? 'Add arrows' : 'Arrows'}>
        {!isNew && <button onClick={() => setConfirm(true)}>Delete</button>}
        <button onClick={save}>Save</button>
      </Bar>
      <main class="form">
        <Card title="The arrows">
          <Text label="Make" value={f.make} onChange={v => set({ make: v })} />
          <Text label="Model (optional)" value={f.model} onChange={v => set({ model: v })} />
          <div class="two">
            <Text label="Length (in)" value={f.length} onChange={v => set({ length: v })} number />
            <Text label="Arrow weight (gr)" value={f.arrowWeight} onChange={v => set({ arrowWeight: v })} number />
          </div>
          <div class="two">
            <Text label="Spine" value={f.spine} onChange={v => set({ spine: v })} max={12} bad={badSpine} onBlur={tidy}
              help={badSpine ? 'e.g. 500, 45-50 or 2213' : spine ? `${SPINE_KIND_NAMES[spine.kind]}: ${spine.text}` : '500 · 45-50 · 2213'} />
            <Text label="Point (gr)" value={f.pointWeight} onChange={v => set({ pointWeight: v })} number />
          </div>
        </Card>
        <Card title="Fletches">
          <div class="two">
            <Text label="How many" value={f.fletchCount} onChange={v => set({ fletchCount: v })} number max={2} />
            <Text label="Length (in)" value={f.fletchLength} onChange={v => set({ fletchLength: v })} number max={6} />
          </div>
          <Select<FletchMaterial> label="Material" value={f.fletchMaterial ?? undefined} options={FLETCH_MATERIALS.map(m => m.code)} none="Not given"
            optionLabel={c => FLETCH_MATERIALS.find(m => m.code === c)!.name} onChange={m => set({ fletchMaterial: m ?? null })} />
          <Text label="Colours" value={f.fletchColours} onChange={v => set({ fletchColours: v })} max={MAX_COLOURS} help="e.g. barred grey, white cock feather" />
        </Card>
        <Card title="Used with" sub="The bows you shoot them from. New round offers them for these (none: kept here, not offered).">
          {!bows.length && <div>No bows yet.</div>}
          {bows.map(b => (
            <label class="check">
              <input type="checkbox" checked={f.bowIds.includes(b.id)}
                onChange={() => set({ bowIds: f.bowIds.includes(b.id) ? f.bowIds.filter(x => x !== b.id) : [...f.bowIds, b.id] })} />
              <span>{bowLabel(b)}{b.deleted ? ' (deleted)' : ''}<span class="sub"> · {bowDetail(b)}</span></span>
            </label>
          ))}
          {!f.bowIds.length && <div class="error">⚠ {bows.length
            ? "No bow picked: these arrows won't be available when you start a round until you pick one."
            : "No bow yet: these arrows won't be available when you start a round until you add a bow and pick it here."}</div>}
        </Card>
        {tried && <Problems list={arrowProblems(f.spine && spine ? { ...f, spine: spine.text } : f)} />}
      </main>
      <ConfirmDelete open={confirm} what="these arrows" onClose={() => setConfirm(false)} onDelete={async () => { setConfirm(false); await deleteArrows(arrowId); back() }} />
    </>
  )
}
