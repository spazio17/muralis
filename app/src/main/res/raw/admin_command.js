// Sends every command form in the background and reports the JSON result inline. Without this
// the browser navigates to the raw /api/command response and the operator has to press Back
// after each action. The forms keep working unchanged if scripting is unavailable, so this is
// an enhancement rather than the only path. Basic-auth credentials ride along because the
// request is same-origin.
// Runs every command form in the background and reports the result inline, so no press ever
// navigates away from the page. Also drives the brightness slider, which applies as it moves
// rather than needing a separate button. The plain forms keep working without scripting.
(function(){
// No on-page status line. It used to print every command and its result at the bottom of
// the page ("display.auto_brightness: accepted"), which is developer output rather than
// something an operator adjusting a wall panel needs to read. Removed at the user's
// request 2026-08-19. Logged to the browser console instead, so a rejection is still
// diagnosable rather than silently vanishing.
function show(text,ok){if(!ok&&window.console){console.warn('Muralis: '+text);}else if(window.console){console.log('Muralis: '+text);}}
// A refusal, though, is said on the page, under the button that was pressed: the same banner
// the playlist page uses, red, with a cross, gone by itself after five seconds. Until
// 2026-09-24 a refused Preview went to the console alone, and a person who pressed it while
// the panel's settings were open saw a button that did nothing (Juri: "the preview does not
// work"). A success still says nothing: the panel itself is the answer. A control that is not
// a form (the brightness slider, the orientation menu) is marked the way admin_setting.js
// marks a refused setting, red border and the reason as its tooltip.
function tell(source,message){
if(!source){return;}
if(source.tagName!=='FORM'){source.classList.toggle('check-bad',!!message);source.title=message||'';return;}
var host=(source.closest&&source.closest('.actions'))||source;
var banner=host.nextElementSibling;
if(!message){if(banner&&banner.classList.contains('banner')){banner.remove();}return;}
if(!banner||!banner.classList.contains('banner')){
banner=document.createElement('p');banner.className='banner bad';banner.setAttribute('role','alert');
banner.innerHTML='<span></span><button type="button" class="dismiss" aria-label="Close">&times;</button>';
banner.querySelector('.dismiss').addEventListener('click',function(){banner.remove();});
host.parentNode.insertBefore(banner,host.nextSibling);}
banner.firstChild.textContent=message;
if(banner.timer){clearTimeout(banner.timer);}
banner.timer=setTimeout(function(){banner.remove();},5000);}
function encode(form){var parts=[];
Array.prototype.forEach.call(form.elements,function(el){
if(el.name){parts.push(encodeURIComponent(el.name)+'='+encodeURIComponent(el.value));}});
return parts.join('&');}
// The source is the form or control the command came from, for tell(); the console line
// carries the label either way.
function send(query,label,source){
show(label+': sending...',true);
return fetch('/api/command',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:query})
.then(function(r){return r.text();})
.then(function(text){var detail=text,ok=true,reason='';
try{var parsed=JSON.parse(text);
detail=parsed.status+(parsed.detail?': '+parsed.detail:'');
ok=parsed.status!=='rejected';reason=parsed.detail||'';}catch(ignored){}
show(label+': '+detail,ok);
tell(source,ok?'':'Not done: '+(reason||'the panel refused it')+'.');})
.catch(function(){show(label+': no response. The device may be rebooting, shutting down, or off the network',false);
tell(source,'The panel did not answer. It may be rebooting or off the network.');});}
Array.prototype.forEach.call(document.querySelectorAll('form.cmd'),function(form){
form.addEventListener('submit',function(event){
event.preventDefault();
send(encode(form),form.elements.cmnd.value,form);});});
// The Dashboard box's second button. It would otherwise submit the whole box to its own
// formaction, which is the scripting-free path; here the click is caught and only the
// address is sent, so the result lands in the console like every other command and the
// page stays put. Nothing is saved either way: that is the Save button's job.
var once=document.getElementById('open-once');
if(once){once.addEventListener('click',function(event){
event.preventDefault();
var box=once.form&&once.form.elements.dashboard_url;
send('cmnd=kiosk.open_url&url='+encodeURIComponent(box?box.value:''),'kiosk.open_url',once.form);});}
var auto=document.getElementById('auto-brightness');
if(auto){auto.addEventListener('change',function(){
// Marked as user-driven so the stats poll does not fight the operator: without this, a
// poll landing between the click and the tablet applying the change would snap the box
// back and look like the click was ignored.
auto.dataset.pending='1';
send('cmnd=display.auto_brightness&enabled='+(auto.checked?'1':'0'),'display.auto_brightness',auto)
.then(function(){setTimeout(function(){delete auto.dataset.pending;},1500);});});}
var orientation=document.getElementById('orientation');
if(orientation){orientation.addEventListener('change',function(){
orientation.dataset.pending='1';
send('cmnd=display.orientation&value='+encodeURIComponent(orientation.value),'display.orientation',orientation)
.then(function(){setTimeout(function(){delete orientation.dataset.pending;},1500);});});}
var slider=document.getElementById('brightness');
if(slider){var label=document.getElementById('brightness-value'),timer=null;
slider.addEventListener('input',function(){
label.textContent=slider.value+'%';
// Without this the five-second poll would yank the thumb back mid-drag.
slider.dataset.pending='1';
clearTimeout(timer);
// Debounced: dragging fires continuously and each command reaches the tablet.
timer=setTimeout(function(){
send('cmnd=display.brightness&percent='+slider.value,'display.brightness',slider)
.then(function(){setTimeout(function(){delete slider.dataset.pending;},1500);});},250);});}
})();
