import {homedir} from 'node:os'
import {join} from 'node:path'
import {mkdir} from 'node:fs/promises'
import assert from 'node:assert/strict'

// All mutation probes require an explicitly selected, disposable board on BOTH clients.
export const testDocumentId = process.env.TEST_DOCUMENT_ID
const testBoardId = process.env.TEST_BOARD_ID
assert(testDocumentId && testBoardId, 'Set TEST_DOCUMENT_ID and TEST_BOARD_ID to your disposable shared test board')
export const serverConfigPath = process.env.TLDRAW_SERVER_CONFIG || join(homedir(), 'Library/Application Support/tldraw/server.json')
export const adbArgs = process.env.ANDROID_SERIAL ? ['-s', process.env.ANDROID_SERIAL] : []
export async function assertAndroidDocument(page) {
 assert(page, 'Android WebView is unavailable; run scripts/attach.sh first')
 assert.equal(await page.locator('.canvas').getAttribute('data-board'), testBoardId, 'Android must be connected to the selected test board')
 assert(await page.evaluate(() => !!window.editor), 'Editor must be ready')
 await mkdir('artifacts', {recursive: true})
}
