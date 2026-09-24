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
el.dataset.pending='1';
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
el.classList.toggle('check-bad',rejected);
el.title=rejected?detail:'';
if(window.console){console.log('Muralis: '+el.dataset.setting+': '+text);}})
.catch(function(){if(window.console){console.warn('Muralis: '+el.dataset.setting+': no response. The device may be rebooting or off the network');}})
.then(function(){setTimeout(function(){delete el.dataset.pending;},1500);});}
Array.prototype.forEach.call(document.querySelectorAll('[data-setting]'),function(el){el.addEventListener('change',function(){apply(el);});});
// The screensaver box shows only the fields its mode uses, the same rule as the tablet's page:
// the address for the web page, the floor for the dimmed page; the wake choice is greyed out for
// the film, which has nothing to glance at. Also called by the stats poll when the mode follows
// a change made elsewhere, hence the window property.
function screensaverFields(){var mode=document.getElementById('screensaver-mode');
if(!mode){return;}
var url=document.getElementById('screensaver-url-field'),dim=document.getElementById('screensaver-dim-field'),wake=document.getElementById('screensaver-on-wake');
var pictures=document.getElementById('screensaver-pictures'),source=document.getElementById('screensaver-source');
// The library box holds the playlists, the browser and the upload. It is a box of its own since
// 2026-09-10, and it belongs to the mode AND the source: there is nothing to browse when the
// pictures come from Bing.
var library=document.getElementById('screensaver-library'),online=document.getElementById('screensaver-online'),credit=document.getElementById('screensaver-credit');
if(url){url.classList.toggle('gone',mode.value!=='url');}
if(dim){dim.classList.toggle('gone',mode.value!=='dim');}
if(pictures){pictures.classList.toggle('gone',mode.value!=='pictures');}
if(wake){var applies=mode.value==='url'||mode.value==='dim'||mode.value==='pictures';wake.disabled=!applies;wake.title=applies?'':'The black film has nothing to glance at: a wake shows the page.';}
if(source){var isLocal=source.value==='local';
if(library){library.classList.toggle('gone',!isLocal||mode.value!=='pictures');}
if(online){online.classList.toggle('gone',isLocal);}
if(credit){credit.disabled=!isLocal;if(!isLocal){credit.checked=true;}credit.title=isLocal?'':'The online sources require their credit line.';}}}
window.muralisScreensaverFields=screensaverFields;
var saverMode=document.getElementById('screensaver-mode'),saverSource=document.getElementById('screensaver-source');
if(saverMode){saverMode.addEventListener('change',screensaverFields);}
if(saverSource){saverSource.addEventListener('change',screensaverFields);}
screensaverFields();
})();
