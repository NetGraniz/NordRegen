'use strict'
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn}=require('node:child_process'),mineflayer=require('mineflayer')
const platform=process.argv[2],root=path.resolve(process.argv[3]),java=process.argv[4]
assert(['Paper','Folia'].includes(platform));assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nord-cleanup-test-20261007-'));assert(!fs.existsSync(root))
const server=path.join(root,'server'),project=path.resolve(__dirname,'..'),seed='C:\\Users\\artyo\\Documents\\Codex\\nordauth-test-20261007-'+platform.toLowerCase()+'\\server'
fs.mkdirSync(path.join(server,'plugins'),{recursive:true})
for(const name of ['server.jar','cache','libraries','eula.txt'])if(fs.existsSync(path.join(seed,name)))fs.cpSync(path.join(seed,name),path.join(server,name),{recursive:true})
for(const name of ['NordRegen-2.0.0.jar','CleanupProbe.jar'])fs.copyFileSync(path.join(project,'target',name),path.join(server,'plugins',name))
fs.writeFileSync(path.join(server,'server.properties'),'server-ip=127.0.0.1\nserver-port=25647\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-name=CleanupSynthetic\nlevel-type=minecraft:flat\n')
let output='',exited=false,bot;const messages=[]
const child=spawn(java,['-Dterminal.jline=false','-Dterminal.ansi=false','-Xms256M','-Xmx1400M','-jar','server.jar','nogui'],{cwd:server,windowsHide:true,stdio:['pipe','pipe','pipe']})
child.on('exit',()=>{exited=true});for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{output+=b.toString()})
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function until(fn,timeout=120000){let start=Date.now();while(!fn()){if(exited || /CLEANUP_TEST_FAIL/.test(output) || Date.now()-start>timeout)throw Error(output.slice(-5000));await sleep(200)}}
async function main(){
 await until(()=>/Done \(/.test(output));assert.match(output,new RegExp(platform+' version'))
 bot=mineflayer.createBot({host:'127.0.0.1',port:25647,username:'CleanupAdmin',auth:'offline',version:'26.2',hideErrors:true})
 bot.on('error',()=>{});bot.once('spawn',()=>{bot.physicsEnabled=false});bot.on('messagestr',m=>messages.push(m))
 await until(()=>/CLEANUP_TEST_PASS/.test(output) && messages.some(m=>m.includes('Chunk cleanup complete')),120000)
 assert(!/Thread failed main thread check|Could not pass event|Exception executing task|Cannot read world asynchronously/.test(output))
 console.log(platform+' CLEANUP PASS: confirmation, permissions, pacing, target blocks/entities, chunk boundary, terrain, inventories, player')
 fs.writeFileSync(path.join(root,'results.json'),JSON.stringify({platform,passed:true,messages,notes:'Synthetic loopback test only; not 1000-player load.'},null,2))
}
main().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{
 if(bot)bot.quit();if(!exited)child.stdin.write('stop\n');let start=Date.now();while(!exited&&Date.now()-start<50000)await sleep(200)
 if(!exited)child.kill();fs.writeFileSync(path.join(root,'output.log'),output)
})
