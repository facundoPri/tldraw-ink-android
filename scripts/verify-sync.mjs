import {testDocumentId, serverConfigPath, adbArgs, assertAndroidDocument} from './test-config.mjs'
import {chromium} from '../web/node_modules/playwright/index.mjs'
import {readFile, writeFile} from 'node:fs/promises'
import assert from 'node:assert/strict'
const auth = JSON.parse(await readFile(serverConfigPath,'utf8'))
async function api(path,code) {const r=await fetch(`http://127.0.0.1:${auth.port}${path}`,{method:'POST',headers:{Authorization:`Bearer ${auth.token}`,'Content-Type':'application/json'},body:JSON.stringify({code})}); const d=await r.json(); if(!d.success) throw Error(JSON.stringify(d)); return d.result}
const docs=await api('/api/search','return await api.getDocs()')
const doc=docs.find(d=>d.documentId===testDocumentId && d.shared)
assert(doc, 'The selected shared board must be open')
const exec=code=>api(`/api/doc/${doc.id}/exec`,code)
const browser=await chromium.connectOverCDP('http://127.0.0.1:9223')
const page=browser.contexts()[0].pages().find(p=>p.url().includes('appassets.androidplatform.net'))
await assertAndroidDocument(page)
const suffix=Date.now(), a=`shape:android-sync-${suffix}`, b=`shape:mac-sync-${suffix}`
const checks=[]
async function poll(fn, message) {for(let n=0;n<80;n++){if(await fn()){checks.push(message);console.log('PASS',message);return}await new Promise(r=>setTimeout(r,100))}throw Error(message)}
const android=code=>page.evaluate(code.startsWith("window.editor.") && /(?:createShape|updateShape|deleteShapes)/.test(code) ? `void (${code})` : code)
try {
 await android(`window.editor.createShape({id:${JSON.stringify(a)},type:'geo',x:1400,y:800,props:{w:130,h:90,color:'blue'}})`)
 await poll(async()=>!!await exec(`return !!editor.getShape(${JSON.stringify(a)})`),'Android creates → Mac receives')
 await android(`window.editor.updateShape({id:${JSON.stringify(a)},type:'geo',x:1540,y:930})`)
 await poll(async()=>await exec(`return editor.getShape(${JSON.stringify(a)})?.x === 1540`),'Android moves → Mac receives')
 await android(`window.editor.deleteShapes([${JSON.stringify(a)}])`)
 await poll(async()=>await exec(`return !editor.getShape(${JSON.stringify(a)})`),'Android deletes → Mac receives')
 await exec(`editor.createShape({id:${JSON.stringify(b)},type:'geo',x:1500,y:900,props:{w:150,h:110,color:'green'}}); return true`)
 await poll(()=>android(`!!window.editor.getShape(${JSON.stringify(b)})`),'Mac creates → Android receives')
 await exec(`editor.updateShape({id:${JSON.stringify(b)},type:'geo',x:1640,y:1040}); return true`)
 await poll(()=>android(`window.editor.getShape(${JSON.stringify(b)})?.x===1640`),'Mac moves → Android receives')
 await exec(`editor.deleteShapes([${JSON.stringify(b)}]); return true`)
 await poll(()=>android(`!window.editor.getShape(${JSON.stringify(b)})`),'Mac deletes → Android receives')
} finally {
 await exec(`editor.deleteShapes(${JSON.stringify([a,b])}); return true`)
 await browser.close()
}
await writeFile('artifacts/sync-results.json', JSON.stringify({date:new Date().toISOString(),docId:doc.id,documentId:doc.documentId,checks,temporaryShapeIds:[a,b],cleanup:true},null,2))
