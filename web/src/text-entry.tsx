import {useState} from 'react'
import {createShapeId, toRichText, useEditor, useValue} from 'tldraw'

declare global {interface Window {TextEntry?: {postMessage(message: string): void; onmessage?: (event: {data: string}) => void}}}
let nextRequest = 0
let requesting = false
export function requestText(dark: boolean, strokes?: {x:number;y:number}[][]): Promise<string | null> {
 const bridge = window.TextEntry
 if (!bridge) return Promise.reject(Error('Handwriting input is available in the Android app.'))
 if (requesting) return Promise.reject(Error('Text entry is already open.'))
 requesting = true
 const id = ++nextRequest
 return new Promise((resolve,reject) => {
  bridge.onmessage = event => {
   let data
   try { data = JSON.parse(event.data) } catch { return }
   if (data.id !== id) return
   requesting = false; bridge.onmessage = undefined
   if (data.error) reject(Error(data.error))
   else resolve(typeof data.text === 'string' ? data.text.slice(0,20000) : null)
  }
  try {bridge.postMessage(JSON.stringify({id,text:'',dark,strokes}))}
  catch {requesting = false; bridge.onmessage = undefined; reject(Error('Could not open handwriting input.'))}
 })
}
export function TextEntryButton() {
 const editor = useEditor()
 const readonly = useValue('text entry readonly', () => editor.getInstanceState().isReadonly, [editor])
 const [busy,setBusy] = useState(false)
 const [error,setError] = useState('')
 async function insert() {
  if (busy || readonly) return
  setBusy(true); setError('')
  const pageId = editor.getCurrentPageId()
  const center = editor.getViewportPageBounds().center
  try {
   const text = (await requestText(editor.getColorMode() === 'dark'))?.trim()
   if (!text) return
   if (editor.isDisposed || editor.getCurrentPageId() !== pageId || editor.getInstanceState().isReadonly) throw Error('The board changed while writing. Open Write text again on the destination board.')
   const id = createShapeId()
   editor.markHistoryStoppingPoint('insert handwritten text')
   editor.createShape({id,type:'text',x:center.x-180,y:center.y-40,props:{richText:toRichText(text),font:'sans',size:'m',w:360,autoSize:false}})
   editor.setCurrentTool('select'); editor.select(id)
  } catch(e) {setError((e as Error).message)} finally {setBusy(false)}
 }
 return <div className="text-entry-control">
  <button aria-label="Write text" title="Write with your pen and insert editable text" disabled={busy || readonly} onClick={insert}>
   <svg className="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 5h11M9.5 5v14M6 19h7M15 16l5-5 2 2-5 5-3 1z"/></svg><span>Write text</span>
  </button>
  {error && <div className="text-entry-error" role="alert">{error}<button aria-label="Dismiss message" onClick={()=>setError('')}>×</button></div>}
 </div>
}
