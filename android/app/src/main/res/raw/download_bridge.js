/*
 * Injected into the article WebView so the mirror's own "Download article"
 * menu (PDF / Markdown) works inside the app.
 *
 * WebView has no download UI of its own, and the mirror saves files in two ways
 * that never reach native code intact:
 *   - Markdown: an <a download> pointing at /api/download, an attachment response.
 *   - PDF: the page fetches /api/pdf, wraps the bytes in a blob: URL, clicks an
 *     <a download> for it and revokes the URL straight away. A blob: URL can't be
 *     fetched from Java, and it's gone by the time a DownloadListener would run.
 * So download links are caught here, the bytes are read in the page and handed to
 * the MuDownloads interface as base64. Idempotent per page.
 */
(function(){
if(window.__muDownloadBridge||!window.MuDownloads)return;
window.__muDownloadBridge=true;
var blobs={};
var createUrl=URL.createObjectURL,revokeUrl=URL.revokeObjectURL;
URL.createObjectURL=function(obj){
var url=createUrl.apply(URL,arguments);
if(typeof Blob!=='undefined'&&obj instanceof Blob)blobs[url]=obj;
return url;
};
URL.revokeObjectURL=function(url){
// Pages revoke right after click(); keep the blob until the copy has been read.
setTimeout(function(){delete blobs[url];revokeUrl.call(URL,url);},60000);
};
function fileName(disposition,fallback){
var m=/filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i.exec(disposition||'');
if(m){try{return decodeURIComponent(m[1].replace(/^"|"$/g,''));}catch(e){}}
m=/filename\s*=\s*"?([^";]+)"?/i.exec(disposition||'');
return m?m[1]:(fallback||'');
}
function lastSegment(href){
try{var p=new URL(href).pathname.split('/');return decodeURIComponent(p[p.length-1]||'');}catch(e){return '';}
}
function fail(name){try{MuDownloads.fail(name||'');}catch(e){}}
function send(blob,name,mime){
var reader=new FileReader();
reader.onload=function(){
var s=String(reader.result||'');
MuDownloads.save(s.substring(s.indexOf(',')+1),name||'',mime||blob.type||'');
};
reader.onerror=function(){fail(name);};
reader.readAsDataURL(blob);
}
function fetchAndSend(href,name){
fetch(href,{credentials:'include'}).then(function(r){
if(!r.ok)throw new Error('HTTP '+r.status);
var n=fileName(r.headers.get('content-disposition'),name||lastSegment(href));
var mime=(r.headers.get('content-type')||'').split(';')[0].trim();
return r.blob().then(function(b){send(b,n,mime||b.type);});
}).catch(function(){fail(name);});
}
function handle(a){
var href=a.href||'',name=a.getAttribute('download')||'';
if(href.indexOf('blob:')===0){
var blob=blobs[href];
if(blob)send(blob,name,blob.type);
else fetchAndSend(href,name);
return true;
}
if(href.indexOf('data:')===0){fetchAndSend(href,name);return true;}
// Cross-origin links can't be read here (CORS); they fall through to the
// WebView's DownloadListener instead.
if(/^https?:/i.test(href)&&a.origin===location.origin){
MuDownloads.started(name);
fetchAndSend(href,name);
return true;
}
return false;
}
document.addEventListener('click',function(e){
var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;
if(a&&handle(a))e.preventDefault();
},true);
})();
