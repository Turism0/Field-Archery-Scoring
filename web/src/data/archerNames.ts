// Names you've given other archers (3.2.23, Pete): kv 'archerNames' (account id → name), in this browser only and never
// sent anywhere. Cleared at sign-out with the account's other things, and put away / brought back with them
// (data/account.ts).
import { getKv, setKv } from './db'
import { withName, type ArcherNameMap } from '../domain/archerNames'

export const getArcherNames = async (): Promise<ArcherNameMap> => (await getKv<ArcherNameMap>('archerNames')) ?? {}

/** Gives [archerId] the name [name]; blank goes back to their username. */
export async function setArcherName(archerId: string, name: string | null) {
  const next = withName(await getArcherNames(), archerId, name)
  await setKv('archerNames', Object.keys(next).length ? next : null)
}
