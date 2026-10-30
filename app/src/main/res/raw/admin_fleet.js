// The Fleet page (redesigned 2026-10-25). Its head opens Add a panel, a dialog that opens as a
// section of the page through its address without scripting. On Panels: the chips, the search
// and the group menu filter the list in place (the chip is kept in the address); List or
// Pictures; ticking rows, or Select all, shows what can be done to all of them, Announce (a
// dialog) included; a panel opened
// at the right asks for its picture and its network speaker once, never with the page, since
// each is a call to that panel. Group chips and the share switches apply at once. The list, the
// counts and a panel's health are drawn again by admin_stats.js when the fleet's key moves; the
// filters are applied again after that. Every click is handled here by delegation, so a region
// drawn again needs nothing bound anew. On Groups a group is dragged to a new place.
(function(){
var DEADLINE=8000;
function all(selector,root){return Array.prototype.slice.call((root||document).querySelectorAll(selector));}
// A shared field that applies to some choices of a menu (the dashboard's app, cameras or
// integration, Ambient's options by mode) shows while the menu holds one of them; the
// server renders the first state.
document.addEventListener('change',function(e){var m=e.target;if(!m.matches('select[name]')||!m.form){return;}
all('[data-when-key="'+m.name+'"]',m.form).forEach(function(box){box.hidden=box.getAttribute('data-when').split(' ').indexOf(m.value)<0;});});
function post(url,body){return fetch(url,{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:body});}
function enc(pairs){return Object.keys(pairs).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(pairs[k]);}).join('&');}
function soon(){if(window.muralisPollSoon){window.muralisPollSoon();}}
// The open tab in view on a phone, where the tab row scrolls sideways.
var openTab=document.querySelector('.tabs a[aria-current=page]');if(openTab&&openTab.scrollIntoView){openTab.scrollIntoView({block:'nearest',inline:'nearest'});}
var tabRow=document.querySelector('.tabs');
function tabsMore(){if(tabRow){tabRow.classList.toggle('more',tabRow.scrollLeft+tabRow.clientWidth<tabRow.scrollWidth-1);}}
if(tabRow){tabRow.addEventListener('scroll',tabsMore,{passive:true});window.addEventListener('resize',tabsMore);tabsMore();}

// This panel's own place on the page: Follow the shared settings stores at once, the switch
// going back when refused; Leave (asked first through data-confirm) then returns to the
// panel's own section.
document.addEventListener('change',function(e){var f=e.target;if(!f.matches('[data-follow]')){return;}
fetch('/api/setting',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},
body:'section=behaviour&fleet_follows='+(f.checked?'1':'0')}).then(function(r){if(!r.ok){f.checked=!f.checked;}})
.catch(function(){f.checked=!f.checked;});});
document.addEventListener('click',function(e){var b=e.target.closest('[data-leave]');if(!b){return;}
fetch('/api/fleet/leave',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:''})
.then(function(){location.href=b.getAttribute('data-leave');});});
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
// None left: a line says so, and the detail of a panel the list no longer shows goes too.
var rows=all('li.fleetrow'),shown=rows.filter(function(li){return !li.hidden;}).length;
if(rows.length){var list=rows[0].parentNode,none=document.getElementById('fleet-none');
if(!none){none=document.createElement('p');none.id='fleet-none';none.className='hint';list.parentNode.insertBefore(none,list.nextSibling);}
none.textContent=text?'No panel matches \u201c'+text+'\u201d.':'No panel matches the filter.';none.hidden=shown>0;
var detail=document.querySelector('#ld-fleet > .detail');if(detail){detail.hidden=shown===0;}}
if(pictures){all('li.fleetrow:not([hidden])').forEach(rowPicture);}
// The bar's count follows the filter.
selection();}
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

// A panel opened at the right: its picture and its settings, once each time it is opened.
// Only a panel on screen is asked: a phone shows the list alone, and its panes are not seen.
function opened(pane){if(!pane||pane.hidden||pane.dataset.asked||pane.offsetParent===null){return;}
pane.dataset.asked='1';
var box=pane.querySelector('.fleet-shot');
if(box){shoot(box.querySelector('img'),box.querySelector('p'),box.getAttribute('data-panel'),box.querySelector('[data-taken]'));}
var own=pane.querySelector('.psettings');if(own){settingsLoad(own);}}
// Its settings: each section of that panel with its summary and where it comes from; its own
// opens a page that changes it on that panel, a shared one the scope that shares it.
var SECTIONS=['dashboard','ambient','display','speaker','mqtt','web_admin'];
function esc(t){var d=document.createElement('span');d.textContent=t==null?'':String(t);return d.innerHTML;}
// Asked once more after a moment when not reached: the picture and the speaker are asked of the
// same panel as it opens, and a busy panel can miss one of three calls at once.
function settingsLoad(box,again){var id=box.getAttribute('data-panel');
function notReached(text){if(!again){setTimeout(function(){settingsLoad(box,true);},2000);return;}
box.innerHTML='<p class="hint">'+esc(text)+'</p>';}
fetch('/api/fleet/settings?panel='+encodeURIComponent(id),{credentials:'same-origin',cache:'no-store'})
.then(function(r){return r.json();}).then(function(j){var sections=j.sections;
if(!sections){notReached('Not reached: '+(j.detail||'the panel did not answer')+'.');return;}
var rows='';SECTIONS.forEach(function(key){var one=sections[key];if(!one){return;}
// A quick view (Juri, 2026-10-28): a shared section opens where it is shared; the rest is
// changed with Manage, in that panel's own pages.
var text='<span class="text"><span class="h">'+esc(one.label)+'</span><span class="s">'+esc(one.from_words)+' · '+esc(one.summary)+'</span></span>';
rows+=one.from?'<li class="two"><a class="rowlink plain" href="'+esc('/fleet?tab=shared&scope='+encodeURIComponent(one.from)+'&section='+key)+'">'+text+'</a></li>'
:'<li class="two">'+text+'</li>';});
box.innerHTML='<ul class="list seg">'+rows+'</ul>';})
.catch(function(){notReached('Not reached: the panel did not answer.');});}
all('.ld .detail>.pane').forEach(function(pane){
opened(pane);
if(window.MutationObserver){new MutationObserver(function(){if(pane.hidden){delete pane.dataset.asked;}else{opened(pane);}})
.observe(pane,{attributes:true,attributeFilter:['hidden']});}});
document.addEventListener('click',function(e){var b=e.target.closest('[data-shot]');if(!b){return;}
var box=b.closest('.fleet-shot');shoot(box.querySelector('img'),box.querySelector('p'),b.getAttribute('data-shot'),box.querySelector('[data-taken]'));});

// Identify one panel; the answer under it.
function tell(line,text){if(!line){return;}line.textContent=text;line.hidden=!text;
if(line.timer){clearTimeout(line.timer);}
if(text){line.timer=setTimeout(function(){if(line.textContent===text){line.textContent='';line.hidden=true;}},8000);}}
function identify(id,line){return post('/api/fleet/identify',enc({panel:id}))
.then(function(r){return r.text();}).then(function(text){var parsed={};try{parsed=JSON.parse(text);}catch(ignored){}
if(parsed.status==='accepted'){tell(line,'');return true;}
tell(line,'Not done: '+(parsed.detail||'the panel refused the request')+'.');return false;})
.catch(function(){tell(line,'The panel did not answer; maybe rebooting or off the network.');return false;});}
document.addEventListener('click',function(e){var b=e.target.closest('[data-identify]');if(!b){return;}
var id=b.getAttribute('data-identify');
var pane=document.querySelector('.fleetpanel[data-panel="'+(window.CSS&&CSS.escape?CSS.escape(id):id)+'"]');
var line=pane?pane.querySelector('[data-identified]'):null;
b.disabled=true;identify(id,line).then(function(){b.disabled=false;});});

// A panel's settings opened, or Muralis left there for its home screen, said under it.
document.addEventListener('click',function(e){var b=e.target.closest('[data-panel-action]');if(!b){return;}
var pane=b.closest('.fleetpanel'),line=pane?pane.querySelector('[data-identified]'):null;b.disabled=true;
post('/api/fleet/panel',enc({panel:b.getAttribute('data-panel'),action:b.getAttribute('data-panel-action')}))
.then(function(r){return r.text();}).then(function(text){var parsed={};try{parsed=JSON.parse(text);}catch(ignored){}
tell(line,parsed.status==='accepted'?'':'Not done: '+(parsed.detail||'the panel refused the request')+'.');})
.catch(function(){tell(line,'The panel did not answer; maybe rebooting or off the network.');})
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

// The list's header row, the selection bar in one: the box and the count with nothing ticked;
// with ticks Close, "2 selected", Announce, Reload and a menu with the rest of a panel's own
// menu, on the ticked panels. data-when says which parts show in which state, and the row
// turns tinted while panels are ticked.
var bar=document.getElementById('fleet-selbar');
function ticked(){return all('li.fleetrow:not([hidden]) input[data-tick]:checked');}
function shownBoxes(){return all('li.fleetrow:not([hidden]) input[data-tick]');}
var tickAll=document.getElementById('fleet-tick-all');
function selection(){if(!bar){return;}var n=ticked().length,count=shownBoxes().length;
document.getElementById('fleet-selcount').textContent=n?n+' selected':count===1?'1 panel':count+' panels';
bar.classList.toggle('selecting',n>0);
all('[data-when]',bar).forEach(function(el){el.hidden=el.getAttribute('data-when')===(n?'none':'ticked');});
if(tickAll){tickAll.checked=n>0&&n===count;tickAll.indeterminate=n>0&&n<count;}}
if(tickAll){tickAll.addEventListener('change',function(){var on=tickAll.checked;
shownBoxes().forEach(function(c){c.checked=on;});selection();});}
document.addEventListener('change',function(e){if(e.target.matches('input[data-tick]')){selection();}});
function clear(){all('input[data-tick]').forEach(function(c){c.checked=false;});selection();}
function say(text){tell(document.getElementById('fleet-sel-result'),text);}
// The bar's menu opens under its button and closes on a choice, a click elsewhere or Escape.
var selMenu=document.getElementById('fleet-sel-menu');
var selMore=selMenu?selMenu.parentNode.querySelector('[aria-controls="fleet-sel-menu"]'):null;
function closeSelMenu(back){if(!selMenu||!selMenu.classList.contains('open')){return;}
selMenu.classList.remove('open');selMore.setAttribute('aria-expanded','false');if(back){selMore.focus();}}
if(selMore){all('button',selMenu).forEach(function(b){b.setAttribute('role','menuitem');});
selMore.addEventListener('click',function(e){e.stopPropagation();
if(selMenu.classList.contains('open')){closeSelMenu(true);return;}
// System stats says what a choice does: Hide when every ticked panel shows them, else Show.
var stats=selMenu.querySelector('[data-sel^="stats_"]');if(stats){var shown=ticked().every(function(c){return c.closest('li').getAttribute('data-stats')==='1';});
stats.setAttribute('data-sel',shown?'stats_off':'stats_on');stats.lastChild.textContent=shown?'Hide system stats':'Show system stats';}
selMenu.classList.add('open');selMore.setAttribute('aria-expanded','true');selMenu.querySelector('button').focus();});
selMenu.addEventListener('keydown',function(e){var list=all('button',selMenu),at=list.indexOf(document.activeElement);
if(e.key==='ArrowDown'){e.preventDefault();list[(at+1)%list.length].focus();}
else if(e.key==='ArrowUp'){e.preventDefault();list[(at+list.length-1)%list.length].focus();}
else if(e.key==='Escape'){e.preventDefault();closeSelMenu(true);}
else if(e.key==='Tab'){closeSelMenu(false);}});
document.addEventListener('click',function(e){if(!selMenu.contains(e.target)){closeSelMenu(false);}});}
document.addEventListener('click',function(e){var b=e.target.closest('[data-sel]');if(!b){return;}
var what=b.getAttribute('data-sel'),boxes=ticked();
var ids=boxes.map(function(c){return c.closest('li').getAttribute('data-id');});
if(what==='close'){clear();return;}
if(what!=='say'){closeSelMenu(false);
var words={reload:'Reload',dashboard:'Open dashboard',settings:'Open settings',display_on:'Display on',
display_off:'Display off',stats_on:'Show system stats',stats_off:'Hide system stats',
restart:'Restart app',reboot:'Reboot'}[what];
if(what==='reboot'&&!window.confirm('Reboot '+(ids.length===1?'1 panel':ids.length+' panels')+'?\n\nEach device starts again and shows the dashboard after a minute or two.')){return;}
Promise.all(ids.map(function(id,at){return post('/api/fleet/panel',enc({panel:id,action:what}))
.then(function(r){
// System stats switched: the row says so at once, before the next check-in redraws the list.
if(r.ok&&what.indexOf('stats_')===0){boxes[at].closest('li').setAttribute('data-stats',what==='stats_on'?'1':'0');}
return r.ok;}).catch(function(){return false;});})).then(function(done){
var missed=done.filter(function(ok){return !ok;}).length;
say(missed?words+' reached '+(done.length-missed)+' of '+done.length+' panels.':'');});return;}
// Announce: only what to say or play and the volume, for the ticked panels; the dialog keeps
// its list of panels out of sight, ticked to match.
if(what==='say'){if(!openDialog('announce')){return;}
all('#speakers-list li.speaker').forEach(function(li){var t=li.querySelector('[data-tick]');if(t){t.checked=ids.indexOf(li.getAttribute('data-id'))>=0;}});
var head=document.querySelector('#announce h2');if(head){head.textContent='Announce to '+(ids.length===1?'1 panel':ids.length+' panels');}}});

// The Groups tab: a group is dragged by its grip to a new place, the higher one winning, with
// the neighbours sliding aside as in an album (admin_pictures.js). Nothing moves until the drop,
// which submits the row's own arrow form with the place it landed on, so the page comes back
// drawn from the new order. A list drawn again during the drag leaves the drop with nothing to do.
var gdrag=null;
function groupRows(list){return Array.prototype.slice.call(list.children);}
function placeGroup(){var d=gdrag,dy=d.lastY+window.scrollY-d.startY,h=d.heights[d.from],last=d.items.length-1;
// Held inside the list, as on the panel, so the row cannot cover the heading or the field below.
d.li.style.transform='translateY('+Math.max(d.tops[0]-d.tops[d.from],Math.min(d.tops[last]+d.heights[last]-h-d.tops[d.from],dy))+'px)';
var centre=d.tops[d.from]+h/2+dy,to=d.from,i;
for(i=d.from+1;i<d.items.length;i++){if(centre>d.tops[i]+d.heights[i]/2){to=i;}}
for(i=d.from-1;i>=0;i--){if(centre<d.tops[i]+d.heights[i]/2){to=i;}}
d.to=to;
d.items.forEach(function(li,k){if(li===d.li){return;}
var shift=k>d.from&&k<=to?-h:k<d.from&&k>=to?h:0;li.style.transform=shift?'translateY('+shift+'px)':'';});}
document.addEventListener('pointerdown',function(e){
var grab=e.target.closest?e.target.closest('#group-list .grab'):null;
if(!grab||e.button>0){return;}
e.preventDefault();
var li=grab.closest('li'),items=groupRows(li.parentNode);
gdrag={li:li,items:items,from:items.indexOf(li),to:items.indexOf(li),pointer:e.pointerId,
startY:e.clientY+window.scrollY,lastY:e.clientY,moved:false,
tops:items.map(function(x){return x.getBoundingClientRect().top+window.scrollY;}),
heights:items.map(function(x){return x.getBoundingClientRect().height;})};
try{grab.setPointerCapture(e.pointerId);}catch(ignored){}});
document.addEventListener('pointermove',function(e){var d=gdrag;
if(!d||e.pointerId!==d.pointer){return;}
d.lastY=e.clientY;
if(!d.moved){if(Math.abs(e.clientY+window.scrollY-d.startY)<4){return;}
d.moved=true;d.li.classList.add('dragging');
d.items.forEach(function(li){if(li!==d.li){li.classList.add('shifting');}});}
placeGroup();});
function endGroupDrag(e,cancelled){var d=gdrag;
if(!d||e.pointerId!==d.pointer){return;}
gdrag=null;
d.items.forEach(function(li){li.classList.remove('dragging','shifting');li.style.transform='';});
if(cancelled||!d.moved||d.to===d.from||!d.li.isConnected){return;}
var form=d.li.querySelector('input[name="what"][value="move_group"]');
form=form&&form.form;if(!form){return;}
form.querySelector('input[name="to"]').value=String(d.to);form.submit();}
document.addEventListener('pointerup',function(e){endGroupDrag(e,false);});
document.addEventListener('pointercancel',function(e){endGroupDrag(e,true);});

// After admin_stats.js draws a region again: the filters, the pictures and the selection bar.
document.addEventListener('muralis-lists',function(){apply();selection();});
// A wide screen grown from a narrow one shows the chosen panel: it is asked then.
var wide=window.matchMedia('(min-width:840px)');
if(wide.addEventListener){wide.addEventListener('change',function(){all('.ld .detail>.pane').forEach(opened);});}
apply();
})();
