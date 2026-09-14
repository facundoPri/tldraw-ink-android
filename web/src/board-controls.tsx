import {createContext, useContext} from 'react'
import {useEditor, useValue} from 'tldraw'

export function UiIcon({name}: {name: 'boards' | 'people' | 'close' | 'arrow'}) {
 const paths = {boards: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z', people:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0', close:'M6 6l12 12M6 18L18 6', arrow:'M5 12h14M13 6l6 6-6 6'}
 return <svg className="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]}/></svg>
}
export const BoardContext = createContext({name: '', status: '', openBoards: () => {}})
function BoardControls() {
 const {name, status, openBoards} = useContext(BoardContext)
 const editor = useEditor()
 const peers = useValue('connected collaborators', () => editor.getCollaborators(), [editor])
 return <div className="board-controls" data-ink-block="true">
  <strong title={name}>{name}</strong>
  <span className={`connection-status ${status === 'Connected' ? 'online' : ''}`} role="status" title={status}><i/>{status === 'Connected' ? 'Connected' : status}</span>
  <details className="people-menu"><summary aria-label="View participants" title="Participants"><UiIcon name="people"/><span>{peers.length + 1}</span></summary><div className="participants"><b>On this board · {peers.length + 1}</b><p>You · tablet</p>{peers.map(peer => <button key={peer.id} onClick={() => editor.zoomToUser(peer.userId)}><i style={{background: peer.color}}/>{peer.userName?.trim() || 'Unnamed'}</button>)}{!peers.length && <small>No other participants are currently visible.</small>}</div></details>
  <button className="boards-button" aria-label="Boards" onClick={openBoards}><UiIcon name="boards"/><span>Boards</span></button>
 </div>
}
export const boardComponents = {SharePanel: BoardControls}
