// Pre-checks a value against the device the moment its field loses focus, and says the verdict
// with colour: green when the value would work right now, red when it would not, with the reason
// in the tooltip. Juri's design (2026-08-24): validation can only test spelling, but "is this
// port bindable", "does this URL answer", "is a broker listening there" are runtime facts only
// the device can know, and the operator should learn them before saving, not after.
// Advisory only. The save paths keep their own refusals; a red field is a warning, the refusal
// on submit is the gate.
// blur rather than input: these checks touch the network, and firing one per keystroke would
// probe a half-typed address once per letter.
(function(){
var checks=[
{name:'http_port',kind:'http_port'},
{name:'dashboard_url',kind:'dashboard_url'},
{name:'mqtt_host',kind:'mqtt_host'}];
function verdict(el,ok,detail){
el.classList.remove('check-ok','check-bad');
el.classList.add(ok?'check-ok':'check-bad');
el.title=detail||'';}
function clear(el){
el.classList.remove('check-ok','check-bad');
el.title='';}
function run(el,kind){
if(el.value.trim()===''){clear(el);return;}
var url='/api/check?kind='+encodeURIComponent(kind)+'&value='+encodeURIComponent(el.value);
if(kind==='mqtt_host'){
// Reachability is host plus port, so the check reads the port box beside it as typed.
var portEl=document.querySelector('input[name="mqtt_port"]');
if(portEl){url+='&port='+encodeURIComponent(portEl.value);}}
fetch(url,{credentials:'same-origin'})
.then(function(r){return r.json();})
.then(function(result){verdict(el,result.ok===true,result.detail);})
// No answer is not a red field: the device may be rebinding or rebooting, and painting the
// value wrong for it would be the check lying about the value.
.catch(function(){clear(el);});}
checks.forEach(function(check){
var el=document.querySelector('input[name="'+check.name+'"]');
if(el){el.addEventListener('blur',function(){run(el,check.kind);});}});
})();
