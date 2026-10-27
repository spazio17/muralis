// The Fleet page's Announce card. Asks each panel on its own how it plays through
// GET /api/fleet/speaker?panel= when the card opens and after each action, never on a timer, and
// draws each row as its panel answers: an offline panel no longer holds back the others for its
// whole timeout. A volume shows on the rows at once and the answers confirm it. Say, Play,
// Stop all and the volume of all go to the panels the segmented button names (all, a group,
// the ticked ones); a row's own slider and switch go to that panel alone. Every
// action is POST /api/fleet/speaker; the answer's line says who took it and who did not.
(function(){
var card=document.getElementById('speakers');if(!card){return;}
var list=document.getElementById('speakers-list'),result=document.getElementById('speakers-result');
var count=document.getElementById('speakers-count'),groupBox=document.getElementById('speakers-group');
var mode='all';
function rows(){return Array.prototype.slice.call(list.querySelectorAll('li.speaker'));}
function target(){
if(mode==='group'){return 'group:'+document.getElementById('speakers-group-menu').value;}
if(mode==='panels'){return 'panels:'+rows().filter(function(li){return li.querySelector('[data-tick]').checked;})
.map(function(li){return li.getAttribute('data-id');}).join(',');}
return 'all';}
function tell(text){result.textContent=text;result.hidden=!text;}
// The rows a target names, for the volume to show on them before the panels answer.
function targetRows(to){if(to==='all'){return rows();}
if(to.indexOf('group:')===0){var g='|'+to.slice(6)+'|';return rows().filter(function(li){return (li.getAttribute('data-groups')||'').indexOf(g)>=0;});}
var ids=to.slice(7).split(',');return rows().filter(function(li){return ids.indexOf(li.getAttribute('data-id'))>=0;});}
function post(to,action,value){
if(action==='volume'){targetRows(to).forEach(function(li){var v=li.querySelector('[data-volume]');if(!v.disabled){v.value=value;}});}
var body='target='+encodeURIComponent(to)+'&action='+encodeURIComponent(action)+(value===undefined?'':'&value='+encodeURIComponent(value));
tell('Sending...');
return fetch('/api/fleet/speaker',{method:'POST',credentials:'same-origin',
headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
.then(function(r){return r.json();}).then(function(j){tell(j.detail||j.error||'');setTimeout(function(){load(targetRows(to));},800);})
.catch(function(){tell('Not sent: this panel did not answer.');});}
var known={};
function paint(states){
states.forEach(function(p){known[p.id]=p;var li=list.querySelector('li.speaker[data-id="'+p.id+'"]');if(!li){return;}
var state=li.querySelector('[data-state]'),vol=li.querySelector('[data-volume]'),sw=li.querySelector('[data-switch]');
if(p.too_old){state.textContent='Its Muralis is too old for this';vol.disabled=true;sw.disabled=true;return;}
if(!p.reached){state.textContent='Not reached';vol.disabled=true;sw.disabled=true;return;}
state.textContent=!p.on?'Speaker off':p.playing?'Playing':'Idle';
sw.disabled=false;sw.checked=!!p.on;
vol.disabled=!p.on;if(p.volume>=0&&document.activeElement!==vol){vol.value=p.volume;}});
var ids=Object.keys(known),on=ids.filter(function(id){return known[id].reached&&known[id].on;}).length;
count.textContent=on+' of '+rows().length+' with the speaker on';}
function load(which){(which||rows()).forEach(function(li){
fetch('/api/fleet/speaker?panel='+encodeURIComponent(li.getAttribute('data-id')),{credentials:'same-origin',cache:'no-store'})
.then(function(r){return r.json();}).then(function(j){paint(j.panels||[]);})
.catch(function(){});});}
Array.prototype.forEach.call(card.querySelectorAll('.segmented button'),function(b){
b.addEventListener('click',function(){mode=b.getAttribute('data-target');
Array.prototype.forEach.call(card.querySelectorAll('.segmented button'),function(o){o.setAttribute('aria-pressed',String(o===b));});
if(groupBox){groupBox.hidden=mode!=='group';}
rows().forEach(function(li){li.querySelector('label.tick').hidden=mode!=='panels';});});});
card.querySelector('[data-action=say]').addEventListener('click',function(){
post(target(),'say',document.getElementById('speakers-sentence').value);});
card.querySelector('[data-action=play]').addEventListener('click',function(){
post(target(),'play',document.getElementById('speakers-sound').value);});
card.querySelector('[data-action=stop]').addEventListener('click',function(){post(target(),'stop');});
var all=document.getElementById('speakers-volume'),allValue=document.getElementById('speakers-volume-value');
all.addEventListener('input',function(){allValue.textContent=all.value+'%';});
all.addEventListener('change',function(){post(target(),'volume',all.value);});
rows().forEach(function(li){var id=li.getAttribute('data-id');
li.querySelector('[data-volume]').addEventListener('change',function(e){post('panels:'+id,'volume',e.target.value);});
li.querySelector('[data-switch]').addEventListener('change',function(e){post('panels:'+id,'switch',e.target.checked?'on':'off');});});
// Inside the Fleet page's Announce dialog the panels are asked when it opens, not with the
// page: each answer is a call to that panel.
window.muralisSpeakersLoad=function(){load();};
if(!card.closest('dialog')){load();}
})();
