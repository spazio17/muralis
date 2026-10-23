// The automation editor shows only what each part of a rule needs. The server draws a When's
// or an If condition's chosen sensor alone, its events or states and the boxes they may need,
// and an action's chosen action with only its own boxes; a change of a Sensor or Action menu
// presses the row's Choose (hidden here, a button without scripting), which posts the form for
// the server to draw the new choice. Among what is drawn this script shows, in a When: the
// level and the minutes where the event has them, the tag or camera menu, the text a value
// must become, and "Not again within" where it applies; in an If condition: a level or a text
// where the state has one. Without scripting every drawn box is shown and the server reads
// the right one. Add, Remove and the moves are the server's too (submit buttons named op).
// Each part carries data-p, the prefix of its names, and is painted from what is inside it
// alone, since a When holds its actions.
(function(){
function each(root,selector,fn){Array.prototype.forEach.call(root.querySelectorAll(selector),fn);}
function box(el){return el?el.parentNode:null;}
function show(el,on){var b=box(el);if(b){b.classList.toggle('gone',!on);}}
function label(el,text){var b=box(el);if(!b){return;}var l=b.querySelector('label');if(l){l.textContent=text;}}
// The radio group of the chosen sensor shown, the others hidden; the ticked radio, the first
// when none is, ticked as the default too so the stats poll does not take the tick for an edit.
function pick(row,chosen){var checked=null;
each(row,'.events',function(group){var on=group.getAttribute('data-sensor')===chosen;
group.classList.toggle('on',on);
if(on){var c=group.querySelector('input:checked');
if(!c){var first=group.querySelector('input');if(first){first.checked=true;first.defaultChecked=true;c=first;}}
checked=c;}});
return checked;}
function painter(row){var p=row.getAttribute('data-p');
function f(name){return row.querySelector('[name="'+p+'_'+name+'"]');}
if(row.classList.contains('when')){return whenPainter(row,f);}
// An action's boxes are the server's alone: its Action menu presses Choose.
if(row.classList.contains('step')){return null;}
return conditionPainter(row,f);}
function whenPainter(row,f){
var sensor=f('sensor'),level=f('level'),at=f('at'),minutes=f('minutes'),tag=f('tag');
var camera=f('camera_tag'),cooldown=f('cooldown'),text=f('match_text');
// The event shown at the last paint; undefined before the first.
var lastEvent;
return function(){
var checked=pick(row,sensor?sensor.value:'');
// A level where the event has one; its unit may be empty (a count of things).
var hasLevel=!!checked&&checked.hasAttribute('data-level');
var unit=hasLevel?checked.getAttribute('data-level'):'';
// A time of day has its own box, the browser's time picker.
show(level,hasLevel&&unit!=='time');
show(at,unit==='time');
if(hasLevel&&unit!=='time'){label(level,unit?'Level ('+unit+')':'Level');}
show(minutes,!!(checked&&checked.getAttribute('data-holds')));
var tagged=!!(checked&&checked.getAttribute('data-tag')),cam=!!(checked&&checked.getAttribute('data-camera'));
// A camera's event names a camera or group in place of a tag; both, and a Changes, wait a
// while before again. Picking another such event starts the box at its own default (60 s for
// a tag or a camera, 0 for a Changes); the event the editor opened with keeps what is stored.
var cools=!!(checked&&checked.getAttribute('data-cools'));
show(tag,tagged&&!cam);show(camera,cam);show(cooldown,cools);
if(cooldown&&cools&&lastEvent!==undefined&&checked!==lastEvent){cooldown.value=checked.getAttribute('data-cooldown');}
lastEvent=checked;
// The text an integration's value must become.
show(text,!!(checked&&checked.getAttribute('data-text')));};}
function conditionPainter(row,f){
var sensor=f('sensor'),level=f('level'),text=f('text');
return function(){
var checked=pick(row,sensor?sensor.value:'');
var hasLevel=!!checked&&checked.hasAttribute('data-level');
var unit=hasLevel?checked.getAttribute('data-level'):'';
show(level,hasLevel);
if(hasLevel){label(level,unit?'Level ('+unit+')':'Level');}
show(text,!!(checked&&checked.getAttribute('data-text')));};}
// Bound once per form, and again for the forms admin_stats.js brings when it swaps the page's
// list for a newer one.
function bind(root){
each(root,'form.automation',function(form){
if(form.dataset.bound){return;}
form.dataset.bound='1';
var paints=[];
each(form,'[data-p]',function(row){var paint=painter(row);if(paint){row.muralisPaint=paint;paints.push(paint);}});
// A Sensor or Action menu presses its row's Choose, the first in the row: the server draws
// what the new choice needs. Any other change repaints the part it was made in.
form.addEventListener('change',function(e){var row=e.target.closest('[data-p]');if(!row){return;}
var choose=e.target.matches('select[name$="_sensor"],select[name$="_action"]')?row.querySelector('button.choose'):null;
if(choose){choose.click();}else if(row.muralisPaint){row.muralisPaint();}});
paints.forEach(function(paint){paint();});
});}
window.muralisBindEditors=bind;
bind(document);
// Unsaved changes are not dropped by accident. The editor counts as changed once something in
// it is typed or picked, or when it is a draft the server drew again (data-draft). Another
// rule's row, Add and Cancel ask first; leaving any other way gets the browser's own question;
// a submit (Save, Delete, Add, Remove, a move) never asks.
var changed=false,leaving=false;
function editor(){return document.querySelector('.detail>.pane:not([hidden]) form.automation')||document.querySelector('form.automation');}
function dirty(){var open=editor();return !leaving&&!!open&&(changed||!!open.dataset.draft);}
document.addEventListener('input',function(e){if(e.target.closest('form.automation')){changed=true;}});
document.addEventListener('change',function(e){if(e.target.closest('form.automation')){changed=true;}});
document.addEventListener('submit',function(e){if(e.target.matches('form.automation')){leaving=true;}});
document.addEventListener('click',function(e){
if(!dirty()||e.target.closest('input,button,label,select')){return;}
var id=editor().querySelector('input[name=id]').value,row=e.target.closest('.nav li[data-id]');
var away=(row&&row.dataset.id!==id)||(id&&e.target.closest('.nav a[href="/automation"]'))||e.target.closest('form.automation a[href="/automations"]');
if(!away){return;}
if(window.confirm('Discard your changes?')){leaving=true;}else{e.preventDefault();e.stopImmediatePropagation();}},true);
window.addEventListener('beforeunload',function(e){if(dirty()){e.preventDefault();e.returnValue='';}});
// A refused save or a change of shape answers under /api/automations/save: the address goes
// back to the editor's own, so a reload shows the page rather than posting the form again
// (review, 2026-10-01). The part the change landed on stays in the address.
if(location.pathname.indexOf('/api/')===0&&history.replaceState){
// The editor in sight first, any editor after: one selector with both took the first form in
// page order, a hidden pane's.
var shown=document.querySelector('.detail>.pane:not([hidden]) form.automation')||document.querySelector('form.automation'),
    hidden=shown?shown.querySelector('input[name=id]'):null;
history.replaceState(null,'',(hidden&&hidden.value?'/automation?id='+encodeURIComponent(hidden.value):'/automation')+location.hash);}
})();
