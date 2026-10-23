/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network speaker's protocol: a UPnP MediaRenderer:1 that plays sound only, written from
 * the UPnP Device Architecture 1.1 and the MediaRenderer:1, AVTransport:1, RenderingControl:1
 * and ConnectionManager:1 specifications, checked against what async_upnp_client, a widely used
 * DLNA controller library, asks for. Every text the panel sends or reads
 * lives here: the device and service descriptions, the SOAP calls and their answers or faults,
 * the GENA event bodies, the SSDP announcements and search answers, and the H:MM:SS times.
 * Which call is allowed in which state is decided here too. Pure, so all of it is host-tested;
 * {@link NetworkSpeaker} runs it on the network and plays the sound.
 *
 * <p>No XML parser is used: Android's and the JDK's differ in which hardening features they
 * accept, so a parser that is safe on the host could be unsafe or refuse to start on a
 * device. The small reader below knows elements, text, CDATA and the five XML entities plus
 * character references, and refuses any DOCTYPE or entity declaration outright, so nothing
 * external is ever resolved.
 */
final class DlnaProtocol {
    static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String DESCRIPTION_PATH = "/dlna/description.xml";
    /** A SOAP call, metadata included; a controller's are about 1 KB. */
    static final int MAX_SOAP_BYTES = 64 * 1024;
    /** How long an announcement stays valid; NetworkSpeaker announces again well before. */
    static final int MAX_AGE_SECONDS = 1800;
    static final String SSDP_ADDRESS = "239.255.255.250";
    static final int SSDP_PORT = 1900;
    /** The friendly name's limit in the Device Architecture. */
    private static final int MAX_FRIENDLY_NAME = 64;
    private static final int MAX_TITLE = 200;
    private static final int MAX_URI = 8192;
    private static final int MAX_DEPTH = 16;
    /** Subscriptions are granted between these, whatever was asked. */
    static final int MIN_SUBSCRIPTION_SECONDS = 300;
    static final int MAX_SUBSCRIPTION_SECONDS = 1800;

    // The transport states this renderer reaches (AVTransport:1, TransportState).
    static final String NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT";
    static final String STOPPED = "STOPPED";
    static final String PLAYING = "PLAYING";
    static final String PAUSED_PLAYBACK = "PAUSED_PLAYBACK";
    static final String TRANSITIONING = "TRANSITIONING";

    /** What the speaker accepts: any audio over HTTP, the common types named for controllers that look. */
    static final String SINK_PROTOCOL_INFO = String.join(",",
            "http-get:*:audio/*:*", "http-get:*:audio/mpeg:*", "http-get:*:audio/mp4:*",
            "http-get:*:audio/x-m4a:*", "http-get:*:audio/aac:*", "http-get:*:audio/flac:*",
            "http-get:*:audio/x-flac:*", "http-get:*:audio/ogg:*", "http-get:*:audio/opus:*",
            "http-get:*:audio/wav:*", "http-get:*:audio/x-wav:*", "http-get:*:audio/webm:*",
            "http-get:*:audio/3gpp:*", "http-get:*:application/vnd.apple.mpegurl:*",
            "http-get:*:application/x-mpegurl:*");

    /** "Not implemented" for a count, the AVTransport:1 value for RelCount and AbsCount. */
    private static final String NO_COUNT = "2147483647";
    private static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    private DlnaProtocol() {
    }

    // ---------------------------------------------------------------------------------------
    // The three services and their tables
    // ---------------------------------------------------------------------------------------

    /** One state variable of a service's table. */
    private static final class Variable {
        final String name;
        final String dataType;
        final boolean events;
        final String[] allowed;
        /** "minimum,maximum,step" or null. */
        final String range;

        Variable(String name, String dataType, boolean events, String[] allowed, String range) {
            this.name = name;
            this.dataType = dataType;
            this.events = events;
            this.allowed = allowed;
            this.range = range;
        }
    }

    /** One action: its in and out arguments, each "Name:RelatedStateVariable", in order. */
    private static final class Action {
        final String name;
        final String[] in;
        final String[] out;

        Action(String name, String[] in, String[] out) {
            this.name = name;
            this.in = in;
            this.out = out;
        }
    }

    private static Variable plain(String name) {
        return new Variable(name, "string", false, null, null);
    }

    private static Variable typed(String name, String dataType) {
        return new Variable(name, dataType, false, null, null);
    }

    private static Variable listed(String name, String... allowed) {
        return new Variable(name, "string", false, allowed, null);
    }

    private static String[] args(String... args) {
        return args;
    }

    private static final String INSTANCE = "InstanceID:A_ARG_TYPE_InstanceID";
    private static final String CHANNEL = "Channel:A_ARG_TYPE_Channel";

    enum Service {
        AV_TRANSPORT("AVTransport", "urn:schemas-upnp-org:metadata-1-0/AVT/",
                new Variable[] {
                    listed("TransportState", NO_MEDIA_PRESENT, STOPPED, PLAYING, PAUSED_PLAYBACK,
                            TRANSITIONING),
                    listed("TransportStatus", "OK", "ERROR_OCCURRED"),
                    listed("PlaybackStorageMedium", "NONE", "NETWORK"),
                    listed("RecordStorageMedium", NOT_IMPLEMENTED),
                    plain("PossiblePlaybackStorageMedia"),
                    plain("PossibleRecordStorageMedia"),
                    listed("CurrentPlayMode", "NORMAL"),
                    listed("TransportPlaySpeed", "1"),
                    listed("RecordMediumWriteStatus", NOT_IMPLEMENTED),
                    listed("CurrentRecordQualityMode", NOT_IMPLEMENTED),
                    plain("PossibleRecordQualityModes"),
                    new Variable("NumberOfTracks", "ui4", false, null, "0,1,1"),
                    new Variable("CurrentTrack", "ui4", false, null, "0,1,1"),
                    plain("CurrentTrackDuration"),
                    plain("CurrentMediaDuration"),
                    plain("CurrentTrackMetaData"),
                    plain("CurrentTrackURI"),
                    plain("AVTransportURI"),
                    plain("AVTransportURIMetaData"),
                    plain("NextAVTransportURI"),
                    plain("NextAVTransportURIMetaData"),
                    plain("RelativeTimePosition"),
                    plain("AbsoluteTimePosition"),
                    typed("RelativeCounterPosition", "i4"),
                    typed("AbsoluteCounterPosition", "i4"),
                    plain("CurrentTransportActions"),
                    new Variable("LastChange", "string", true, null, null),
                    // REL_TIME only: controllers offer seeking exactly when this lists it.
                    listed("A_ARG_TYPE_SeekMode", "REL_TIME"),
                    plain("A_ARG_TYPE_SeekTarget"),
                    typed("A_ARG_TYPE_InstanceID", "ui4"),
                },
                new Action[] {
                    new Action("SetAVTransportURI", args(INSTANCE, "CurrentURI:AVTransportURI",
                            "CurrentURIMetaData:AVTransportURIMetaData"), args()),
                    new Action("GetMediaInfo", args(INSTANCE), args(
                            "NrTracks:NumberOfTracks", "MediaDuration:CurrentMediaDuration",
                            "CurrentURI:AVTransportURI",
                            "CurrentURIMetaData:AVTransportURIMetaData",
                            "NextURI:NextAVTransportURI",
                            "NextURIMetaData:NextAVTransportURIMetaData",
                            "PlayMedium:PlaybackStorageMedium",
                            "RecordMedium:RecordStorageMedium",
                            "WriteStatus:RecordMediumWriteStatus")),
                    new Action("GetTransportInfo", args(INSTANCE), args(
                            "CurrentTransportState:TransportState",
                            "CurrentTransportStatus:TransportStatus",
                            "CurrentSpeed:TransportPlaySpeed")),
                    new Action("GetPositionInfo", args(INSTANCE), args(
                            "Track:CurrentTrack", "TrackDuration:CurrentTrackDuration",
                            "TrackMetaData:CurrentTrackMetaData", "TrackURI:CurrentTrackURI",
                            "RelTime:RelativeTimePosition", "AbsTime:AbsoluteTimePosition",
                            "RelCount:RelativeCounterPosition",
                            "AbsCount:AbsoluteCounterPosition")),
                    new Action("GetDeviceCapabilities", args(INSTANCE), args(
                            "PlayMedia:PossiblePlaybackStorageMedia",
                            "RecMedia:PossibleRecordStorageMedia",
                            "RecQualityModes:PossibleRecordQualityModes")),
                    new Action("GetTransportSettings", args(INSTANCE), args(
                            "PlayMode:CurrentPlayMode",
                            "RecQualityMode:CurrentRecordQualityMode")),
                    new Action("GetCurrentTransportActions", args(INSTANCE),
                            args("Actions:CurrentTransportActions")),
                    new Action("Stop", args(INSTANCE), args()),
                    new Action("Play", args(INSTANCE, "Speed:TransportPlaySpeed"), args()),
                    new Action("Pause", args(INSTANCE), args()),
                    new Action("Seek", args(INSTANCE, "Unit:A_ARG_TYPE_SeekMode",
                            "Target:A_ARG_TYPE_SeekTarget"), args()),
                }),
        RENDERING_CONTROL("RenderingControl", "urn:schemas-upnp-org:metadata-1-0/RCS/",
                new Variable[] {
                    new Variable("LastChange", "string", true, null, null),
                    typed("Mute", "boolean"),
                    // 0 to 100: controllers scale a 0..1 level by this maximum.
                    new Variable("Volume", "ui2", false, null, "0,100,1"),
                    listed("A_ARG_TYPE_Channel", "Master"),
                    typed("A_ARG_TYPE_InstanceID", "ui4"),
                },
                new Action[] {
                    new Action("GetMute", args(INSTANCE, CHANNEL), args("CurrentMute:Mute")),
                    new Action("SetMute", args(INSTANCE, CHANNEL, "DesiredMute:Mute"), args()),
                    new Action("GetVolume", args(INSTANCE, CHANNEL), args("CurrentVolume:Volume")),
                    new Action("SetVolume", args(INSTANCE, CHANNEL, "DesiredVolume:Volume"),
                            args()),
                }),
        CONNECTION_MANAGER("ConnectionManager", null,
                new Variable[] {
                    new Variable("SourceProtocolInfo", "string", true, null, null),
                    new Variable("SinkProtocolInfo", "string", true, null, null),
                    new Variable("CurrentConnectionIDs", "string", true, null, null),
                    listed("A_ARG_TYPE_ConnectionStatus", "OK", "ContentFormatMismatch",
                            "InsufficientBandwidth", "UnreliableChannel", "Unknown"),
                    plain("A_ARG_TYPE_ConnectionManager"),
                    listed("A_ARG_TYPE_Direction", "Input", "Output"),
                    plain("A_ARG_TYPE_ProtocolInfo"),
                    typed("A_ARG_TYPE_ConnectionID", "i4"),
                    typed("A_ARG_TYPE_AVTransportID", "i4"),
                    typed("A_ARG_TYPE_RcsID", "i4"),
                },
                new Action[] {
                    new Action("GetProtocolInfo", args(),
                            args("Source:SourceProtocolInfo", "Sink:SinkProtocolInfo")),
                    new Action("GetCurrentConnectionIDs", args(),
                            args("ConnectionIDs:CurrentConnectionIDs")),
                    new Action("GetCurrentConnectionInfo",
                            args("ConnectionID:A_ARG_TYPE_ConnectionID"), args(
                                    "RcsID:A_ARG_TYPE_RcsID",
                                    "AVTransportID:A_ARG_TYPE_AVTransportID",
                                    "ProtocolInfo:A_ARG_TYPE_ProtocolInfo",
                                    "PeerConnectionManager:A_ARG_TYPE_ConnectionManager",
                                    "PeerConnectionID:A_ARG_TYPE_ConnectionID",
                                    "Direction:A_ARG_TYPE_Direction",
                                    "Status:A_ARG_TYPE_ConnectionStatus")),
                });

        final String name;
        /** The namespace of this service's LastChange events; null when it has none. */
        final String lastChangeNamespace;
        private final Variable[] variables;
        private final Action[] actions;

        Service(String name, String lastChangeNamespace, Variable[] variables, Action[] actions) {
            this.name = name;
            this.lastChangeNamespace = lastChangeNamespace;
            this.variables = variables;
            this.actions = actions;
        }

        String type() {
            return "urn:schemas-upnp-org:service:" + name + ":1";
        }

        String id() {
            return "urn:upnp-org:serviceId:" + name;
        }

        String scpdPath() {
            return "/dlna/" + name + ".xml";
        }

        String controlPath() {
            return "/dlna/" + name + "/control";
        }

        String eventPath() {
            return "/dlna/" + name + "/event";
        }

        private Action action(String actionName) {
            for (Action action : actions) {
                if (action.name.equals(actionName)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** The service whose description, control or event address {@code path} is, or null. */
    static Service scpdAt(String path) {
        for (Service service : Service.values()) {
            if (service.scpdPath().equals(path)) {
                return service;
            }
        }
        return null;
    }

    static Service controlAt(String path) {
        for (Service service : Service.values()) {
            if (service.controlPath().equals(path)) {
                return service;
            }
        }
        return null;
    }

    static Service eventAt(String path) {
        for (Service service : Service.values()) {
            if (service.eventPath().equals(path)) {
                return service;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Descriptions
    // ---------------------------------------------------------------------------------------

    /** A UDN for a panel that has none: minted once and stored by the caller. */
    static String newUdn() {
        return "uuid:" + java.util.UUID.randomUUID();
    }

    /** Whether {@code udn} is one {@link #newUdn} could have made. */
    static boolean isUdn(String udn) {
        return udn != null && udn.matches("uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** The root device description; its service addresses are relative, as UDA 1.1 asks. */
    static String deviceDescription(String friendlyName, String udn) {
        String name = friendlyName == null || friendlyName.trim().isEmpty() ? "Muralis"
                : friendlyName.trim();
        if (name.length() > MAX_FRIENDLY_NAME) {
            name = name.substring(0, MAX_FRIENDLY_NAME);
        }
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" configId=\"1\">")
                .append("<specVersion><major>1</major><minor>1</minor></specVersion>")
                .append("<device>")
                .append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>")
                .append("<friendlyName>").append(escape(name)).append("</friendlyName>")
                .append("<manufacturer>spazio17</manufacturer>")
                .append("<modelName>Muralis</modelName>")
                .append("<UDN>").append(escape(udn)).append("</UDN>")
                .append("<serviceList>");
        for (Service service : Service.values()) {
            xml.append("<service>")
                    .append("<serviceType>").append(service.type()).append("</serviceType>")
                    .append("<serviceId>").append(service.id()).append("</serviceId>")
                    .append("<SCPDURL>").append(service.scpdPath()).append("</SCPDURL>")
                    .append("<controlURL>").append(service.controlPath()).append("</controlURL>")
                    .append("<eventSubURL>").append(service.eventPath()).append("</eventSubURL>")
                    .append("</service>");
        }
        return xml.append("</serviceList></device></root>\n").toString();
    }

    /** A service's description (SCPD), from the same table the calls are checked against. */
    static String serviceDescription(Service service) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
                .append("<specVersion><major>1</major><minor>1</minor></specVersion>")
                .append("<actionList>");
        for (Action action : service.actions) {
            xml.append("<action><name>").append(action.name).append("</name>");
            if (action.in.length + action.out.length > 0) {
                xml.append("<argumentList>");
                appendArguments(xml, action.in, "in");
                appendArguments(xml, action.out, "out");
                xml.append("</argumentList>");
            }
            xml.append("</action>");
        }
        xml.append("</actionList><serviceStateTable>");
        for (Variable variable : service.variables) {
            xml.append("<stateVariable sendEvents=\"").append(variable.events ? "yes" : "no")
                    .append("\"><name>").append(variable.name).append("</name><dataType>")
                    .append(variable.dataType).append("</dataType>");
            if (variable.allowed != null) {
                xml.append("<allowedValueList>");
                for (String value : variable.allowed) {
                    xml.append("<allowedValue>").append(value).append("</allowedValue>");
                }
                xml.append("</allowedValueList>");
            }
            if (variable.range != null) {
                String[] range = variable.range.split(",");
                xml.append("<allowedValueRange><minimum>").append(range[0])
                        .append("</minimum><maximum>").append(range[1])
                        .append("</maximum><step>").append(range[2])
                        .append("</step></allowedValueRange>");
            }
            xml.append("</stateVariable>");
        }
        return xml.append("</serviceStateTable></scpd>\n").toString();
    }

    private static void appendArguments(StringBuilder xml, String[] arguments, String direction) {
        for (String argument : arguments) {
            int colon = argument.indexOf(':');
            xml.append("<argument><name>").append(argument, 0, colon)
                    .append("</name><direction>").append(direction)
                    .append("</direction><relatedStateVariable>")
                    .append(argument.substring(colon + 1))
                    .append("</relatedStateVariable></argument>");
        }
    }

    // ---------------------------------------------------------------------------------------
    // SOAP
    // ---------------------------------------------------------------------------------------

    /**
     * A UPnP error: the code the specification gives the case, and the reason in words, which
     * goes into the fault's errorDescription and into the panel's log.
     */
    static final class Fault extends Exception {
        final int code;

        Fault(int code, String reason) {
            super(reason);
            this.code = code;
        }
    }

    /** A control call as read: its service, its action and its arguments by name. */
    static final class Call {
        final Service service;
        final String action;
        final Map<String, String> arguments;

        Call(Service service, String action, Map<String, String> arguments) {
            this.service = service;
            this.action = action;
            this.arguments = arguments;
        }
    }

    /**
     * Reads a control call posted to {@code service}'s control address. The SOAPACTION header
     * names the service and the action; the body must name the same action.
     *
     * @throws Fault 401 when the header or the action is not this service's, 402 when the body
     *     is too large, not well formed, carries a DOCTYPE or an unknown entity
     */
    static Call parseCall(Service service, String soapAction, byte[] body) throws Fault {
        String action = actionOf(service, soapAction);
        if (body == null || body.length > MAX_SOAP_BYTES) {
            throw new Fault(402, "the request is larger than " + MAX_SOAP_BYTES + " bytes");
        }
        XmlScanner scanner = new XmlScanner(new String(body, StandardCharsets.UTF_8));
        Deque<String> open = new ArrayDeque<>();
        boolean inBody = false;
        boolean inAction = false;
        String bodyAction = null;
        String argument = null;
        StringBuilder argumentText = new StringBuilder();
        Map<String, String> arguments = new LinkedHashMap<>();
        for (XmlToken token = scanner.next(); token != null; token = scanner.next()) {
            int depth = open.size();
            if (token.kind == XmlToken.START) {
                if (depth >= MAX_DEPTH) {
                    throw new Fault(402, "the request nests deeper than " + MAX_DEPTH + " levels");
                }
                String local = localName(token.name);
                if (depth == 0 && !"Envelope".equals(local)) {
                    throw new Fault(402, "the request is not a SOAP envelope");
                } else if (depth == 1 && "Body".equals(local)) {
                    inBody = !token.empty;
                } else if (depth == 2 && inBody) {
                    if (bodyAction != null) {
                        throw new Fault(402, "the request names more than one action");
                    }
                    bodyAction = local;
                    inAction = !token.empty;
                } else if (depth == 3 && inAction) {
                    if (arguments.containsKey(local)) {
                        throw new Fault(402, "the argument " + local + " is given twice");
                    }
                    argument = local;
                    argumentText.setLength(0);
                    if (token.empty) {
                        arguments.put(local, "");
                        argument = null;
                    }
                } else if (depth >= 4 && argument != null) {
                    throw new Fault(402, "the argument " + argument + " holds markup, not text");
                }
                if (!token.empty) {
                    open.push(token.name);
                }
            } else if (token.kind == XmlToken.END) {
                if (open.isEmpty() || !open.peek().equals(token.name)) {
                    throw new Fault(402, "the request is not well-formed XML");
                }
                open.pop();
                if (open.size() == 3 && argument != null) {
                    arguments.put(argument, argumentText.toString());
                    argument = null;
                } else if (open.size() == 2) {
                    inAction = false;
                } else if (open.size() == 1) {
                    inBody = false;
                }
            } else if (argument != null && depth == 4) {
                argumentText.append(token.text);
            }
        }
        if (!open.isEmpty()) {
            throw new Fault(402, "the request is not well-formed XML");
        }
        if (bodyAction == null) {
            throw new Fault(401, "the request's body names no action");
        }
        if (!bodyAction.equals(action)) {
            throw new Fault(401, "the body's action " + bodyAction
                    + " is not the SOAPACTION header's " + action);
        }
        return new Call(service, action, arguments);
    }

    /** The action a SOAPACTION header names, when it names one of {@code service}'s. */
    private static String actionOf(Service service, String soapAction) throws Fault {
        if (soapAction == null) {
            throw new Fault(401, "the request has no SOAPACTION header");
        }
        String value = soapAction.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        int hash = value.indexOf('#');
        if (hash < 0 || !value.substring(0, hash).equals(service.type())) {
            throw new Fault(401, "the SOAPACTION header is not for " + service.name);
        }
        String action = value.substring(hash + 1);
        if (service.action(action) == null) {
            throw new Fault(401, service.name + " has no action " + action);
        }
        return action;
    }

    /** The SOAP answer to {@code action}, its out arguments in the order the SCPD lists them. */
    static String response(Service service, String action, Map<String, String> out) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
                .append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                .append("<s:Body><u:").append(action).append("Response xmlns:u=\"")
                .append(service.type()).append("\">");
        Action declared = service.action(action);
        if (declared != null) {
            for (String argument : declared.out) {
                String name = argument.substring(0, argument.indexOf(':'));
                String value = out.get(name);
                xml.append('<').append(name).append('>').append(escape(value == null ? "" : value))
                        .append("</").append(name).append('>');
            }
        }
        return xml.append("</u:").append(action).append("Response></s:Body></s:Envelope>\n")
                .toString();
    }

    /** A SOAP fault carrying a UPnPError; sent with HTTP status 500. */
    static String fault(Fault fault) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><s:Fault><faultcode>s:Client</faultcode>"
                + "<faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + fault.code + "</errorCode>"
                + "<errorDescription>" + escape(fault.getMessage()) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>\n";
    }

    // ---------------------------------------------------------------------------------------
    // The renderer: its state, and which call it takes in which state
    // ---------------------------------------------------------------------------------------

    /** What the renderer is doing, read at one moment. */
    static final class State {
        final String transport;
        /** The last playback ended in an error; cleared by the next call that changes it. */
        final boolean errorOccurred;
        final String uri;
        final String title;
        /** The length in milliseconds, or -1 when the stream does not say (a live stream). */
        final long durationMs;
        final long positionMs;
        final boolean seekable;
        /** The media volume as 0 to 100, and whether it is muted, as the device has them now. */
        final int volume;
        final boolean muted;

        State(String transport, boolean errorOccurred, String uri, String title, long durationMs,
                long positionMs, boolean seekable, int volume, boolean muted) {
            this.transport = transport;
            this.errorOccurred = errorOccurred;
            this.uri = uri == null ? "" : uri;
            this.title = title == null ? "" : title;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.seekable = seekable;
            this.volume = volume;
            this.muted = muted;
        }

        boolean hasMedia() {
            return !NO_MEDIA_PRESENT.equals(transport);
        }
    }

    /**
     * The player behind the protocol. {@link #answer} has already checked that the call is
     * allowed in the current state; a method throws a Fault only for what happens on the
     * device (an address that cannot be opened, a volume that is fixed).
     */
    interface Renderer {
        State state();

        /** A new address; plays it at once when the renderer was playing, as AVTransport:1 asks. */
        void setUri(String uri, String title) throws Fault;

        void play() throws Fault;

        void pause() throws Fault;

        void stop() throws Fault;

        void seek(long positionMs) throws Fault;

        void setVolume(int percent) throws Fault;

        void setMute(boolean muted) throws Fault;
    }

    /**
     * Carries out {@code call} on {@code renderer} and returns the SOAP answer.
     *
     * @throws Fault the UPnP error for an action this service lacks, a missing or bad
     *     argument, or a call the current state does not allow, with the reason
     */
    static String answer(Call call, Renderer renderer) throws Fault {
        Action action = call.service.action(call.action);
        if (action == null) {
            throw new Fault(401, call.service.name + " has no action " + call.action);
        }
        for (String argument : action.in) {
            String name = argument.substring(0, argument.indexOf(':'));
            if (!call.arguments.containsKey(name)) {
                throw new Fault(402, call.action + " needs the argument " + name);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        switch (call.service) {
            case AV_TRANSPORT:
                transport(call.action, call.arguments, renderer, out);
                break;
            case RENDERING_CONTROL:
                rendering(call.action, call.arguments, renderer, out);
                break;
            default:
                connections(call.action, call.arguments, out);
                break;
        }
        return response(call.service, call.action, out);
    }

    private static void transport(String action, Map<String, String> arguments, Renderer renderer,
            Map<String, String> out) throws Fault {
        instance(arguments, 718);
        State state = renderer.state();
        switch (action) {
            case "SetAVTransportURI":
                renderer.setUri(checkedUri(arguments.get("CurrentURI")),
                        titleOf(arguments.get("CurrentURIMetaData")));
                break;
            case "Play":
                if (!"1".equals(arguments.get("Speed").trim())) {
                    throw new Fault(717, "only speed 1 is supported");
                }
                if (!state.hasMedia()) {
                    throw new Fault(701, "nothing to play: no address was set");
                }
                // Play while playing, or while starting to, is no change and no error.
                if (STOPPED.equals(state.transport) || PAUSED_PLAYBACK.equals(state.transport)) {
                    renderer.play();
                }
                break;
            case "Pause":
                if (PLAYING.equals(state.transport)) {
                    renderer.pause();
                } else if (!PAUSED_PLAYBACK.equals(state.transport)) {
                    throw new Fault(701, "Pause is not available while " + state.transport);
                }
                break;
            case "Stop":
                renderer.stop();
                break;
            case "Seek": {
                if (!"REL_TIME".equals(arguments.get("Unit").trim())) {
                    throw new Fault(710, "only REL_TIME seeking is supported");
                }
                long target = parseTime(arguments.get("Target"));
                if (target < 0) {
                    throw new Fault(711, "the target is not a time of the form H:MM:SS");
                }
                if (!(PLAYING.equals(state.transport) || PAUSED_PLAYBACK.equals(state.transport))
                        || !state.seekable) {
                    throw new Fault(701, "this sound cannot be sought while " + state.transport);
                }
                if (target > state.durationMs) {
                    throw new Fault(711, "the target is past the end, "
                            + formatTime(state.durationMs));
                }
                renderer.seek(target);
                break;
            }
            case "GetMediaInfo":
                out.put("NrTracks", state.hasMedia() ? "1" : "0");
                out.put("MediaDuration", durationOf(state));
                out.put("CurrentURI", state.uri);
                out.put("CurrentURIMetaData", trackMetadata(state.title));
                out.put("NextURI", "");
                out.put("NextURIMetaData", "");
                out.put("PlayMedium", state.hasMedia() ? "NETWORK" : "NONE");
                out.put("RecordMedium", NOT_IMPLEMENTED);
                out.put("WriteStatus", NOT_IMPLEMENTED);
                break;
            case "GetTransportInfo":
                out.put("CurrentTransportState", state.transport);
                out.put("CurrentTransportStatus", state.errorOccurred ? "ERROR_OCCURRED" : "OK");
                out.put("CurrentSpeed", "1");
                break;
            case "GetPositionInfo":
                out.put("Track", state.hasMedia() ? "1" : "0");
                out.put("TrackDuration", durationOf(state));
                out.put("TrackMetaData", trackMetadata(state.title));
                out.put("TrackURI", state.uri);
                out.put("RelTime", formatTime(state.positionMs));
                out.put("AbsTime", formatTime(state.positionMs));
                out.put("RelCount", NO_COUNT);
                out.put("AbsCount", NO_COUNT);
                break;
            case "GetDeviceCapabilities":
                out.put("PlayMedia", "NETWORK");
                out.put("RecMedia", NOT_IMPLEMENTED);
                out.put("RecQualityModes", NOT_IMPLEMENTED);
                break;
            case "GetTransportSettings":
                out.put("PlayMode", "NORMAL");
                out.put("RecQualityMode", NOT_IMPLEMENTED);
                break;
            default: // GetCurrentTransportActions, the last in the table
                out.put("Actions", transportActions(state));
                break;
        }
    }

    private static void rendering(String action, Map<String, String> arguments, Renderer renderer,
            Map<String, String> out) throws Fault {
        instance(arguments, 702);
        if (!"Master".equals(arguments.get("Channel").trim())) {
            throw new Fault(402, "only the Master channel exists");
        }
        switch (action) {
            case "GetMute":
                out.put("CurrentMute", renderer.state().muted ? "1" : "0");
                break;
            case "SetMute":
                renderer.setMute(upnpBoolean(arguments.get("DesiredMute"), "DesiredMute"));
                break;
            case "GetVolume":
                out.put("CurrentVolume", Integer.toString(renderer.state().volume));
                break;
            default: { // SetVolume
                int volume;
                try {
                    volume = Integer.parseInt(arguments.get("DesiredVolume").trim());
                } catch (NumberFormatException notNumber) {
                    throw new Fault(402, "DesiredVolume is not a whole number");
                }
                if (volume < 0 || volume > 100) {
                    throw new Fault(601, "DesiredVolume must be between 0 and 100");
                }
                renderer.setVolume(volume);
                break;
            }
        }
    }

    /** ConnectionManager: one fixed connection, 0, as a renderer without PrepareForConnection has. */
    private static void connections(String action, Map<String, String> arguments,
            Map<String, String> out) throws Fault {
        switch (action) {
            case "GetProtocolInfo":
                out.put("Source", "");
                out.put("Sink", SINK_PROTOCOL_INFO);
                break;
            case "GetCurrentConnectionIDs":
                out.put("ConnectionIDs", "0");
                break;
            default: // GetCurrentConnectionInfo
                if (!"0".equals(arguments.get("ConnectionID").trim())) {
                    throw new Fault(706, "the only connection is 0");
                }
                out.put("RcsID", "0");
                out.put("AVTransportID", "0");
                out.put("ProtocolInfo", "");
                out.put("PeerConnectionManager", "");
                out.put("PeerConnectionID", "-1");
                out.put("Direction", "Input");
                out.put("Status", "OK");
                break;
        }
    }

    /** InstanceID must be 0, the one instance; another number is {@code wrongInstance}. */
    private static void instance(Map<String, String> arguments, int wrongInstance) throws Fault {
        String value = arguments.get("InstanceID").trim();
        if (!value.matches("\\d{1,10}")) {
            throw new Fault(402, "InstanceID is not a number");
        }
        if (!value.matches("0+")) {
            throw new Fault(wrongInstance, "the only InstanceID is 0");
        }
    }

    private static boolean upnpBoolean(String value, String name) throws Fault {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "yes":
                return true;
            case "0":
            case "false":
            case "no":
                return false;
            default:
                throw new Fault(402, name + " is not a boolean");
        }
    }

    /**
     * The address SetAVTransportURI may set: http or https with a host, nothing else, since
     * the panel fetches it itself and any other scheme would reach into the device.
     */
    static String checkedUri(String value) throws Fault {
        String uri = value == null ? "" : value.trim();
        if (uri.isEmpty()) {
            throw new Fault(402, "CurrentURI is empty");
        }
        if (uri.length() > MAX_URI) {
            throw new Fault(402, "CurrentURI is longer than " + MAX_URI + " characters");
        }
        java.net.URI parsed;
        try {
            parsed = new java.net.URI(uri);
        } catch (java.net.URISyntaxException malformed) {
            throw new Fault(716, "CurrentURI is not a valid address");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new Fault(716, "only http and https addresses can be played");
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw new Fault(716, "CurrentURI names no host");
        }
        return uri;
    }

    /** CurrentTransportActions: what can be called now and succeed. */
    static String transportActions(State state) {
        switch (state.transport) {
            case PLAYING:
                return state.seekable ? "Pause,Stop,Seek" : "Pause,Stop";
            case PAUSED_PLAYBACK:
                return state.seekable ? "Play,Stop,Seek" : "Play,Stop";
            case STOPPED:
                return "Play,Stop";
            default:
                // NO_MEDIA_PRESENT and TRANSITIONING. Never empty: async_upnp_client reads an empty
                // list as "everything is allowed" and would offer Play with nothing to play.
                return "Stop";
        }
    }

    private static String durationOf(State state) {
        return state.hasMedia() ? formatTime(state.durationMs) : formatTime(0);
    }

    // ---------------------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------------------

    /**
     * The title out of a DIDL-Lite text (its first dc:title), or "" when it has none. Only the
     * title is read; nothing in the metadata is fetched or resolved.
     *
     * @throws Fault 402 when it carries a DOCTYPE or an entity XML does not define
     */
    static String titleOf(String didl) throws Fault {
        if (didl == null || didl.trim().isEmpty() || NOT_IMPLEMENTED.equals(didl.trim())) {
            return "";
        }
        if (didl.length() > MAX_SOAP_BYTES) {
            throw new Fault(402, "the metadata is larger than " + MAX_SOAP_BYTES + " bytes");
        }
        XmlScanner scanner = new XmlScanner(didl);
        StringBuilder title = null;
        int titleDepth = -1;
        int depth = 0;
        for (XmlToken token = scanner.next(); token != null; token = scanner.next()) {
            if (token.kind == XmlToken.START) {
                if (title == null && !token.empty && "title".equals(localName(token.name))) {
                    title = new StringBuilder();
                    titleDepth = depth + 1;
                }
                if (!token.empty) {
                    depth++;
                }
            } else if (token.kind == XmlToken.END) {
                if (depth == titleDepth) {
                    break;
                }
                depth--;
            } else if (title != null && depth == titleDepth && title.length() < MAX_TITLE) {
                title.append(token.text);
            }
        }
        if (title == null) {
            return "";
        }
        // Collapsed to one line for the log and a controller's display.
        String clean = title.toString().replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_TITLE ? clean.substring(0, MAX_TITLE) : clean;
    }

    /** The DIDL-Lite the speaker reports for its sound: the title alone, or "" for none. */
    static String trackMetadata(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escape(title) + "</dc:title>"
                + "<upnp:class>object.item.audioItem</upnp:class></item></DIDL-Lite>";
    }

    // ---------------------------------------------------------------------------------------
    // Events (GENA)
    // ---------------------------------------------------------------------------------------

    /**
     * The evented values of {@code service} in {@code state}: for AVTransport and
     * RenderingControl those that LastChange carries, for ConnectionManager its own three.
     * RelativeTimePosition is never evented, as AVTransport:1 says; controllers ask for it.
     */
    static Map<String, String> evented(Service service, State state) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (service) {
            case AV_TRANSPORT:
                values.put("TransportState", state.transport);
                values.put("TransportStatus", state.errorOccurred ? "ERROR_OCCURRED" : "OK");
                values.put("CurrentTransportActions", transportActions(state));
                values.put("AVTransportURI", state.uri);
                values.put("AVTransportURIMetaData", trackMetadata(state.title));
                values.put("CurrentTrackURI", state.uri);
                values.put("CurrentTrackMetaData", trackMetadata(state.title));
                values.put("CurrentTrackDuration", durationOf(state));
                values.put("CurrentMediaDuration", durationOf(state));
                values.put("NumberOfTracks", state.hasMedia() ? "1" : "0");
                values.put("CurrentTrack", state.hasMedia() ? "1" : "0");
                values.put("PlaybackStorageMedium", state.hasMedia() ? "NETWORK" : "NONE");
                values.put("CurrentPlayMode", "NORMAL");
                values.put("TransportPlaySpeed", "1");
                break;
            case RENDERING_CONTROL:
                values.put("Volume", Integer.toString(state.volume));
                values.put("Mute", state.muted ? "1" : "0");
                break;
            default:
                values.put("SourceProtocolInfo", "");
                values.put("SinkProtocolInfo", SINK_PROTOCOL_INFO);
                values.put("CurrentConnectionIDs", "0");
                break;
        }
        return values;
    }

    /**
     * The NOTIFY body for what changed between {@code previous} and {@code now} (everything when
     * {@code previous} is null, the initial event), or null when nothing changed.
     */
    static String eventBody(Service service, Map<String, String> previous,
            Map<String, String> now) {
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : now.entrySet()) {
            if (previous == null || !entry.getValue().equals(previous.get(entry.getKey()))) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        if (changed.isEmpty()) {
            return null;
        }
        if (service.lastChangeNamespace == null) {
            return propertySet(changed);
        }
        return propertySet(Collections.singletonMap("LastChange", lastChange(service, changed)));
    }

    /** The LastChange document: one InstanceID, the values as val attributes. */
    static String lastChange(Service service, Map<String, String> changed) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<Event xmlns=\"").append(service.lastChangeNamespace)
                .append("\"><InstanceID val=\"0\">");
        for (Map.Entry<String, String> entry : changed.entrySet()) {
            xml.append('<').append(entry.getKey());
            if (service == Service.RENDERING_CONTROL) {
                xml.append(" channel=\"Master\"");
            }
            xml.append(" val=\"").append(escape(entry.getValue())).append("\"/>");
        }
        return xml.append("</InstanceID></Event>").toString();
    }

    private static String propertySet(Map<String, String> values) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            xml.append("<e:property><").append(entry.getKey()).append('>')
                    .append(escape(entry.getValue())).append("</").append(entry.getKey())
                    .append("></e:property>");
        }
        return xml.append("</e:propertyset>\n").toString();
    }

    /**
     * The first http address of a SUBSCRIBE's CALLBACK header ({@code <url><url>...}), or null
     * when it has none the speaker can deliver to.
     */
    static String callbackOf(String header) {
        if (header == null || header.length() > 1024) {
            return null;
        }
        Matcher matcher = Pattern.compile("<([^<>]*)>").matcher(header);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            try {
                java.net.URI uri = new java.net.URI(url);
                if ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && !uri.getHost().isEmpty() && uri.getPort() <= 65535) {
                    return url;
                }
            } catch (java.net.URISyntaxException notAnAddress) {
                // The next one, if any.
            }
        }
        return null;
    }

    /** The subscription length granted for a TIMEOUT header ("Second-N" or "Second-infinite"). */
    static int subscriptionSeconds(String header) {
        int asked = MAX_SUBSCRIPTION_SECONDS;
        if (header != null && header.trim().regionMatches(true, 0, "Second-", 0, 7)) {
            String number = header.trim().substring(7);
            if (number.matches("\\d{1,9}")) {
                asked = Integer.parseInt(number);
            }
        }
        return Math.max(MIN_SUBSCRIPTION_SECONDS, Math.min(MAX_SUBSCRIPTION_SECONDS, asked));
    }

    // ---------------------------------------------------------------------------------------
    // SSDP
    // ---------------------------------------------------------------------------------------

    /**
     * What the device announces, as {NT, USN} pairs: the root device, the device by its UDN,
     * the device type, and each service type (UDA 1.1, 1.1.2).
     */
    static List<String[]> announcements(String udn) {
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"upnp:rootdevice", udn + "::upnp:rootdevice"});
        pairs.add(new String[] {udn, udn});
        pairs.add(new String[] {DEVICE_TYPE, udn + "::" + DEVICE_TYPE});
        for (Service service : Service.values()) {
            pairs.add(new String[] {service.type(), udn + "::" + service.type()});
        }
        return pairs;
    }

    /** An ssdp:alive NOTIFY. */
    static String alive(String nt, String usn, String location, String server, long bootId) {
        return "NOTIFY * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "CACHE-CONTROL: max-age=" + MAX_AGE_SECONDS + "\r\n"
                + "LOCATION: " + location + "\r\n"
                + "NT: " + nt + "\r\n"
                + "NTS: ssdp:alive\r\n"
                + "SERVER: " + server + "\r\n"
                + "USN: " + usn + "\r\n"
                + "BOOTID.UPNP.ORG: " + bootId + "\r\n"
                + "CONFIGID.UPNP.ORG: 1\r\n"
                + "\r\n";
    }

    /** An ssdp:byebye NOTIFY. */
    static String byebye(String nt, String usn, long bootId) {
        return "NOTIFY * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "NT: " + nt + "\r\n"
                + "NTS: ssdp:byebye\r\n"
                + "USN: " + usn + "\r\n"
                + "BOOTID.UPNP.ORG: " + bootId + "\r\n"
                + "CONFIGID.UPNP.ORG: 1\r\n"
                + "\r\n";
    }

    /** An M-SEARCH as read: what it searches for and how long the answer may wait. */
    static final class Search {
        final String target;
        final int waitSeconds;

        Search(String target, int waitSeconds) {
            this.target = target;
            this.waitSeconds = waitSeconds;
        }
    }

    /** The M-SEARCH in a datagram, or null when it is none (a NOTIFY, a malformed request). */
    static Search parseSearch(String datagram) {
        if (datagram == null || !datagram.startsWith("M-SEARCH * HTTP/1.1")) {
            return null;
        }
        String target = null;
        String man = null;
        int wait = 1;
        for (String line : datagram.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("ST")) {
                target = value;
            } else if (name.equals("MAN")) {
                man = value;
            } else if (name.equals("MX") && value.matches("\\d{1,4}")) {
                // At most 5 s, as UDA 1.1 says; 0 is taken as 1 to spread answers a little.
                wait = Math.max(1, Math.min(5, Integer.parseInt(value)));
            }
        }
        if (target == null || target.isEmpty() || !"\"ssdp:discover\"".equals(man)) {
            return null;
        }
        return new Search(target, wait);
    }

    /** The answers to a search for {@code target}; empty when it is not for this device. */
    static List<String> searchResponses(String target, String udn, String location,
            String server, long bootId, String date) {
        List<String> answers = new ArrayList<>();
        for (String[] pair : announcements(udn)) {
            boolean match = target.equals("ssdp:all") || target.equals(pair[0])
                    || (target.equalsIgnoreCase(udn) && pair[0].equals(udn));
            if (match) {
                answers.add("HTTP/1.1 200 OK\r\n"
                        + "CACHE-CONTROL: max-age=" + MAX_AGE_SECONDS + "\r\n"
                        + "DATE: " + date + "\r\n"
                        + "EXT:\r\n"
                        + "LOCATION: " + location + "\r\n"
                        + "SERVER: " + server + "\r\n"
                        + "ST: " + pair[0] + "\r\n"
                        + "USN: " + pair[1] + "\r\n"
                        + "BOOTID.UPNP.ORG: " + bootId + "\r\n"
                        + "CONFIGID.UPNP.ORG: 1\r\n"
                        + "\r\n");
            }
        }
        return answers;
    }

    /** An HTTP date (RFC 1123), for DATE headers. */
    static String httpDate(long epochMs) {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs),
                        java.time.ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------------------------------
    // Times
    // ---------------------------------------------------------------------------------------

    private static final Pattern TIME = Pattern.compile(
            "\\+?(\\d{1,5}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,9})|\\.(\\d{1,9})/(\\d{1,9}))?");

    /** H:MM:SS for a time in milliseconds; NOT_IMPLEMENTED for an unknown (negative) one. */
    static String formatTime(long ms) {
        if (ms < 0) {
            return NOT_IMPLEMENTED;
        }
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60,
                seconds % 60);
    }

    /**
     * Milliseconds for an AVTransport time: H+:MM:SS with an optional .F+ or .F0/F1 fraction.
     * Single-digit minutes and seconds are taken too, because async_upnp_client sends "0:1:5".
     * Returns -1 for anything else, NOT_IMPLEMENTED and negative times included.
     */
    static long parseTime(String text) {
        if (text == null) {
            return -1;
        }
        Matcher matcher = TIME.matcher(text.trim());
        if (!matcher.matches()) {
            return -1;
        }
        long hours = Long.parseLong(matcher.group(1));
        int minutes = Integer.parseInt(matcher.group(2));
        int seconds = Integer.parseInt(matcher.group(3));
        if (minutes > 59 || seconds > 59) {
            return -1;
        }
        long fraction = 0;
        if (matcher.group(4) != null) {
            String digits = (matcher.group(4) + "00").substring(0, 3);
            fraction = Long.parseLong(digits);
        } else if (matcher.group(5) != null) {
            long numerator = Long.parseLong(matcher.group(5));
            long denominator = Long.parseLong(matcher.group(6));
            if (denominator == 0 || numerator >= denominator) {
                return -1;
            }
            fraction = numerator * 1000 / denominator;
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction;
    }

    // ---------------------------------------------------------------------------------------
    // XML
    // ---------------------------------------------------------------------------------------

    /**
     * Text made safe for an XML element or a quoted attribute. Characters XML 1.0 cannot carry
     * at all (most control characters) are left out, so a title with one stays well formed.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (c != 0xFFFE && c != 0xFFFF) {
                            out.append(c);
                        }
                    }
                    break;
            }
        }
        return out.toString();
    }

    private static String localName(String qualified) {
        int colon = qualified.indexOf(':');
        return colon < 0 ? qualified : qualified.substring(colon + 1);
    }

    /** One piece of an XML text. */
    private static final class XmlToken {
        static final int START = 1;
        static final int END = 2;
        static final int TEXT = 3;

        final int kind;
        final String name;
        final boolean empty;
        final String text;

        XmlToken(int kind, String name, boolean empty, String text) {
            this.kind = kind;
            this.name = name;
            this.empty = empty;
            this.text = text;
        }
    }

    /**
     * Reads elements and text, skipping the XML declaration, processing instructions and
     * comments. A DOCTYPE, an entity declaration or any other {@code <!} construct is refused,
     * and so is an entity reference other than the five XML defines and character references.
     */
    private static final class XmlScanner {
        private final String xml;
        private int at;

        XmlScanner(String xml) {
            this.xml = xml;
        }

        XmlToken next() throws Fault {
            while (at < xml.length()) {
                if (xml.charAt(at) != '<') {
                    int end = xml.indexOf('<', at);
                    end = end < 0 ? xml.length() : end;
                    String raw = xml.substring(at, end);
                    at = end;
                    return new XmlToken(XmlToken.TEXT, null, false, unescape(raw));
                }
                if (xml.startsWith("<?", at)) {
                    skipPast("?>");
                } else if (xml.startsWith("<!--", at)) {
                    skipPast("-->");
                } else if (xml.startsWith("<![CDATA[", at)) {
                    int end = xml.indexOf("]]>", at + 9);
                    if (end < 0) {
                        throw malformed();
                    }
                    String raw = xml.substring(at + 9, end);
                    at = end + 3;
                    return new XmlToken(XmlToken.TEXT, null, false, raw);
                } else if (xml.startsWith("<!", at)) {
                    throw new Fault(402, "a DOCTYPE or an entity declaration is refused");
                } else {
                    return tag();
                }
            }
            return null;
        }

        private XmlToken tag() throws Fault {
            // A '>' inside a quoted attribute value does not end the tag.
            char quote = 0;
            int end = -1;
            for (int i = at + 1; i < xml.length(); i++) {
                char c = xml.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '<') {
                    throw malformed();
                } else if (c == '>') {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                throw malformed();
            }
            String inner = xml.substring(at + 1, end);
            at = end + 1;
            if (inner.startsWith("/")) {
                String name = inner.substring(1).trim();
                if (name.isEmpty()) {
                    throw malformed();
                }
                return new XmlToken(XmlToken.END, name, false, null);
            }
            boolean empty = inner.endsWith("/");
            if (empty) {
                inner = inner.substring(0, inner.length() - 1);
            }
            int space = 0;
            while (space < inner.length() && !Character.isWhitespace(inner.charAt(space))) {
                space++;
            }
            String name = inner.substring(0, space);
            if (name.isEmpty()) {
                throw malformed();
            }
            return new XmlToken(XmlToken.START, name, empty, null);
        }

        private void skipPast(String marker) throws Fault {
            int end = xml.indexOf(marker, at);
            if (end < 0) {
                throw malformed();
            }
            at = end + marker.length();
        }

        private static Fault malformed() {
            return new Fault(402, "the request is not well-formed XML");
        }

        private static String unescape(String raw) throws Fault {
            int amp = raw.indexOf('&');
            if (amp < 0) {
                return raw;
            }
            StringBuilder out = new StringBuilder(raw.length());
            int from = 0;
            while (amp >= 0) {
                out.append(raw, from, amp);
                int semi = raw.indexOf(';', amp);
                if (semi < 0 || semi - amp > 12) {
                    throw malformed();
                }
                String entity = raw.substring(amp + 1, semi);
                switch (entity) {
                    case "lt": out.append('<'); break;
                    case "gt": out.append('>'); break;
                    case "amp": out.append('&'); break;
                    case "quot": out.append('"'); break;
                    case "apos": out.append('\''); break;
                    default:
                        out.appendCodePoint(characterReference(entity));
                        break;
                }
                from = semi + 1;
                amp = raw.indexOf('&', from);
            }
            return out.append(raw, from, raw.length()).toString();
        }

        private static int characterReference(String entity) throws Fault {
            int codePoint = -1;
            try {
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    codePoint = Integer.parseInt(entity.substring(2), 16);
                } else if (entity.startsWith("#")) {
                    codePoint = Integer.parseInt(entity.substring(1));
                }
            } catch (NumberFormatException notNumber) {
                codePoint = -1;
            }
            if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT
                    || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                throw new Fault(402, "the entity &" + entity + "; is not one XML defines");
            }
            return codePoint;
        }
    }
}
