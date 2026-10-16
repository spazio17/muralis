// The Fleet page's Screenshots and Identify (block 16). Screenshots asks every panel's
// picture at once, each through /api/fleet/screenshot, which this panel answers from its own
// capture or fetches from the other panel's member endpoint; the pictures load only on the
// press, never with the page, since each one is a capture on that wall. A panel that does
// not answer within the deadline says so in its area, and never keeps the picture from
// before. The cache-busting query keeps the browser from showing an old picture.
// Identify posts /api/fleet/identify for one panel or all, and says a refusal under the
// button, as the command forms do.
(function(){
var DEADLINE=8000;
var shots=document.getElementById('fleet-shots');
function area(id){return document.querySelector('.fleet-shot[data-panel="'+id+'"]');}
function say(box,text){var img=box.querySelector('img'),hint=box.querySelector('p');
if(img.src&&img.src.indexOf('blob:')===0){URL.revokeObjectURL(img.src);}
img.removeAttribute('src');img.hidden=true;hint.textContent=text;hint.hidden=false;}
function fetchOne(box){
var id=box.getAttribute('data-panel'),hint=box.querySelector('p'),img=box.querySelector('img');
hint.textContent='Asking the panel...';hint.hidden=false;img.hidden=true;
var control=window.AbortController?new AbortController():null;
var timer=control?setTimeout(function(){control.abort();},DEADLINE):null;
return fetch('/api/fleet/screenshot?panel='+encodeURIComponent(id)+'&t='+Date.now(),
{credentials:'same-origin',cache:'no-store',signal:control?control.signal:undefined})
.then(function(r){if(timer){clearTimeout(timer);}
if(r.ok){return r.blob().then(function(blob){
if(img.src&&img.src.indexOf('blob:')===0){URL.revokeObjectURL(img.src);}
img.src=URL.createObjectURL(blob);img.hidden=false;hint.hidden=true;});}
return r.text().then(function(text){var reason='';
try{reason=JSON.parse(text).detail||'';}catch(ignored){}
say(box,reason||'No picture: the panel did not answer');});})
.catch(function(){if(timer){clearTimeout(timer);}say(box,'No picture: the panel did not answer');});}
if(shots){shots.addEventListener('click',function(){
shots.disabled=true;
var boxes=Array.prototype.slice.call(document.querySelectorAll('.fleet-shot'));
Promise.all(boxes.map(fetchOne)).then(function(){shots.disabled=false;},function(){shots.disabled=false;});});}
function tell(button,text){
var id=button.getAttribute('data-identify'),box=id==='all'?null:area(id);
var line=id==='all'?document.getElementById('fleet-identify-all'):(box?box.querySelector('p'):null);
if(!line){return;}
line.textContent=text;line.hidden=!text;
if(line.timer){clearTimeout(line.timer);}
if(text){line.timer=setTimeout(function(){if(line.textContent===text){line.textContent='';line.hidden=true;}},8000);}}
Array.prototype.forEach.call(document.querySelectorAll('[data-identify]'),function(button){
button.addEventListener('click',function(){
var id=button.getAttribute('data-identify');
button.disabled=true;
fetch('/api/fleet/identify',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:'panel='+encodeURIComponent(id)})
.then(function(r){return r.text();})
.then(function(text){var parsed={};try{parsed=JSON.parse(text);}catch(ignored){}
button.disabled=false;
if(parsed.status==='accepted'&&id!=='all'){tell(button,'');return;}
tell(button,parsed.status==='accepted'?parsed.detail:'Not done: '+(parsed.detail||'the panel refused it')+'.');})
.catch(function(){button.disabled=false;tell(button,'The panel did not answer. It may be rebooting or off the network.');});});});
})();
// The Fleet page's share switches post their form the moment they change, as the panel's do;
// the Save beside each is for a browser without scripting.
Array.prototype.forEach.call(document.querySelectorAll('input[data-submit]'),function(box){
box.addEventListener('change',function(){if(box.form){box.form.submit();}});});
