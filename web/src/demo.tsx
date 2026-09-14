// Development-only design sandbox. Vite's production entry is index.html.
// Uses a fresh in-memory store: no Share connection, invitation, or real document.
import {createRoot} from 'react-dom/client'
import {Tldraw, toRichText, compressLegacySegments, type Editor} from 'tldraw'
import {getAssetUrlsByImport} from '@tldraw/assets/imports.vite'
import {BoardContext, boardComponents} from './board-controls'
import 'tldraw/tldraw.css'
import './style.css'

function populate(editor: Editor) {
 window.editor = editor
 editor.user.updateUserPreferences({colorScheme: 'light'})
 editor.createShapes([
  {type:'text',x:150,y:150,props:{richText:toRichText('A little room to think.'),font:'draw',size:'xl',color:'black'}},
  {type:'text',x:153,y:215,props:{richText:toRichText('Sketch on your tablet. Keep going on your desktop.'),font:'sans',size:'s',color:'grey'}},
  {type:'geo',x:155,y:310,props:{geo:'rectangle',w:250,h:150,color:'blue',fill:'semi',richText:toRichText('Catch an idea'),font:'draw',size:'m'}},
  {type:'geo',x:480,y:310,props:{geo:'rectangle',w:250,h:150,color:'yellow',fill:'semi',richText:toRichText('Give it shape'),font:'draw',size:'m'}},
  {type:'arrow',x:418,y:385,props:{start:{x:0,y:0},end:{x:49,y:0},color:'black'}},
  {type:'text',x:160,y:520,props:{richText:toRichText('Notes, diagrams, and the next good idea.'),font:'draw',size:'m',color:'black'}},
  {type:'draw',x:160,y:580,props:{segments:compressLegacySegments([{type:'free',points:Array.from({length:81},(_,i)=>({x:i*5,y:8*Math.sin(i/10),z:.5}))}]),isPen:true,isComplete:true,color:'blue',size:'s'}},
 ])
 editor.setCamera({x:0,y:0,z:1})
}
createRoot(document.getElementById('root')!).render(
 <BoardContext.Provider value={{name:'A fresh page',status:'Demo local',openBoards:()=>location.assign('/')}}>
  <main className="canvas"><Tldraw assetUrls={getAssetUrlsByImport()} components={boardComponents} onMount={populate}/></main>
 </BoardContext.Provider>
)
