export type RecentBoard = {origin: string; board: string; token: string; name: string; visited: number}
declare global {interface Window {RecentBoards?: {postMessage(message: string): void; onmessage?: (event: {data: string}) => void}}}
let sequence = 0
const pending = new Map<number, {resolve: (value: any) => void; reject: (error: Error) => void}>()
function request(method: string, items?: RecentBoard[]): Promise<any> {
 const bridge = window.RecentBoards
 if (!bridge) return Promise.reject(Error('Secure history requires the latest Android app.'))
 bridge.onmessage = event => {
  const data = JSON.parse(event.data), callback = pending.get(data.id)
  if (!callback) return
  pending.delete(data.id)
  if (data.error) callback.reject(Error(data.error)); else callback.resolve(data.result)
 }
 const id = ++sequence
 return new Promise((resolve, reject) => {
  const timer = setTimeout(() => {pending.delete(id); reject(Error('Local history did not respond.'))}, 5000)
  pending.set(id, {resolve: value => {clearTimeout(timer); resolve(value)}, reject: error => {clearTimeout(timer); reject(error)}})
  bridge.postMessage(JSON.stringify({id, method, items}))
 })
}
export const loadHistory = (): Promise<RecentBoard[]> => request('load')
export const saveHistory = (items: RecentBoard[]): Promise<void> => request('save', items.slice(0,12))
export const sameBoard = (a: RecentBoard, b: {origin: string; board: string}) => a.origin === b.origin && a.board === b.board
