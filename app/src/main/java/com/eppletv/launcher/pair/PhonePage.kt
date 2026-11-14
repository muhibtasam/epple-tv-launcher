package com.eppletv.launcher.pair

/**
 * The page the phone opens from the QR code. Self-contained: no external fonts, scripts, styles or images, so the
 * phone talks to nothing but the TV. The only inline script and style carry the per-response CSP nonce.
 *
 * Flow: the token is read from the address and immediately removed from it (history.replaceState), the page asks to
 * pair and shows the 4-digit code, then polls until the TV owner chooses Allow. The session secret and the
 * single-use nonce live only in this page's memory (never in storage, cookies or URLs). Keys are sent as a form
 * body; pictures are shrunk on the phone to at most 1920x1080 cover size and sent one by one as JPEG.
 * The page contains no user data, so it is a constant apart from the nonce.
 */
object PhonePage {
    fun html(nonce: String): String = HEAD.replace("NONCE", nonce) + BODY + SCRIPT.replace("NONCE", nonce)

    private val HEAD = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="dark">
<title>Epple TV setup</title>
<style nonce="NONCE">
*{box-sizing:border-box}
body{margin:0;background:#0b0c10;color:#f2f3f5;font:16px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;
 padding:max(24px,env(safe-area-inset-top)) max(18px,env(safe-area-inset-right)) max(40px,env(safe-area-inset-bottom)) max(18px,env(safe-area-inset-left))}
main{max-width:540px;margin:0 auto}
h1{font-size:28px;letter-spacing:-.02em;margin:4px 0 6px}
h2{font-size:19px;margin:0 0 4px}
p{margin:6px 0;color:#aeb3bd}
.card{background:#16181f;border:1px solid #262a33;border-radius:18px;padding:18px;margin:16px 0}
.code{font:700 54px/1 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.18em;text-align:center;margin:18px 0 10px;color:#fff}
.hide{display:none!important}
label{display:block;font-weight:600;margin:16px 0 6px}
.row{display:flex;gap:8px}
input[type=password],input[type=text]{flex:1;min-width:0;font-size:16px;padding:13px 14px;border-radius:12px;border:1px solid #333845;background:#0f1116;color:#fff}
input:focus{outline:2px solid #6aa8ff;outline-offset:1px}
button,.pick{font-family:inherit;font-size:16px;font-weight:600;line-height:1;border:0;border-radius:12px;padding:14px 18px;background:#2a2f3a;color:#fff;cursor:pointer}
button.main,.pick{background:#f2f3f5;color:#0b0c10;width:100%;margin-top:18px;display:block;text-align:center}
button:disabled{opacity:.5}
.small{font-size:14px;color:#8d93a0}
a{color:#8fbcff}
.st{font-size:14px;margin-top:6px;min-height:1px}
.ok{color:#7ce38b}.no{color:#ff8a80}.wait{color:#ffb86b}
#files{position:absolute;width:1px;height:1px;opacity:0}
ul{padding-left:20px;margin:8px 0;color:#aeb3bd;word-break:break-word}
</style></head>
"""

    private val BODY = """<body><main>
<h1>Epple TV</h1>
<p id="lead">Connecting to your TV…</p>

<section id="pairing" class="card hide">
 <h2>Check the code</h2>
 <div class="code" id="code">····</div>
 <p>Your TV shows the same four digits. Choose <b>Allow</b> on the TV to continue.</p>
 <p class="small">If the codes don't match, choose Deny on the TV.</p>
</section>

<section id="ended" class="card hide">
 <h2 id="endTitle">This code has ended</h2>
 <p id="endText">Choose New Code on your TV and scan again.</p>
</section>

<form id="keys" class="card hide" autocomplete="off">
 <h2>Content providers</h2>
 <p>All optional. Fill in only the keys you have; the TV tests each one before saving it. Keys go only to your TV, over your home network.</p>

 <label for="tmdb">TMDB API key</label>
 <div class="row"><input id="tmdb" name="tmdb" type="password" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="400" placeholder="API key or read access token"><button type="button" class="eye" data-for="tmdb">Show</button></div>
 <div class="small">Movies, TV Shows, Search and artwork. <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener noreferrer">How to get it</a></div>
 <div class="st" id="st-tmdb"></div>

 <label for="watchmode">Watchmode API key</label>
 <div class="row"><input id="watchmode" name="watchmode" type="password" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="400"><button type="button" class="eye" data-for="watchmode">Show</button></div>
 <div class="small">New releases on your services. <a href="https://api.watchmode.com/" target="_blank" rel="noopener noreferrer">How to get it</a></div>
 <div class="st" id="st-watchmode"></div>

 <label for="trakt">Trakt Client ID</label>
 <div class="row"><input id="trakt" name="trakt" type="password" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="400"><button type="button" class="eye" data-for="trakt">Show</button></div>
 <div class="small">What's trending. Create an API app on Trakt and copy its <b>Client ID</b>, never the secret. <a href="https://trakt.tv/oauth/applications" target="_blank" rel="noopener noreferrer">How to get it</a></div>
 <div class="st" id="st-trakt"></div>

 <label for="youtube">YouTube Data API key</label>
 <div class="row"><input id="youtube" name="youtube" type="password" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="400"><button type="button" class="eye" data-for="youtube">Show</button></div>
 <div class="small">Trending videos. Enable “YouTube Data API v3”, then create an API key. <a href="https://console.cloud.google.com/apis/credentials" target="_blank" rel="noopener noreferrer">How to get it</a></div>
 <div class="st" id="st-youtube"></div>

 <button type="submit" class="main" id="save">Save to TV</button>
 <div class="st" id="saveSt"></div>
</form>

<section id="photos" class="card hide">
 <h2>Home wallpapers</h2>
 <p>Pictures for your TV's Home screen. This phone resizes them and sends them only to your TV.</p>
 <label class="pick" for="files">Choose Pictures</label>
 <input id="files" type="file" accept="image/*" multiple>
 <div class="st" id="photoSt"></div>
 <ul id="photoList"></ul>
</section>

<p id="done" class="small hide">When you're finished, choose <b>Done</b> on your TV. This page stops working when the TV closes setup.</p>
</main>
"""

    private val SCRIPT = """<script nonce="NONCE">
(function(){
'use strict';
var parts=location.pathname.split('/');
var token=parts.length===3&&parts[1]==='p'?parts[2]:'';
try{history.replaceState(null,'','/');}catch(e){}
var secret=null,nonce=null,busy=false,timer=null,over=false,misses=0;
function el(id){return document.getElementById(id);}
function show(id,on){el(id).classList.toggle('hide',!on);}
function say(id,text,cls){var e=el(id);e.textContent=text;e.className='st'+(cls?' '+cls:'');}
function end(title,text){
 over=true;if(timer)clearTimeout(timer);secret=null;nonce=null;
 ['pairing','keys','photos','done'].forEach(function(s){show(s,false);});
 FIELDS.forEach(function(f){el(f).value='';});
 el('lead').textContent='';el('endTitle').textContent=title;el('endText').textContent=text;show('ended',true);
}
var GONE=['This code has ended','Choose New Code on your TV and scan again.'];
function poll(ms){if(!over)timer=setTimeout(state,ms);}
function state(){
 fetch('/s/state',{headers:{'X-Epple-Session':secret},cache:'no-store'}).then(function(r){
  misses=0;
  if(r.status===403||r.status===410){end(GONE[0],GONE[1]);return null;}
  return r.json();
 }).then(function(j){
  if(!j)return;
  if(j.state==='confirming'){poll(1500);return;}
  if(j.state==='paired'){
   if(!busy&&j.nonce)nonce=j.nonce;
   if(el('keys').classList.contains('hide')){
    show('pairing',false);show('keys',true);show('photos',true);show('done',true);
    el('lead').textContent='Connected to your TV.';
   }
   poll(5000);return;
  }
  if(j.state==='denied'){end('Not allowed','The TV refused this phone. Choose New Code on the TV to try again.');return;}
  if(j.state==='done'){end('All done','Setup is finished on your TV. You can close this page.');return;}
  end(GONE[0],GONE[1]);
 }).catch(function(){
  if(++misses>=3){end('Setup closed','Your TV closed setup or left the network. To continue, choose Add from Phone or New Code on the TV.');return;}
  poll(3000);
 });
}
var FIELDS=['tmdb','watchmode','trakt','youtube'];
function start(){
 if(!token){end(GONE[0],GONE[1]);return;}
 fetch('/p/'+token+'/pair',{method:'POST',cache:'no-store'}).then(function(r){
  if(r.status===409){end('Already connected','Another phone is using this code. Choose New Code on the TV to use this phone.');return null;}
  if(!r.ok){end(GONE[0],GONE[1]);return null;}
  return r.json();
 }).then(function(j){
  if(!j)return;
  token='';secret=j.secret;el('code').textContent=j.code;
  el('lead').textContent='Almost there.';show('pairing',true);poll(1200);
 }).catch(function(){end('Can’t reach your TV','Make sure this phone is on the same Wi-Fi as the TV, then scan the code again.');});
}
Array.prototype.forEach.call(document.querySelectorAll('.eye'),function(b){
 b.addEventListener('click',function(){var i=el(b.getAttribute('data-for'));var hidden=i.type==='password';i.type=hidden?'text':'password';b.textContent=hidden?'Hide':'Show';});
});
function claim(){if(busy||!nonce)return null;busy=true;var n=nonce;nonce=null;return n;}
function release(j){busy=false;if(j&&j.nonce)nonce=j.nonce;}
function failed(r){
 if(r.status===429)return 'Limit reached for this code. Choose New Code on the TV to send more.';
 if(r.status===409)return 'Please try again.';
 if(r.status===403||r.status===410){end(GONE[0],GONE[1]);return '';}
 return 'The TV couldn’t take that ('+r.status+').';
}
el('keys').addEventListener('submit',function(ev){
 ev.preventDefault();
 var body=new URLSearchParams(),any=false;
 FIELDS.forEach(function(f){var v=el(f).value.trim();say('st-'+f,'');if(v){body.append(f,v);any=true;}});
 if(!any){say('saveSt','Enter at least one key, or skip this part.','wait');return;}
 var n=claim();if(!n){say('saveSt','One moment, then try again.','wait');return;}
 body.append('nonce',n);
 el('save').disabled=true;say('saveSt','Testing keys on the TV…','wait');
 fetch('/s/save',{method:'POST',headers:{'X-Epple-Session':secret},body:body,cache:'no-store'}).then(function(r){
  if(!r.ok){release(null);var m=failed(r);if(m)say('saveSt',m,'no');return null;}
  return r.json();
 }).then(function(j){
  if(!j)return;release(j);
  var good=0;
  j.results.forEach(function(x){
   if(FIELDS.indexOf(x.field)<0)return;
   say('st-'+x.field,(x.ok?'✓ ':'✕ ')+x.message,x.ok?'ok':'no');
   if(x.ok){good++;el(x.field).value='';}
  });
  say('saveSt',good?'Saved on your TV.':'Nothing was saved.',good?'ok':'no');
 }).catch(function(){release(null);say('saveSt','Lost the connection to your TV.','no');})
 .then(function(){el('save').disabled=false;});
});
function shrink(file){
 return new Promise(function(ok,fail){
  var url=URL.createObjectURL(file),img=new Image();
  img.onload=function(){
   var w=img.naturalWidth,h=img.naturalHeight,k=Math.min(1,Math.max(1920/w,1080/h));
   var c=document.createElement('canvas');c.width=Math.max(1,Math.round(w*k));c.height=Math.max(1,Math.round(h*k));
   c.getContext('2d').drawImage(img,0,0,c.width,c.height);URL.revokeObjectURL(url);
   c.toBlob(function(b){if(b)ok(b);else fail();},'image/jpeg',0.88);
  };
  img.onerror=function(){URL.revokeObjectURL(url);fail();};
  img.src=url;
 });
}
function line(text){var li=document.createElement('li');li.textContent=text;el('photoList').appendChild(li);return li;}
function wait(ms){return new Promise(function(r){setTimeout(r,ms);});}
function send(b,li,name,tries){
 var n=claim();
 if(!n)return tries>20?Promise.resolve(false):wait(500).then(function(){return send(b,li,name,tries+1);});
 li.textContent=name+': sending…';
 return fetch('/s/photo',{method:'POST',headers:{'X-Epple-Session':secret,'X-Epple-Nonce':n,'Content-Type':'image/jpeg'},body:b,cache:'no-store'}).then(function(r){
  if(!r.ok){release(null);var m=failed(r);li.textContent=name+': '+(m||'stopped');li.className='no';return false;}
  return r.json().then(function(j){
   release(j);var x=j.results[0]||{ok:false,message:'Not added'};
   li.textContent=name+': '+x.message;li.className=x.ok?'ok':'no';return !!x.ok;
  });
 });
}
el('files').addEventListener('change',function(){
 var list=Array.prototype.slice.call(el('files').files||[]);el('files').value='';
 if(!list.length)return;
 var added=0,i=0;
 say('photoSt','Sending '+list.length+(list.length===1?' picture…':' pictures…'),'wait');
 function next(){
  if(over)return;
  if(i>=list.length){say('photoSt',added?added+(added===1?' picture added to your TV.':' pictures added to your TV.'):'No pictures were added.',added?'ok':'no');return;}
  var f=list[i++],name=(f.name||'Picture').slice(0,60),li=line(name+': preparing…');
  shrink(f).then(function(b){
   if(b.size>6000000){li.textContent=name+': too large';li.className='no';return false;}
   return send(b,li,name,0);
  },function(){li.textContent=name+': this phone couldn’t open it';li.className='no';return false;})
  .then(function(ok){if(ok)added++;},function(){release(null);li.textContent=name+': lost the connection';li.className='no';})
  .then(next);
 }
 next();
});
start();
})();
</script></body></html>"""
}
