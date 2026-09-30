// Applies every standalone control the moment it is touched, so none of them needs a Save
// button. The same shape as the brightness controls above, and for the same reason: a setting
// that depends on nothing else has nothing to wait for.
// These controls used to live together in a Behaviour box. They now sit in the box each one
// is actually about, the overlay switch under the stats it switches on, the publish interval
// inside MQTT, which is why this is keyed on the data-setting attribute rather than on
// a container: the script does not care where on the page a control ended up.
// The data-pending flag exists here for the reason it exists on the slider. The stats
// poll below writes these controls from the device's real state every five seconds, and a poll
// landing between the click and the tablet storing the change would snap the box back and make
// the click look ignored.
// Note the change event rather than input: a time field fires input on
// every digit, so a half-typed "0" would be posted as 00:00 on the way to 04:00.
(function(){
function apply(el){
var value=el.type==='checkbox'?(el.checked?'1':'0'):el.value;
// A radio belongs to a group, and the poll follows the group, so the flag goes on the group.
var pendingOn=el.type==='radio'&&el.closest?(el.closest('.radios')||el):el;
pendingOn.dataset.pending='1';
// "behaviour" is the section name POST /api/setting has always accepted. It
// outlived the box it was named after and is kept as the wire name rather than
// renamed, so a page cached in a browser keeps working against a newer tablet.
var body='section=behaviour&'+encodeURIComponent(el.dataset.setting)+'='+encodeURIComponent(value);
fetch('/api/setting',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
.then(function(r){return r.text();})
// Console, not the page: a wall-panel operator has no use for a running log of saves,
// and a rejection still has to be diagnosable.
.then(function(text){var rejected=false,detail=text;
try{var parsed=JSON.parse(text);rejected=parsed.status==='rejected';detail=parsed.detail||text;}catch(ignored){}
el.classList.toggle('check-bad',rejected);delete el.dataset.heldOver;
el.title=rejected?detail:'';
// A text box says why under itself, the field's own support line, as the panel does; a
// tooltip a phone never shows was the only word before (review, 2026-10-01).
if(el.tagName==='INPUT'&&el.type!=='checkbox'&&el.type!=='radio'&&el.closest){var field=el.closest('.field');
if(field){var why=field.querySelector('.support.refused');
if(rejected){if(!why){why=document.createElement('p');why.className='support bad refused';field.appendChild(why);}why.textContent=/^Not saved:/.test(detail)?detail:'Not saved: '+detail;}
else if(why){why.parentNode.removeChild(why);}}}
if(!rejected){
// Stored: every other control of the same setting on the page (a sensor's row in the list and
// in its detail) shows it at once, and the poll leaves this setting alone until the panel's
// document has caught up, however slow the path to it is (Juri, 2026-09-27: ten seconds on a
// tablet behind a slow link, the two rows disagreeing meanwhile).
window.muralisLocal=window.muralisLocal||{};
window.muralisLocal[el.dataset.setting]={value:value,at:Date.now()};
Array.prototype.forEach.call(document.querySelectorAll('[data-setting="'+el.dataset.setting+'"]'),function(other){
if(other===el){return;}
if(other.type==='checkbox'){other.checked=el.checked;}else if(other.type!=='radio'){other.value=el.value;}});
if(window.muralisPollSoon){window.muralisPollSoon();}}
if(window.console){console.log('Muralis: '+el.dataset.setting+': '+text);}})
.catch(function(){if(window.console){console.warn('Muralis: '+el.dataset.setting+': no response. The device may be rebooting or off the network');}})
.then(function(){setTimeout(function(){delete pendingOn.dataset.pending;},1500);});}
// On the document, not on each control: admin_stats.js swaps a list in place when another
// surface changed it, and the switches it brings must apply like the ones it replaced.
document.addEventListener('change',function(e){var el=e.target;
if(el&&el.dataset&&el.dataset.setting){apply(el);}});
// A box that stores on change sits in a form whose Save is for a browser without scripting:
// Enter there would post the form with the old baseline right after the change stored, and
// answer "changed elsewhere" for a name that was saved. Enter leaves the box instead.
Array.prototype.forEach.call(document.querySelectorAll('form'),function(form){
if(!form.querySelector('.actions.nojs')){return;}
form.addEventListener('submit',function(e){e.preventDefault();var a=document.activeElement;if(a&&a.blur){a.blur();}});});
// The screensaver page shows only the fields its mode uses, the same rule as the tablet's page:
// the address for the web page, the floor for the dimmed page, and for the film no wake choice at
// all, since it has nothing to look at on waking. Also called by the stats poll when the mode
// follows a change made elsewhere, hence the window property.
// The mode is the settings card's menu, the one place it is chosen since 2026-09-28; the
// screensaver page has no chooser and carries the mode on its shell, which admin_stats.js keeps
// current, with the options panel's title for every mode rendered beside it.
function modeValue(){var el=document.getElementById('screensaver-mode');
if(el){return el.value;}
var page=document.getElementById('screensaver-page');
return page?page.dataset.mode:null;}
function optionsTitle(mode){var page=document.getElementById('screensaver-page');
var titles=page&&page.dataset.titles?JSON.parse(page.dataset.titles):{};
return titles[mode]||'';}
function screensaverFields(){var mode=modeValue();
if(mode===null){return;}
var url=document.getElementById('screensaver-url-field'),dim=document.getElementById('screensaver-dim-field'),wake=document.getElementById('screensaver-wake-field');
var pictures=document.getElementById('screensaver-pictures'),source=document.getElementById('screensaver-source');
// The library box holds the playlists, the browser and the upload. It is a box of its own since
// 2026-09-10, and it belongs to the mode AND the source: there is nothing to browse when the
// pictures come from Bing.
var library=document.getElementById('screensaver-library'),online=document.getElementById('screensaver-online'),credit=document.getElementById('screensaver-credit');
// The second panel is named after the mode it belongs to and is not there at all for Off: a
// screensaver that is off has no settings (Juri, 2026-09-11). The name comes from the titles
// the server rendered on the page's shell, so the two surfaces cannot drift apart over a word.
var options=document.getElementById('screensaver-options'),
    title=document.getElementById('screensaver-options-title'),
    more=document.getElementById('screensaver-more');
if(options){options.classList.toggle('gone',mode==='off');}
if(title){title.textContent=optionsTitle(mode);}
// Off has no page: the way to it leaves with the options.
if(more){more.classList.toggle('gone',mode==='off');}
if(url){url.classList.toggle('gone',mode!=='url');}
if(dim){dim.classList.toggle('gone',mode!=='dim');}
if(pictures){pictures.classList.toggle('gone',mode!=='pictures');}
// Gone, not greyed out: a control that can never be enabled is clutter.
if(wake){wake.classList.toggle('gone',!(mode==='url'||mode==='dim'||mode==='pictures'));}
if(source){var isLocal=source.value==='local';
if(library){library.classList.toggle('gone',!isLocal||mode!=='pictures');
// Without the playlists the options are the page's only panel, centred like the legal pages.
var page=document.getElementById('screensaver-page');if(page){page.classList.toggle('one',library.classList.contains('gone'));}}
if(online){online.classList.toggle('gone',isLocal);}
if(credit){credit.disabled=!isLocal;if(!isLocal){credit.checked=true;}credit.title=isLocal?'':'The online sources require their credit line.';}}}
window.muralisScreensaverFields=screensaverFields;
var saverMode=document.getElementById('screensaver-mode'),saverSource=document.getElementById('screensaver-source');
if(saverMode){saverMode.addEventListener('change',screensaverFields);}
if(saverSource){saverSource.addEventListener('change',screensaverFields);}
screensaverFields();
})();
