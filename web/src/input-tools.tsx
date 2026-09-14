import {atom, GeoShapeGeoStyle, DefaultToolbar, DefaultToolbarContent, TldrawUiMenuToolItem, useEditor, useValue, type Editor, type TLUiOverrides} from 'tldraw'
import {useEffect} from 'react'

type Choice = {tool: string; geo?: string}
type Device = 'pen' | 'touch'
const states = new WeakMap<Editor, ReturnType<typeof makeState>>()
function makeState() {return atom('input tool assignments',{target:'pen' as Device,pen:{tool:'draw'} as Choice,touch:{tool:'select'} as Choice})}
export function inputTools(editor: Editor) {let value=states.get(editor);if(!value){value=makeState();states.set(editor,value)}return value}
export function activateChoice(editor:Editor,choice:Choice) {
 if(editor.getCurrentToolId()!==choice.tool) editor.setCurrentTool(choice.tool)
 if(choice.geo) editor.setStyleForNextShapes(GeoShapeGeoStyle,choice.geo as any)
}
export const inputOverrides: TLUiOverrides = {
 tools(editor,tools) {
  const updated={...tools,'ink-text-lasso':{id:'ink-text-lasso',label:'Lasso text',icon:'ink-text-lasso',onSelect:()=>editor.setCurrentTool('ink-text-lasso')}}
  return Object.fromEntries(Object.entries(updated).map(([key,tool])=>[key,{...tool,onSelect:(...args:any[])=>{
   ;(tool.onSelect as Function)(...args)
   if(['asset','media','embed'].includes(key)) return
   const state=inputTools(editor),current=state.get()
   state.set({...current,[current.target]:{tool:editor.getCurrentToolId(),geo:editor.getCurrentToolId()==='geo'?editor.getStyleForNextShape(GeoShapeGeoStyle):undefined}})
  }}]))
 }
}
export function attachInputRouting(editor:Editor) {
 const root=editor.getContainer()
 let activePointer:number|null=null
 const isCanvas=(event:PointerEvent)=>event.target instanceof Element && !event.target.closest('[data-ink-block],.tlui-layout,button,input,textarea,[contenteditable="true"],[role="dialog"],[role="menu"]')
 const down=(event:PointerEvent)=>{
  if(!isCanvas(event) || activePointer!==null) return
  activePointer=event.pointerId
  const state=inputTools(editor).get()
  const device=event.pointerType==='touch'?'touch':event.pointerType==='pen'?'pen':state.target
  // tldraw's automatic pen-only mode otherwise swallows finger tool gestures.
  if(event.pointerType==='touch') editor.updateInstanceState({isPenMode:false})
  activateChoice(editor,state[device])
 }
 const up=(event:PointerEvent)=>{if(event.pointerId===activePointer)activePointer=null}
 root.addEventListener('pointerdown',down,true)
 root.addEventListener('pointerup',up,true);root.addEventListener('pointercancel',up,true)
 return ()=>{root.removeEventListener('pointerdown',down,true);root.removeEventListener('pointerup',up,true);root.removeEventListener('pointercancel',up,true)}
}
export function InputToolbar() {
 const editor=useEditor()
 const state=useValue('input tool assignments',()=>inputTools(editor).get(),[editor])
 const selected=useValue('lasso selected',()=>editor.getCurrentToolId()==='ink-text-lasso',[editor])
 useEffect(()=>attachInputRouting(editor),[editor])
 function configure(target:Device) {const value=inputTools(editor);value.set({...value.get(),target});activateChoice(editor,value.get()[target])}
 const name=(choice:Choice)=>choice.tool==='ink-text-lasso'?'Lasso text':choice.geo || choice.tool
 return <div className="input-toolbar" data-ink-block="true">
  <div className="input-assignments" role="group" aria-label="Assign toolbar tools">
   <button aria-pressed={state.target==='pen'} onClick={()=>configure('pen')}>Pen: {name(state.pen)}</button>
   <button aria-pressed={state.target==='touch'} onClick={()=>configure('touch')}>Touch: {name(state.touch)}</button>
  </div>
  <DefaultToolbar><TldrawUiMenuToolItem toolId="ink-text-lasso" isSelected={selected}/><DefaultToolbarContent/></DefaultToolbar>
 </div>
}

export const lassoIcon = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><g fill="none" stroke="black" stroke-width="1.8" stroke-linecap="round"><path d="M18 16c5-2 4-10-3-12C8 1 1 5 2 11s10 10 15 5c3-3-2-5-4-2s0 7 4 7"/></g></svg>')
