import {StateNode, atom, useEditor, useValue, getPointsFromDrawSegments, createShapeId, toRichText, type Editor, type TLDrawShape} from 'tldraw'
import {requestText} from './text-entry'
type Point={x:number;y:number}
const notices=new WeakMap<Editor,ReturnType<typeof makeNotice>>()
function makeNotice(){return atom('handwriting selection',{busy:false,message:''})}
function notice(editor:Editor){let state=notices.get(editor);if(!state){state=makeNotice();notices.set(editor,state)}return state}
export function insidePolygon(point:Point,polygon:Point[]) {
 let inside=false
 for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){
  const a=polygon[i],b=polygon[j]
  const cross=(point.x-a.x)*(b.y-a.y)-(point.y-a.y)*(b.x-a.x)
  if(Math.abs(cross)<1e-6&&point.x>=Math.min(a.x,b.x)&&point.x<=Math.max(a.x,b.x)&&point.y>=Math.min(a.y,b.y)&&point.y<=Math.max(a.y,b.y))return true
  if((a.y>point.y)!==(b.y>point.y)&&point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x)inside=!inside
 }
 return inside
}
export function handwritingInPolygon(editor:Editor,polygon:Point[]) {
 if(polygon.length<3)return []
 return editor.getCurrentPageShapesSorted().filter((s):s is TLDrawShape=>s.type==='draw'&&!editor.isShapeOrAncestorLocked(s)).map(shape=>{
  const transform=editor.getShapePageTransform(shape)
  const points=getPointsFromDrawSegments(shape.props.segments).map(p=>transform.applyToPoint(p))
  return {shape,points}
 }).filter(item=>item.points.length>0&&item.points.every(p=>insidePolygon(p,polygon)))
}
export async function convertHandwriting(editor:Editor,polygon:Point[]) {
 const state=notice(editor)
 if(state.get().busy||editor.getInstanceState().isReadonly)return
 const selected=handwritingInPolygon(editor,polygon)
 if(!selected.length){state.set({busy:false,message:'Enclose complete handwritten strokes with the lasso. Locked shapes and images are not converted.'});return}
 const originals=selected.map(s=>s.shape),pageId=editor.getCurrentPageId()
 const snapshots=originals.map(s=>JSON.stringify(s))
 const transforms=originals.map(s=>JSON.stringify(editor.getShapePageTransform(s)))
 let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity
 selected.forEach(s=>s.points.forEach(p=>{minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y)}))
 const strokes=selected.flatMap(({shape})=>shape.props.segments.map(segment=>{
  const transform=editor.getShapePageTransform(shape)
  const raw=getPointsFromDrawSegments([segment]).map(p=>transform.applyToPoint(p))
  const stride=Math.max(1,Math.ceil(raw.length/1500))
  return raw.filter((_,i)=>i%stride===0||i===raw.length-1).map(p=>({x:p.x-minX,y:p.y-minY}))
 }))
 if(strokes.reduce((n,s)=>n+s.length,0)>30000){state.set({busy:false,message:'Select a smaller section of handwriting and try again.'});return}
 editor.select(...originals.map(s=>s.id));state.set({busy:true,message:'Review the recognized text before replacing the selected ink.'})
 try {
  const text=(await requestText(editor.getColorMode()==='dark',strokes))?.trim()
  if(!text)return
  if(editor.isDisposed||editor.getCurrentPageId()!==pageId||editor.getInstanceState().isReadonly||originals.some((shape,i)=>JSON.stringify(editor.getShape(shape.id))!==snapshots[i]||JSON.stringify(editor.getShapePageTransform(shape.id))!==transforms[i]||editor.isShapeOrAncestorLocked(shape.id)))throw Error('The selected ink changed while recognizing. Nothing was replaced; select it again.')
  const id=createShapeId()
  editor.markHistoryStoppingPoint('replace handwriting with text')
  editor.run(()=>{
   editor.deleteShapes(originals.map(s=>s.id))
   editor.createShape({id,type:'text',x:minX,y:minY,props:{richText:toRichText(text),font:'sans',size:'m',w:Math.max(200,maxX-minX),autoSize:false}})
   editor.select(id)
  })
  state.set({busy:false,message:'Handwriting converted. Undo restores the original strokes.'})
 }catch(e){state.set({busy:false,message:(e as Error).message});return}
 finally {if(state.get().busy)state.set({busy:false,message:''})}
}
export class InkTextLasso extends StateNode {
 static override id='ink-text-lasso'
 private points:Point[]=[]
 private scribble:string|null=null
 override onEnter(){this.editor.setCursor({type:'cross',rotation:0})}
 override onPointerDown(){
  if(notice(this.editor).get().busy||this.editor.getInstanceState().isReadonly)return
  this.points=[];this.scribble=this.editor.scribbles.addScribble({color:'selection-stroke',size:2,delay:10000}).id
  this.addPoint()
 }
 private addPoint(){if(!this.scribble)return;const p=this.editor.inputs.getCurrentPagePoint();this.points.push({x:p.x,y:p.y});this.editor.scribbles.addPoint(this.scribble,p.x,p.y)}
 override onPointerMove(){this.addPoint()}
 override onPointerUp(){if(!this.scribble)return;this.addPoint();const points=this.points;this.clear();void convertHandwriting(this.editor,points)}
 private clear(){if(this.scribble)this.editor.scribbles.stop(this.scribble);this.scribble=null;this.points=[]}
 override onCancel(){this.clear()}
 override onExit(){this.clear()}
}
export function LassoNotice(){const editor=useEditor();const value=useValue('lasso message',()=>notice(editor).get(),[editor]);return value.message?<div className="lasso-notice" data-ink-block="true" role="status">{value.message}{!value.busy&&<button aria-label="Dismiss lasso message" onClick={()=>notice(editor).set({busy:false,message:''})}>×</button>}</div>:null}
