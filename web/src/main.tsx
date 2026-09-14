import React, { useMemo, useState, useCallback, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { Tldraw, defaultShapeUtils, defaultBindingUtils, type Editor, type TLAssetStore } from 'tldraw'
import { useSync } from '@tldraw/sync'
import { commentSchemaRecords } from '@tldraw/tlschema'
import { getAssetUrlsByImport } from '@tldraw/assets/imports.vite'
import 'tldraw/tldraw.css'
import './style.css'
import {UiIcon, BoardContext, boardComponents} from './board-controls'
import appIcon from './icon.svg'
import { attachInk } from './ink'
import { loadHistory, saveHistory, sameBoard, type RecentBoard } from './history'

const assetUrls = getAssetUrlsByImport()
type Connection = { origin: string; board: string; token: string; metadata: any }
declare global { interface Window { editor?: Editor } }
function parseShare(value: string) {
 const url = new URL(value.trim())
 const match = url.pathname.match(/^\/join\/([a-zA-Z0-9_-]+)\/?$/)
 if (!['http:', 'https:'].includes(url.protocol) || !match || !url.hash.slice(1) || url.username || url.password) throw Error('Paste a complete Share link, including the part after #.')
 return {origin: url.origin, board: match[1], token: decodeURIComponent(url.hash.slice(1))}
}
function Board({connection, close}: {connection: Connection; close: () => void}) {
 const assets = useMemo<TLAssetStore>(() => {
  const endpoint = (hash: string) => `${connection.origin}/api/boards/${encodeURIComponent(connection.board)}/assets/${encodeURIComponent(hash)}?token=${encodeURIComponent(connection.token)}`
  return {
   async upload(_asset, file) {
    const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))
    const hash = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
    const response = await fetch(endpoint(hash), {method: 'PUT', headers: {Authorization: `Bearer ${connection.token}`}, body: file})
    if (!response.ok) throw Error(`Could not upload the file (${response.status}).`)
    return {src: `asset:${hash}`}
   },
   resolve(asset) {
    const src = asset.props.src
    return typeof src === 'string' && src.startsWith('asset:') ? endpoint(src.slice(6)) : src ?? null
   }
  }
 }, [connection])
 const records = useMemo(() => Object.keys(connection.metadata.schema.serialized.sequences).some(k => k.includes('comment')) ? commentSchemaRecords : undefined, [connection])
 const store = useSync({uri: `${connection.origin}/connect/${encodeURIComponent(connection.board)}?token=${encodeURIComponent(connection.token)}`,assets, shapeUtils: defaultShapeUtils, bindingUtils: defaultBindingUtils, records})
 const mount = useCallback((editor: Editor) => {window.editor = editor; editor.user.updateUserPreferences({locale: 'en'}); editor.zoomToFit(); const detach = attachInk(editor); return () => {detach(); delete window.editor}}, [])
 const status = store.status === 'synced-remote' ? store.connectionStatus === 'online' ? 'Connected' : 'Reconnecting… · keep the app open' : store.status === 'error' ? 'Could not sync. Check Share and your network.' : 'Connecting…'
 return <BoardContext.Provider value={{name: connection.metadata.displayName || 'Board', status, openBoards: close}}><main className="canvas" data-board={connection.board}><Tldraw licenseKey={import.meta.env.VITE_TLDRAW_LICENSE_KEY} store={store} assetUrls={assetUrls} components={boardComponents} onMount={mount}/></main></BoardContext.Provider>
}
function App() {
 const [connection, setConnection] = useState<Connection | null>(null)
 const [hubOpen, setHubOpen] = useState(false)
 const [hubTheme, setHubTheme] = useState('light')
 const [recent, setRecent] = useState<RecentBoard[]>([])
 const [historyError, setHistoryError] = useState('')
 const [url, setUrl] = useState('')
 const [error, setError] = useState('')
 const [busy, setBusy] = useState(false)
 useEffect(() => {loadHistory().then(setRecent).catch(e => setHistoryError(e.message))}, [])
 async function connect(value: string | RecentBoard) {
  if (busy) return
  setBusy(true); setError('')
  try {
   const c = typeof value === 'string' ? parseShare(value) : value
   const response = await fetch(`${c.origin}/api/boards/${encodeURIComponent(c.board)}`, {headers: {Authorization: `Bearer ${c.token}`}, signal: AbortSignal.timeout(12000)})
   if (!response.ok) throw Error(response.status === 401 || response.status === 403 ? 'This invitation is invalid or revoked. Paste a new Share link to update this board.' : `Share returned ${response.status}.`)
   const metadata = await response.json()
   if (!metadata.schema?.serialized) throw Error('The server did not return a compatible schema.')
   if (Object.values(metadata.schema.scriptTypes || {}).some((types: any) => types.length)) throw Error('This board uses custom types, which are not supported yet.')
   const updated = [{origin:c.origin,board:c.board,token:c.token,name:metadata.displayName || 'Board',visited:Date.now()}, ...recent.filter(r => !sameBoard(r,c))].slice(0,12)
   try {await saveHistory(updated); setRecent(updated); setHistoryError('')} catch(e) {setHistoryError((e as Error).message)}
   setConnection({...c, metadata}); setUrl(''); setHubOpen(false)
  } catch (e) {setError(e instanceof TypeError ? 'Could not connect. Check that both devices are on the same Wi-Fi and Share is enabled.' : (e as Error).message)} finally {setBusy(false)}
 }
 async function forget(item: RecentBoard) {
  const updated = recent.filter(r => !sameBoard(r,item))
  try {await saveHistory(updated); setRecent(updated); setHistoryError('')} catch(e) {setHistoryError((e as Error).message)}
 }
 return <>
  {connection && <Board key={`${connection.origin}/${connection.board}/${connection.token}`} connection={connection} close={() => {setHubTheme(window.editor?.getColorMode() || 'light');setHubOpen(true)}}/>}
  {(!connection || hubOpen) && <section className={`join ${connection ? 'hub-overlay' : ''}`} role={connection ? 'dialog' : undefined} data-theme={hubTheme} aria-label="Boards" aria-modal={connection ? true : undefined} data-ink-block="true" onKeyDown={e => {if(e.key === 'Escape' && !busy) setHubOpen(false)}}><div className="hub-card">
   <div className="hub-heading"><div className="brand"><img src={appIcon} alt=""/><div><p className="eyebrow">INK SHARE</p><h1>Boards</h1></div></div>{connection && <button className="secondary close-hub" aria-label="Back to canvas" title="Back to canvas" disabled={busy} onClick={() => setHubOpen(false)}><UiIcon name="close"/></button>}</div>
   {recent.length > 0 && <div className="recent-list"><h2>Recent boards</h2>{recent.map(item => <div className="recent-row" data-board={item.board} key={`${item.origin}/${item.board}`}><button disabled={busy} onClick={() => connect(item)}><span className="recent-title"><UiIcon name="boards"/><strong>{item.name}</strong>{connection && sameBoard(item,connection) && <span className="current-badge">Open</span>}</span><small>{item.origin}</small></button><button className="forget" disabled={busy} onClick={() => forget(item)} aria-label={`Forget ${item.name}`} title="Remove from recents"><UiIcon name="close"/></button></div>)}</div>}
   <form onSubmit={e => {e.preventDefault(); void connect(url)}}><label htmlFor="share">Add a Share link</label><input id="share" type="password" value={url} onChange={e => setUrl(e.target.value)} placeholder="http://…/join/…#…" autoComplete="off" spellCheck={false}/><button disabled={busy || !url}>{busy ? 'Connecting…' : 'Open board'}</button></form>
   <p role="alert">{error || historyError}</p><small className="hub-footer">Connect both devices to the same network. Recent boards are saved only on this tablet.</small>
  </div></section>}
 </>
}
createRoot(document.getElementById('root')!).render(<App/> )
