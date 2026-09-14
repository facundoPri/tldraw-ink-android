import {chromium} from '../web/node_modules/playwright/index.mjs'
import {mkdir, writeFile} from 'node:fs/promises'
import assert from 'node:assert/strict'

const browser = await chromium.launch({headless:true, ...(process.env.BROWSER_CHANNEL ? {channel:process.env.BROWSER_CHANNEL} : {})})
const context = await browser.newContext({viewport:{width:1204,height:752},deviceScaleFactor:1,locale:'es-ES'})
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))
await mkdir('artifacts', {recursive:true})
const base = process.env.DESIGN_URL || 'http://127.0.0.1:5179'
try {
 await page.goto(`${base}/demo.html`)
 await page.waitForFunction(() => !!window.editor)
 await page.evaluate(() => window.editor.user.updateUserPreferences({locale:'es'}))
 await page.reload()
 await page.waitForFunction(() => window.editor?.user.getLocale() === 'en')
 assert.equal(await page.locator('html').getAttribute('lang'), 'en')
 await page.evaluate(() => document.fonts.ready)
 await page.getByRole('button', {name:'Boards',exact:true}).waitFor()
 await page.screenshot({path:'docs/images/canvas.png'})
 await page.getByLabel('View participants').click()
 assert.match(await page.locator('.participants').innerText(), /You · tablet/)
 await page.getByLabel('View participants').click()
 await page.evaluate(() => window.editor.user.updateUserPreferences({colorScheme:'dark'}))
 await page.waitForFunction(() => window.editor.getColorMode() === 'dark')
 const dark = await page.locator('.board-controls').evaluate(e => ({color:getComputedStyle(e).color,background:getComputedStyle(e).backgroundColor}))
 assert.notEqual(dark.background, 'rgb(252, 252, 252)')
 await page.screenshot({path:'artifacts/design-dark.png'})
 await page.setViewportSize({width:600,height:900})
 assert(await page.getByRole('button',{name:'Boards',exact:true}).isVisible())
 const bounds = await page.locator('.board-controls').boundingBox()
 assert(bounds.x >= 0 && bounds.x+bounds.width <= 600)
 await page.screenshot({path:'artifacts/design-narrow.png'})
 // Stand-in for the Android history bridge, with no credentials or persisted data.
 await context.addInitScript(() => {
  window.RecentBoards = {postMessage(message) {
   const {id,method} = JSON.parse(message)
   queueMicrotask(() => window.RecentBoards.onmessage?.({data:JSON.stringify({id,result:method === 'load' ? [] : null})}))
  }}
 })
 await page.getByRole('button',{name:'Boards',exact:true}).click()
 await page.getByLabel('Add a Share link').waitFor()
 await page.setViewportSize({width:1204,height:752})
 await page.screenshot({path:'docs/images/boards.png'})
 assert.equal(await page.locator('[role=alert]').innerText(), '')
 assert(await page.getByRole('button',{name:'Open board'}).isDisabled())
 await page.getByLabel('Add a Share link').fill('invalid')
 await page.getByRole('button',{name:'Open board'}).click()
 assert((await page.locator('[role=alert]').innerText()).length > 0)
 assert.deepEqual(errors, [])
 await writeFile('artifacts/design-results.json',JSON.stringify({checks:['English overrides Spanish browser and saved locale','canvas renders','participant menu opens','dark theme controls','narrow layout and accessible Boards button','Boards navigation','empty input disabled','invalid link error'],dark,errors},null,2))
 console.log('PASS: design, theme, navigation, input validation; screenshots saved.')
} finally {await browser.close()}
