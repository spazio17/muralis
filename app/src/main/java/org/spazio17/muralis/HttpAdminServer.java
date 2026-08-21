/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A local HTTP control/admin surface mirroring the authenticated MQTT command set, the same way
 * Tasmota or WLED let HTTP and MQTT drive identical commands. Plaintext HTTP only, intended for a
 * trusted LAN exactly like the plain-TCP MQTT transport. Fails closed: no
 * socket is bound unless an admin password has been configured locally on the device first.
 */
final class HttpAdminServer {
    private static final String TAG = "MuralisHttp";
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 8;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    /** Long enough for a socket close to land, short enough that a reload never looks like a hang. */
    private static final int SHUTDOWN_WAIT_MS = 1_000;
    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_LINES = 40;
    private static final int MAX_BODY_BYTES = 16_384;
    private static final int WORKER_THREADS = 4;
    /** Backoff after a failed {@code accept()}; see the comment at that call site. */
    private static final long ACCEPT_RETRY_DELAY_MS = 250L;

    /**
     * Sends every command form in the background and reports the JSON result inline. Without this
     * the browser navigates to the raw /api/command response and the operator has to press Back
     * after each action. The forms keep working unchanged if scripting is unavailable, so this is
     * an enhancement rather than the only path. Basic-auth credentials ride along because the
     * request is same-origin.
     */
    /**
     * Runs every command form in the background and reports the result inline, so no press ever
     * navigates away from the page. Also drives the brightness slider, which applies as it moves
     * rather than needing a separate button. The plain forms keep working without scripting.
     */
    private static final String COMMAND_SCRIPT = "<script>\n"
            + "(function(){\n"
            // No on-page status line. It used to print every command and its result at the bottom of
            // the page ("display.auto_brightness: accepted"), which is developer output rather than
            // something an operator adjusting a wall panel needs to read. Removed at the user's
            // request 2026-08-19. Logged to the browser console instead, so a rejection is still
            // diagnosable rather than silently vanishing.
            + "function show(text,ok){if(!ok&&window.console){console.warn('Muralis: '+text);}"
            + "else if(window.console){console.log('Muralis: '+text);}}\n"
            + "function encode(form){var parts=[];\n"
            + "Array.prototype.forEach.call(form.elements,function(el){\n"
            + "if(el.name){parts.push(encodeURIComponent(el.name)+'='+encodeURIComponent(el.value));}"
            + "});\n"
            + "return parts.join('&');}\n"
            + "function send(query,label){\n"
            + "show(label+': sending...',true);\n"
            + "return fetch('/api/command?'+query,{credentials:'same-origin',"
            + "headers:{'Accept':'application/json'}})\n"
            + ".then(function(r){return r.text();})\n"
            + ".then(function(text){var detail=text,ok=true;\n"
            + "try{var parsed=JSON.parse(text);\n"
            + "detail=parsed.status+(parsed.detail?': '+parsed.detail:'');\n"
            + "ok=parsed.status!=='rejected';}catch(ignored){}\n"
            + "show(label+': '+detail,ok);})\n"
            + ".catch(function(){show(label+': no response. The device may be rebooting,"
            + " shutting down, or off the network',false);});}\n"
            + "Array.prototype.forEach.call(document.querySelectorAll('form.cmd'),function(form){\n"
            + "form.addEventListener('submit',function(event){\n"
            + "event.preventDefault();\n"
            + "send(encode(form),form.elements.cmnd.value);});});\n"
            + "var auto=document.getElementById('auto-brightness');\n"
            + "if(auto){auto.addEventListener('change',function(){\n"
            // Marked as user-driven so the stats poll does not fight the operator: without this, a
            // poll landing between the click and the tablet applying the change would snap the box
            // back and look like the click was ignored.
            + "auto.dataset.pending='1';\n"
            + "send('cmnd=display.auto_brightness&enabled='+(auto.checked?'1':'0'),"
            + "'display.auto_brightness')\n"
            + ".then(function(){setTimeout(function(){delete auto.dataset.pending;},1500);});"
            + "});}\n"
            + "var slider=document.getElementById('brightness');\n"
            + "if(slider){var label=document.getElementById('brightness-value'),timer=null;\n"
            + "slider.addEventListener('input',function(){\n"
            + "label.textContent=slider.value+'%';\n"
            // Without this the five-second poll would yank the thumb back mid-drag.
            + "slider.dataset.pending='1';\n"
            + "clearTimeout(timer);\n"
            + "// Debounced: dragging fires continuously and each command reaches the tablet.\n"
            + "timer=setTimeout(function(){\n"
            + "send('cmnd=display.brightness&percent='+slider.value,'display.brightness')\n"
            + ".then(function(){setTimeout(function(){delete slider.dataset.pending;},1500);});},250);"
            + "});}\n"
            + "})();\n"
            + "</script>";

    /**
     * Applies every standalone Behaviour control the moment it is touched, so that box needs no Save
     * button. The same shape as the brightness controls above, and for the same reason: a setting
     * that depends on nothing else has nothing to wait for.
     *
     * <p>The {@code data-pending} flag exists here for the reason it exists on the slider. The stats
     * poll below writes these controls from the device's real state every five seconds, and a poll
     * landing between the click and the tablet storing the change would snap the box back and make
     * the click look ignored.
     *
     * <p>Note the {@code change} event rather than {@code input}: a time field fires {@code input} on
     * every digit, so a half-typed "0" would be posted as 00:00 on the way to 04:00.
     */
    private static final String BEHAVIOUR_SCRIPT = "<script>\n"
            + "(function(){\n"
            + "function apply(el){\n"
            + "var value=el.type==='checkbox'?(el.checked?'1':'0'):el.value;\n"
            + "el.dataset.pending='1';\n"
            + "var body='section=behaviour&'+encodeURIComponent(el.dataset.setting)+'='"
            + "+encodeURIComponent(value);\n"
            + "fetch('/api/setting',{method:'POST',credentials:'same-origin',"
            + "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})\n"
            + ".then(function(r){return r.text();})\n"
            // Console, not the page: a wall-panel operator has no use for a running log of saves,
            // and a rejection still has to be diagnosable.
            + ".then(function(text){if(window.console){console.log('Muralis: '"
            + "+el.dataset.setting+': '+text);}})\n"
            + ".catch(function(){if(window.console){console.warn('Muralis: '+el.dataset.setting"
            + "+': no response. The device may be rebooting or off the network');}})\n"
            + ".then(function(){setTimeout(function(){delete el.dataset.pending;},1500);});}\n"
            + "Array.prototype.forEach.call(document.querySelectorAll('[data-setting]'),"
            + "function(el){el.addEventListener('change',function(){apply(el);});});\n"
            + "})();\n"
            + "</script>";

    /**
     * Live stats and the configuration actually applied on the device, so this page can be trusted
     * after somebody has changed something at the tablet, rather than showing whatever was current
     * when it was loaded.
     */
    private static final String STATS_SCRIPT = "<script>\n"
            + "(function(){\n"
            + "var target=document.getElementById('stats');\n"
            + "function mb(kb){return kb==null?'--':Math.round(kb/1024)+'M';}\n"
            + "function num(v,d){return v==null?'--':v.toFixed(d||0);}\n"
            + "function dur(ms){if(ms==null||ms<0){return '--';}\n"
            + "var s=Math.floor(ms/1000),d=Math.floor(s/86400),h=Math.floor(s%86400/3600),"
            + "m=Math.floor(s%3600/60);\n"
            + "return d>0?(d+'d'+h+'h'):(h>0?(h+'h'+m+'m'):(m+'m'));}\n"
            + "function render(data){\n"
            + "var sys=data.system||{},run=data.runtime||{},bat=data.battery||{},"
            + "net=data.network||{},mem=data.memory||{},cfg=data.config||{};\n"
            + "var lines=[];\n"
            + "lines.push('uptime '+dur(data.uptime_ms)+'   cpu '+num(sys.cpu_busy_percent)+'%'"
            + "+'   load '+(sys.load_average?num(sys.load_average[0],2):'--'));\n"
            + "lines.push('ram  '+mb(sys.mem_used_kb)+'/'+mb(sys.mem_total_kb)"
            + "+'   zram '+mb(sys.swap_used_kb)+'/'+mb(sys.swap_total_kb)"
            + "+(mem.low?'   LOW MEMORY':''));\n"
            + "lines.push('temp '+num(sys.cpu_temperature_c,1)+'C cpu   '"
            + "+num(sys.gpu_temperature_c,1)+'C gpu   batt '+num(bat.percent)+'%');\n"
            + "lines.push('ip   '+(net.ip_address||'--'));\n"
            + "lines.push('wifi '+(net.wifi_rssi_dbm!=null?net.wifi_rssi_dbm+'dBm':'--')"
            + "+'   renderer deaths '+(run.renderer_deaths!=null?run.renderer_deaths:'--')"
            + "+'   last load '+dur(run.last_page_finished_ago_ms)+' ago');\n"
            + "if(run.last_page_error){lines.push('last error '+run.last_page_error);}\n"
            + "lines.push('recycles '+(run.recycles||0));\n"
            + "var auto=document.getElementById('auto-brightness');\n"
            + "if(auto&&!auto.dataset.pending&&cfg.auto_brightness!=null){"
            + "auto.checked=cfg.auto_brightness;}\n"
            // The Behaviour box has no Save button, so nothing else would ever correct it after
            // somebody changed the same setting on the tablet or from a second browser.
            + "function follow(id,value){var el=document.getElementById(id);\n"
            + "if(!el||el.dataset.pending||value==null){return;}\n"
            + "if(el.type==='checkbox'){el.checked=value;}else if(el.value!==value){"
            + "el.value=value;}}\n"
            + "follow('stats-overlay',cfg.stats_overlay);\n"
            + "if(cfg.telemetry_interval_seconds!=null){"
            + "follow('telemetry-interval',String(cfg.telemetry_interval_seconds));}\n"
            // The slider follows the real backlight, except while the operator is actually dragging it.
            + "var disp=data.display||{},sl=document.getElementById('brightness');\n"
            + "if(sl&&!sl.dataset.pending&&disp.brightness_percent!=null){\n"
            + "sl.value=disp.brightness_percent;\n"
            + "var lbl=document.getElementById('brightness-value');\n"
            + "if(lbl){lbl.textContent=disp.brightness_percent+'%';}}\n"
            // Updated on every poll and NOT gated on dataset.pending: the mode is a fact about the
            // device, not something the operator is mid-way through editing, and it is exactly the
            // thing that was previously stuck reading "automatic" after auto was switched off.
            + "var md=document.getElementById('brightness-mode');\n"
            + "if(md&&disp.source){md.textContent='('+(disp.source==='display_off'?'display off':"
            + "(disp.auto?'automatic':'manual'))+')';}\n"
            // The slider follows the mode, since a level set while the sensor is in charge is
            // refused rather than applied.
            + "if(sl&&disp.auto!=null){sl.disabled=!!disp.auto;}\n"
            + "target.textContent=lines.join('\\n');\n"
            + "var battery=document.getElementById('chip-battery');\n"
            + "if(battery){var pct=bat.percent;\n"
            + "battery.textContent=(pct==null?'--':Math.round(pct)+'%')"
            + "+(bat.charge_state?' '+bat.charge_state:'');\n"
            // The battery glyph is a real gauge: the fill rectangle is resized in place, the bolt
            // is revealed while charging, and a nearly flat panel on battery turns red.
            + "var fillEl=document.getElementById('chip-battery-fill'),"
            + "top=5.9,bottom=20.1,frac=pct==null?0:Math.max(0,Math.min(100,pct))/100;\n"
            + "fillEl.setAttribute('height',((bottom-top)*frac).toFixed(2));\n"
            + "fillEl.setAttribute('y',(bottom-(bottom-top)*frac).toFixed(2));\n"

            + "var low=pct!=null&&pct<15&&!bat.plugged,tone=low?'var(--bad)':'var(--text)';\n"
            + "document.getElementById('chip-battery-icon').style.color=tone;\n"
            + "battery.style.color=tone;\n"
            + "battery.parentNode.title='battery'+(bat.charge_state?', '+bat.charge_state:'');\n"
            + "var addr=document.getElementById('chip-address');\n"
            + "addr.textContent=net.ip_address||'--';\n"
            + "addr.parentNode.title='address of this panel on the network';\n"
            + "var ram=document.getElementById('chip-ram');\n"
            + "ram.textContent=(sys.mem_used_kb&&sys.mem_total_kb?"
            + "Math.round(100*sys.mem_used_kb/sys.mem_total_kb)+'%':'--');\n"
            + "ram.parentNode.title='memory in use';\n"
            + "var cpu=document.getElementById('chip-cpu');\n"
            + "cpu.textContent=num(sys.cpu_busy_percent)+'%';\n"
            + "cpu.parentNode.title='processor load';}}\n"
            + "function poll(){fetch('/api/stats',{credentials:'same-origin'})\n"
            + ".then(function(r){return r.json();}).then(render)\n"
            + ".catch(function(){target.textContent='stats unavailable, no response';});}\n"
            + "poll();setInterval(poll,5000);\n"
            + "})();\n"
            + "</script>";


    /** Light / dark / follow-the-device, remembered in the browser rather than on the tablet. */
    private static final String THEME_SCRIPT = "<script>\n"
            + "(function(){\n"
            + "var KEY='kiosk-admin-theme';\n"
            + "function apply(mode){\n"
            + "document.documentElement.setAttribute('data-theme',mode);\n"
            + "Array.prototype.forEach.call(document.querySelectorAll('.themepick button'),\n"
            + "function(b){b.setAttribute('aria-pressed',String(b.dataset.theme===mode));});}\n"
            + "var saved=null;\n"
            + "try{saved=localStorage.getItem(KEY);}catch(e){}\n"
            + "apply(saved||'system');\n"
            + "document.addEventListener('click',function(event){\n"
            + "var button=event.target.closest?event.target.closest('.themepick button'):null;\n"
            + "if(!button){return;}\n"
            + "apply(button.dataset.theme);\n"
            + "try{localStorage.setItem(KEY,button.dataset.theme);}catch(e){}});\n"
            + "})();\n"
            + "</script>";


    /**
     * Catppuccin, Mocha and Latte, matching KioskTheme on the tablet. Sections flow into as many
     * columns as the window allows rather than one tall stack, which on a landscape tablet or a
     * desktop browser turned every text field into a full-width slab.
     */
    private static final String PAGE_CSS =
            ":root{color-scheme:light dark;"
            + "--base:#eff1f5;--mantle:#e6e9ef;--surface:#dce0e8;--surface-alt:#ccd0da;"
            + "--border:#bcc0cc;--text:#4c4f69;--subtext:#6c6f85;--accent:#8226ef;"
            + "--accent-alt:#1e66f5;--ok:#2fa019;--warn:#d68000;--bad:#d20f39;--radius:14px}"
            + "@media (prefers-color-scheme:dark){:root{"
            + "--base:#1e1e2e;--mantle:#181825;--surface:#313244;--surface-alt:#45475a;"
            + "--border:#585b70;--text:#cdd6f4;--subtext:#a6adc8;--accent:#c08cff;"
            + "--accent-alt:#7aa2ff;--ok:#8ee88a;--warn:#ffdf8f;--bad:#ff6f91}}"
            + "html[data-theme=light]{color-scheme:light;"
            + "--base:#eff1f5;--mantle:#e6e9ef;--surface:#dce0e8;--surface-alt:#ccd0da;"
            + "--border:#bcc0cc;--text:#4c4f69;--subtext:#6c6f85;--accent:#8226ef;"
            + "--accent-alt:#1e66f5;--ok:#2fa019;--warn:#d68000;--bad:#d20f39}"
            + "html[data-theme=dark]{color-scheme:dark;"
            + "--base:#1e1e2e;--mantle:#181825;--surface:#313244;--surface-alt:#45475a;"
            + "--border:#585b70;--text:#cdd6f4;--subtext:#a6adc8;--accent:#c08cff;"
            + "--accent-alt:#7aa2ff;--ok:#8ee88a;--warn:#ffdf8f;--bad:#ff6f91}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;padding:1.5rem 1.25rem 4rem;background:var(--base);color:var(--text);"
            + "font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;line-height:1.5}"
            + "main{max-width:1400px;margin:0 auto}"
            + "header{display:flex;flex-wrap:wrap;gap:1rem;align-items:center;"
            + "justify-content:space-between;margin-bottom:1.25rem}"
            + "h1{font-size:1.8rem;margin:0;color:var(--accent);font-weight:650}"
            + ".sub{color:var(--subtext);font-size:.9rem;margin:.1rem 0 0}"
            // The chip: one glyph per row so no bare percentage has to be guessed at. Monochrome in
            // the style of a Pixel status bar, matching StatusIcon on the tablet glyph for glyph.
            + ".chip{display:flex;flex-direction:column;align-items:flex-end;gap:.15rem;"
            + "line-height:1.3;font-size:.85rem;color:var(--subtext)}"
            + ".chip .row{display:flex;align-items:center;gap:.45rem}"
            + ".chip .row.pair{gap:1.1rem}"
            + ".ico{width:17px;height:17px;flex:none;color:var(--text)}"
            // Badges above the sections, the way Home Assistant floats them over a view.
            + ".badges{display:flex;justify-content:center;margin:0 0 1.25rem}"
            + ".notice{margin:0 0 1.25rem;padding:.7rem .9rem;border-radius:10px;"
            + "border-left:4px solid var(--bad);background:var(--mantle);color:var(--text);"
            + "font-size:.9rem}"
            + "label.inline{display:flex;align-items:center;gap:.6rem;color:var(--text);"
            + "font-size:.9rem;margin-top:.8rem}"
            + "label.inline input[type=time]{width:auto;margin:0;padding:.4rem .55rem;"
            + "border-radius:10px;border:1px solid var(--border);background:var(--surface-alt);"
            + "color:var(--text)}"
            + "label.inline input[type=range]{flex:1;accent-color:var(--accent)}"
            + "label.inline output{min-width:3.2rem;text-align:right;"
            + "font-variant-numeric:tabular-nums}"
            + ".themepick{display:flex;gap:.25rem;background:var(--surface);padding:.25rem;"
            + "border-radius:999px;border:1px solid var(--border)}"
            // Excluded from the 3D button treatment below: a segmented pill picker, not a
            // discrete action, and a shadowed edge on each pill would look like clutter.
            + ".themepick button{margin:0;padding:.35rem .85rem;border:0;border-radius:999px;"
            + "background:transparent;color:var(--subtext);font-size:.85rem;cursor:pointer;"
            + "box-shadow:none;transition:none}"
            + ".themepick button[aria-pressed=true]{background:var(--accent);color:var(--base);"
            + "font-weight:600}"
            // The sections layout: as many columns as fit, each at least 300px.
            + ".grid{display:grid;gap:1rem;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));"
            + "align-items:start}"
            + "fieldset{border:1px solid var(--accent);background:var(--surface);margin:0;"
            + "border-radius:var(--radius);padding:1rem 1.1rem 1.2rem}"
            + "legend{padding:0 .4rem;font-weight:600;font-size:.95rem}"
            + "label{display:block;margin-top:.7rem;color:var(--subtext);font-size:.8rem}"
            + "input[type=text],input[type=password],input[type=number],select{width:100%;"
            + "margin-top:.25rem;padding:.5rem .65rem;border-radius:10px;"
            + "border:1px solid var(--border);background:var(--surface-alt);color:var(--text);"
            + "font-size:.95rem}"
            + "input:focus,select:focus{outline:none;border-color:var(--accent-alt);"
            + "box-shadow:0 0 0 2px color-mix(in srgb,var(--accent-alt) 30%,transparent)}"
            + "input[type=checkbox]{width:auto;margin-right:.5rem;accent-color:var(--accent)}"
            + "label.check{display:flex;align-items:center;color:var(--text);font-size:.9rem;"
            + "margin-top:.7rem}"
            + ".hint{color:var(--subtext);font-size:.78rem;margin:.5rem 0 0}"
            // A slight 3D lift: a coloured "bottom edge" plus a soft shadow, both gone on
            // :active and the button nudged down a pixel, so pressing one reads as pressing
            // something solid rather than just a colour change.
            + "button{margin-top:.6rem;padding:.5rem .9rem;border-radius:10px;"
            + "border:1px solid var(--accent-alt);background:transparent;color:var(--accent-alt);"
            + "font-size:.9rem;cursor:pointer;"
            + "box-shadow:0 2px 0 0 var(--accent-alt),0 2px 4px rgba(0,0,0,.18);"
            + "transition:transform .08s ease,box-shadow .08s ease}"
            + "button:hover{background:color-mix(in srgb,var(--accent-alt) 12%,transparent)}"
            + "button:active{transform:translateY(2px);"
            + "box-shadow:0 0 0 0 transparent,0 1px 2px rgba(0,0,0,.15)}"
            + "button.primary{background:var(--accent);border-color:var(--accent);"
            + "color:var(--base);font-weight:600;"
            + "box-shadow:0 2px 0 0 color-mix(in srgb,var(--accent) 70%,black 20%),"
            + "0 2px 4px rgba(0,0,0,.22)}"
            + "button.primary:active{box-shadow:0 0 0 0 transparent,0 1px 2px rgba(0,0,0,.2)}"
            + ".actions{display:flex;flex-wrap:wrap;gap:.4rem}"
            + ".actions form{display:inline}"
            + ".slider{display:flex;align-items:center;gap:.75rem;margin-top:.6rem}"
            + ".slider input[type=range]{flex:1;accent-color:var(--accent)}"
            + ".slider output{min-width:3.2rem;text-align:right;font-variant-numeric:tabular-nums}"
            + "#stats{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8rem;"
            + "white-space:pre-wrap;background:var(--mantle);border-radius:10px;padding:.7rem;"
            + "overflow-x:auto;color:var(--subtext);margin:.6rem 0 0}"
            + "dl{display:grid;grid-template-columns:auto 1fr;gap:.25rem .8rem;margin:.6rem 0 0;"
            + "font-size:.85rem}"
            + "dt{color:var(--subtext)}"
            + "dd{margin:0;overflow-wrap:anywhere;font-family:ui-monospace,SFMono-Regular,Menlo,"
            + "monospace}"
            + "a{color:var(--accent-alt)}";

    private final Context context;
    private final KioskService kioskService;

    private ServerSocket serverSocket;
    /**
     * Every accepted connection, so {@code stop()} can close them. A worker blocked reading a
     * browser's speculative connection is not interruptible and only wakes when SOCKET_TIMEOUT_MS
     * expires, which made a configuration reload freeze the caller for ten seconds.
     */
    private final java.util.Set<Socket> liveSockets =
            Collections.synchronizedSet(new java.util.HashSet<>());
    private ExecutorService workers;
    private Thread acceptThread;
    private volatile boolean running;
    private String boundAdminPassword = "";

    HttpAdminServer(Context context, KioskService kioskService) {
        this.context = context;
        this.kioskService = kioskService;
    }

    void start() {
        KioskConfig config = KioskConfig.load(context);
        if (config.httpAdminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            Log.i(TAG, "HTTP admin disabled: no admin password of sufficient length is configured");
            KioskRuntimeState.publishHttpAdminState(false, config.httpPort);
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.httpPort));
        } catch (IOException exception) {
            Log.e(TAG, "Unable to bind HTTP admin port " + config.httpPort, exception);
            serverSocket = null;
            KioskRuntimeState.publishHttpAdminState(false, config.httpPort);
            return;
        }
        boundAdminPassword = config.httpAdminPassword;
        workers = Executors.newFixedThreadPool(WORKER_THREADS);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "MuralisHttpAccept");
        acceptThread.start();
        KioskRuntimeState.publishHttpAdminState(true, config.httpPort);
        Log.i(TAG, "HTTP admin listening on port " + config.httpPort);
    }

    void stop() {
        running = false;
        KioskRuntimeState.publishHttpAdminState(false, KioskRuntimeState.httpAdminPort());
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // The listening socket is being discarded either way.
            }
        }
        // Closing the accepted sockets is what actually unblocks the workers: a thread parked in a
        // socket read ignores interrupt(), so shutdownNow() alone left stop() waiting for the read
        // timeout. A configuration reload runs on the main thread, so that wait was a frozen UI.
        synchronized (liveSockets) {
            for (Socket socket : liveSockets) {
                closeQuietly(socket);
            }
            liveSockets.clear();
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(SHUTDOWN_WAIT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException exception) {
                if (running) {
                    Log.w(TAG, "HTTP admin accept failed", exception);
                    // Back off before retrying. accept() failing usually means the process is out
                    // of file descriptors, and that condition persists: retrying immediately spun
                    // this thread at 100% CPU with a log line per iteration, on a panel that is
                    // supposed to sit on a wall for months. A short sleep costs nothing on the
                    // one-off failures and turns the pathological case into a slow retry.
                    try {
                        Thread.sleep(ACCEPT_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            try {
                workers.execute(() -> handleConnection(socket));
            } catch (RejectedExecutionException busy) {
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket) {
        liveSockets.add(socket);
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            String requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                writeResponse(output, 400, "text/plain", bytes("Bad Request"));
                return;
            }
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = readLine(input, MAX_REQUEST_LINE_LENGTH);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            byte[] body = readBody(input, headers);

            if (!isAuthorized(headers.get("authorization"))) {
                Map<String, String> extra = new HashMap<>();
                extra.put("WWW-Authenticate", "Basic realm=\"Muralis\"");
                writeResponse(output, 401, "text/plain", bytes("Unauthorized"), extra);
                return;
            }

            route(method, target, headers, body, output);
        } catch (IOException exception) {
            Log.w(TAG, "HTTP admin connection error", exception);
        } catch (RuntimeException unexpected) {
            // The barrier that keeps a bad request from killing the panel.
            //
            // These run on an ExecutorService worker, so an escaping RuntimeException reaches
            // Android's default uncaught handler and takes the whole process down with it: the
            // dashboard blinks out and every uptime counter resets. That is unacceptable here for
            // any input, and it was reachable from a single malformed query string -- URLDecoder
            // throws IllegalArgumentException on a truncated escape like "100%", which the
            // documented `?cmnd=kiosk.set_url&url=...` workflow makes easy to send by accident.
            //
            // Fail soft like the rest of the app: log it, answer 500, keep serving.
            Log.w(TAG, "HTTP admin request failed", unexpected);
            try {
                writeResponse(socket.getOutputStream(), 500, "text/plain",
                        bytes("Internal Server Error"));
            } catch (IOException | RuntimeException ignored) {
                // The socket is already unusable, or the response was partly written. Nothing left
                // to say to this client; the point was to survive, and we have.
            }
        } finally {
            liveSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void route(
            String method, String target, Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        String path = target;
        String query = "";
        int questionMark = target.indexOf('?');
        if (questionMark >= 0) {
            path = target.substring(0, questionMark);
            query = target.substring(questionMark + 1);
        }

        if (path.equals("/") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildSettingsPage()));
        } else if (path.equals("/") && method.equals("POST")) {
            String refusal = saveSettings(parseFormBody(headers, body));
            writeResponse(output, refusal == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildSettingsPage(refusal)));
            if (refusal == null) {
                // Reload asynchronously through the service's own message queue: doing it inline
                // here would have this worker thread join the very executor it is running on.
                KioskService.reloadConfiguration(context);
            }
        } else if (path.equals("/api/setting") && method.equals("POST")) {
            handleSetting(parseFormBody(headers, body), output);
        } else if (path.equals("/api/command") && (method.equals("GET") || method.equals("POST"))) {
            handleCommand(method, query, headers, body, output);
        } else if (path.equals("/api/stats") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(kioskService.statsJson().toString()));
        } else if (path.equals("/privacy") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(renderLegalPage(
                    context.getString(R.string.privacy_policy_title), R.raw.privacy_policy)));
        } else if (path.equals("/terms") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(renderLegalPage(
                    context.getString(R.string.terms_title), R.raw.terms)));
        } else {
            writeResponse(output, 404, "text/plain", bytes("Not Found"));
        }
    }

    private void handleCommand(
            String method, String query, Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        String command;
        int percent = -1;
        String url = null;
        Boolean enabled = null;

        String contentType = headers.getOrDefault("content-type", "");
        if (method.equals("POST") && contentType.contains("application/json") && body.length > 0) {
            try {
                JSONObject envelope = new JSONObject(new String(body, StandardCharsets.UTF_8));
                command = envelope.optString("command", "");
                JSONObject args = envelope.optJSONObject("args");
                if (args != null) {
                    percent = args.optInt("percent", -1);
                    url = args.has("url") ? args.optString("url", null) : null;
                    if (args.has("enabled")) {
                        enabled = args.optBoolean("enabled", false);
                    }
                }
            } catch (JSONException malformed) {
                writeResponse(output, 400, "application/json",
                        bytes("{\"status\":\"rejected\",\"detail\":\"malformed json\"}"));
                return;
            }
        } else {
            Map<String, String> params = parseQuery(query);
            command = params.getOrDefault("cmnd", "");
            if (params.containsKey("percent")) {
                try {
                    percent = Integer.parseInt(params.get("percent"));
                } catch (NumberFormatException ignored) {
                    percent = -1;
                }
            }
            url = params.get("url");
            if (params.containsKey("enabled")) {
                String flag = params.get("enabled");
                // Accept the spellings a shell or a browser form is likely to send.
                enabled = "1".equals(flag) || "true".equalsIgnoreCase(flag)
                        || "on".equalsIgnoreCase(flag);
            }
        }

        if (command.isEmpty()) {
            writeResponse(output, 400, "application/json",
                    bytes("{\"status\":\"rejected\",\"detail\":\"missing command\"}"));
            return;
        }

        KioskCommandDispatcher.Result result = kioskService.dispatch(
                command, new KioskCommandDispatcher.CommandArgs(percent, url, enabled));
        JSONObject response = new JSONObject();
        try {
            response.put("status", result.status);
            response.put("detail", result.detail);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        writeResponse(output, 200, "application/json", bytes(response.toString()));
    }

    /**
     * Applies one standalone control, the moment it is touched, with no Save button anywhere near it.
     *
     * <p>Every field in the Behaviour box stands on its own: an overlay switch, a recycle switch and
     * a time. Nothing there has to agree with anything else, so making the operator tick a box and
     * then find a button is ceremony, and it is ceremony this page had already dropped for the
     * brightness slider and the auto-brightness checkbox. The boxes that keep their Save button,
     * Dashboard, MQTT, the web admin and the escape sequences, are the ones whose fields only mean
     * something together: a host with no password, or one sequence saved before the other, is a
     * half-applied setting, and for those a deliberate save is the point.
     *
     * <p>Deliberately confined to sections whose settings are read live by whoever uses them, so no
     * controller has to be restarted to apply one. Restarting the MQTT client and rebinding the admin
     * socket on every keystroke is not something to do behind an operator's back; those sections keep
     * their button and their explicit reload.
     */
    private void handleSetting(Map<String, String> form, OutputStream output) throws IOException {
        String section = form.getOrDefault("section", "");
        if (!section.equals("behaviour")) {
            writeResponse(output, 400, "application/json",
                    bytes("{\"status\":\"rejected\",\"detail\":\"not an instantly applied "
                            + "setting\"}"));
            return;
        }
        String refusal = saveSettings(form);
        if (refusal == null) {
            // The mirror of what KioskService.dispatch does after an accepted command, and needed
            // for the same reason: these controls bypass the dispatcher, so without this the change
            // reaches storage and the tablet but not Home Assistant, whose switch then disagrees
            // with the panel until the next telemetry tick, up to five minutes at the longest
            // interval preset.
            KioskService.publishTelemetrySoon(context);
        }
        JSONObject response = new JSONObject();
        try {
            response.put("status", refusal == null ? "accepted" : "rejected");
            response.put("detail", refusal == null ? "saved" : refusal);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        writeResponse(output, refusal == null ? 200 : 400, "application/json",
                bytes(response.toString()));
    }

    /**
     * Applies one box's fields. Returns a message to show the operator, or null when the save was
     * unremarkable. A password below the minimum used to be stored happily and then refuse to bind,
     * so the page that had just been used to set it became permanently unreachable with the reason
     * only in logcat.
     */
    private String saveSettings(Map<String, String> form) {
        KioskConfig fresh = KioskConfig.load(context);
        String section = form.getOrDefault("section", "main");

        // Each box on the page posts only its own fields. Applying everything from every post is
        // what made a partial form read as "all checkboxes unchecked".
        switch (section) {
            case "sequences":
                saveEscapeSequences(form, fresh);
                return null;
            case "dashboard":
                fresh.dashboardUrl = form.getOrDefault("dashboard_url", fresh.dashboardUrl).trim();
                fresh.deviceId = form.getOrDefault("device_id", fresh.deviceId).trim();
                break;
            case "mqtt": {
                fresh.mqttHost = form.getOrDefault("mqtt_host", fresh.mqttHost).trim();
                Integer brokerPort = parsePort(form.get("mqtt_port"), fresh.mqttPort);
                if (brokerPort == null) {
                    return "Broker port must be between 1 and 65535.";
                }
                fresh.mqttPort = brokerPort;
                fresh.mqttUsername = form.getOrDefault("mqtt_username", fresh.mqttUsername);
                String mqttPassword = form.get("mqtt_password");
                if (mqttPassword != null && !mqttPassword.isEmpty()) {
                    fresh.mqttPassword = mqttPassword;
                }
                break;
            }
            case "webadmin": {
                // Range-checked and REFUSED, not clamped, and this is the highest-severity input on
                // the page. ServerSocket.bind throws IllegalArgumentException, not IOException, for
                // a port outside 1-65535, and HttpAdminServer.start only catches IOException. So a
                // value like 99999 was persisted, answered with a success page, and then threw an
                // unchecked exception on the main thread inside restartControllers. START_STICKY
                // brings the service back, onCreate starts the controllers, and it throws again:
                // a permanent crash loop that survives reboot, takes the HOME activity down with
                // it, and cannot be undone from the panel because the panel no longer runs.
                Integer adminPort = parsePort(form.get("http_port"), fresh.httpPort);
                if (adminPort == null) {
                    return "Web admin port must be between 1 and 65535.";
                }
                fresh.httpPort = adminPort;
                String adminPassword = form.get("http_admin_password");
                if (adminPassword != null && !adminPassword.isEmpty()) {
                    if (adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
                        return "Password unchanged: it must be at least "
                                + MIN_ADMIN_PASSWORD_LENGTH + " characters. A shorter one would "
                                + "switch this page off, and only the tablet could switch it back "
                                + "on.";
                    }
                    fresh.httpAdminPassword = adminPassword;
                }
                break;
            }
            case "behaviour":
                // Presence used to carry the meaning, because an unchecked box sends nothing and
                // the whole box was posted at once. These controls now post one at a time as they
                // are touched, so presence would read every post as "the others were just
                // cleared". The value carries the meaning instead, and an absent key is simply not
                // being set.
                if (form.containsKey("stats_overlay")) {
                    fresh.statsOverlay = isTrue(form.get("stats_overlay"));
                }
                if (form.containsKey("telemetry_interval_seconds")) {
                    int seconds = parseIntOrDefault(
                            form.get("telemetry_interval_seconds"), -1);
                    // Rejected rather than clamped: the presets are the only values a select
                    // element can ever actually send, so anything else reaching here is not a real
                    // request, and clamping it to the default would silently substitute a value
                    // nobody asked for.
                    if (!TelemetryInterval.isValid(seconds)) {
                        return "telemetry interval must be 10, 30, 60 or 300 seconds";
                    }
                    fresh.telemetryIntervalSeconds = seconds;
                }
                break;
            default:
                Log.i(TAG, "Ignoring a settings post with no known section");
                return null;
        }
        fresh.save(context);
        return null;
    }

    /** The spellings a browser form, a shell or a hand-written client is likely to send. */
    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    /**
     * Escape sequences are saved only from their own form, and only when this page was rendered
     * from the values still stored on the device.
     *
     * <p>A page left open in a browser keeps the combination that was current when it loaded.
     * Submitting it later silently reverted a combination recorded on the tablet in the meantime,
     * which is exactly what happened twice on 2026-08-17. A missing baseline is treated as stale
     * rather than current, because the only pages without one are older than this check.
     */
    private void saveEscapeSequences(Map<String, String> form, KioskConfig fresh) {
        String baseline = form.get("sequence_baseline");
        String current = fresh.settingsSequence + "|" + fresh.launcherSequence;
        if (baseline == null || !baseline.equals(current)) {
            Log.i(TAG, "Ignoring escape sequences from a page that no longer matches the device");
            return;
        }

        String settingsSequence = form.get("settings_sequence");
        if (settingsSequence != null
                && EscapeSequence.isValid(EscapeSequence.parse(settingsSequence))) {
            fresh.settingsSequence = EscapeSequence.format(EscapeSequence.parse(settingsSequence));
        }
        String launcherSequence = form.get("launcher_sequence");
        if (launcherSequence != null
                && EscapeSequence.isValid(EscapeSequence.parse(launcherSequence))
                && !EscapeSequence.format(EscapeSequence.parse(launcherSequence))
                        .equals(fresh.settingsSequence)) {
            fresh.launcherSequence = EscapeSequence.format(EscapeSequence.parse(launcherSequence));
        }
        fresh.saveEscapeSequences(context);
    }

    private boolean isAuthorized(String authorizationHeader) {
        // Explicit, not merely implied by start() refusing to bind without a password. Left to the
        // comparison below, an empty expected password matched an empty supplied one and
        // authorized everyone, unreachable today, but only by an invariant enforced in a
        // different method, which is one refactor away from an authentication bypass.
        if (boundAdminPassword.isEmpty()) {
            return false;
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(authorizationHeader.substring(6).trim());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        String credentials = new String(decoded, StandardCharsets.UTF_8);
        int colon = credentials.indexOf(':');
        String password = colon >= 0 ? credentials.substring(colon + 1) : credentials;
        byte[] expected = boundAdminPassword.getBytes(StandardCharsets.UTF_8);
        byte[] actual = password.getBytes(StandardCharsets.UTF_8);
        return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
    }

    private String buildSettingsPage() {
        return buildSettingsPage(null);
    }

    private String buildSettingsPage(String notice) {
        KioskConfig config = KioskConfig.load(context);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Muralis admin</title><style>")
                .append(PAGE_CSS)
                .append("</style></head><body><main>")
                .append("<header><div><h1>Muralis</h1><p class=\"sub\">")
                .append(escapeHtml(config.deviceId)).append("</p></div>")
                .append(statusChip())
                .append("</header>")
                .append("<div class=\"badges\">")
                .append("<div class=\"themepick\" role=\"group\" aria-label=\"Colour theme\">")
                .append("<button type=\"button\" data-theme=\"system\">Auto</button>")
                .append("<button type=\"button\" data-theme=\"light\">Light</button>")
                .append("<button type=\"button\" data-theme=\"dark\">Dark</button>")
                .append("</div></div>");

        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }

        // Every box that can be saved carries its own form and its own button, so no control is
        // stranded away from the thing that saves it.
        html.append("<div class=\"grid\">")

                .append(sectionFormStart("dashboard", "Dashboard"))
                .append(field("text", "dashboard_url", "Dashboard URL", config.dashboardUrl))
                .append(field("text", "device_id", "Device ID", config.deviceId))
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("mqtt", "MQTT"))
                .append(field("text", "mqtt_host", "Broker host", config.mqttHost))
                .append(field("number", "mqtt_port", "Broker port",
                        Integer.toString(config.mqttPort)))
                .append(field("text", "mqtt_username", "Username", config.mqttUsername))
                .append(field("password", "mqtt_password",
                        "Password (blank keeps the current one)", ""))
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("webadmin", "Local web admin"))
                .append(field("number", "http_port", "Port", Integer.toString(config.httpPort)))
                .append(field("password", "http_admin_password",
                        "Admin password (blank keeps the current one)", ""))
                .append("<p class=\"hint\">At least ").append(MIN_ADMIN_PASSWORD_LENGTH)
                .append(" characters. No username.</p>")
                .append(sectionFormEnd("Save"))

                // No form and no Save button: every control here stands alone and applies itself,
                // the way the brightness slider and the auto-brightness checkbox already did.
                // data-setting names the field each control posts; see BEHAVIOUR_SCRIPT.
                .append("<fieldset><legend>Behaviour</legend>")
                .append("<label class=\"check\"><input type=\"checkbox\" ")
                .append("data-setting=\"stats_overlay\" id=\"stats-overlay\"")
                .append(config.statsOverlay ? " checked" : "")
                .append("> Show system stats on the dashboard</label>")
                .append("<label>MQTT update interval")
                .append("<select data-setting=\"telemetry_interval_seconds\" ")
                .append("id=\"telemetry-interval\">")
                .append(telemetryIntervalOption(10, "10 seconds", config.telemetryIntervalSeconds))
                .append(telemetryIntervalOption(30, "30 seconds", config.telemetryIntervalSeconds))
                .append(telemetryIntervalOption(60, "60 seconds", config.telemetryIntervalSeconds))
                .append(telemetryIntervalOption(300, "5 minutes", config.telemetryIntervalSeconds))
                .append("</select></label>")
                .append("</fieldset>")

                .append(sectionFormStart("sequences", "Escape sequences"))
                .append("<input type=\"hidden\" name=\"sequence_baseline\" value=\"")
                .append(escapeHtml(config.settingsSequence + "|" + config.launcherSequence))
                .append("\">")
                .append("<p class=\"hint\">Corner taps in order: TL, TR, BL, BR, separated by ")
                .append("commas. Between ").append(EscapeSequence.MIN_LENGTH).append(" and ")
                .append(EscapeSequence.MAX_LENGTH).append(" taps. Recording them on the tablet ")
                .append("is easier than typing them here.</p>")
                .append(field("text", "settings_sequence", "Open Muralis settings",
                        config.settingsSequence))
                .append(field("text", "launcher_sequence", "Exit to the system launcher",
                        config.launcherSequence))
                .append(sectionFormEnd("Save"))

                .append("<fieldset><legend>Quick actions</legend><div class=\"actions\">")
                .append(quickAction("kiosk.reload", "Reload"))
                .append(quickAction("kiosk.restart", "Restart kiosk"))
                .append(quickAction("system.reboot", "Reboot"))
                .append("</div></fieldset>")

                .append("<fieldset><legend>Display</legend><div class=\"actions\">")
                .append(quickAction("display.wake", "Wake display"))
                .append(quickAction("display.visual_off", "Display off"))
                .append("</div>")
                .append(brightnessControl())
                .append(autoBrightnessControl())
                .append("</fieldset>")

                .append("<fieldset><legend>Open a URL now</legend>")
                .append("<form class=\"cmd\" method=\"get\" action=\"/api/command\">")
                .append("<input type=\"hidden\" name=\"cmnd\" value=\"kiosk.set_url\">")
                .append("<input type=\"text\" name=\"url\" ")
                .append("placeholder=\"http://homeassistant.local:8123/\">")
                .append("<button type=\"submit\">Go</button></form></fieldset>")

                .append("<fieldset><legend>System stats</legend>")
                .append("<pre id=\"stats\">loading...</pre></fieldset>")

                .append("<fieldset><legend>Legal</legend><div class=\"actions\">")
                .append(navButton("/privacy", context.getString(R.string.privacy_policy_title)))
                .append(navButton("/terms", context.getString(R.string.terms_title)))
                .append("</div></fieldset>")

                .append("</div>");

        html.append(COMMAND_SCRIPT);
        html.append(BEHAVIOUR_SCRIPT);
        html.append(STATS_SCRIPT);
        html.append(THEME_SCRIPT);
        html.append("</main></body></html>");
        return html.toString();
    }

    /**
     * Renders one legal document ({@code res/raw/privacy_policy.txt} or {@code terms.txt}) as a
     * standalone page, for parity with the tablet's own About screen. Behind the same Basic Auth as
     * everything else on this server: whoever reaches this page already reached the rest of it.
     *
     * <p>Blocks are separated by blank lines; an ALL-CAPS block is a heading, matching the format
     * {@code KioskActivity#addDocumentBlocks} renders on the tablet.
     */
    private String renderLegalPage(String title, int rawRes) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(title)).append(" &middot; Muralis</title>")
                .append("<style>").append(PAGE_CSS)
                .append("main{max-width:640px}h2{color:var(--accent);font-size:1rem;"
                        + "letter-spacing:.04em;margin:1.6rem 0 .4rem}"
                        + "p.doc{margin:.2rem 0}</style></head><body><main>")
                .append(navButton("/", "← Back"))
                .append("<h1>").append(escapeHtml(title)).append("</h1>")
                .append("<p class=\"notice\">")
                .append(escapeHtml(context.getString(R.string.legal_draft_warning)))
                .append("</p>");
        for (String block : readRawText(rawRes).trim().split("\n\\s*\n")) {
            String content = block.trim();
            if (content.isEmpty()) {
                continue;
            }
            boolean heading = content.equals(content.toUpperCase(Locale.ROOT))
                    && content.chars().anyMatch(Character::isLetter);
            html.append(heading ? "<h2>" : "<p class=\"doc\">")
                    .append(escapeHtml(content))
                    .append(heading ? "</h2>" : "</p>");
        }
        html.append("</main></body></html>");
        return html.toString();
    }

    /** Reads a {@code res/raw} text file in full, mirroring {@code KioskActivity#readRawText}. */
    private String readRawText(int rawRes) {
        try (InputStream in = context.getResources().openRawResource(rawRes);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not read document resource", unavailable);
            return "This document could not be loaded.";
        }
    }

    /**
     * The brightness slider, positioned at the backlight's actual level.
     *
     * <p>It used to be hardcoded to {@code value="70"} and never moved, which was worse than
     * uninformative: with the ambient sensor covered the panel was almost black while the slider still
     * read 70%, so the page confidently contradicted the device. Reported from the panel 2026-08-19.
     *
     * <p>Rendered from {@code KioskService.statsJson().display} and then kept live by
     * {@code STATS_SCRIPT} on every poll, which matters most in automatic mode, where the value moves
     * on its own as the light changes and no page load would ever catch up.
     */
    private String brightnessControl() {
        int percent = 70;
        String mode = "manual";
        boolean sensorInCharge = false;
        try {
            org.json.JSONObject display = kioskService.statsJson().optJSONObject("display");
            if (display != null) {
                percent = display.optInt("brightness_percent", 70);
                sensorInCharge = display.optBoolean("auto", false);
                mode = describeBrightnessMode(display.optString("source"), sensorInCharge);
            }
        } catch (RuntimeException unavailable) {
            // Fall back to the old fixed position rather than dropping the control.
        }
        percent = Math.max(1, Math.min(100, percent));
        // The mode label is ALWAYS rendered and carries an id, so STATS_SCRIPT can keep it current.
        // It first shipped as a bare "(automatic)" with no id, emitted only in automatic mode: it never
        // changed to "manual" when the checkbox was cleared, and never changed at all after page load,
        // which is precisely the staleness this control was rewritten to remove. Naming the mode is
        // worth doing, because in automatic mode the slider is a readout the sensor keeps moving, and
        // dragging it takes the panel away from the sensor.
        // Disabled while the sensor is in charge, because since 2026-08-20 a level set in automatic
        // mode is refused rather than written. It was refused for a good reason (the backlight never
        // followed it, so the panel reported a brightness nobody was looking at), but a control that
        // moves and is then rejected is its own small lie. The mode label beside it says why, and
        // STATS_SCRIPT keeps both in step with the switch.
        return "<label class=\"inline\">Brightness"
                + "<input type=\"range\" id=\"brightness\" min=\"1\" max=\"100\" value=\""
                + percent + "\"" + (sensorInCharge ? " disabled" : "") + ">"
                + "<output id=\"brightness-value\">" + percent + "%</output>"
                + " <small id=\"brightness-mode\">(" + mode + ")</small></label>";
    }

    /**
     * Names the brightness mode, from the checkbox and nothing else.
     *
     * <p>Two states only, by design. There was briefly a third, "manual override", for a window
     * override pinned above automatic mode; it was removed by making {@code display.brightness} write
     * the system setting instead, so the state cannot arise. **The checkbox is the mode**: ticked means
     * the sensor decides, cleared means the slider does, and this label can never contradict it.
     *
     * <p>{@code display_off} is not a mode. It is the {@code display.visual_off} presentation state,
     * where the panel is dimmed to 1% behind a black overlay, and it says so rather than claiming the
     * operator chose 1%.
     */
    private static String describeBrightnessMode(String source, boolean autoMode) {
        if ("display_off".equals(source)) {
            return "display off";
        }
        return autoMode ? "automatic" : "manual";
    }

    /**
     * The automatic-brightness control: a checkbox showing state, or nothing at all.
     *
     * <p>Omitted entirely on hardware with no ambient light sensor, because a control that cannot work
     * is worse than an absent one. The command behind it agrees:
     * {@code display.auto_brightness} is rejected with "this device has no ambient light sensor"
     * rather than silently accepted.
     *
     * <p><b>This was a button whose label flipped</b>, reading "Switch to manual brightness" when
     * automatic was on. Two problems, and the second was a bug. It described an action while looking
     * like a statement of state, which is genuinely ambiguous; and the state was read once when the
     * page was rendered and never refreshed, so if the mode changed afterwards, from the tablet's own
     * shade toggle or another browser, the button kept sending the stale value and did the opposite of
     * what it said. A checkbox shows the state directly, and {@code STATS_SCRIPT} keeps it honest by
     * syncing it from every poll.
     *
     * <p>It maps to exactly the setting a person toggles next to the brightness slider in the
     * notification shade: {@code Settings.System.SCREEN_BRIGHTNESS_MODE}, system-wide, not an
     * app-local preference.
     */
    private String autoBrightnessControl() {
        if (!KioskService.hasLightSensor(context)) {
            return "";
        }
        boolean on = KioskService.isAutoBrightnessOn(context);
        return "<label class=\"check\"><input type=\"checkbox\" id=\"auto-brightness\""
                + (on ? " checked" : "") + "> Adjust brightness automatically</label>";
    }

    /**
     * The status chip: battery, network, address and load, each with its own glyph.
     *
     * <p>The five glyphs are the same shapes {@link StatusIcon} draws on the tablet, at the same
     * 24-unit geometry, and they are monochrome for the same reason, one tint for the whole family,
     * strength carried by shape. They inherit {@code currentColor}, so the theme switch above
     * recolours them with no extra work. Keep the two sets in step when either changes.
     */
    private static String statusChip() {
        return "<div class=\"chip\">"
                + "<div class=\"row\"><span id=\"chip-battery\">--</span>"
                + BATTERY_SVG + "</div>"
                + "<div class=\"row\"><span id=\"chip-address\">--</span>"
                + ADDRESS_SVG + "</div>"
                + "<div class=\"row pair\">"
                + "<span class=\"row\"><span id=\"chip-ram\">--</span>" + MEMORY_SVG + "</span>"
                + "<span class=\"row\"><span id=\"chip-cpu\">--</span>" + CPU_SVG + "</span>"
                + "</div></div>";
    }

    private static final String SVG_OPEN =
            "<svg class=\"ico\" viewBox=\"0 0 24 24\" fill=\"none\" "
            + "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" "
            + "stroke-linejoin=\"round\" aria-hidden=\"true\">";

    /**
     * Upright cell with a nub, filled from the bottom. No charging bolt, for the same reason
     * {@code StatusIcon} has none: it is a smudge at this size, and the words beside it say which way
     * the charge is going.
     */
    private static final String BATTERY_SVG =
            "<svg class=\"ico\" id=\"chip-battery-icon\" viewBox=\"0 0 24 24\" "
            + "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" "
            + "stroke-linejoin=\"round\" role=\"img\" aria-label=\"battery\">"
            + "<rect x=\"10\" y=\"1.9\" width=\"4\" height=\"2.3\" rx=\"1\" "
            + "fill=\"currentColor\" stroke=\"none\"/>"
            + "<rect x=\"6.5\" y=\"4\" width=\"11\" height=\"18\" rx=\"3\"/>"
            + "<rect id=\"chip-battery-fill\" x=\"8.4\" y=\"20.1\" width=\"7.2\" "
            + "height=\"0\" rx=\"1\" fill=\"currentColor\" stroke=\"none\"/>"
            + "</svg>";

    /** A globe: the address is how anything else on the network reaches this panel. */
    private static final String ADDRESS_SVG = SVG_OPEN
            + "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18\"/>"
            + "<ellipse cx=\"12\" cy=\"12\" rx=\"4.2\" ry=\"9\"/></svg>";

    /** A memory chip with its pins, the conventional glyph for RAM. */
    private static final String MEMORY_SVG = SVG_OPEN
            + "<rect x=\"6\" y=\"6\" width=\"12\" height=\"12\" rx=\"2\"/>"
            + "<rect x=\"9.5\" y=\"9.5\" width=\"5\" height=\"5\" rx=\"1\" "
            + "fill=\"currentColor\" stroke=\"none\"/>"
            + "<path stroke-width=\"1.4\" d=\"M9 3v3M12 3v3M15 3v3M9 18v3M12 18v3M15 18v3"
            + "M3 9h3M3 12h3M3 15h3M18 9h3M18 12h3M18 15h3\"/></svg>";

    /** A dial with a needle: how hard the thing is working. */
    private static final String CPU_SVG = SVG_OPEN
            + "<path d=\"M3.5 16 A8.5 8.5 0 0 1 20.5 16\"/><path d=\"M12 16 L16.6 10\"/>"
            + "<circle cx=\"12\" cy=\"16\" r=\"1.5\" fill=\"currentColor\" "
            + "stroke=\"none\"/></svg>";

    /** Opens a box that is itself a form, so its save button sits inside it. */
    private static String sectionFormStart(String section, String legend) {
        return "<form method=\"post\" action=\"/\"><fieldset><legend>" + escapeHtml(legend)
                + "</legend><input type=\"hidden\" name=\"section\" value=\"" + section + "\">";
    }

    private static String sectionFormEnd(String label) {
        return "<button class=\"primary\" type=\"submit\">" + escapeHtml(label)
                + "</button></fieldset></form>";
    }

    private static String telemetryIntervalOption(int seconds, String label, int current) {
        return "<option value=\"" + seconds + "\""
                + (seconds == current ? " selected" : "") + ">" + escapeHtml(label) + "</option>";
    }

    private static String field(String type, String name, String label, String value) {
        return "<label>" + escapeHtml(label) + "<input type=\"" + type + "\" name=\"" + name
                + "\" value=\"" + escapeHtml(value) + "\"></label>";
    }

    private static String quickAction(String command, String label) {
        return "<form class=\"cmd\" method=\"get\" action=\"/api/command\" style=\"display:inline\">"
                + "<input type=\"hidden\" name=\"cmnd\" value=\"" + command + "\">"
                + "<button type=\"submit\">" + escapeHtml(label) + "</button></form>";
    }

    /** A plain navigation, styled as a button rather than a hyperlink, matching quickAction(). */
    private static String navButton(String path, String label) {
        return "<form method=\"get\" action=\"" + path + "\" style=\"display:inline\">"
                + "<button type=\"submit\">" + escapeHtml(label) + "</button></form>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * A TCP port, or null when the caller sent something that is not one.
     *
     * <p>Null rather than the fallback so the caller can refuse with a reason. The tablet's
     * equivalent clamps ({@code KioskActivity.parsePort}) because a stored value has to yield
     * something usable whatever is in it; a form submission is somebody asking for a specific
     * thing, and silently substituting 8080 for the 99999 they typed is the quiet substitution this
     * project keeps deleting. An absent field means "not being set" and keeps the current value.
     */
    private static Integer parsePort(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            String decodedKey = decodeOrNull(key);
            String decodedValue = decodeOrNull(value);
            if (decodedKey != null && decodedValue != null) {
                result.put(decodedKey, decodedValue);
            }
            // A pair that will not decode is dropped rather than failing the whole request: the
            // command handlers already reject a missing argument with a useful message, which is a
            // better answer than a blanket 500 for one bad field among several.
        }
        return result;
    }

    /**
     * Percent-decodes one field, or returns null if it is malformed.
     *
     * <p>{@code URLDecoder.decode} throws {@link IllegalArgumentException} on a truncated or
     * non-hex escape, {@code "100%"}, {@code "%zz"}, {@code "abc%2"}. That used to travel all the
     * way out of the worker thread and kill the process, which a documented curl one-liner could
     * trigger just by passing a URL containing a bare {@code %}.
     */
    private static String decodeOrNull(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Map<String, String> parseFormBody(Map<String, String> headers, byte[] body) {
        String contentType = headers.getOrDefault("content-type", "");
        if (!contentType.contains("application/x-www-form-urlencoded")) {
            return Collections.emptyMap();
        }
        return parseQuery(new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream input, int maxLength) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            buffer.write(b);
            if (buffer.size() > maxLength) {
                throw new IOException("request line too long");
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers)
            throws IOException {
        String lengthHeader = headers.get("content-length");
        if (lengthHeader == null) {
            return new byte[0];
        }
        int length;
        try {
            length = Integer.parseInt(lengthHeader.trim());
        } catch (NumberFormatException invalid) {
            return new byte[0];
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > MAX_BODY_BYTES) {
            throw new IOException("request body too large");
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int chunk = input.read(body, read, length - read);
            if (chunk == -1) {
                throw new IOException("unexpected end of body");
            }
            read += chunk;
        }
        return body;
    }

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body) throws IOException {
        writeResponse(output, status, contentType, body, Collections.emptyMap());
    }

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status))
                .append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("\r\n");
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 413:
                return "Payload Too Large";
            default:
                return "Error";
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket is being discarded either way.
        }
    }
}
