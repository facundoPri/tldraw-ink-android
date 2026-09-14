#!/usr/bin/env node
import {execFileSync} from 'node:child_process'
import {readFile,writeFile,copyFile,mkdir,readdir} from 'node:fs/promises'
import {fileURLToPath} from 'node:url'
import {dirname,join} from 'node:path'
import {createHash} from 'node:crypto'
import assert from 'node:assert/strict'

process.chdir(dirname(dirname(fileURLToPath(import.meta.url))))
const git = (...args) => execFileSync('git',args,{encoding:'utf8'}).trim()
assert.equal(git('branch','--show-current'),'main','Package releases from main')
assert.equal(git('status','--porcelain'),'','Commit all changes before packaging a release')
const commit = git('rev-parse','HEAD')
assert.equal(git('ls-remote','origin','refs/heads/main').split(/\s/)[0],commit,'Push this main commit before packaging')
for (const name of ['VITE_TLDRAW_LICENSE_KEY','SIGNING_STORE_FILE','SIGNING_STORE_PASSWORD','SIGNING_KEY_ALIAS','SIGNING_KEY_PASSWORD','ANDROID_HOME']) {
 assert(process.env[name],`Set ${name} before packaging`)
}
execFileSync('./scripts/build.sh',['release'],{stdio:'inherit'})
const buildTools = join(process.env.ANDROID_HOME,'build-tools')
const versions = (await readdir(buildTools)).sort((a,b)=>b.localeCompare(a,undefined,{numeric:true}))
assert(versions.length, 'Install Android SDK build tools')
const apk = 'android/app/build/outputs/apk/release/app-release.apk'
execFileSync(join(buildTools,versions[0],'apksigner'),['verify',apk],{stdio:'inherit'})
const version = JSON.parse(await readFile('web/package.json','utf8')).version
const output = 'artifacts/release'
await mkdir(output,{recursive:true})
const filename = `ink-share-${version}.apk`
await copyFile(apk,join(output,filename))
const checksum = createHash('sha256').update(await readFile(apk)).digest('hex')
await writeFile(join(output,'SHA256SUMS'),`${checksum}  ${filename}\n`)
await writeFile(join(output,'build-info.json'),JSON.stringify({version,commit,mode:'production',signed:true},null,2)+'\n')
console.log(`Release assets ready in ${output}. Test the signed APK before publishing.`)
