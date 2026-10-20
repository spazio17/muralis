/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws cameras in a web view without anything they send reaching the device's storage. The view
 * shows a small page of the app's own at {@code http://muralis.camera/}; each camera is a tile in
 * it. Every request the view makes is fetched here, from memory to the screen, by
 * {@link HttpURLConnection} with no response cache, so nothing enters the browser engine's HTTP
 * cache; cookies a camera sets are dropped; the view's cache mode is "no cache" as a second guard
 * and its cache is cleared when it closes. A camera's sign-in is added here, Basic or Digest as the
 * camera asks ({@link DigestAuth}), and only for requests to that camera's own host, so it never
 * sits in an address the page can see.
 *
 * <p>A tile first asks what the camera sends ({@code /probe/i}): an MJPEG stream or a picture is
 * drawn from {@code /stream/i}, a picture fetched again every two seconds; a camera page is the
 * page itself (the whole view for one camera). A camera that does not answer says so in its tile
 * ("No picture", "Sign-in refused" or "Certificate not trusted") and is asked again, at growing
 * pauses. Only a camera's name is ever logged.
 */
final class CameraView {
    private static final String TAG = "MuralisCamera";
    static final String BASE = "http://muralis.camera/";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    /** For the status publish when a camera's certificate starts waiting for Trust. */
    private static volatile android.content.Context app;

    private CameraView() {
    }

    /** How a view draws its cameras; the defaults are a plain card's: no zoom, no opening. */
    static final class Options {
        /** The camera on show may be pinched up to four times, a double tap back. */
        boolean zoom;
        /** A tap opens one camera of a grid over the whole view, a tap brings the grid back. */
        boolean focus;
        /** The camera on show alone uses its full-screen address when it has one. */
        boolean big;
        /** "auto", or a fixed layout: "1+3", "1+5", "2x2", "3x3" ({@link Cameras#LAYOUTS}). */
        String layout = "auto";
        /** The camera of the big tile in "1+3" and "1+5", by its place in the list. */
        int bigTile;
        /** Each tile's name on it. */
        boolean names;
        /** The browser engine died under the view: what the owner does, close or rebuild. */
        Runnable gone;
    }

    /**
     * Sets {@code web} up for {@code sources} and starts drawing them; {@code pins} are the
     * certificates a person trusted, host to fingerprint, as for pages.
     */
    static void show(WebView web, List<Cameras.Tile> sources, Map<String, String> pins,
            Options options) {
        final Runnable gone = options.gone;
        final boolean zoom = options.zoom;
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(zoom && sources.size() == 1);
        settings.setDisplayZoomControls(false);
        final List<Cameras.Tile> list = new ArrayList<>(sources);
        app = web.getContext().getApplicationContext();
        final Map<String, String> trusted = new LinkedHashMap<>(pins);
        final Streams streams = new Streams();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                return intercept(list, trusted, streams, request);
            }

            /**
             * The browser engine died or was killed for memory. It is one process for all of the
             * app's web views, so a view that does not answer this takes the whole app down with
             * it; this one is handed to its owner to close or rebuild, as the dashboard's is.
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "The browser engine under the cameras is gone");
                streams.closeAll();
                if (gone != null) {
                    view.post(gone);
                }
                return true;
            }
        });
        web.loadDataWithBaseURL(BASE, page(list, options), "text/html", "utf-8", null);
    }

    /** Clears what the view may hold, then destroys it. */
    static void close(WebView web) {
        web.stopLoading();
        web.clearCache(true);
        web.destroy();
    }

    /**
     * The stream each tile holds open: a tile emptied by the page (another opened over the
     * grid) asks for its own to be closed ({@code /stop/i}), since the browser engine keeps
     * reading a stream whose picture is gone. Only the newest is kept: a tile asks again only
     * once its last one ended (a snapshot every two seconds, a stream after a failure), so the
     * one before is closed, and nothing piles up on a view that runs for weeks.
     */
    static final class Streams {
        private final Map<Integer, HttpURLConnection> open = new java.util.HashMap<>();

        void add(int tile, HttpURLConnection connection) {
            HttpURLConnection before;
            synchronized (this) {
                before = open.put(tile, connection);
            }
            if (before != null && before != connection) {
                before.disconnect();
            }
        }

        void close(int tile) {
            HttpURLConnection connection;
            synchronized (this) {
                connection = open.remove(tile);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }

        void closeAll() {
            List<Integer> tiles;
            synchronized (this) {
                tiles = new ArrayList<>(open.keySet());
            }
            for (int tile : tiles) {
                close(tile);
            }
        }
    }

    static WebResourceResponse intercept(List<Cameras.Tile> sources, Map<String, String> pins,
            Streams streams, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (url.startsWith(BASE)) {
            String path = request.getUrl().getPath();
            int index = indexIn(path, sources.size());
            if (index < 0) {
                return text(404, "Not Found", "text/plain", "");
            }
            Cameras.Tile source = sources.get(index);
            // The bigger address, asked for when the camera is on show alone.
            String address = "1".equals(request.getUrl().getQueryParameter("full"))
                    && !source.full.isEmpty() ? source.full : source.url;
            if (path.startsWith("/probe/")) {
                return probe(source, address, pins);
            }
            if (path.startsWith("/stream/")) {
                // The camera's own sign-in: an NVR's channels share a host, not always a user.
                return fetch(Cameras.fill(address, source.login), source.login, null,
                        source.name, pins, connection -> streams.add(index, connection));
            }
            if (path.startsWith("/stop/")) {
                streams.close(index);
                return text(204, "No Content", "text/plain", "");
            }
            return text(404, "Not Found", "text/plain", "");
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !(url.startsWith("http://") || url.startsWith("https://"))) {
            // A post goes to the network as it is: the browser engine never caches one.
            return null;
        }
        // A camera page's own requests may name the sign-in as {user} and {password} too.
        Cameras.Login login = loginFor(sources, url);
        return fetch(Cameras.fill(url, login), login, request.getRequestHeaders(), null, pins,
                null);
    }

    private static int indexIn(String path, int count) {
        if (path == null) {
            return -1;
        }
        String[] parts = path.split("/");
        if (parts.length != 3) {
            return -1;
        }
        try {
            int index = Integer.parseInt(parts[2]);
            return index >= 0 && index < count ? index : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** The sign-in of the camera whose host {@code url} names; null when none. */
    private static Cameras.Login loginFor(List<Cameras.Tile> sources, String url) {
        String host = Cameras.hostOf(url);
        for (Cameras.Tile source : sources) {
            if (source.login != null && !host.isEmpty() && (host.equals(Cameras.hostOf(source.url))
                    || host.equals(Cameras.hostOf(source.full)))) {
                return source.login;
            }
        }
        return null;
    }

    /** What a camera sends, as the tile's script needs it: mjpeg, image, page or none. */
    private static WebResourceResponse probe(Cameras.Tile source, String address,
            Map<String, String> pins) {
        String kind;
        HttpURLConnection connection = null;
        try {
            connection = open(Cameras.fill(address, source.login), source.login, null, pins);
            int status = connection.getResponseCode();
            String type = connection.getContentType() == null ? ""
                    : connection.getContentType().toLowerCase(Locale.ROOT);
            if (status == 401 || status == 403) {
                kind = "denied";
                Log.i(TAG, "Camera " + source.name + " refused the sign-in");
            } else if (status >= 400) {
                kind = "none";
                Log.i(TAG, "Camera " + source.name + " answered " + status);
            } else if (type.startsWith("multipart/")) {
                kind = "mjpeg";
            } else if (type.startsWith("image/")) {
                kind = "image";
            } else {
                kind = "page";
            }
        } catch (javax.net.ssl.SSLException secure) {
            // A certificate refused, or another TLS failure (an old camera's protocol), which
            // is simply no picture.
            boolean certificate = false;
            for (Throwable cause = secure; cause != null; cause = cause.getCause()) {
                certificate |= cause instanceof java.security.cert.CertificateException;
            }
            kind = certificate ? "untrusted" : "none";
            if (!certificate) {
                Log.i(TAG, "Camera " + source.name + " cannot be reached securely");
                return text(200, "OK", "application/json", "{\"kind\":\"none\"}");
            }
            Log.i(TAG, "Camera " + source.name + " has a certificate this panel does not trust");
            // Both surfaces show the waiting certificate from the status document.
            android.content.Context context = app;
            if (context != null) {
                KioskService.publishTelemetrySoon(context);
            }
        } catch (IOException | java.security.GeneralSecurityException unreachable) {
            kind = "none";
            Log.i(TAG, "Camera " + source.name + " cannot be reached: "
                    + unreachable.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return text(200, "OK", "application/json", "{\"kind\":\"" + kind + "\"}");
    }

    /**
     * One request fetched here and handed to the view from memory. {@code name} is the camera's,
     * for the log; null for a camera page's own requests, which are not logged one by one.
     */
    private static WebResourceResponse fetch(String url, Cameras.Login login,
            Map<String, String> headers, String name, Map<String, String> pins,
            java.util.function.Consumer<HttpURLConnection> held) {
        try {
            HttpURLConnection connection = open(url, login, headers, pins);
            if (held != null) {
                held.accept(connection);
            }
            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            if (body == null) {
                body = new ByteArrayInputStream(new byte[0]);
            }
            String type = connection.getContentType() == null ? "application/octet-stream"
                    : connection.getContentType();
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                String key = header.getKey();
                if (key == null || header.getValue() == null || header.getValue().isEmpty()) {
                    continue;
                }
                String lower = key.toLowerCase(Locale.ROOT);
                // Cookies would be kept by the browser engine; length and encoding no longer
                // describe the bytes handed over.
                if (lower.equals("set-cookie") || lower.equals("set-cookie2")
                        || lower.equals("content-length") || lower.equals("content-encoding")
                        || lower.equals("transfer-encoding") || lower.equals("cache-control")) {
                    continue;
                }
                out.put(key, String.join(", ", header.getValue()));
            }
            out.put("Cache-Control", "no-store");
            String reason = connection.getResponseMessage();
            if (status < 200 || (status >= 300 && status < 400)) {
                // A redirect to another scheme, which the connection does not follow.
                body.close();
                return text(502, "Bad Gateway", "text/plain", "");
            }
            int semicolon = type.indexOf(';');
            String mime = semicolon < 0 ? type.trim() : type.substring(0, semicolon).trim();
            String charset = null;
            int at = type.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (at >= 0) {
                charset = type.substring(at + 8).replace("\"", "").split(";")[0].trim();
            }
            // A multipart stream's boundary travels in its Content-Type header, kept above.
            WebResourceResponse response = new WebResourceResponse(mime, charset, status,
                    reason == null || reason.trim().isEmpty() ? "OK" : reason, out, body);
            return response;
        } catch (IOException | java.security.GeneralSecurityException unreachable) {
            if (name != null) {
                Log.i(TAG, "Camera " + name + " stream failed: "
                        + unreachable.getClass().getSimpleName());
            }
            return text(502, "Bad Gateway", "text/plain", "");
        }
    }

    /**
     * How each camera address last asked to be signed in to, "basic" or its Digest challenge, kept in
     * memory only, so a snapshot fetched every two seconds is not refused first each time.
     */
    private static final Map<String, Object> SIGN_INS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A connection to {@code url} with its reply read. With a sign-in, the camera says how it wants
     * it: nothing is sent until it asks (a wrong guess counts as a failed sign-in on some cameras),
     * then Basic or Digest as asked, remembered for that host. At most one sign-in is tried per
     * fetch: a refused one is not tried again at once, so a wrong password costs one failure a
     * fetch, not a lock-out; the only immediate retry is the first answer to a camera's challenge,
     * or a Digest nonce the camera itself calls stale. A nonce changed without that word is used
     * at the next fetch.
     */
    private static HttpURLConnection open(String url, Cameras.Login login,
            Map<String, String> headers, Map<String, String> pins)
            throws IOException, java.security.GeneralSecurityException {
        // Per camera address, not per host: an NVR's channels or two cameras behind one proxy
        // may ask for different kinds of sign-in.
        String host = signInKey(url);
        Object known = login == null ? null : SIGN_INS.get(host);
        HttpURLConnection connection = openOnce(url, authorization(known, login, url), headers,
                pins);
        if (login == null || connection.getResponseCode() != 401) {
            return connection;
        }
        List<String> asked = connection.getHeaderFields().get("WWW-Authenticate");
        if (asked == null) {
            asked = connection.getHeaderFields().get("www-authenticate");
        }
        Object scheme = DigestAuth.parse(asked);
        if (scheme == null && asked != null) {
            for (String one : asked) {
                if (one != null && one.trim().regionMatches(true, 0, "Basic", 0, 5)) {
                    scheme = "basic";
                }
            }
        }
        if (scheme == null) {
            return connection;
        }
        SIGN_INS.put(host, scheme);
        // A different kind of sign-in asked for is not a wrong password either.
        boolean kindChanged = (known instanceof DigestAuth.Challenge)
                != (scheme instanceof DigestAuth.Challenge);
        boolean retry = known == null || kindChanged
                || (scheme instanceof DigestAuth.Challenge && ((DigestAuth.Challenge) scheme).stale);
        if (!retry) {
            return connection;
        }
        drain(connection);
        return openOnce(url, authorization(scheme, login, url), headers, pins);
    }

    /** Where a sign-in kind is remembered: the address without its query. */
    private static String signInKey(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String authorization(Object scheme, Cameras.Login login, String url) {
        if (login == null || scheme == null) {
            return null;
        }
        if (scheme instanceof DigestAuth.Challenge) {
            URL address;
            try {
                address = new URL(url);
            } catch (java.net.MalformedURLException unreadable) {
                return null;
            }
            String uri = address.getFile().isEmpty() ? "/" : address.getFile();
            byte[] cnonce = new byte[8];
            new java.security.SecureRandom().nextBytes(cnonce);
            return DigestAuth.header((DigestAuth.Challenge) scheme, "GET", uri, login.user,
                    login.password, android.util.Base64.encodeToString(cnonce,
                            android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING));
        }
        return "Basic " + android.util.Base64.encodeToString((login.user + ":" + login.password)
                .getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream body = connection.getErrorStream()) {
            if (body != null) {
                byte[] skip = new byte[4096];
                int total = 0;
                int read;
                while (total < 65_536 && (read = body.read(skip)) > 0) {
                    total += read;
                }
            }
        } catch (IOException ignored) {
            // A refusal's body is not needed; the connection is closed either way.
        }
        connection.disconnect();
    }

    /** One TLS set-up per host and trusted certificate, not one per picture. */
    private static final Map<String, MqttTls.Connection> TLS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static MqttTls.Connection tlsFor(String host, String pin)
            throws java.security.GeneralSecurityException {
        String key = host + " " + pin;
        MqttTls.Connection tls = TLS.get(key);
        if (tls == null) {
            tls = MqttTls.connection(host, pin, null,
                    refused -> CertificatePins.waiting = refused);
            TLS.put(key, tls);
        }
        return tls;
    }

    private static HttpURLConnection openOnce(String url, String authorization,
            Map<String, String> headers, Map<String, String> pins)
            throws IOException, java.security.GeneralSecurityException {
        URL address = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        if (connection instanceof javax.net.ssl.HttpsURLConnection) {
            // Android's authorities, or the certificate a person trusted for this host as for a
            // page; a refused one waits where a page's does, for Trust on either surface.
            String host = address.getHost().toLowerCase(Locale.ROOT);
            String pin = pins.get(host);
            MqttTls.Connection tls = tlsFor(host, pin == null ? "" : pin);
            ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(tls.socketFactory);
            ((javax.net.ssl.HttpsURLConnection) connection).setHostnameVerifier(
                    tls.hostnameVerifier);
        }
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String lower = header.getKey().toLowerCase(Locale.ROOT);
                if (!lower.equals("cookie") && !lower.equals("accept-encoding")
                        && !lower.equals("authorization")) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
        }
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    private static WebResourceResponse text(int status, String reason, String type, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(type, "utf-8", status, reason, headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The view's own page: one tile a camera, drawn and kept alive by its script, laid out and
     * touched as {@code options} say ({@link Options}). Zoom lets the one camera on show be
     * pinched; focus lets a tap open one tile of a grid over the whole view, the other streams
     * stopped, and a tap on it (not zoomed), or a minute without a touch, bring the grid back.
     */
    static String page(List<Cameras.Tile> sources, Options options) {
        StringBuilder names = new StringBuilder("[");
        StringBuilder urls = new StringBuilder("[");
        StringBuilder fulls = new StringBuilder("[");
        for (int i = 0; i < sources.size(); i++) {
            names.append(i == 0 ? "" : ",").append(jsString(sources.get(i).name));
            urls.append(i == 0 ? "" : ",").append(jsString(sources.get(i).url));
            fulls.append(i == 0 ? "" : ",").append(jsString(sources.get(i).full));
        }
        names.append(']');
        urls.append(']');
        fulls.append(']');
        String layout = Cameras.isLayout(options.layout) ? options.layout : Cameras.LAYOUT_AUTO;
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#grid{display:grid;height:100%;gap:2px}"
                + ".tile{position:relative;overflow:hidden;background:#111}"
                + ".tile img,.tile iframe{position:absolute;inset:0;width:100%;height:100%;"
                + "object-fit:contain;border:0;transform:var(--z,none);transform-origin:center}"
                + ".touch{touch-action:none}"
                + ".tile p{position:absolute;inset:0;margin:0;display:flex;flex-direction:column;"
                + "align-items:center;justify-content:center;gap:6px;color:#c9cede;"
                + "font:16px/1.3 system-ui,sans-serif;text-align:center}"
                + ".tile p b{color:#fff}"
                + ".names .tile::after{content:attr(data-name);position:absolute;left:8px;bottom:8px;"
                + "padding:2px 8px;border-radius:4px;background:rgba(0,0,0,.6);color:#fff;"
                + "font:14px/1.4 system-ui,sans-serif;pointer-events:none}"
                + "</style></head><body" + (options.names ? " class=\"names\"" : "")
                + "><div id=\"grid\"></div><script>"
                + "var names=" + names + ",urls=" + urls + ",fulls=" + fulls
                + ",zoom=" + options.zoom + ",focus=" + options.focus + ",big=" + options.big
                + ",shape=" + jsString(layout) + ",bigTile=" + Math.max(0, options.bigTile)
                + ";var grid=document.getElementById('grid'),tiles=[],focused=-1,idle=0;"
                + "names.forEach(function(name,i){var t=document.createElement('div');"
                + "t.className='tile';t.dataset.name=name;grid.appendChild(t);tiles.push(t);"
                + "if(zoom||focus){gestures(t,i);}});"
                // The full-screen address for the camera on show alone: the one camera of a
                // full view, or the tile opened over the grid.
                + "function full(i){return !!fulls[i]&&((big&&tiles.length===1)||focused===i);}"
                // The grid by the number of cameras on show and the shape of the view, as
                // recorders lay it out: two side by side or stacked, three as one big and two
                // small, then 2x2, 3x2, 3x3 and 4x3, turned for an upright view.
                // A fixed layout instead: 1+3 one big over three small, 1+5 one big with five
                // around it, 2x2 and 3x3; cameras past its places are not shown.
                + "function layout(){var n=focused<0?tiles.length:1,w=innerWidth>=innerHeight,c,r,"
                + "first=focused<0?(shape==='1+3'||shape==='1+5'?Math.min(bigTile,tiles.length-1):0):focused,"
                + "places=99,cs='',rs='';"
                + "if(focused>=0||shape==='auto'){if(n<=1){c=1;r=1;}else if(n===2){c=w?2:1;r=w?1:2;}"
                + "else if(n<=4){c=2;r=2;}else if(n<=6){c=w?3:2;r=w?2:3;}else if(n<=9){c=3;r=3;}"
                + "else{c=w?4:3;r=w?3:4;}if(focused<0&&n===3){if(w){rs='span 2';}else{cs='span 2';}}}"
                + "else if(shape==='1+3'){c=3;r=3;places=4;cs='span 3';rs='span 2';}"
                + "else if(shape==='1+5'){c=3;r=3;places=6;cs='span 2';rs='span 2';}"
                + "else if(shape==='2x2'){c=2;r=2;places=4;}else{c=3;r=3;places=9;}"
                + "grid.style.gridTemplateColumns='repeat('+c+',1fr)';"
                + "grid.style.gridTemplateRows='repeat('+r+',1fr)';"
                + "var shown=0;tiles.forEach(function(t,i){var lead=i===first;"
                + "var on=focused>=0?focused===i:(lead||shown<places-1);if(on&&!lead&&focused<0){shown++;}"
                + "t.style.display=on?'':'none';t.style.order=lead?-1:0;"
                + "t.style.gridColumn=lead?cs:'';t.style.gridRow=lead?rs:'';"
                + "if(!on&&t.firstChild&&focused<0){t.innerHTML='';fetch('/stop/'+i);}});}"
                + "layout();addEventListener('resize',layout);"
                // Laid out first, so a camera past a fixed layout's places never starts.
                + "tiles.forEach(function(t,i){start(t,i,0);});"
                + "function focusOn(i){focused=i;tiles.forEach(function(t,j){if(j!==i){t.innerHTML='';"
                + "fetch('/stop/'+j);}});"
                // The opened tile changes to its bigger stream, and back on the grid.
                + "if(fulls[i]){fetch('/stop/'+i);start(tiles[i],i,0);}layout();arm();}"
                + "function unfocus(){var i=focused;if(i<0)return;focused=-1;clearTimeout(idle);"
                + "unzoom(tiles[i]);if(fulls[i]){fetch('/stop/'+i);start(tiles[i],i,0);}"
                + "layout();tiles.forEach(function(t,j){if(j!==i&&t.style.display!=='none'){start(t,j,0);}});}"
                + "function arm(){clearTimeout(idle);if(focused>=0){idle=setTimeout(unfocus,60000);}}"
                + GESTURES
                + "function none(t,i,tries,why){t.innerHTML='';var p=document.createElement('p');"
                + "var b=document.createElement('b');b.textContent=names[i];p.appendChild(b);"
                + "p.appendChild(document.createTextNode(why||'No picture'));t.appendChild(p);"
                + "setTimeout(function(){start(t,i,tries+1);},Math.min(60,5*Math.pow(2,tries))*1000);}"
                // A tile hidden behind an opened one fetches nothing until the grid is back.
                + "function start(t,i,tries){if((focused>=0&&focused!==i)||t.style.display==='none')return;"
                + "fetch('/probe/'+i+(full(i)?'?full=1':'')).then(function(r){return r.json();})"
                + ".then(function(a){if((focused>=0&&focused!==i)||t.style.display==='none')return;"
                + "draw(t,i,a.kind,tries);})"
                + ".catch(function(){none(t,i,tries);});}"
                + "function draw(t,i,kind,tries){t.innerHTML='';"
                + "var page=full(i)?fulls[i]:urls[i];"
                + "if(kind==='page'){if(names.length===1){location.replace(page);return;}"
                + "var f=document.createElement('iframe');f.src=page;t.appendChild(f);return;}"
                + "if(kind==='denied'){none(t,i,tries,'Sign-in refused');return;}"
                + "if(kind==='untrusted'){none(t,i,tries,'Certificate not trusted');return;}"
                + "if(kind!=='mjpeg'&&kind!=='image'){none(t,i,tries);return;}"
                + "var img=document.createElement('img');img.alt=names[i];"
                + "img.onerror=function(){none(t,i,tries);};"
                + "if(kind==='image'){img.onload=function(){again(t,i,img,0);};}"
                + "img.src=stream(i);t.appendChild(img);}"
                + "function stream(i){return '/stream/'+i+'?'+(full(i)?'full=1&':'')+'t='+Date.now();}"
                // A picture is fetched again every two seconds; the new one replaces the old
                // only once it has arrived, so the tile never flickers empty.
                // One failed refresh keeps the last picture and tries again at the next tick (a
                // camera that changed its sign-in nonce answers the next one); two in a row say so.
                + "function again(t,i,img,fails){img.onerror=null;setTimeout(function(){"
                + "if(!img.isConnected)return;var n=new Image();n.alt=img.alt;"
                + "n.onload=function(){if(img.isConnected){t.replaceChild(n,img);again(t,i,n,0);}};"
                + "n.onerror=function(){if(!img.isConnected)return;"
                + "if(fails<1){again(t,i,img,fails+1);}else{none(t,i,0);}};"
                + "n.src=stream(i);},2000);}"
                + "</script></body></html>";
    }

    /**
     * A tile's touches. Pinch, for the one camera on show when zooming is on: two fingers scale
     * it between 1 and 4 times, one finger moves a zoomed picture without letting its edge leave
     * the tile, a double tap goes back to the whole picture; the transform is a CSS variable on
     * the tile, so a snapshot's next picture keeps it. A single tap, when focusing is on, opens
     * the tile over the grid, or takes an opened, unzoomed tile back to the grid.
     */
    private static final String GESTURES = "function unzoom(t){t.zs=1;t.zx=0;t.zy=0;"
            + "t.style.setProperty('--z','none');}"
            + "function gestures(t,i){unzoom(t);var p={},g=null,last=0,downAt=0,from=null,moved=false,"
            + "tap=0;t.classList.add('touch');"
            + "function can(){return zoom&&(tiles.length===1||focused===i);}"
            + "function put(){var w=t.clientWidth,h=t.clientHeight,mx=(t.zs-1)*w/2,my=(t.zs-1)*h/2;"
            + "t.zx=Math.max(-mx,Math.min(mx,t.zx));t.zy=Math.max(-my,Math.min(my,t.zy));"
            + "t.style.setProperty('--z',t.zs===1?'none':'translate('+t.zx+'px,'+t.zy+'px) scale('+t.zs+')');}"
            + "function pts(){return Object.keys(p).map(function(k){return p[k];});}"
            + "function grab(){g={s:t.zs,x:t.zx,y:t.zy,a:pts().map(function(q){return {x:q.x,y:q.y};})};}"
            + "t.addEventListener('pointerdown',function(e){p[e.pointerId]={x:e.clientX,y:e.clientY};"
            + "if(pts().length===1){downAt=Date.now();from={x:e.clientX,y:e.clientY};moved=false;}"
            + "else{moved=true;}grab();arm();});"
            + "t.addEventListener('pointermove',function(e){if(!p[e.pointerId]||!g)return;"
            + "p[e.pointerId]={x:e.clientX,y:e.clientY};var a=pts();"
            + "if(from&&Math.hypot(e.clientX-from.x,e.clientY-from.y)>10){moved=true;}"
            + "if(!can())return;"
            + "if(a.length>=2&&g.a.length>=2){var d0=Math.hypot(g.a[0].x-g.a[1].x,g.a[0].y-g.a[1].y),"
            + "d1=Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y);if(d0>0){t.zs=Math.max(1,Math.min(4,g.s*d1/d0));}}"
            + "else if(a.length===1&&g.a.length===1&&t.zs>1){t.zx=g.x+a[0].x-g.a[0].x;"
            + "t.zy=g.y+a[0].y-g.a[0].y;}put();});"
            + "function up(e){if(!p[e.pointerId])return;delete p[e.pointerId];"
            + "if(pts().length===0&&!moved&&Date.now()-downAt<300){var n=Date.now();"
            + "if(n-last<300){clearTimeout(tap);last=0;if(can()){unzoom(t);}}"
            + "else{last=n;tap=setTimeout(function(){if(!focus)return;"
            + "if(focused<0){focusOn(i);}else if(focused===i&&t.zs===1){unfocus();}},300);}}grab();}"
            + "t.addEventListener('pointerup',up);t.addEventListener('pointercancel',up);}";

    private static String jsString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
