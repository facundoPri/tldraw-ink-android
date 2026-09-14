import { chromium } from '../web/node_modules/playwright/index.mjs'
const browser = await chromium.connectOverCDP('http://127.0.0.1:9223')
const page = browser.contexts()[0].pages().find(p => p.url().includes('appassets.androidplatform.net'))
if (!page) throw Error('WebView no disponible')
let share = ''; for await (const chunk of process.stdin) share += chunk
if (!share.trim()) throw Error('Falta la invitación en stdin')
await page.locator('#share').fill(share.trim())
await page.getByRole('button', {name:'Abrir board'}).click()
try {await page.waitForFunction(() => !!window.editor, {timeout:20000})} catch {console.log((await page.locator('body').innerText()).slice(0,2000)); throw Error('Editor no montado')}
console.log(await page.evaluate(() => ({status:document.querySelector('[role=status]')?.textContent,count:window.editor.getCurrentPageShapes().length})))
await page.screenshot({path:'artifacts/connected.png'})
await browser.close()
