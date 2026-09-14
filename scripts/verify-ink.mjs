import {testDocumentId, serverConfigPath, adbArgs, assertAndroidDocument} from './test-config.mjs'
import {chromium} from '../web/node_modules/playwright/index.mjs'
import {readFile,writeFile} from 'node:fs/promises'
import {execFileSync} from 'node:child_process'
import assert from 'node:assert/strict'
const config=JSON.parse(await readFile(serverConfigPath,'utf8'))
async function api(path,code){const r=await fetch(`http://127.0.0.1:${config.port}${path}`,{method:'POST',headers:{Authorization:`Bearer ${config.token}`,'Content-Type':'application/json'},body:JSON.stringify({code})});const d=await r.json();if(!d.success)throw Error(JSON.stringify(d));return d.result}
const docs=await api('/api/search','return await api.getDocs()'); const doc=docs.find(d=>d.documentId===testDocumentId&&d.shared); assert(doc)
const desktop=code=>api(`/api/doc/${doc.id}/exec`,code)
const browser=await chromium.connectOverCDP('http://127.0.0.1:9223');const page=browser.contexts()[0].pages()[0]
await assertAndroidDocument(page)
const state=await page.evaluate(()=>({camera:window.editor.getCamera(),tool:window.editor.getCurrentToolId(),ids:Array.from(window.editor.getCurrentPageShapeIds())}))
const checks=[];let created=[]
try {
 await page.evaluate(()=>{window.editor.setCamera({x:-20000,y:-20000,z:1});window.editor.setCurrentTool('draw')})
 await new Promise(r=>setTimeout(r,500))
 const xml=await readFile('artifacts/ui-ink.xml','utf8'); const match=xml.match(/class="android.webkit.WebView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert(match)
 const [left,top,right,bottom]=match.slice(1).map(Number);const x=Math.round((left+right)/2), y=Math.round((top+bottom)/2)
 execFileSync('adb',[...adbArgs,'shell','input','stylus','swipe',String(x),String(y),String(x+220),String(y+90),'650'])
 for(let i=0;i<40;i++){created=await page.evaluate(ids=>window.editor.getCurrentPageShapes().filter(s=>s.id.startsWith('shape:ink-')&&!ids.includes(s.id)),state.ids);if(created.length)break;await new Promise(r=>setTimeout(r,100))}
 assert.equal(created.length,1,'The stylus gesture must produce exactly one Ink shape')
 assert.equal(created[0].type,'draw');assert(created[0].props.isPen);checks.push('MotionEvent stylus → Jetpack Ink → one draw shape')
 const remote=await desktop(`return editor.getShape(${JSON.stringify(created[0].id)})`);assert(remote);assert.equal(remote.props.segments[0].path,created[0].props.segments[0].path);checks.push('Native stroke synced to Mac')
 await page.screenshot({path:'artifacts/ink-native.png'})
 console.log(checks)
} finally {
 if(created.length)await desktop(`editor.deleteShapes(${JSON.stringify(created.map(s=>s.id))});return true`)
 await page.evaluate(s=>{window.editor.setCamera(s.camera);window.editor.setCurrentTool(s.tool)},state)
 await browser.close()
}
await writeFile('artifacts/ink-results.json',JSON.stringify({date:new Date().toISOString(),checks,ids:created.map(s=>s.id),cleanup:true,input:'adb synthetic stylus; real S Pen pressure/latency pending'},null,2))
