// The automation editor shows only what the chosen sensor and action need: the events of the
// sensor picked in the menu, the level and the minutes where the event has them, the tag menu
// for a tag event (the camera menu for a camera's event), the argument box named after the
// action (Sentence, Sound address), and a camera, how and how long for Show a camera.
// Without scripting every group is shown (the stylesheet hides them only under html.js) and
// the server still reads the right one, since the event radios are named per sensor. One editor per rule stands on the wide page, so each form
// is bound on its own, by the ids that carry its suffix.
(function(){
// Bound once per form, and again for the forms admin_stats.js brings when it swaps the page's
// list for a newer one.
function bind(root){
Array.prototype.forEach.call(root.querySelectorAll('form.automation'),function(form){
if(form.dataset.bound){return;}
form.dataset.bound='1';
function by(prefix){return form.querySelector('[id^="'+prefix+'-"]');}
var sensor=by('a-sensor'),action=by('a-action');
var level=by('a-level'),at=by('a-at'),minutes=by('a-minutes'),tag=by('a-tag');
var camera=by('a-camera'),cooldown=by('a-cooldown'),text=by('a-text');
var argument=by('a-argument'),target=by('a-target'),shown=by('a-show'),seconds=by('a-seconds'),chime=by('a-chime');
var only=by('a-only'),from=by('a-from'),to=by('a-to');
// The note under the sensor for one that is not running while the display sleeps.
var stops=form.querySelector('.stopsnote');
var silent=stops&&stops.getAttribute('data-silent')?stops.getAttribute('data-silent').split(','):[];
function box(el){return el?el.parentNode:null;}
function show(el,on){var b=box(el);if(b){b.classList.toggle('gone',!on);}}
function label(el,text){var b=box(el);if(!b){return;}var l=b.querySelector('label');if(l){l.textContent=text;}}
function paint(){
var chosen=sensor?sensor.value:'';
if(stops){stops.classList.toggle('gone',silent.indexOf(chosen)<0);}
var checked=null;
Array.prototype.forEach.call(form.querySelectorAll('.events'),function(group){
var on=group.getAttribute('data-sensor')===chosen;
group.classList.toggle('on',on);
if(on){var c=group.querySelector('input:checked');
// Ticked as the default too, so the stats poll does not take the tick for an edit.
if(!c){var first=group.querySelector('input');if(first){first.checked=true;first.defaultChecked=true;c=first;}}
checked=c;}});
// A level where the event has one; its unit may be empty (a count of things).
var hasLevel=!!checked&&checked.hasAttribute('data-level');
var unit=hasLevel?checked.getAttribute('data-level'):'';
// A time of day has its own box, the browser's time picker.
show(level,hasLevel&&unit!=='time');
show(at,unit==='time');
if(hasLevel&&unit!=='time'){label(level,unit?'Level ('+unit+')':'Level');}
show(minutes,!!(checked&&checked.getAttribute('data-holds')));
var tagged=!!(checked&&checked.getAttribute('data-tag')),cam=!!(checked&&checked.getAttribute('data-camera'));
// A camera's event names a camera or group in place of a tag; both wait a while before again.
show(tag,tagged&&!cam);show(camera,cam);show(cooldown,tagged);
// The text an integration's value must become.
show(text,!!(checked&&checked.getAttribute('data-text')));
var opt=action&&action.options[action.selectedIndex];
var arg=opt?opt.getAttribute('data-argument'):'';
show(argument,!!arg);
var shows=!!(opt&&opt.getAttribute('data-shows')),camAct=!!(opt&&opt.getAttribute('data-camera'));
show(target,camAct);show(shown,camAct);show(seconds,shows);show(chime,shows);
if(arg){label(argument,arg);}
// The address keyboard for a sound address, as the panel's box has it.
if(argument){if(opt&&(opt.value==='play_sound'||opt.value==='open_page'||opt.value==='call_url')){argument.setAttribute('inputmode','url');}else{argument.removeAttribute('inputmode');}}
var windowed=only&&only.checked;
show(from,!!windowed);show(to,!!windowed);}
Array.prototype.forEach.call(form.querySelectorAll('select,input[type=radio],input[type=checkbox]'),function(el){el.addEventListener('change',paint);});
paint();
});}
window.muralisBindEditors=bind;
bind(document);
// A refused save answers under /api/automations/save: the address goes back to the editor's
// own, so a reload shows the page rather than posting the form again (review, 2026-10-01).
if(location.pathname.indexOf('/api/')===0&&history.replaceState){
// The editor in sight first, any editor after: one selector with both took the first form in
// page order, a hidden pane's.
var shown=document.querySelector('.detail>.pane:not([hidden]) form.automation')||document.querySelector('form.automation'),
    hidden=shown?shown.querySelector('input[name=id]'):null;
history.replaceState(null,'',hidden&&hidden.value?'/automation?id='+encodeURIComponent(hidden.value):'/automation');}
})();
