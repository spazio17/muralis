/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Host tests for {@link DlnaProtocol}: every action called and answered, the faults, the
 * refusals that keep the reader safe, the event bodies, the SSDP texts and the times. Answers
 * and event bodies are checked for well-formedness with the JDK's parser, which plays the part
 * of the controller here; the protocol class itself uses none.
 */
public final class DlnaProtocolTest {
    private static final String UDN = "uuid:12345678-1234-4234-8234-123456789abc";
    private static final DlnaProtocol.Service AVT = DlnaProtocol.Service.AV_TRANSPORT;
    private static final DlnaProtocol.Service RCS = DlnaProtocol.Service.RENDERING_CONTROL;
    private static final DlnaProtocol.Service CM = DlnaProtocol.Service.CONNECTION_MANAGER;

    public static void main(String[] args) throws Exception {
        descriptions();
        transportRoundTrips();
        transportStates();
        renderingRoundTrips();
        connectionRoundTrips();
        faults();
        unsafeXmlIsRefused();
        oversizeIsRefused();
        titles();
        times();
        events();
        subscriptions();
        ssdp();
        System.out.println("DlnaProtocolTest passed");
    }

    /** A renderer that records what it was told and holds a state the test sets. */
    private static final class FakeRenderer implements DlnaProtocol.Renderer {
        String transport = DlnaProtocol.NO_MEDIA_PRESENT;
        String uri = "";
        String title = "";
        long duration = -1;
        long position = 0;
        int volume = 40;
        boolean muted;
        String last = "";

        @Override
        public DlnaProtocol.State state() {
            return new DlnaProtocol.State(transport, false, uri, title, duration, position,
                    duration > 0, volume, muted);
        }

        @Override
        public void setUri(String newUri, String newTitle) {
            uri = newUri;
            title = newTitle;
            transport = DlnaProtocol.STOPPED;
            last = "setUri";
        }

        @Override
        public void play() {
            transport = DlnaProtocol.PLAYING;
            last = "play";
        }

        @Override
        public void pause() {
            transport = DlnaProtocol.PAUSED_PLAYBACK;
            last = "pause";
        }

        @Override
        public void stop() {
            transport = uri.isEmpty() ? DlnaProtocol.NO_MEDIA_PRESENT : DlnaProtocol.STOPPED;
            last = "stop";
        }

        @Override
        public void seek(long positionMs) {
            position = positionMs;
            last = "seek";
        }

        @Override
        public void setVolume(int percent) {
            volume = percent;
            last = "volume";
        }

        @Override
        public void setMute(boolean mute) {
            muted = mute;
            last = "mute";
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void descriptions() throws Exception {
        String description = DlnaProtocol.deviceDescription("Kitchen <panel> & co", UDN);
        wellFormed(description);
        require(description.contains("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"),
                "the device type is MediaRenderer:1");
        require(description.contains("<friendlyName>Kitchen &lt;panel&gt; &amp; co</friendlyName>"),
                "the friendly name is escaped");
        require(description.contains("<manufacturer>spazio17</manufacturer>")
                && description.contains("<modelName>Muralis</modelName>"), "maker and model");
        require(description.contains("<UDN>" + UDN + "</UDN>"), "the UDN");
        // async_upnp_client's DmrDevice needs all three service ids (SERVICE_IDS).
        for (String id : new String[] {"urn:upnp-org:serviceId:AVTransport",
                "urn:upnp-org:serviceId:RenderingControl",
                "urn:upnp-org:serviceId:ConnectionManager"}) {
            require(description.contains("<serviceId>" + id + "</serviceId>"), "service id " + id);
        }
        require(description.contains("<controlURL>/dlna/AVTransport/control</controlURL>"),
                "relative control address");
        String longName = DlnaProtocol.deviceDescription("x".repeat(100), UDN);
        require(longName.contains("<friendlyName>" + "x".repeat(64) + "</friendlyName>"),
                "a friendly name is cut at 64 characters");
        require(DlnaProtocol.deviceDescription("  ", UDN).contains("<friendlyName>Muralis<"),
                "an empty name falls back to Muralis");

        for (DlnaProtocol.Service service : DlnaProtocol.Service.values()) {
            String scpd = DlnaProtocol.serviceDescription(service);
            wellFormed(scpd);
            require(DlnaProtocol.scpdAt(service.scpdPath()) == service, "scpd path " + service);
            require(DlnaProtocol.controlAt(service.controlPath()) == service, "control " + service);
            require(DlnaProtocol.eventAt(service.eventPath()) == service, "event " + service);
        }
        String avt = DlnaProtocol.serviceDescription(AVT);
        require(avt.contains("<name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>"
                + "<allowedValueList><allowedValue>REL_TIME</allowedValue></allowedValueList>"),
                "REL_TIME is the seek mode async_upnp_client looks for");
        require(avt.contains("<stateVariable sendEvents=\"yes\"><name>LastChange</name>"),
                "LastChange is evented");
        String rcs = DlnaProtocol.serviceDescription(RCS);
        require(rcs.contains("<name>Volume</name><dataType>ui2</dataType><allowedValueRange>"
                + "<minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>"),
                "Volume 0 to 100");
        require(DlnaProtocol.scpdAt("/dlna/Other.xml") == null, "unknown path");

        require(DlnaProtocol.isUdn(DlnaProtocol.newUdn()), "a minted UDN is one");
        require(!DlnaProtocol.isUdn(""), "empty is no UDN");
        require(!DlnaProtocol.isUdn("uuid:not-one"), "garbage is no UDN");
    }

    private static void transportRoundTrips() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        String metadata = DlnaProtocol.escape("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><item id=\"0\" parentID=\"-1\""
                + " restricted=\"false\"><dc:title>Door &amp; bell</dc:title>"
                + "<res protocolInfo=\"http-get:*:audio/mpeg:*\">http://ha.local/x.mp3</res>"
                + "</item></DIDL-Lite>");
        Map<String, String> answer = call(AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                "CurrentURI", "http://ha.local:8123/api/tts/x.mp3?token=a&amp;b=1",
                "CurrentURIMetaData", metadata);
        require(answer.isEmpty(), "SetAVTransportURI answers no values");
        require(renderer.uri.equals("http://ha.local:8123/api/tts/x.mp3?token=a&b=1"),
                "the address is unescaped: " + renderer.uri);
        require(renderer.title.equals("Door & bell"), "the title from dc:title: " + renderer.title);

        answer = call(AVT, "GetTransportInfo", renderer, "InstanceID", "0");
        require(answer.get("CurrentTransportState").equals("STOPPED"), "stopped after the address");
        require(answer.get("CurrentTransportStatus").equals("OK"), "status OK");
        require(answer.get("CurrentSpeed").equals("1"), "speed 1");

        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Play,Stop"), "stopped offers Play: " + answer);

        call(AVT, "Play", renderer, "InstanceID", "0", "Speed", "1");
        require(renderer.last.equals("play"), "Play reaches the renderer");
        renderer.duration = 125_000;
        renderer.position = 61_500;
        answer = call(AVT, "GetPositionInfo", renderer, "InstanceID", "0");
        require(answer.get("Track").equals("1"), "one track");
        require(answer.get("TrackDuration").equals("0:02:05"), "duration " + answer);
        require(answer.get("RelTime").equals("0:01:01"), "position " + answer);
        require(answer.get("AbsTime").equals("0:01:01"), "abs position");
        require(answer.get("RelCount").equals("2147483647"), "counts not implemented");
        require(answer.get("TrackURI").equals(renderer.uri), "track address");
        require(answer.get("TrackMetaData").contains("<dc:title>Door &amp; bell</dc:title>"),
                "track metadata carries the title");
        wellFormed(answer.get("TrackMetaData"));

        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Pause,Stop,Seek"), "playing offers Seek");

        call(AVT, "Seek", renderer, "InstanceID", "0", "Unit", "REL_TIME", "Target", "0:1:5");
        require(renderer.position == 65_000, "seek to 0:1:5 is 65 s: " + renderer.position);

        call(AVT, "Pause", renderer, "InstanceID", "0");
        require(renderer.transport.equals(DlnaProtocol.PAUSED_PLAYBACK), "paused");
        call(AVT, "Pause", renderer, "InstanceID", "0");
        require(renderer.transport.equals(DlnaProtocol.PAUSED_PLAYBACK), "pause twice is fine");
        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Play,Stop,Seek"), "paused offers Play");
        call(AVT, "Play", renderer, "InstanceID", "0", "Speed", "1");
        require(renderer.transport.equals(DlnaProtocol.PLAYING), "resumed");

        answer = call(AVT, "GetMediaInfo", renderer, "InstanceID", "0");
        require(answer.get("NrTracks").equals("1") && answer.get("PlayMedium").equals("NETWORK")
                && answer.get("CurrentURI").equals(renderer.uri)
                && answer.get("MediaDuration").equals("0:02:05")
                && answer.get("NextURI").isEmpty(), "media info " + answer);

        answer = call(AVT, "GetTransportSettings", renderer, "InstanceID", "0");
        require(answer.get("PlayMode").equals("NORMAL"), "play mode");
        answer = call(AVT, "GetDeviceCapabilities", renderer, "InstanceID", "0");
        require(answer.get("PlayMedia").equals("NETWORK"), "capabilities");

        call(AVT, "Stop", renderer, "InstanceID", "0");
        require(renderer.transport.equals(DlnaProtocol.STOPPED), "stopped");
    }

    private static void transportStates() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        Map<String, String> answer = call(AVT, "GetMediaInfo", renderer, "InstanceID", "0");
        require(answer.get("NrTracks").equals("0") && answer.get("PlayMedium").equals("NONE")
                && answer.get("MediaDuration").equals("0:00:00"), "no media " + answer);
        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Stop"), "never an empty list: " + answer);
        expectFault(701, AVT, "Play", renderer, "InstanceID", "0", "Speed", "1");
        expectFault(701, AVT, "Pause", renderer, "InstanceID", "0");
        call(AVT, "Stop", renderer, "InstanceID", "0");
        require(renderer.transport.equals(DlnaProtocol.NO_MEDIA_PRESENT), "stop with nothing");

        // A live stream: no length, so no seeking.
        renderer.uri = "http://radio.example/stream";
        renderer.transport = DlnaProtocol.PLAYING;
        renderer.duration = -1;
        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Pause,Stop"), "no Seek on a live stream");
        answer = call(AVT, "GetPositionInfo", renderer, "InstanceID", "0");
        require(answer.get("TrackDuration").equals("NOT_IMPLEMENTED"), "unknown length");
        expectFault(701, AVT, "Seek", renderer, "InstanceID", "0", "Unit", "REL_TIME",
                "Target", "0:00:10");
        // Play while playing is no change.
        renderer.last = "";
        call(AVT, "Play", renderer, "InstanceID", "0", "Speed", "1");
        require(renderer.last.isEmpty(), "Play while playing does nothing");

        renderer.duration = 60_000;
        expectFault(711, AVT, "Seek", renderer, "InstanceID", "0", "Unit", "REL_TIME",
                "Target", "0:01:01");
        expectFault(711, AVT, "Seek", renderer, "InstanceID", "0", "Unit", "REL_TIME",
                "Target", "soon");
        expectFault(710, AVT, "Seek", renderer, "InstanceID", "0", "Unit", "ABS_TIME",
                "Target", "0:00:10");
        expectFault(710, AVT, "Seek", renderer, "InstanceID", "0", "Unit", "TRACK_NR",
                "Target", "1");
        renderer.transport = DlnaProtocol.TRANSITIONING;
        answer = call(AVT, "GetCurrentTransportActions", renderer, "InstanceID", "0");
        require(answer.get("Actions").equals("Stop"), "transitioning offers Stop");
        expectFault(701, AVT, "Pause", renderer, "InstanceID", "0");
    }

    private static void renderingRoundTrips() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        Map<String, String> answer = call(RCS, "GetVolume", renderer, "InstanceID", "0",
                "Channel", "Master");
        require(answer.get("CurrentVolume").equals("40"), "volume read");
        call(RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "73");
        require(renderer.volume == 73, "volume set");
        call(RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "0");
        require(renderer.volume == 0, "volume 0");
        call(RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "100");
        require(renderer.volume == 100, "volume 100");
        answer = call(RCS, "GetMute", renderer, "InstanceID", "0", "Channel", "Master");
        require(answer.get("CurrentMute").equals("0"), "not muted");
        call(RCS, "SetMute", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredMute", "1");
        require(renderer.muted, "muted by 1");
        call(RCS, "SetMute", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredMute", "false");
        require(!renderer.muted, "unmuted by false");
        call(RCS, "SetMute", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredMute", "true");
        answer = call(RCS, "GetMute", renderer, "InstanceID", "0", "Channel", "Master");
        require(answer.get("CurrentMute").equals("1"), "muted reads 1");

        expectFault(601, RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "101");
        expectFault(601, RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "-1");
        expectFault(402, RCS, "SetVolume", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredVolume", "loud");
        expectFault(402, RCS, "SetMute", renderer, "InstanceID", "0", "Channel", "Master",
                "DesiredMute", "maybe");
        expectFault(402, RCS, "GetVolume", renderer, "InstanceID", "0", "Channel", "LF");
        expectFault(702, RCS, "GetVolume", renderer, "InstanceID", "1", "Channel", "Master");
    }

    private static void connectionRoundTrips() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        Map<String, String> answer = call(CM, "GetProtocolInfo", renderer);
        require(answer.get("Source").isEmpty(), "no source");
        require(answer.get("Sink").startsWith("http-get:*:audio/*:*,"), "any audio over HTTP");
        require(answer.get("Sink").contains("http-get:*:audio/mpeg:*"), "mp3 named");
        answer = call(CM, "GetCurrentConnectionIDs", renderer);
        require(answer.get("ConnectionIDs").equals("0"), "connection 0");
        answer = call(CM, "GetCurrentConnectionInfo", renderer, "ConnectionID", "0");
        require(answer.get("Direction").equals("Input") && answer.get("Status").equals("OK")
                && answer.get("PeerConnectionID").equals("-1"), "connection info " + answer);
        expectFault(706, CM, "GetCurrentConnectionInfo", renderer, "ConnectionID", "3");
    }

    private static void faults() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        expectFault(401, AVT, "Next", renderer, "InstanceID", "0");
        expectFault(401, AVT, "Record", renderer, "InstanceID", "0");
        // A missing argument, a wrong instance, a non-number instance, a wrong speed.
        expectFault(402, AVT, "Play", renderer, "InstanceID", "0");
        expectFault(718, AVT, "GetTransportInfo", renderer, "InstanceID", "2");
        expectFault(402, AVT, "GetTransportInfo", renderer, "InstanceID", "zero");
        renderer.uri = "http://a/b.mp3";
        renderer.transport = DlnaProtocol.STOPPED;
        expectFault(717, AVT, "Play", renderer, "InstanceID", "0", "Speed", "2");
        // Only http and https: everything else would reach into the device.
        for (String refused : new String[] {"file:///sdcard/x.mp3", "content://media/1",
                "rtsp://cam/stream", "ftp://host/x.mp3", "javascript:alert(1)", "http:///nohost"}) {
            expectFault(716, AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                    "CurrentURI", refused, "CurrentURIMetaData", "");
        }
        expectFault(402, AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                "CurrentURI", "", "CurrentURIMetaData", "");
        expectFault(402, AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                "CurrentURI", "http://a/" + "x".repeat(9000), "CurrentURIMetaData", "");
        call(AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                "CurrentURI", "HTTPS://host:8443/a.flac", "CurrentURIMetaData", "");
        require(renderer.uri.equals("HTTPS://host:8443/a.flac") && renderer.title.isEmpty(),
                "https in capitals, no metadata");

        // The header decides the service and action; the body must agree.
        try {
            DlnaProtocol.parseCall(AVT, null, envelope(AVT, "Stop", "InstanceID", "0"));
            fail("no SOAPACTION");
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == 401, "no SOAPACTION is 401");
        }
        try {
            DlnaProtocol.parseCall(AVT, "\"" + RCS.type() + "#GetVolume\"",
                    envelope(RCS, "GetVolume", "InstanceID", "0", "Channel", "Master"));
            fail("another service's action");
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == 401, "another service is 401");
        }
        try {
            DlnaProtocol.parseCall(AVT, AVT.type() + "#Play",
                    envelope(AVT, "Stop", "InstanceID", "0"));
            fail("header and body disagree");
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == 401, "disagreeing body is 401");
        }
        expectParseFault(402, "<s:Envelope xmlns:s=\"x\"><s:Body><u:Stop><InstanceID>0</InstanceID>"
                + "</u:Stop></s:Body>", "an unclosed envelope");
        expectParseFault(402, "<s:Envelope xmlns:s=\"x\"><s:Body><u:Stop><InstanceID>0</Other>"
                + "</u:Stop></s:Body></s:Envelope>", "mismatched tags");
        expectParseFault(402, "<Other/>", "not an envelope");
        expectParseFault(402, "<s:Envelope><s:Body><u:Stop><InstanceID><b>0</b></InstanceID>"
                + "</u:Stop></s:Body></s:Envelope>", "markup in an argument");
        expectParseFault(402, "<s:Envelope><s:Body><u:Stop><InstanceID>0</InstanceID>"
                + "<InstanceID>0</InstanceID></u:Stop></s:Body></s:Envelope>", "a doubled argument");
        expectParseFault(401, "<s:Envelope><s:Body></s:Body></s:Envelope>", "no action");

        // The fault is a SOAP fault carrying the UPnPError, as async_upnp_client reads it.
        String fault = DlnaProtocol.fault(new DlnaProtocol.Fault(401, "no <such> action"));
        wellFormed(fault);
        require(fault.contains("<errorCode>401</errorCode>")
                && fault.contains("<errorDescription>no &lt;such&gt; action</errorDescription>")
                && fault.contains("urn:schemas-upnp-org:control-1-0"), "fault shape");
    }

    private static void unsafeXmlIsRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<s:Envelope xmlns:s=\"x\"><s:Body><u:Stop xmlns:u=\"" + AVT.type() + "\">"
                + "<InstanceID>&x;</InstanceID></u:Stop></s:Body></s:Envelope>";
        expectParseFault(402, xxe, "a DOCTYPE with an external entity");
        expectParseFault(402, "<!doctype x><s:Envelope/>", "a lower-case doctype");
        expectParseFault(402, "<s:Envelope><s:Body><u:Stop><InstanceID>&x;</InstanceID></u:Stop>"
                + "</s:Body></s:Envelope>", "an undeclared entity");
        expectParseFault(402, "<s:Envelope><s:Body><u:Stop><InstanceID>&#0;</InstanceID></u:Stop>"
                + "</s:Body></s:Envelope>", "character reference 0");
        expectParseFault(402, "<s:Envelope><s:Body><u:Stop><InstanceID>a & b</InstanceID></u:Stop>"
                + "</s:Body></s:Envelope>", "a bare ampersand");
        // The same inside the metadata, which is XML inside an argument.
        FakeRenderer renderer = new FakeRenderer();
        String metadata = DlnaProtocol.escape("<!DOCTYPE d [<!ENTITY e SYSTEM \"http://evil/\">]>"
                + "<DIDL-Lite><item><dc:title>&e;</dc:title></item></DIDL-Lite>");
        expectFault(402, AVT, "SetAVTransportURI", renderer, "InstanceID", "0",
                "CurrentURI", "http://a/b.mp3", "CurrentURIMetaData", metadata);
        require(renderer.uri.isEmpty(), "nothing was set");
        // Comments, CDATA and character references are fine.
        try {
            DlnaProtocol.Call call = DlnaProtocol.parseCall(AVT, AVT.type() + "#Stop",
                    ("<?xml version=\"1.0\"?><!-- a comment --><s:Envelope><s:Body><u:Stop>"
                            + "<InstanceID><![CDATA[0]]></InstanceID></u:Stop></s:Body>"
                            + "</s:Envelope>").getBytes(StandardCharsets.UTF_8));
            require(call.arguments.get("InstanceID").equals("0"), "CDATA read");
            call = DlnaProtocol.parseCall(AVT, AVT.type() + "#Stop",
                    "<s:Envelope><s:Body><u:Stop><InstanceID>&#48;</InstanceID></u:Stop></s:Body></s:Envelope>"
                            .getBytes(StandardCharsets.UTF_8));
            require(call.arguments.get("InstanceID").equals("0"), "character reference read");
        } catch (DlnaProtocol.Fault fault) {
            fail("a harmless request was refused: " + fault.getMessage());
        }
    }

    private static void oversizeIsRefused() {
        String padding = "x".repeat(DlnaProtocol.MAX_SOAP_BYTES);
        byte[] body = envelope(AVT, "SetAVTransportURI", "InstanceID", "0",
                "CurrentURI", "http://a/b.mp3", "CurrentURIMetaData", padding);
        try {
            DlnaProtocol.parseCall(AVT, AVT.type() + "#SetAVTransportURI", body);
            fail("an oversize request was read");
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == 402 && fault.getMessage().contains("larger"), "oversize refused");
        }
        StringBuilder deep = new StringBuilder("<s:Envelope><s:Body><u:Stop>");
        for (int i = 0; i < 30; i++) {
            deep.append("<a>");
        }
        expectParseFault(402, deep.toString(), "nesting");
    }

    private static void titles() throws Exception {
        require(DlnaProtocol.titleOf("").isEmpty(), "no metadata");
        require(DlnaProtocol.titleOf("NOT_IMPLEMENTED").isEmpty(), "NOT_IMPLEMENTED metadata");
        require(DlnaProtocol.titleOf("<DIDL-Lite><item><upnp:class>x</upnp:class></item></DIDL-Lite>")
                .isEmpty(), "no title element");
        require(DlnaProtocol.titleOf("<DIDL-Lite><item><dc:title>  Two\n lines </dc:title>"
                + "<dc:title>second</dc:title></item></DIDL-Lite>").equals("Two lines"),
                "first title, one line");
        require(DlnaProtocol.titleOf("<DIDL-Lite><item><title>&#x263A; ok</title></item></DIDL-Lite>")
                .equals("☺ ok"), "a title without prefix, with a character reference");
        require(DlnaProtocol.titleOf("<DIDL-Lite><item><dc:title>" + "y".repeat(500)
                + "</dc:title></item></DIDL-Lite>").length() == 200, "title capped at 200");
        String metadata = DlnaProtocol.trackMetadata("A \"quote\" & <tag>");
        wellFormed(metadata);
        require(DlnaProtocol.titleOf(metadata).equals("A \"quote\" & <tag>"), "metadata round trip");
        require(DlnaProtocol.trackMetadata("").isEmpty(), "no title, no metadata");
        require(DlnaProtocol.escape("a\u0001b\u0000c\tz").equals("abc\tz"),
                "control characters left out");
    }

    private static void times() {
        require(DlnaProtocol.formatTime(0).equals("0:00:00"), "0");
        require(DlnaProtocol.formatTime(999).equals("0:00:00"), "under a second");
        require(DlnaProtocol.formatTime(59_000).equals("0:00:59"), "59 s");
        require(DlnaProtocol.formatTime((59 * 60 + 59) * 1000L).equals("0:59:59"), "59:59");
        require(DlnaProtocol.formatTime(36_000_000L).equals("10:00:00"), "10 hours");
        require(DlnaProtocol.formatTime(-1).equals("NOT_IMPLEMENTED"), "unknown");

        require(DlnaProtocol.parseTime("0:00:00") == 0, "parse 0");
        require(DlnaProtocol.parseTime("0:59:59") == 3_599_000L, "parse 59:59");
        require(DlnaProtocol.parseTime("10:00:00") == 36_000_000L, "parse 10 hours");
        require(DlnaProtocol.parseTime("0:1:5") == 65_000L, "async_upnp_client's unpadded form");
        require(DlnaProtocol.parseTime("00:00:01.5") == 1_500L, "decimal fraction");
        require(DlnaProtocol.parseTime("0:00:01.250") == 1_250L, "milliseconds");
        require(DlnaProtocol.parseTime("0:00:01.1/4") == 1_250L, "F0/F1 fraction");
        require(DlnaProtocol.parseTime(" 1:02:03 ") == 3_723_000L, "spaces around");
        for (String bad : new String[] {"NOT_IMPLEMENTED", "", "1:60:00", "0:00:60", "-0:00:05",
                "12", "a:b:c", "0:00:01.4/4", "0:00:01.1/0", null}) {
            require(DlnaProtocol.parseTime(bad) == -1, "refused time " + bad);
        }
        for (long ms : new long[] {0, 1_000, 59_000, 3_599_000, 36_000_000, 359_999_000}) {
            require(DlnaProtocol.parseTime(DlnaProtocol.formatTime(ms)) == ms, "round trip " + ms);
        }
    }

    private static void events() throws Exception {
        DlnaProtocol.State playing = new DlnaProtocol.State(DlnaProtocol.PLAYING, false,
                "http://a/b.mp3?x=1&y=2", "Bell & \"chime\"", 30_000, 1_000, true, 55, false);
        Map<String, String> avt = DlnaProtocol.evented(AVT, playing);
        String initial = DlnaProtocol.eventBody(AVT, null, avt);
        wellFormed(initial);
        require(initial.contains("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
                && initial.contains("<e:property><LastChange>"), "a LastChange property");
        String lastChange = innerText(initial, "LastChange");
        wellFormed(lastChange);
        require(lastChange.startsWith("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">"
                + "<InstanceID val=\"0\">"), "AVT namespace and instance 0: " + lastChange);
        require(lastChange.contains("<TransportState val=\"PLAYING\"/>"), "state evented");
        require(lastChange.contains("<CurrentTransportActions val=\"Pause,Stop,Seek\"/>"),
                "actions evented");
        require(lastChange.contains("<AVTransportURI val=\"http://a/b.mp3?x=1&amp;y=2\"/>"),
                "address escaped as an attribute");
        require(lastChange.contains("<CurrentTrackDuration val=\"0:00:30\"/>"), "duration");
        require(!lastChange.contains("RelativeTimePosition"), "position is never evented");
        // The metadata is XML inside an attribute inside the escaped LastChange: three levels.
        require(lastChange.contains("&lt;dc:title&gt;Bell &amp;amp; &amp;quot;chime&amp;quot;"),
                "metadata escaped twice inside LastChange");

        require(DlnaProtocol.eventBody(AVT, avt, avt) == null, "nothing changed, nothing sent");
        DlnaProtocol.State paused = new DlnaProtocol.State(DlnaProtocol.PAUSED_PLAYBACK, false,
                playing.uri, playing.title, 30_000, 2_000, true, 55, false);
        String change = DlnaProtocol.eventBody(AVT, avt, DlnaProtocol.evented(AVT, paused));
        wellFormed(change);
        String changed = innerText(change, "LastChange");
        require(changed.contains("<TransportState val=\"PAUSED_PLAYBACK\"/>")
                && changed.contains("<CurrentTransportActions val=\"Play,Stop,Seek\"/>")
                && !changed.contains("AVTransportURI"), "only what changed: " + changed);

        Map<String, String> rcs = DlnaProtocol.evented(RCS, playing);
        String volume = innerText(DlnaProtocol.eventBody(RCS, null, rcs), "LastChange");
        wellFormed(volume);
        require(volume.contains("urn:schemas-upnp-org:metadata-1-0/RCS/")
                && volume.contains("<Volume channel=\"Master\" val=\"55\"/>")
                && volume.contains("<Mute channel=\"Master\" val=\"0\"/>"), "RCS " + volume);
        DlnaProtocol.State louder = new DlnaProtocol.State(DlnaProtocol.PLAYING, false,
                playing.uri, playing.title, 30_000, 1_000, true, 80, true);
        String louderText = innerText(DlnaProtocol.eventBody(RCS, rcs,
                DlnaProtocol.evented(RCS, louder)), "LastChange");
        require(louderText.contains("val=\"80\"") && louderText.contains("val=\"1\""),
                "volume and mute changes " + louderText);

        String cm = DlnaProtocol.eventBody(CM, null,
                DlnaProtocol.evented(CM, playing));
        wellFormed(cm);
        require(cm.contains("<e:property><SinkProtocolInfo>http-get:*:audio/*:*")
                && cm.contains("<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs>")
                && !cm.contains("LastChange"), "ConnectionManager events its own values");
    }

    private static void subscriptions() {
        require("http://192.168.1.5:8001/notify".equals(
                DlnaProtocol.callbackOf("<http://192.168.1.5:8001/notify>")), "one callback");
        require("http://10.0.0.2/a".equals(DlnaProtocol.callbackOf(
                "<https://x/y><http://10.0.0.2/a>")), "the first http callback");
        require(DlnaProtocol.callbackOf("http://no-brackets/") == null, "brackets needed");
        require(DlnaProtocol.callbackOf("<file:///etc/passwd>") == null, "http only");
        require(DlnaProtocol.callbackOf(null) == null, "no header");
        require(DlnaProtocol.subscriptionSeconds("Second-540") == 540, "a controller's 9 minutes");
        require(DlnaProtocol.subscriptionSeconds("Second-5") == 300, "at least 300");
        require(DlnaProtocol.subscriptionSeconds("Second-99999") == 1800, "at most 1800");
        require(DlnaProtocol.subscriptionSeconds("Second-infinite") == 1800, "infinite is 1800");
        require(DlnaProtocol.subscriptionSeconds(null) == 1800, "none is 1800");
    }

    private static void ssdp() {
        String location = "http://192.168.1.20:2325/dlna/description.xml";
        String server = "Android/14 UPnP/1.1 Muralis/0.7.0";
        List<String[]> pairs = DlnaProtocol.announcements(UDN);
        require(pairs.size() == 6, "root, uuid, device type and three services");
        String alive = DlnaProtocol.alive(pairs.get(2)[0], pairs.get(2)[1], location, server, 7);
        require(alive.startsWith("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"), "notify");
        require(alive.contains("\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                && alive.contains("\r\nUSN: " + UDN + "::urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                && alive.contains("\r\nLOCATION: " + location + "\r\n")
                && alive.contains("\r\nNTS: ssdp:alive\r\n")
                && alive.contains("\r\nCACHE-CONTROL: max-age=1800\r\n")
                && alive.contains("\r\nBOOTID.UPNP.ORG: 7\r\n")
                && alive.endsWith("\r\n\r\n"), "alive " + alive);
        String bye = DlnaProtocol.byebye(pairs.get(0)[0], pairs.get(0)[1], 7);
        require(bye.contains("\r\nNTS: ssdp:byebye\r\n") && !bye.contains("LOCATION")
                && bye.contains("\r\nUSN: " + UDN + "::upnp:rootdevice\r\n"), "byebye " + bye);

        String search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\nMX: 4\r\nST: ssdp:all\r\n\r\n";
        DlnaProtocol.Search parsed = DlnaProtocol.parseSearch(search);
        require(parsed != null && parsed.target.equals("ssdp:all") && parsed.waitSeconds == 4,
                "search read");
        require(DlnaProtocol.parseSearch(search.replace("MX: 4", "MX: 120")).waitSeconds == 5,
                "wait capped at 5");
        require(DlnaProtocol.parseSearch(search.replace("\"ssdp:discover\"", "ssdp:discover"))
                == null, "MAN must be quoted ssdp:discover");
        require(DlnaProtocol.parseSearch(alive) == null, "a NOTIFY is no search");

        List<String> all = DlnaProtocol.searchResponses("ssdp:all", UDN, location, server, 7,
                "Thu, 22 Oct 2026 10:00:00 GMT");
        require(all.size() == 6, "ssdp:all answers everything");
        String[][] targets = {
            {"upnp:rootdevice", UDN + "::upnp:rootdevice"},
            {UDN, UDN},
            {DlnaProtocol.DEVICE_TYPE, UDN + "::" + DlnaProtocol.DEVICE_TYPE},
            {AVT.type(), UDN + "::" + AVT.type()},
            {RCS.type(), UDN + "::" + RCS.type()},
            {CM.type(), UDN + "::" + CM.type()},
        };
        for (String[] target : targets) {
            List<String> one = DlnaProtocol.searchResponses(target[0], UDN, location, server, 7,
                    "Thu, 22 Oct 2026 10:00:00 GMT");
            require(one.size() == 1, "one answer for " + target[0]);
            String answer = one.get(0);
            require(answer.startsWith("HTTP/1.1 200 OK\r\n")
                    && answer.contains("\r\nST: " + target[0] + "\r\n")
                    && answer.contains("\r\nUSN: " + target[1] + "\r\n")
                    && answer.contains("\r\nLOCATION: " + location + "\r\n")
                    && answer.contains("\r\nEXT:\r\n")
                    && answer.contains("\r\nCACHE-CONTROL: max-age=1800\r\n"), "answer " + answer);
        }
        require(DlnaProtocol.searchResponses("urn:schemas-upnp-org:device:MediaServer:1", UDN,
                location, server, 7, "d").isEmpty(), "another device type is not answered");
        require(DlnaProtocol.searchResponses("uuid:other", UDN, location, server, 7, "d").isEmpty(),
                "another uuid is not answered");
        require(DlnaProtocol.httpDate(0).equals("Thu, 1 Jan 1970 00:00:00 GMT"), "HTTP date");
    }

    // ---------------------------------------------------------------------------------------

    /** Calls an action through the whole path: SOAP request, parse, answer, SOAP response. */
    private static Map<String, String> call(DlnaProtocol.Service service, String action,
            DlnaProtocol.Renderer renderer, String... arguments) throws Exception {
        DlnaProtocol.Call call = DlnaProtocol.parseCall(service,
                "\"" + service.type() + "#" + action + "\"", envelope(service, action, arguments));
        String response = DlnaProtocol.answer(call, renderer);
        return responseArguments(response, action);
    }

    private static void expectFault(int code, DlnaProtocol.Service service, String action,
            DlnaProtocol.Renderer renderer, String... arguments) {
        try {
            call(service, action, renderer, arguments);
            fail(action + " was not refused with " + code);
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == code, action + " refused with " + fault.code + " ("
                    + fault.getMessage() + "), expected " + code);
            require(!fault.getMessage().isEmpty(), "a refusal has a reason");
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    private static void expectParseFault(int code, String body, String what) {
        try {
            DlnaProtocol.parseCall(AVT, AVT.type() + "#Stop", body.getBytes(StandardCharsets.UTF_8));
            fail(what + " was read");
        } catch (DlnaProtocol.Fault fault) {
            require(fault.code == code, what + ": " + fault.code + " " + fault.getMessage());
        }
    }

    /** A request as async_upnp_client builds one: escaped values, the u: prefix. */
    private static byte[] envelope(DlnaProtocol.Service service, String action, String... arguments) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\""
                + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service.type() + "\">");
        for (int i = 0; i < arguments.length; i += 2) {
            // Values are passed already escaped where a test needs raw markup in them.
            String value = arguments[i + 1];
            xml.append('<').append(arguments[i]).append('>')
                    .append(value.contains("&") || value.contains("&lt;") ? value
                            : DlnaProtocol.escape(value))
                    .append("</").append(arguments[i]).append(">\n");
        }
        xml.append("</u:").append(action).append("></s:Body></s:Envelope>");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Reads a SOAP response with the JDK's parser, as a controller would. */
    private static Map<String, String> responseArguments(String response, String action)
            throws Exception {
        org.w3c.dom.Document document = parse(response);
        org.w3c.dom.NodeList found = document.getElementsByTagNameNS("*", action + "Response");
        require(found.getLength() == 1, "one " + action + "Response");
        Map<String, String> values = new LinkedHashMap<>();
        org.w3c.dom.NodeList children = found.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                values.put(child.getNodeName(), child.getTextContent());
            }
        }
        return values;
    }

    private static String innerText(String xml, String element) throws Exception {
        return parse(xml).getElementsByTagName(element).item(0).getTextContent();
    }

    private static void wellFormed(String xml) throws Exception {
        parse(xml);
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void fail(String what) {
        throw new AssertionError(what);
    }
}
