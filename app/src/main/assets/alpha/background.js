const CHECK_ALARM="jmb-license-check";function uuid(){return crypto.randomUUID()}async function getBuyerId(){const{jmbBuyerId:t}=await chrome.storage.local.get("jmbBuyerId");if(t)return t;const e=uuid();return await chrome.storage.local.set({jmbBuyerId:e}),e}async function setStatus(t){await chrome.storage.local.set({jmbLicenseStatus:t,jmbLicenseCheckedAt:Date.now()})}async function activate(){const t=await getBuyerId();try{const e=await fetch(`${LICENSE_SERVER}/api/activate`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({buyerUuid:t,extVersion:chrome.runtime.getManifest().version})});if(!e.ok)throw new Error("activate failed: "+e.status);const a=await e.json();await setStatus(a.status)}catch(e){console.warn("[ALPHA] activate failed",e)}}async function checkLicense(){const t=await getBuyerId();try{const e=await fetch(`${LICENSE_SERVER}/api/check?buyerUuid=${encodeURIComponent(t)}`);if(e.status===404)return activate();if(!e.ok)throw new Error("check failed: "+e.status);const a=await e.json();await setStatus(a.status)}catch(e){console.warn("[ALPHA] license check failed",e)}}chrome.runtime.onInstalled.addListener(async()=>{await activate(),chrome.alarms.create(CHECK_ALARM,{periodInMinutes:30})}),chrome.runtime.onStartup.addListener(()=>{checkLicense(),chrome.alarms.create(CHECK_ALARM,{periodInMinutes:30})}),chrome.alarms.onAlarm.addListener(t=>{t.name===CHECK_ALARM&&checkLicense()}),chrome.runtime.onMessage.addListener((t,e,a)=>{if(t&&t.type==="JMB_LICENSE_RECHECK")return checkLicense().then(async()=>{const{jmbLicenseStatus:n}=await chrome.storage.local.get("jmbLicenseStatus");a({status:n||"pending"})}),!0;if(t&&t.type==="JMB_GET_BUYER_ID")return getBuyerId().then(n=>a({buyerUuid:n})),!0});


// Persistent on-device media library (IndexedDB). This avoids chrome.storage quota limits
// and survives popup/page close and extension restarts on the same device/profile.
const MEDIA_DB = "alpha-media-db";
const MEDIA_STORE = "media";
function mediaDb(){return new Promise((resolve,reject)=>{const r=indexedDB.open(MEDIA_DB,1);r.onupgradeneeded=()=>{const db=r.result;if(!db.objectStoreNames.contains(MEDIA_STORE))db.createObjectStore(MEDIA_STORE,{keyPath:"id"});};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}
async function mediaSave(item){const db=await mediaDb();return new Promise((resolve,reject)=>{const tx=db.transaction(MEDIA_STORE,"readwrite");tx.objectStore(MEDIA_STORE).put(item);tx.oncomplete=()=>resolve(true);tx.onerror=()=>reject(tx.error);});}
async function mediaList(){const db=await mediaDb();return new Promise((resolve,reject)=>{const tx=db.transaction(MEDIA_STORE,"readonly");const r=tx.objectStore(MEDIA_STORE).getAll();r.onsuccess=()=>resolve(r.result.map(x=>({id:x.id,kind:x.kind,name:x.name,type:x.type||"audio/webm",size:x.blob?.size||0,createdAt:x.createdAt})));r.onerror=()=>reject(r.error);});}
async function mediaGet(id){const db=await mediaDb();return new Promise((resolve,reject)=>{const tx=db.transaction(MEDIA_STORE,"readonly");const r=tx.objectStore(MEDIA_STORE).get(id);r.onsuccess=()=>resolve(r.result||null);r.onerror=()=>reject(r.error);});}
async function mediaDelete(id){const db=await mediaDb();return new Promise((resolve,reject)=>{const tx=db.transaction(MEDIA_STORE,"readwrite");tx.objectStore(MEDIA_STORE).delete(id);tx.oncomplete=()=>resolve(true);tx.onerror=()=>reject(tx.error);});}

// Blobs cannot cross chrome.runtime messages (they arrive as {}), so audio travels as base64.
async function blobToB64(b){const u=new Uint8Array(await b.arrayBuffer());let s="";const CH=0x8000;for(let i=0;i<u.length;i+=CH){s+=String.fromCharCode.apply(null,u.subarray(i,i+CH));}return btoa(s);}
function b64ToU8(b){const s=atob(b);const u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return u;}
chrome.runtime.onMessage.addListener((m,sender,sendResponse)=>{
  if(!m||!String(m.type||"").startsWith("ALPHA_MEDIA_")) return;
  (async()=>{
    try{
      if(m.type==="ALPHA_MEDIA_SAVE"){
        const blob = new Blob([b64ToU8(m.b64)], {type: m.mimeType || "audio/webm"});
        await mediaSave({id:m.id,kind:m.kind,name:m.name||m.id,type:blob.type,blob,createdAt:Date.now()});
        sendResponse({ok:true});
      } else if(m.type==="ALPHA_MEDIA_LIST") sendResponse({items:await mediaList()});
      else if(m.type==="ALPHA_MEDIA_GET"){const x=await mediaGet(m.id);sendResponse(x?{id:x.id,name:x.name,type:x.type,b64:await blobToB64(x.blob)}:{});}
      else if(m.type==="ALPHA_MEDIA_DELETE"){await mediaDelete(m.id);sendResponse({ok:true});}
    }catch(e){sendResponse({ok:false,error:String(e)});}
  })(); return true;
});
