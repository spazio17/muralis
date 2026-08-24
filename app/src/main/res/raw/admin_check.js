// Pre-checks a value against the device the moment its field loses focus, and says the verdict
// with colour: green when the value would work right now, red when it would not, with the reason
// in the tooltip. Juri's design (2026-08-24): validation can only test spelling, but "is this
// port bindable", "does this URL answer", "is a broker listening there" are runtime facts only
// the device can know, and the operator should learn them before saving, not after.
// Advisory only. The save paths keep their own refusals; a red field is a warning, the refusal
// on submit is the gate.
// blur rather than input: these checks touch the network, and firing one per keystroke would
// probe a half-typed address once per letter.
// POST rather than GET, deliberately: the check makes the panel connect out to a caller-chosen
// address, so it sits behind the same cross-site gate as the commands. See HttpAdminServer's
// /api/check route.
(function(){
var checks=[
{name:'http_port',kind:'http_port'},
{name:'dashboard_url',kind:'dashboard_url'},
{name:'mqtt_host',kind:'mqtt_host'}];
// Per-field generation, so a slow probe on a dead host cannot land after a fast probe on the
// corrected value and repaint a good field red. Same guard as the tablet's runPrecheck.
var generation={};
function verdict(el,ok,detail,reason){
el.classList.remove('check-ok','check-bad');
el.classList.add(ok?'check-ok':'check-bad');
// The short clause plus the underlying reason: a tooltip has room for both, a panel's status
// line does not, which is why SettingProbe.Verdict carries them separately.
el.title=(detail||'')+(reason?' ('+reason+')':'');}
function clear(el){
el.classList.remove('check-ok','check-bad');
el.title='';}
function run(el,kind){
var mine=(generation[kind]||0)+1;
generation[kind]=mine;
if(el.value.trim()===''){clear(el);return;}
var body='kind='+encodeURIComponent(kind)+'&value='+encodeURIComponent(el.value);
if(kind==='mqtt_host'){
// Reachability is host plus port, so the check reads the port box beside it as typed.
var portEl=document.querySelector('input[name="mqtt_port"]');
if(portEl){body+='&port='+encodeURIComponent(portEl.value);}}
fetch('/api/check',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
.then(function(r){return r.json();})
.then(function(result){if(generation[kind]===mine){verdict(el,result.ok===true,result.detail,result.reason);}})
// No answer is not a red field: the device may be rebinding or rebooting, and painting the
// value wrong for it would be the check lying about the value.
.catch(function(){if(generation[kind]===mine){clear(el);}});}
checks.forEach(function(check){
var el=document.querySelector('input[name="'+check.name+'"]');
if(el){el.addEventListener('blur',function(){run(el,check.kind);});}});
// The broker port belongs to the same address as the host, so leaving it re-checks the pair.
var mqttPort=document.querySelector('input[name="mqtt_port"]');
var mqttHost=document.querySelector('input[name="mqtt_host"]');
if(mqttPort&&mqttHost){mqttPort.addEventListener('blur',function(){run(mqttHost,'mqtt_host');});}
})();
