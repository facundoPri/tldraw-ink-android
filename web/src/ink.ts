import { inputTools } from './input-tools'
import { sampleForHandoff } from './stroke-sampling'
import { compressLegacySegments, createShapeId, DefaultColorStyle, DefaultSizeStyle, DefaultDashStyle, getColorValue, react, type Editor, type TLDefaultColorStyle } from 'tldraw'

type NativeInk = {
 resetBoard(): void
 setInkEnabled(enabled: boolean): void
 setInkStyle(color: string, rendered: string, width: number, pressure: number, profile: string, dash: string): void
 setViewport(zoom: number, x: number, y: number, left: number, top: number): void
 setHitRegions(json: string): void
 strokeCommitted(token: string): void
 reportWebReady(): void
}
type Payload = {dash?: 'draw' | 'solid'; token: string; viewWidth: number; viewHeight: number; color: string; strokeWidth: number; viewport: {zoom: number; scrollX: number; scrollY: number; offsetLeft: number; offsetTop: number}; points: {x: number; y: number; pressure: number}[]}
declare global {interface Window {
 AndroidInk?: NativeInk
 hybridInkCommit?: (json: string) => boolean
 hybridInkSetEraser?: (active: boolean) => boolean
 hybridInkEraserEvent?: (json: string) => boolean
}}
const sizes = {s: 1, m: 1.75, l: 2.5, xl: 5} as const
export function attachInk(editor: Editor) {
 const native = window.AndroidInk
 if (!native) return () => {}
 const stop = react('Android Ink style and camera', () => {
  const tool = inputTools(editor).get().pen.tool
  const dash = editor.getStyleForNextShape(DefaultDashStyle)
  native.setInkEnabled(tool === 'draw' && (dash === 'draw' || dash === 'solid') && !editor.getInstanceState().isReadonly)
  const camera = editor.getCamera(), bounds = editor.getViewportScreenBounds()
  native.setViewport(camera.z, camera.x, camera.y, bounds.x, bounds.y)
  const theme = editor.getCurrentTheme()
  const color = getColorValue(theme.colors[editor.getColorMode()], editor.getStyleForNextShape(DefaultColorStyle), 'solid')
  const size = editor.getStyleForNextShape(DefaultSizeStyle)
  const baseWidth = theme.strokeWidth * sizes[size]
  const forceSolid = camera.z < 0.5 && camera.z < 1.5 / (baseWidth + 1)
  native.setInkStyle(color, color, baseWidth, 1, forceSolid || dash === 'solid' ? 'monoline' : 'pressure', dash)
 })
 function hitRegions() {
  const selectors = '[data-ink-block],.tlui-share-zone,.tlui-toolbar,.tlui-style-panel,.tlui-navigation-panel,.tlui-help-menu,[role="dialog"],[role="menu"],.tlui-popover,.tlui-layout button,.tlui-layout input,.tlui-layout [role="button"]'
  const rects = Array.from(document.querySelectorAll(selectors)).filter(el => {const style = getComputedStyle(el); return style.pointerEvents !== 'none' && style.visibility === 'visible' && style.display !== 'none'}).map(el => el.getBoundingClientRect()).filter(r => r.width && r.height).map(r => [r.left / innerWidth, r.top / innerHeight, r.right / innerWidth, r.bottom / innerHeight])
  native!.setHitRegions(JSON.stringify(rects))
 }
 hitRegions()
 const regionTimer = window.setInterval(hitRegions, 150)
 window.hybridInkCommit = json => {
  try {
   const p: Payload = JSON.parse(json)
   if (!p.points.length || editor.isDisposed || editor.getInstanceState().isReadonly) return false
   const id = createShapeId(`ink-${p.token}`)
   if (!editor.getShape(id)) {
    const v = p.viewport
    let pressure = 0.5
    const pagePoints = p.points.map(point => {
     if (point.pressure > 0.01) pressure = Math.min(1, point.pressure)
     return {x: (point.x * innerWidth / p.viewWidth - v.offsetLeft) / v.zoom - v.scrollX, y: (point.y * innerHeight / p.viewHeight - v.offsetTop) / v.zoom - v.scrollY, z: pressure}
    })
    const origin = pagePoints[0]
    const size = (Object.keys(sizes) as (keyof typeof sizes)[]).reduce((a, b) => Math.abs(editor.getCurrentTheme().strokeWidth * sizes[a] - p.strokeWidth) < Math.abs(editor.getCurrentTheme().strokeWidth * sizes[b] - p.strokeWidth) ? a : b)
    const colors = editor.getCurrentTheme().colors[editor.getColorMode()]
    const color = (Object.keys(colors).find(key => getColorValue(colors, key, 'solid').toLowerCase() === p.color.toLowerCase()) || 'black') as TLDefaultColorStyle
    editor.markHistoryStoppingPoint('S Pen stroke')
    editor.createShape({id, type:'draw', x:origin.x, y:origin.y, props:{color, size, dash:p.dash || 'draw', isPen:true, isComplete:true, segments:compressLegacySegments([{type:'free',points:sampleForHandoff(pagePoints, devicePixelRatio * v.zoom).map(q=>({x:q.x-origin.x,y:q.y-origin.y,z:q.z}))}])}})
   }
   requestAnimationFrame(() => requestAnimationFrame(() => native.strokeCommitted(p.token)))
   return true
  } catch { return false }
 }
 window.hybridInkSetEraser = active => {
  if (editor.getInstanceState().isReadonly) return false
  if (active) editor.markHistoryStoppingPoint('S Pen eraser')
  return true
 }
 window.hybridInkEraserEvent = json => {
  if (editor.getInstanceState().isReadonly) return false
  const p = JSON.parse(json)
  if (p.action === 'cancel') return true
  for (const point of p.points) {
   const at = editor.screenToPage({x: point.x * innerWidth / p.viewWidth,y: point.y * innerHeight / p.viewHeight})
   const shape = editor.getShapeAtPoint(at, {hitInside: false, margin: 8 / editor.getZoomLevel()})
   if (shape && !editor.isShapeOrAncestorLocked(shape)) editor.deleteShapes([shape.id])
  }
  return true
 }
 native.reportWebReady()
 return () => { stop(); clearInterval(regionTimer); native.setInkEnabled(false); native.resetBoard(); delete window.hybridInkCommit; delete window.hybridInkSetEraser; delete window.hybridInkEraserEvent }
}
