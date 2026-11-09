import { render } from 'preact'
import './styles.css'
import { Toasts, useDesktop, useRoute } from './ui/common'
import { Shell } from './ui/Shell'
import { Dashboard, PhoneOnly } from './ui/Dashboard'
import { PaperEntry } from './ui/PaperEntry'
import { Home } from './ui/Home'
import { AccountScreen, ProfileScreen } from './ui/Account'
import { History } from './ui/History'
import { NewRound } from './ui/NewRound'
import { Scoring } from './ui/Scoring'
import { Scorecard } from './ui/Scorecard'
import { Join, Lobby } from './ui/Live'
import { Stats } from './ui/Stats'
import { HandicapCalculator } from './ui/Handicap'
import { FriendRounds, FriendScorecard, Friends, Offer } from './ui/Friends'
import { LocationMap, LocationPageScreen } from './ui/Locations'
import { EditRound } from './ui/EditRound'
import { Admin, AdminDuplicates, AdminHandicap, AdminLocations } from './ui/Admin'
import { startAutoSync } from './data/rounds'
import { askToPersist } from './data/db'

function Screen({ parts, q }: { parts: string[]; q: URLSearchParams }) {
  const desktop = useDesktop()
  switch (parts[0]) {
    case 'account': return <><AccountScreen /></>
    case 'profile': return <><ProfileScreen /></>
    case 'history': return <><History /></>
    case 'new': case 'add': return desktop ? <PaperEntry key="new" /> : <NewRound />
    case 'grid': return desktop ? <PaperEntry key={parts[1]} id={parts[1]} /> : <Scoring id={parts[1]} />
    case 'score': return desktop ? <PaperEntry key={parts[1]} id={parts[1]} /> : <Scoring id={parts[1]} target={Number(q.get('t')) || undefined} />
    case 'card': return <><Scorecard id={parts[1]} /></>
    case 'join': return desktop ? <PhoneOnly /> : <Join code={q.get('code') ?? ''} />
    case 'lobby': return desktop ? <PhoneOnly /> : <Lobby groupId={parts[1]} />
    case 'stats': return <><Stats /></>
    case 'handicap': return <><HandicapCalculator /></>
    case 'friends': return <><Friends /></>
    case 'friend': return <><FriendRounds id={parts[1]} name={q.get('name') ?? ''} /></>
    case 'friendcard': return <><FriendScorecard id={parts[1]} roundId={parts[2]} name={q.get('name') ?? ''} /></>
    case 'offer': return <><Offer roundId={parts[1]} /></>
    case 'map': return <><LocationMap pick={q.get('pick') ?? undefined} /></>
    case 'location': return <><LocationPageScreen id={parts[1]} /></>
    case 'edit': return <><EditRound id={parts[1]} /></>
    case 'admin': return <>{parts[1] === 'duplicates' ? <AdminDuplicates /> : parts[1] === 'locations' ? <AdminLocations /> : parts[1] === 'handicap' ? <AdminHandicap /> : <Admin />}</>
    default: return desktop ? <Dashboard /> : <Home />
  }
}

function App() {
  const route = useRoute()
  const desktop = useDesktop()
  const [path, query] = route.split('?')
  const parts = path.split('/').filter(Boolean)
  const screen = <Screen parts={parts} q={new URLSearchParams(query ?? '')} />
  return <>{desktop ? <Shell section={parts[0] ?? ''}>{screen}</Shell> : screen}<Toasts /></>
}

render(<App />, document.getElementById('app')!)
startAutoSync()
askToPersist()
