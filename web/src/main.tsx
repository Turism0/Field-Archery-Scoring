import { render } from 'preact'
import './styles.css'
import { Toasts, useRoute } from './ui/common'
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

function App() {
  const route = useRoute()
  const [path, query] = route.split('?')
  const parts = path.split('/').filter(Boolean)
  const q = new URLSearchParams(query ?? '')
  switch (parts[0]) {
    case 'account': return <><AccountScreen /><Toasts /></>
    case 'profile': return <><ProfileScreen /><Toasts /></>
    case 'history': return <><History /><Toasts /></>
    case 'new': return <><NewRound /><Toasts /></>
    case 'score': return <><Scoring id={parts[1]} target={Number(q.get('t')) || undefined} /><Toasts /></>
    case 'card': return <><Scorecard id={parts[1]} /><Toasts /></>
    case 'join': return <><Join code={q.get('code') ?? ''} /><Toasts /></>
    case 'lobby': return <><Lobby groupId={parts[1]} /><Toasts /></>
    case 'stats': return <><Stats /><Toasts /></>
    case 'handicap': return <><HandicapCalculator /><Toasts /></>
    case 'friends': return <><Friends /><Toasts /></>
    case 'friend': return <><FriendRounds id={parts[1]} name={q.get('name') ?? ''} /><Toasts /></>
    case 'friendcard': return <><FriendScorecard id={parts[1]} roundId={parts[2]} name={q.get('name') ?? ''} /><Toasts /></>
    case 'offer': return <><Offer roundId={parts[1]} /><Toasts /></>
    case 'map': return <><LocationMap pick={q.get('pick') ?? undefined} /><Toasts /></>
    case 'location': return <><LocationPageScreen id={parts[1]} /><Toasts /></>
    case 'edit': return <><EditRound id={parts[1]} /><Toasts /></>
    case 'admin': return <>{parts[1] === 'duplicates' ? <AdminDuplicates /> : parts[1] === 'locations' ? <AdminLocations /> : parts[1] === 'handicap' ? <AdminHandicap /> : <Admin />}<Toasts /></>
    default: return <><Home /><Toasts /></>
  }
}

render(<App />, document.getElementById('app')!)
startAutoSync()
askToPersist()
