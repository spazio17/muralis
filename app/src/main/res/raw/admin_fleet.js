// The Fleet page (redesigned 2026-10-25). Its head opens Add a panel, a dialog that opens as a
// section of the page through its address without scripting. On Panels: the chips, the search
// and the group menu filter the list in place (the chip is kept in the address); List or
// Pictures; ticking rows, or Select all, shows what can be done to all of them, Announce (a
// dialog) included; a panel opened
// at the right asks for its picture and its network speaker once, never with the page, since
// each is a call to that panel. Group chips and the share switches apply at once. The list, the
// counts and a panel's health are drawn again by admin_stats.js when the fleet's key moves; the
// filters are applied again after that. Every click is handled here by delegation, so a region
// drawn again needs nothing bound anew.
(function(){
var DEADLINE=8000;
function all(selector,root){return Array.prototype.slice.call((root||document).querySelectorAll(selector));}
function post(url,body){return fetch(url,{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:body});}
function enc(pairs){return Object.keys(pairs).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(pairs[k]);}).join('&');}
function soon(){if(window.muralisPollSoon){window.muralisPollSoon();}}

// Dialogs.
function openDialog(id){var d=document.getElementById(id);if(!d||!d.showModal){return false;}
if(!d.open){d.showModal();}
if(id==='announce'&&window.muralisSpeakersLoad){window.muralisSpeakersLoad();}
return true;}
document.addEventListener('click',function(e){
var opener=e.target.closest('[data-dialog]');
if(opener&&openDialog(opener.getAttribute('data-dialog'))){e.preventDefault();return;}
var closer=e.target.closest('dialog [data-close]');
if(closer){e.preventDefault();closer.closest('dialog').close();}});
if(location.hash.length>1&&document.getElementById(location.hash.slice(1))&&document.getElementById(location.hash.slice(1)).tagName==='DIALOG'){
openDialog(location.hash.slice(1));if(history.replaceState){history.replaceState(null,'',location.pathname+location.search);}}
all('#add-panel .segmented button').forEach(function(b){b.addEventListener('click',function(){
var way=b.getAttribute('data-way');
all('#add-panel .segmented button').forEach(function(o){o.setAttribute('aria-pressed',String(o===b));});
all('#add-panel [data-way-pane]').forEach(function(p){p.hidden=p.getAttribute('data-way-pane')!==way;});});});
var firstWay=document.querySelector('#add-panel .segmented button');if(firstWay){firstWay.click();}

// Filters: health chip, search, group.
var show=(new URLSearchParams(location.search)).get('show')||'all';
function apply(){
var text=(document.getElementById('fleet-search')||{value:''}).value.trim().toLowerCase();
var menu=document.getElementById('fleet-group-menu'),group=menu?menu.value:'';
all('#fleet-tools .fchip').forEach(function(c){if(c.getAttribute('data-show')===show){c.setAttribute('aria-current','true');}else{c.removeAttribute('aria-current');}});
all('li.fleetrow').forEach(function(li){
var on=(show==='all'||li.getAttribute('data-health')===show)
&&(!text||li.getAttribute('data-name').indexOf(text)>=0)
&&(!group||li.getAttribute('data-groups').indexOf('|'+group+'|')>=0);
li.hidden=!on;});
if(pictures){all('li.fleetrow:not([hidden])').forEach(rowPicture);}}
document.addEventListener('click',function(e){var chip=e.target.closest('#fleet-tools .fchip');if(!chip){return;}
e.preventDefault();show=chip.getAttribute('data-show');
if(history.replaceState){var q=new URLSearchParams(location.search);if(show==='all'){q.delete('show');}else{q.set('show',show);}
var s=q.toString();history.replaceState(null,'',location.pathname+(s?'?'+s:''));}
apply();});
var search=document.getElementById('fleet-search');if(search){search.addEventListener('input',apply);}
var menu=document.getElementById('fleet-group-menu');if(menu){menu.addEventListener('change',apply);}

// Pictures: one small picture under every row shown, asked once.
var pictures=false;
function shoot(img,hint,id,taken){
if(hint){hint.textContent='Asking the panel...';hint.hidden=false;}
var control=window.AbortController?new AbortController():null;
var timer=control?setTimeout(function(){control.abort();},DEADLINE):null;
function fail(text){if(hint){hint.textContent=text||'No picture: the panel did not answer';hint.hidden=false;}img.hidden=true;}
return fetch('/api/fleet/screenshot?panel='+encodeURIComponent(id)+'&t='+Date.now(),
{credentials:'same-origin',cache:'no-store',signal:control?control.signal:undefined})
.then(function(r){if(timer){clearTimeout(timer);}
if(r.ok){return r.blob().then(function(blob){
if(img.src&&img.src.indexOf('blob:')===0){URL.revokeObjectURL(img.src);}
img.src=URL.createObjectURL(blob);img.hidden=false;if(hint){hint.hidden=true;}
if(taken){var d=new Date();taken.textContent='Taken '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);}});}
return r.text().then(function(text){var reason='';try{reason=JSON.parse(text).detail||'';}catch(ignored){}fail(reason);});})
.catch(function(){if(timer){clearTimeout(timer);}fail('');});}
function rowPicture(li){if(li.querySelector('img.rowshot')){return;}
var img=document.createElement('img');img.className='rowshot';img.alt='';img.hidden=true;li.appendChild(img);
shoot(img,null,li.getAttribute('data-id'),null);}
all('#fleet-tools [data-view]').forEach(function(b){b.addEventListener('click',function(){
pictures=b.getAttribute('data-view')==='pictures';
all('#fleet-tools [data-view]').forEach(function(o){o.setAttribute('aria-pressed',String(o===b));});
var rows=document.getElementById('fleet-rows');if(rows){rows.classList.toggle('pictures',pictures);}
apply();});});

// A panel opened at the right: its picture and its speaker, once each time it is opened.
// Only a panel on screen is asked: a phone shows the list alone, and its panes are not seen.
function opened(pane){if(!pane||pane.hidden||pane.dataset.asked||pane.offsetParent===null){return;}
pane.dataset.asked='1';
var box=pane.querySelector('.fleet-shot');
if(box){shoot(box.querySelector('img'),box.querySelector('p'),box.getAttribute('data-panel'),box.querySelector('[data-taken]'));}
var speaker=pane.querySelector('.pspeaker');if(speaker){speakerLoad(speaker);}}
all('.ld .detail>.pane').forEach(function(pane){
opened(pane);
if(window.MutationObserver){new MutationObserver(function(){if(pane.hidden){delete pane.dataset.asked;}else{opened(pane);}})
.observe(pane,{attributes:true,attributeFilter:['hidden']});}});
document.addEventListener('click',function(e){var b=e.target.closest('[data-shot]');if(!b){return;}
var box=b.closest('.fleet-shot');shoot(box.querySelector('img'),box.querySelector('p'),b.getAttribute('data-shot'),box.querySelector('[data-taken]'));});

// A panel's network speaker: its state, switch and volume, for that panel alone.
function speakerPaint(el,p){var state=el.querySelector('[data-state]'),vol=el.querySelector('[data-volume]'),sw=el.querySelector('[data-switch]');
if(!p){state.textContent='Not reached';vol.disabled=true;sw.disabled=true;return;}
if(p.too_old){state.textContent='Its Muralis is too old for this';vol.disabled=true;sw.disabled=true;return;}
if(!p.reached){state.textContent='Not reached';vol.disabled=true;sw.disabled=true;return;}
state.textContent=!p.on?'Speaker off':p.playing?'Playing':'Idle';
sw.disabled=false;sw.checked=!!p.on;vol.disabled=!p.on;if(p.volume>=0&&document.activeElement!==vol){vol.value=p.volume;}}
function speakerLoad(el){var id=el.getAttribute('data-panel');
fetch('/api/fleet/speaker?panel='+encodeURIComponent(id),{credentials:'same-origin',cache:'no-store'})
.then(function(r){return r.json();}).then(function(j){speakerPaint(el,(j.panels||[])[0]);})
.catch(function(){speakerPaint(el,null);});}
document.addEventListener('change',function(e){var el=e.target.closest('.pspeaker');if(!el){return;}
var id=el.getAttribute('data-panel'),target='panels:'+id;
var body=e.target.hasAttribute('data-switch')?enc({target:target,action:'switch',value:e.target.checked?'on':'off'})
:enc({target:target,action:'volume',value:e.target.value});
post('/api/fleet/speaker',body).then(function(){setTimeout(function(){speakerLoad(el);},800);});});

// Identify one panel or all; the answer under the panel, or under the head for all.
function tell(line,text){if(!line){return;}line.textContent=text;line.hidden=!text;
if(line.timer){clearTimeout(line.timer);}
if(text){line.timer=setTimeout(function(){if(line.textContent===text){line.textContent='';line.hidden=true;}},8000);}}
function identify(id,line){return post('/api/fleet/identify',enc({panel:id}))
.then(function(r){return r.text();}).then(function(text){var parsed={};try{parsed=JSON.parse(text);}catch(ignored){}
if(parsed.status==='accepted'){tell(line,id==='all'?parsed.detail:'');return true;}
tell(line,'Not done: '+(parsed.detail||'the panel refused it')+'.');return false;})
.catch(function(){tell(line,'The panel did not answer. It may be rebooting or off the network.');return false;});}
document.addEventListener('click',function(e){var b=e.target.closest('[data-identify]');if(!b){return;}
var id=b.getAttribute('data-identify');
var pane=document.querySelector('.fleetpanel[data-panel="'+(window.CSS&&CSS.escape?CSS.escape(id):id)+'"]');
var line=id==='all'?document.getElementById('fleet-identify-all'):pane?pane.querySelector('[data-identified]'):null;
b.disabled=true;identify(id,line).then(function(){b.disabled=false;});});

// A panel's settings opened, or Muralis left there for its home screen, said under it.
document.addEventListener('click',function(e){var b=e.target.closest('[data-panel-action]');if(!b){return;}
var pane=b.closest('.fleetpanel'),line=pane?pane.querySelector('[data-identified]'):null;b.disabled=true;
post('/api/fleet/panel',enc({panel:b.getAttribute('data-panel'),action:b.getAttribute('data-panel-action')}))
.then(function(r){return r.text();}).then(function(text){var parsed={};try{parsed=JSON.parse(text);}catch(ignored){}
tell(line,parsed.status==='accepted'?'':'Not done: '+(parsed.detail||'the panel refused it')+'.');})
.catch(function(){tell(line,'The panel did not answer. It may be rebooting or off the network.');})
.then(function(){b.disabled=false;});});

// Group chips and the share switches apply the moment they change.
document.addEventListener('change',function(e){
if(e.target.matches('input[data-submit]')&&e.target.form){e.target.form.submit();return;}
if(!e.target.matches('input[data-at-once]')){return;}
var form=e.target.form;
post(form.action,new URLSearchParams(new FormData(form)).toString())
.then(function(r){if(r.ok){all('input[data-at-once]',form).forEach(function(c){c.defaultChecked=c.checked;});soon();return;}
// Refused: the boxes go back, and the reason the page gives is said under the panel.
all('input[data-at-once]',form).forEach(function(c){c.checked=c.defaultChecked;});
return r.text().then(function(html){var page=document.createElement('template');page.innerHTML=html;
var notice=page.content.querySelector('.notice');var pane=form.closest('.fleetpanel');
tell(pane?pane.querySelector('[data-identified]'):null,notice?notice.textContent:'Not done.');});})
.catch(function(){all('input[data-at-once]',form).forEach(function(c){c.checked=c.defaultChecked;});});});

// The selection: what can be done to all the ticked panels.
var bar=document.getElementById('fleet-selbar'),tools=document.getElementById('fleet-tools');
function ticked(){return all('li.fleetrow:not([hidden]) input[data-tick]:checked');}
var tickAll=document.getElementById('fleet-tick-all');
function selection(){if(!bar){return;}var n=ticked().length;
bar.hidden=n===0;if(tools){tools.hidden=n>0;}
document.getElementById('fleet-selcount').textContent=n+' selected';
if(tickAll){var shown=all('li.fleetrow:not([hidden]) input[data-tick]').length;tickAll.checked=n>0&&n===shown;tickAll.indeterminate=n>0&&n<shown;}}
if(tickAll){tickAll.addEventListener('change',function(){var on=tickAll.checked;
all('li.fleetrow:not([hidden]) input[data-tick]').forEach(function(c){c.checked=on;});selection();});}
document.addEventListener('change',function(e){if(e.target.matches('input[data-tick]')){selection();}});
function clear(){all('input[data-tick]').forEach(function(c){c.checked=false;});selection();}
function say(text){tell(document.getElementById('fleet-sel-result'),text);}
document.addEventListener('click',function(e){var b=e.target.closest('[data-sel]');if(!b){return;}
var what=b.getAttribute('data-sel'),boxes=ticked();
var ids=boxes.map(function(c){return c.closest('li').getAttribute('data-id');});
var keys=boxes.filter(function(c){return !c.hasAttribute('data-self');}).map(function(c){return c.value;});
if(what==='close'){clear();return;}
if(what==='identify'){Promise.all(ids.map(function(id){return identify(id,null);})).then(function(done){
var missed=done.filter(function(ok){return !ok;}).length;
say(missed?'Identify reached '+(done.length-missed)+' of '+done.length+' panels.':'');});return;}
if(what==='say'){if(!openDialog('announce')){return;}
var choose=document.querySelector('#speakers .segmented [data-target=panels]');if(choose){choose.click();}
all('#speakers-list li.speaker').forEach(function(li){var t=li.querySelector('[data-tick]');if(t){t.checked=ids.indexOf(li.getAttribute('data-id'))>=0;}});return;}
if(what==='group'){var group=document.getElementById('fleet-sel-group').value;
post('/fleet',enc({what:'add_to_group',group:group,panels:boxes.map(function(c){return c.value;}).join(','),'return':'/fleet'}))
.then(function(r){if(r.ok){clear();soon();}else{say('Not done: the panels were not added to the group.');}});return;}
if(what==='remove'){var skipped=boxes.length-keys.length;
if(!keys.length){say('This panel leaves with Leave, in its settings, not with Remove.');return;}
if(!window.confirm('Remove '+(keys.length===1?'1 panel':keys.length+' panels')+'?\n\nThey are taken off the fleet\'s list on every panel and their keys are no longer trusted. They can join again with a new code.'+(skipped?'\n\nThis panel is not removed: it leaves with Leave.':''))){return;}
post('/fleet',enc({what:'remove_panels',panels:keys.join(','),'return':'/fleet'}))
.then(function(r){if(r.ok){location.href='/fleet';}else{say('Not done: the panels were not removed.');}});}});

// After admin_stats.js draws a region again: the filters, the pictures and the selection bar.
document.addEventListener('muralis-lists',function(){apply();selection();});
// A wide screen grown from a narrow one shows the chosen panel: it is asked then.
var wide=window.matchMedia('(min-width:840px)');
if(wide.addEventListener){wide.addEventListener('change',function(){all('.ld .detail>.pane').forEach(opened);});}
apply();
})();
