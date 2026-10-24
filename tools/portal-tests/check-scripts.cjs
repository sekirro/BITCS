const vm = require('node:vm');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const scripts = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/,''));
let count = 0;
function context(fetchImpl, href=scripts.page) {
  const c={location:{href},document:{readyState:'complete'},URL,URLSearchParams,AbortController,setTimeout,clearTimeout,fetch:fetchImpl};
  c.window=c; c.top=c; vm.createContext(c); return c;
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
async function completed(fetchImpl) {
  const c=context(fetchImpl); assert.equal(vm.runInContext(scripts.begin,c),'started');
  await settle(); await settle(); return [c,JSON.parse(vm.runInContext(scripts.poll,c))];
}
(async()=>{
  let fetches=0;
  const wrong=context(()=>{fetches++;},'https://sso.bit.edu.cn/cas/login');
  assert.equal(vm.runInContext(scripts.begin,wrong),'not_ready'); assert.equal(fetches,0); count++;
  const [valid,empty]=await completed(async(url,options)=>{
    assert.equal(url,scripts.api); assert.equal(options.credentials,'same-origin');
    assert.equal(options.method,'POST'); assert.equal(options.body,'XNXQDM=2025-2026-1');
    return {url,status:200,ok:true,text:async()=>'{"datas":{"cxxszhxqkb":{"rows":[]}}}'};
  });
  assert.equal(empty.status,'complete'); assert.equal(JSON.parse(empty.body).datas.cxxszhxqkb.rows.length,0); count++;
  const [,unauth]=await completed(async()=>({url:scripts.api,status:401,ok:false}));
  assert.equal(unauth.status,'auth'); count++;
  const [,redirect]=await completed(async()=>({url:'https://sso.bit.edu.cn/cas/login',status:200,ok:true}));
  assert.equal(redirect.status,'auth'); count++;
  const [,jsonAuth]=await completed(async()=>({url:scripts.api,status:200,ok:true,text:async()=>'{"code":401}'}));
  assert.equal(jsonAuth.status,'auth'); count++;
  const [,htmlAuth]=await completed(async()=>({url:scripts.api,status:200,ok:true,text:async()=>'<form><input type="password"></form>'}));
  assert.equal(htmlAuth.status,'auth'); count++;
  let aborted=false;
  const c=context((url,options)=>new Promise((resolve,reject)=>{
    options.signal.addEventListener('abort',()=>{aborted=true; reject(new Error('offline abort'));});
  }));
  vm.runInContext(scripts.begin,c); vm.runInContext(scripts.clear,c); await settle();
  assert.equal(aborted,true); assert.equal(JSON.parse(vm.runInContext(scripts.poll,c)).status,'missing'); count++;
  let resolveFetch;
  const moved=context(()=>new Promise(resolve=>{resolveFetch=resolve;}));
  vm.runInContext(scripts.begin,moved); moved.location.href=scripts.page+'?changed';
  resolveFetch({url:scripts.api,status:200,ok:true,text:async()=>'{"rows":["old"]}'}); await settle();
  assert.equal(JSON.parse(vm.runInContext(scripts.poll,moved)).status,'changed');
  assert.equal(moved[scripts.token].body,undefined); vm.runInContext(scripts.clear,moved); count++;
  assert.equal(valid[scripts.token].abort instanceof AbortController,true);
  console.log(`PASS: ${count} JavaScript async security tests; mocked fetch only, no network.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
