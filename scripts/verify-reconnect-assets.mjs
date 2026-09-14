import {testDocumentId, serverConfigPath, adbArgs, assertAndroidDocument} from './test-config.mjs'
import {chromium} from '../web/node_modules/playwright/index.mjs'
import {readFile,writeFile} from 'node:fs/promises'
import assert from 'node:assert/strict'
const config=JSON.parse(await readFile(serverConfigPath,'utf8'))
async function api(path,code){const r=await fetch(`http://127.0.0.1:${config.port}${path}`,{method:'POST',headers:{Authorization:`Bearer ${config.token}`,'Content-Type':'application/json'},body:JSON.stringify({code})});const d=await r.json();if(!d.success)throw Error(JSON.stringify(d));return d.result}
const docs=await api('/api/search','return await api.getDocs()');const doc=docs.find(d=>d.documentId===testDocumentId&&d.shared);assert(doc)
const desktop=code=>api(`/api/doc/${doc.id}/exec`,code)
const browser=await chromium.connectOverCDP('http://127.0.0.1:9223');const page=browser.contexts()[0].pages()[0];const cdp=await page.context().newCDPSession(page)
await assertAndroidDocument(page)
const id=`shape:reconnect-${Date.now()}`, assetId=`asset:sync-probe-${Date.now()}`, imageId=`shape:asset-probe-${Date.now()}`
const checks=[]
async function wait(fn,msg){for(let i=0;i<100;i++){if(await fn()){checks.push(msg);console.log('PASS',msg);return}await new Promise(r=>setTimeout(r,100))}throw Error(msg)}
try {
 await cdp.send('Network.enable')
 await cdp.send('Network.emulateNetworkConditions',{offline:true,latency:0,downloadThroughput:0,uploadThroughput:0})
 await wait(async()=>(await page.locator('[role=status]').innerText()).includes('Reconectando'),'Interrupción de red detectada')
 await page.evaluate(id=>{window.editor.createShape({id,type:'geo',x:20000,y:20000,props:{w:50,h:50}})},id)
 await new Promise(r=>setTimeout(r,500))
 assert.equal(await desktop(`return !!editor.getShape(${JSON.stringify(id)})`),false)
 await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:0,downloadThroughput:-1,uploadThroughput:-1})
 await wait(()=>desktop(`return !!editor.getShape(${JSON.stringify(id)})`),'Edición pendiente llega al reconectar')
 const uploaded=await page.evaluate(async({assetId,imageId})=>{
  const canvas=document.createElement('canvas');canvas.width=32;canvas.height=32;const ctx=canvas.getContext('2d');ctx.fillStyle='#245f45';ctx.fillRect(0,0,32,32);ctx.fillStyle='#ffffff';ctx.fillRect(8,8,16,16)
  const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));const file=new File([blob],'sync-probe.png',{type:'image/png'})
  const asset={id:assetId,typeName:'asset',type:'image',props:{name:file.name,src:null,w:32,h:32,mimeType:'image/png',isAnimated:false,fileSize:file.size},meta:{}}
  const result=await window.editor.uploadAsset(asset,file)
  asset.props.src=result.src;window.editor.createAssets([asset]);window.editor.createShape({id:imageId,type:'image',x:20100,y:20000,props:{assetId,w:128,h:128}})
  const url=await window.editor.resolveAssetUrl(assetId,{screenScale:1});const r=await fetch(url)
  return {src:result.src,status:r.status,bytes:(await r.arrayBuffer()).byteLength}
 },{assetId,imageId})
 assert(uploaded.src.startsWith('asset:'));assert.equal(uploaded.status,200);assert(uploaded.bytes>0);checks.push('PNG subido y descargado por API assets del Share')
 await wait(()=>desktop(`return editor.getShape(${JSON.stringify(imageId)})?.props.assetId===${JSON.stringify(assetId)}`),'Imagen Android recibida por Mac')
 const resolved=await desktop(`const src=await editor.resolveAssetUrl(${JSON.stringify(assetId)},{screenScale:1});return typeof src === 'string' && src.length > 0`);assert(resolved);checks.push('Mac resuelve el asset compartido')
 const remoteId=`shape:mac-asset-probe-${Date.now()}`
 await desktop(`editor.createShape({id:${JSON.stringify(remoteId)},type:'image',x:20300,y:20000,props:{assetId:${JSON.stringify(assetId)},w:64,h:64}});return true`)
 try {await wait(()=>page.evaluate(id=>!!window.editor.getShape(id),remoteId),'Imagen creada en Mac recibida por Android')}finally{await desktop(`editor.deleteShapes([${JSON.stringify(remoteId)}]);return true`)}
} finally {
 await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:0,downloadThroughput:-1,uploadThroughput:-1})
 await desktop(`editor.deleteShapes(${JSON.stringify([id,imageId])});editor.deleteAssets([${JSON.stringify(assetId)}]);return true`)
 await cdp.detach();await browser.close()
}
await writeFile('artifacts/reconnect-assets-results.json',JSON.stringify({date:new Date().toISOString(),checks,cleanup:true,note:'Host may retain content-addressed PNG blob until its garbage collection.'},null,2))
