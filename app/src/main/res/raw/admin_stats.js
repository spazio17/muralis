// Live stats and the configuration actually applied on the device, so this page can be trusted
// after somebody has changed something at the tablet, rather than showing whatever was current
// when it was loaded.
(function(){
// The readout itself is optional. The settings page has one; the screensaver page has this
// poll without it, because the poll is also what keeps every field on that page current when
// somebody changes a setting at the panel. Writing to a missing element threw on every tick
// and took the chip and the field-following down with it (caught in the browser, 2026-09-10).
var target=document.getElementById('stats');
function show(text){if(target){target.textContent=text;}}
function mb(kb){return kb==null?'--':Math.round(kb/1024)+'M';}
function num(v,d){return v==null?'--':v.toFixed(d||0);}
function usedPercent(u,t){return u==null||!t?'--':Math.round(100*u/t)+'%';}
function dur(ms){if(ms==null||ms<0){return '--';}
var s=Math.floor(ms/1000),d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);
return d>0?(d+'d'+h+'h'):(h>0?(h+'h'+m+'m'):(m+'m'));}
function render(data){
var sys=data.system||{},run=data.runtime||{},bat=data.battery||{},net=data.network||{},mem=data.memory||{},cfg=data.config||{};
// The same eight rows, in the same order, as the tablet's own overlay; see
// SystemStats.formatOverlayHtml. Padded labels so the values line up in the <pre>.
var lines=[];
lines.push('CPU  '+num(sys.cpu_busy_percent)+'%'+(sys.cpu_max_frequency_khz!=null?'   '+(sys.cpu_max_frequency_khz/1000000).toFixed(2)+'GHz':'')+(sys.load_average?'   load '+num(sys.load_average[0],2):''));
lines.push('RAM  '+mb(sys.mem_used_kb)+'/'+mb(sys.mem_total_kb)+'   '+usedPercent(sys.mem_used_kb,sys.mem_total_kb)+(mem.low?'   LOW MEMORY':''));
lines.push('ZRAM '+mb(sys.swap_used_kb)+'/'+mb(sys.swap_total_kb));
lines.push('TEMP '+num(sys.cpu_temperature_c,1)+'C cpu   '+num(sys.gpu_temperature_c,1)+'C gpu');
var pw=data.power||{};
lines.push(bat.present===false?'MAINS'+(pw.volts!=null?' '+pw.volts.toFixed(1)+'V':'')+(pw.watts!=null?' '+pw.watts.toFixed(1)+'W':''):'BAT  '+(bat.percent==null?'--':Math.round(bat.percent)+'%')+(bat.charge_state?' '+bat.charge_state:''));
lines.push('IP   '+(net.ip_address||'--')+'   '+(net.wifi_rssi_dbm!=null?net.wifi_rssi_dbm+'dBm':'--'));
// The age is of the last renderer death, not the last page load: see the same row in
// SystemStats.formatOverlayHtml for why those two must not be confused.
lines.push('WEB  '+(run.renderer_deaths!=null?run.renderer_deaths:'--')+' deaths'+'   '+(run.last_renderer_death_ago_ms!=null&&run.last_renderer_death_ago_ms>=0?dur(run.last_renderer_death_ago_ms)+' ago':'never')+'   '+(run.recycles||0)+' recycles');
// app_uptime_ms, not uptime_ms: the same row the tablet's overlay shows, and for the
// same reason. See SystemStats.RuntimeFacts.appUptimeMs.
lines.push('UP   '+dur(data.app_uptime_ms));
if(run.last_page_error){lines.push('ERR  '+run.last_page_error);}
var auto=document.getElementById('auto-brightness');
if(auto&&!auto.dataset.pending&&cfg.auto_brightness!=null){auto.checked=cfg.auto_brightness;}
// These controls have no Save button, so nothing else would ever correct them after
// somebody changed the same setting on the tablet or from a second browser.
function follow(id,value){var el=document.getElementById(id);
if(!el||el.disabled||el.dataset.pending||value==null){return;}
// A radio group is a div holding the radios, since the screensaver mode stopped being a menu
// on 2026-09-11. Following it means ticking the one whose value matches, and the group carries
// the pending flag for all of them so a poll landing on a fresh click cannot undo it.
if(el.classList.contains('radios')){
var radios=el.getElementsByTagName('input'),i;
for(i=0;i<radios.length;i++){
if(radios[i].value===String(value)&&!radios[i].checked){radios[i].checked=true;
if(window.muralisScreensaverFields){window.muralisScreensaverFields();}}}
return;}
var typing=el.tagName==='INPUT'&&el.type!=='checkbox'&&document.activeElement===el;
if(typing){return;}
if(el.type==='checkbox'){el.checked=value;}else if(el.value!==String(value)){el.value=String(value);el.classList.remove('check-bad');el.title='';}}
var disp=data.display||{};
follow('stats-overlay',cfg.stats_overlay);
// The sensors' switches and readings, from the status document's sensors block.
var sensors=data.sensors||{};
Object.keys(sensors).forEach(function(id){var one=sensors[id]||{};
follow('sensor-'+id,one.enabled);
var out=document.getElementById('sensor-'+id+'-value');
if(!out){return;}
var v=one.value,txt='';
if(one.enabled){
if(v==null){txt='--';}
else if(typeof v==='boolean'){txt=id==='movement'?(v?'moving':'still'):(v?'on':'off');}
else if(id==='proximity'){txt=one.near?'near':'far';}
else{txt=String(v)+({light:' lx',pressure:' hPa',temperature:' \u00b0C',humidity:' %'}[id]||'');}}
out.textContent=txt;});
var ss=document.getElementById('sum-sensors');
if(ss){var ids=Object.keys(sensors),on=ids.filter(function(id){return sensors[id]&&sensors[id].enabled;}).length;
ss.textContent=ids.length?on+' of '+ids.length+' on':'None on this device';}
follow('orientation',cfg.orientation);
follow('display-off-method',cfg.display_off_method);
// The sentence under it changes by itself when a sleep ends badly, so it is a fact to follow,
// not a control, and is never gated on pending.
var dn=document.getElementById('display-off-note');
if(dn&&disp.off_method_reason!=null){dn.textContent=disp.off_method_reason;dn.classList.toggle('bad',!!disp.off_method_warning);}
var ss=data.screensaver||{};
follow('screensaver-mode',ss.mode);
if(window.muralisScreensaverFields){window.muralisScreensaverFields();}
follow('screensaver-idle',ss.idle_s);
follow('screensaver-off',ss.off_s);
follow('screensaver-url',ss.url);
follow('screensaver-dim',ss.dim_percent);
follow('screensaver-on-wake',ss.on_wake);
follow('screensaver-source',ss.source);
follow('screensaver-picture-s',ss.picture_s);
follow('screensaver-transition',ss.transition);
follow('screensaver-shuffle',ss.shuffle);
follow('screensaver-one',ss.one_per_cycle);
if(ss.source==='local'){follow('screensaver-credit',ss.credit);}
follow('screensaver-corner',ss.credit_corner);
if(window.muralisScreensaverFields){window.muralisScreensaverFields();}
var sn=document.getElementById('screensaver-note');
if(sn&&ss.summary!=null){sn.textContent=ss.summary;sn.classList.toggle('bad',ss.problem!=null);}
var so=document.getElementById('screensaver-source-note');
if(so&&ss.source_state!=null){var pic=ss.picture&&ss.picture.title?' Showing: '+ss.picture.title+(ss.picture.credit?' ('+ss.picture.credit+')':'')+'.':'';
so.textContent=ss.source_state+pic;so.classList.toggle('bad',ss.source_problem!=null);}
// The slider follows the real backlight, except while the operator is actually dragging it,
// and except while the panel is dark: it reports zero then, below this slider's 1% floor, and
// the mode label below says "display off" for it.
var sl=document.getElementById('brightness');
if(sl&&!sl.dataset.pending&&disp.brightness_percent!=null&&disp.source!=='display_off'){
sl.value=disp.brightness_percent;
var lbl=document.getElementById('brightness-value');
if(lbl){lbl.textContent=disp.brightness_percent+'%';}}
// Updated on every poll and NOT gated on dataset.pending: the mode is a fact about the
// device, not something the operator is mid-way through editing, and it is exactly the
// thing that was previously stuck reading "automatic" after auto was switched off.
var md=document.getElementById('brightness-mode');
if(md&&disp.source){md.textContent='('+(disp.source==='display_off'?'display off':disp.source==='screensaver'?'screensaver':(disp.auto?'automatic':'manual'))+')';}
// The slider follows the mode, since a level set while the sensor is in charge is
// refused rather than applied.
if(sl&&disp.auto!=null){sl.disabled=!!disp.auto;}
show(lines.join('\n'));
// The System stats section's one-line summary, on the settings page, so the closed row reads
// like a status line without opening it.
var sum=document.getElementById('sum-stats');
if(sum){var parts=[];
if(sys.mem_used_kb&&sys.mem_total_kb){parts.push(Math.round(100*sys.mem_used_kb/sys.mem_total_kb)+'% memory');}
if(sys.cpu_busy_percent!=null){parts.push(Math.round(sys.cpu_busy_percent)+'% CPU');}
if(parts.length){sum.textContent=parts.join(' \u00b7 ');}}
var battery=document.getElementById('chip-battery');
if(battery){var pct=bat.present===false?null:bat.percent,mains=bat.present===false;
battery.textContent=mains?'mains':(pct==null?'--':Math.round(pct)+'%')+(bat.charge_state?' '+bat.charge_state:'');
// The battery glyph is a real gauge: the fill rectangle is resized in place, the bolt
// is revealed while charging, and a nearly flat panel on battery turns red.
var fillEl=document.getElementById('chip-battery-fill'),top=5.9,bottom=20.1,frac=pct==null?0:Math.max(0,Math.min(100,pct))/100;
fillEl.setAttribute('height',((bottom-top)*frac).toFixed(2));
fillEl.setAttribute('y',(bottom-(bottom-top)*frac).toFixed(2));
var low=pct!=null&&pct<15&&!bat.plugged,tone=low?'var(--bad)':'var(--text)';
document.getElementById('chip-battery-icon').style.color=tone;
battery.style.color=tone;
battery.parentNode.title=mains?'no battery, mains powered':'battery'+(bat.charge_state?', '+bat.charge_state:'');
var addr=document.getElementById('chip-address');
addr.textContent=net.ip_address||'--';
addr.parentNode.title='address of this panel on the network';
var ram=document.getElementById('chip-ram');
ram.textContent=(sys.mem_used_kb&&sys.mem_total_kb?Math.round(100*sys.mem_used_kb/sys.mem_total_kb)+'%':'--');
ram.parentNode.title='memory in use';
var cpu=document.getElementById('chip-cpu');
cpu.textContent=num(sys.cpu_busy_percent)+'%';
cpu.parentNode.title='processor load';}}
// Why this is not three lines and a setInterval.
//
// It used to be, and every way it could fail printed the same sentence: "stats
// unavailable, no response". That sentence was usually a lie. r.json() was called
// without looking at r.ok, so a 401, a 429 or a 500 had its plain-text body parsed as
// JSON, threw, and landed in the same catch as an actual dead socket. An operator was
// told the panel had not answered when it had answered perfectly clearly.
//
// Worse, the retry made a transient failure permanent. Every poll that reaches the
// tablet without usable credentials is an authentication failure to AuthThrottle, and
// FAILURES_BEFORE_LOCKOUT of them locks this whole machine out for thirty seconds,
// then a minute, doubling to fifteen. A fixed five-second retry walks straight up that
// ladder and stays there, which is a page that has taken itself off the air and is
// blaming the network. So: an unauthorised or throttled poll stops the loop instead of
// feeding it, and everything else backs off.
//
// setTimeout chained from the response, not setInterval: a poll slower than the
// interval used to overlap the next one, and two connections in flight where one was
// expected is what PER_HOST_CONNECTIONS counts.
var fails=0,shown=false,stopped=false;
function stop(text){stopped=true;show(text);}
// A refused poll is a normal event here, not an outage: the per-host connection cap
// exists to refuse them. Blanking a wall panel's whole readout for one, chip included,
// threw away good numbers to report a hiccup. The figures stay, with a line saying how
// stale they are.
function note(text){if(!target){return;}
var el=document.getElementById('stats-stale');
if(!el){el=document.createElement('p');el.id='stats-stale';el.className='hint';
target.parentNode.insertBefore(el,target.nextSibling);}
el.textContent=text;}
function clearNote(){var el=document.getElementById('stats-stale');
if(el){el.parentNode.removeChild(el);}}
function again(){if(stopped){return;}
setTimeout(poll,fails?Math.min(60000,5000*Math.pow(2,Math.min(fails,4))):5000);}
function ok(){fails=0;shown=true;clearNote();again();}
function bad(text){fails++;
if(shown){note(text+'; showing the last reading');}else{show(text);}
again();}
function poll(){fetch('/api/stats',{credentials:'same-origin'})
.then(function(r){
if(r.status===401||r.status===403){stop('stats unavailable: this browser was not authorised. Reload the page to sign in again.');return null;}
if(r.status===429){stop('stats unavailable: the panel is refusing this machine after too many failed sign-ins. Wait a minute, then reload.');return null;}
if(!r.ok){throw new Error('HTTP '+r.status);}
return r.json();})
.then(function(data){if(data===null){return;}
// A bug in render() is not the panel being unreachable, and reporting it as one sent
// somebody to check the network cable. The data arrived; say so, and log the reason.
try{render(data);}catch(e){shown=false;fails=0;clearNote();
show('stats received but could not be displayed: '+e.message);
if(window.console){console.error('Muralis: stats render failed',e);}
again();return;}
ok();})
.catch(function(error){if(stopped){return;}
bad('stats unavailable: '+((error&&error.message)||'no response'));});}
poll();
})();
