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
.then(function(text){if(window.console){console.log('Muralis: '+el.dataset.setting+': '+text);}})
.catch(function(){if(window.console){console.warn('Muralis: '+el.dataset.setting+': no response. The device may be rebooting or off the network');}})
.then(function(){setTimeout(function(){delete el.dataset.pending;},1500);});}
Array.prototype.forEach.call(document.querySelectorAll('[data-setting]'),function(el){el.addEventListener('change',function(){apply(el);});});
})();
