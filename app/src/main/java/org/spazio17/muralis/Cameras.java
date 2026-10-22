/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The panel's cameras, the Cameras section in Screen: each a name and an address (a snapshot, an
 * MJPEG stream or a camera page), with an optional sign-in. A camera that takes its sign-in in
 * the address (Reolink's {@code ...&user=...&password=...}) has {@code {user}} and
 * {@code {password}} there instead, filled in only when the camera is fetched. The list is a plain setting; each
 * camera's user and password are one secret, kept apart in {@code SecretStore}, so neither an
 * export without secrets nor a setup QR carries them. A camera is only ever streamed: what it
 * sends is shown from memory and never written to the device ({@code CameraView}).
 * {@code camera.show} shows one. Pure: the list's form and checks are host-tested.
 */
final class Cameras {
    static final int MAX_CAMERAS = 10;
    static final int MAX_NAME = 40;
    static final int MAX_LOGIN = 128;

    /** One camera as stored. */
    static final class Camera {
        final String name;
        final String url;
        /**
         * The same camera's bigger stream, for full screen and zoom (a recorder's main stream
         * beside its substream); empty when the one address serves both.
         */
        final String full;

        Camera(String name, String url) {
            this(name, url, "");
        }

        Camera(String name, String url, String full) {
            this.name = name;
            this.url = url;
            this.full = full == null ? "" : full;
        }
    }

    /** A camera's sign-in, sent as HTTP Basic to the camera's own host only. */
    static final class Login {
        final String user;
        final String password;

        Login(String user, String password) {
            this.user = user == null ? "" : user;
            this.password = password == null ? "" : password;
        }

        boolean isEmpty() {
            return user.isEmpty() && password.isEmpty();
        }
    }

    /**
     * One camera to draw, as a pop-up or the grid receives it: name, address, the bigger address
     * for full screen (empty for none) and sign-in.
     */
    static final class Tile {
        final String name;
        final String url;
        final String full;
        final Login login;

        Tile(String name, String url, Login login) {
            this(name, url, "", login);
        }

        Tile(String name, String url, String full, Login login) {
            this.name = name;
            this.url = url;
            this.full = full == null ? "" : full;
            this.login = login == null || login.isEmpty() ? null : login;
        }
    }

    /** What the question before removing a camera or a group adds, on both surfaces. */
    static final String REMOVED = "It is taken out of every group it is in, and its sign-in is"
            + " deleted. An automation or the dashboard that shows it then has nothing to show.";
    static final String GROUP_REMOVED = "Its cameras stay. An automation or the dashboard that"
            + " shows it then has nothing to show.";

    private Cameras() {
    }

    /** The section's line on both surfaces: "None yet", "1 camera", "3 cameras". */
    static String summary(List<Camera> cameras) {
        int count = cameras == null ? 0 : cameras.size();
        return count == 0 ? "None yet" : count == 1 ? "1 camera" : count + " cameras";
    }

    /** Null when the camera can be stored; otherwise the reason. */
    static String check(String name, String url) {
        String named = checkName(name);
        if (named != null) {
            return named;
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))
                || url.chars().anyMatch(c -> c <= ' ')) {
            return "the address must start with http:// or https:// and hold no spaces";
        }
        if (hostOf(url).isEmpty()) {
            return "the address must name the camera's host";
        }
        return null;
    }

    /** Null when the full-screen address is empty or one a camera can have; otherwise the reason. */
    static String checkFull(String full) {
        if (full == null || full.isEmpty()) {
            return null;
        }
        String problem = check("x", full);
        return problem == null ? null : "the full-screen " + problem.substring(4);
    }

    /** Null when the sign-in can be stored; otherwise the reason. */
    static String checkLogin(String user, String password) {
        if ((user == null || user.isEmpty()) && password != null && !password.isEmpty()) {
            // Without a user the sign-in would be dropped; a camera that ignores the user
            // (one that asks only for a password) takes any name.
            return "type a user too; for a camera that ignores it, any name works";
        }
        if ((user != null && (user.length() > MAX_LOGIN || user.contains(":")
                || user.chars().anyMatch(Character::isISOControl)))
                || (password != null && (password.length() > MAX_LOGIN
                        || password.chars().anyMatch(Character::isISOControl)))) {
            return "the user and the password must be one line of at most " + MAX_LOGIN
                    + " characters, and the user may hold no colon";
        }
        return null;
    }

    static final String USER = "{user}";
    static final String PASSWORD = "{password}";

    /** The query names a camera may take its sign-in under, user first, then password. */
    private static final String[][] QUERY_LOGINS = {{"user", "password"}, {"usr", "pwd"},
            {"username", "password"}};

    /**
     * An address typed with its sign-in inside, {@code http://user:pass@host/...} or in its query
     * ({@code ?user=...&password=...}), split into the address without it and the sign-in, so the
     * password is never kept in the address: the query's values become {@code {user}} and
     * {@code {password}}, filled in again only when the camera is fetched ({@link #fill}). An
     * address without one comes back unchanged with a null login.
     */
    static Object[] splitLogin(String url) {
        if (url == null) {
            return new Object[] {"", null};
        }
        Object[] query = splitQueryLogin(url);
        if (query[1] != null) {
            return query;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return new Object[] {url, null};
        }
        int start = scheme + 3;
        int end = url.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = url.indexOf(stop, start);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        int at = url.lastIndexOf('@', end - 1);
        if (at < start) {
            return new Object[] {url, null};
        }
        String userInfo = url.substring(start, at);
        int colon = userInfo.indexOf(':');
        String user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
        String password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        return new Object[] {url.substring(0, start) + url.substring(at + 1),
                new Login(user, password)};
    }

    private static Object[] splitQueryLogin(String url) {
        int mark = url.indexOf('?');
        if (mark < 0) {
            return new Object[] {url, null};
        }
        int hash = url.indexOf('#', mark);
        String query = hash < 0 ? url.substring(mark + 1) : url.substring(mark + 1, hash);
        String[] pairs = query.split("&", -1);
        // The pair whose user name is actually there first, then a password alone, so a
        // username= address is not read as a password with no user.
        for (boolean needUser : new boolean[] {true, false}) {
            for (String[] names : QUERY_LOGINS) {
                String user = value(pairs, names[0]);
                String password = value(pairs, names[1]);
                if (password == null || password.isEmpty() || password.equals(PASSWORD)
                        || (needUser && user == null)) {
                    continue;
                }
                StringBuilder rebuilt = new StringBuilder();
                for (String pair : pairs) {
                    int equals = pair.indexOf('=');
                    String key = equals < 0 ? pair : pair.substring(0, equals);
                    if (rebuilt.length() > 0) {
                        rebuilt.append('&');
                    }
                    if (key.equalsIgnoreCase(names[0]) && user != null) {
                        rebuilt.append(key).append('=').append(USER);
                    } else if (key.equalsIgnoreCase(names[1])) {
                        rebuilt.append(key).append('=').append(PASSWORD);
                    } else {
                        rebuilt.append(pair);
                    }
                }
                return new Object[] {url.substring(0, mark + 1) + rebuilt
                        + (hash < 0 ? "" : url.substring(hash)),
                        new Login(user == null ? "" : decode(user), decode(password))};
            }
        }
        return new Object[] {url, null};
    }

    /** A query value by its name, ignoring case; null when the name is not there. */
    private static String value(String[] pairs, String name) {
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equalsIgnoreCase(name)) {
                return equals < 0 ? "" : pair.substring(equals + 1);
            }
        }
        return null;
    }

    /**
     * The address as it is fetched: {@code {user}} and {@code {password}} (also as the browser
     * writes them, {@code %7Buser%7D}) filled in from the sign-in, encoded for a query.
     */
    static String fill(String url, Login login) {
        if (url == null || login == null || url.indexOf('{') < 0 && !url.contains("%7B")
                && !url.contains("%7b")) {
            return url;
        }
        String user = encode(login.user);
        String password = encode(login.password);
        return url.replace(USER, user).replace(PASSWORD, password)
                .replaceAll("(?i)%7Buser%7D", java.util.regex.Matcher.quoteReplacement(user))
                .replaceAll("(?i)%7Bpassword%7D",
                        java.util.regex.Matcher.quoteReplacement(password));
    }

    private static String encode(String part) {
        try {
            return java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return part;
        }
    }

    private static String decode(String part) {
        try {
            return java.net.URLDecoder.decode(part.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException kept) {
            return part;
        }
    }

    /** The host and port an address names, lower case, as "host:port"; empty when none. */
    static String hostOf(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.replace(USER, "u").replace(PASSWORD, "p"));
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return "";
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (java.net.URISyntaxException unreadable) {
            return "";
        }
    }

    /** The stored list from its JSON; anything unreadable or refused by the checks is left out. */
    static List<Camera> parse(String json) {
        List<Camera> cameras = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return cameras;
        }
        try {
            for (Object one : TinyJson.array(TinyJson.parse(json))) {
                Map<String, Object> entry = TinyJson.object(one);
                String name = TinyJson.string(entry, "name", "").trim();
                String url = TinyJson.string(entry, "url", "").trim();
                String full = TinyJson.string(entry, "full", "").trim();
                if (check(name, url) == null && checkFull(full) == null
                        && find(cameras, name) == null && cameras.size() < MAX_CAMERAS) {
                    cameras.add(new Camera(name, url, full));
                }
            }
        } catch (IllegalArgumentException unreadable) {
            return new ArrayList<>();
        }
        return cameras;
    }

    static String format(List<Camera> cameras) {
        List<Object> out = new ArrayList<>();
        for (Camera camera : cameras) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", camera.name);
            entry.put("url", camera.url);
            if (!camera.full.isEmpty()) {
                entry.put("full", camera.full);
            }
            out.add(entry);
        }
        return TinyJson.write(out);
    }

    /** The sign-ins from their secret JSON, by camera name in lower case. */
    static Map<String, Login> parseLogins(String json) {
        Map<String, Login> logins = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) {
            return logins;
        }
        try {
            for (Map.Entry<String, Object> entry
                    : TinyJson.object(TinyJson.parse(json)).entrySet()) {
                Map<String, Object> one = TinyJson.object(entry.getValue());
                Login login = new Login(TinyJson.string(one, "user", ""),
                        TinyJson.string(one, "password", ""));
                if (!login.isEmpty() && checkLogin(login.user, login.password) == null) {
                    logins.put(entry.getKey().toLowerCase(Locale.ROOT), login);
                }
            }
        } catch (IllegalArgumentException unreadable) {
            return new LinkedHashMap<>();
        }
        return logins;
    }

    static String formatLogins(Map<String, Login> logins) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Login> entry : logins.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("user", entry.getValue().user);
            one.put("password", entry.getValue().password);
            out.put(entry.getKey().toLowerCase(Locale.ROOT), one);
        }
        return TinyJson.write(out);
    }

    /**
     * The sign-ins after a camera was saved: {@code was} renamed to {@code name}, its user set,
     * its password replaced only when one was typed (an empty box keeps the stored one), and
     * removed altogether when the user is emptied.
     */
    static Map<String, Login> withLogin(Map<String, Login> logins, String was, String name,
            String user, String typedPassword) {
        Map<String, Login> next = new LinkedHashMap<>(logins);
        Login old = next.remove((was == null || was.isEmpty() ? name : was)
                .toLowerCase(Locale.ROOT));
        if (user == null || user.isEmpty()) {
            return next;
        }
        String password = typedPassword == null || typedPassword.isEmpty()
                ? (old == null ? "" : old.password) : typedPassword;
        next.put(name.toLowerCase(Locale.ROOT), new Login(user, password));
        return next;
    }

    static Map<String, Login> withoutLogin(Map<String, Login> logins, String name) {
        Map<String, Login> next = new LinkedHashMap<>(logins);
        next.remove(name.toLowerCase(Locale.ROOT));
        return next;
    }

    /** By name, ignoring case; null when there is none. */
    static Camera find(List<Camera> cameras, String name) {
        for (Camera camera : cameras) {
            if (camera.name.equalsIgnoreCase(name == null ? "" : name.trim())) {
                return camera;
            }
        }
        return null;
    }

    /**
     * The list with one camera put in: {@code was} replaced in place (a rename keeps the
     * camera's place), or a new one at the end; null when it would be the eleventh.
     */
    static List<Camera> with(List<Camera> cameras, String was, Camera camera) {
        List<Camera> next = new ArrayList<>();
        String key = was == null || was.isEmpty() ? camera.name : was;
        boolean replaced = false;
        for (Camera one : cameras) {
            if (one.name.equalsIgnoreCase(key)) {
                next.add(camera);
                replaced = true;
            } else {
                next.add(one);
            }
        }
        if (!replaced) {
            if (next.size() >= MAX_CAMERAS) {
                return null;
            }
            next.add(camera);
        }
        return next;
    }

    static List<Camera> without(List<Camera> cameras, String name) {
        List<Camera> next = new ArrayList<>();
        for (Camera one : cameras) {
            if (!one.name.equalsIgnoreCase(name)) {
                next.add(one);
            }
        }
        return next;
    }

    /**
     * Why a camera cannot be saved, or null: the shared checks, the name not taken by another
     * camera, and the list not full. {@code was} is the camera being edited, empty for a new one.
     */
    static String saveProblem(List<Camera> cameras, String was, String name, String url,
            String user, String password) {
        return saveProblem(cameras, new ArrayList<>(), was, name, url, "", user, password);
    }

    /** The same, a name also refused when a group has it, so a name always means one thing. */
    static String saveProblem(List<Camera> cameras, List<Group> groups, String was, String name,
            String url, String full, String user, String password) {
        String problem = check(name, url);
        if (problem == null) {
            problem = checkFull(full);
        }
        if (problem == null && findGroup(groups, name) != null) {
            problem = "a group has that name";
        }
        if (problem == null) {
            problem = checkLogin(user, password);
        }
        if (problem == null && !name.equalsIgnoreCase(was == null ? "" : was)
                && find(cameras, name) != null) {
            problem = "another camera has that name";
        }
        if (problem == null && with(cameras, was, new Camera(name, url)) == null) {
            problem = "there are " + MAX_CAMERAS + " cameras already";
        }
        return problem;
    }

    /** The camera view's layouts: Auto by the number of cameras, or a fixed one. */
    static final String LAYOUT_AUTO = "auto";
    static final String[][] LAYOUTS = {
            {LAYOUT_AUTO, "Auto"},
            {"1+3", "One big, three small"},
            {"1+5", "One big, five small"},
            {"2x2", "Four, 2 by 2"},
            {"3x3", "Nine, 3 by 3"},
    };

    static boolean isLayout(String value) {
        for (String[] layout : LAYOUTS) {
            if (layout[0].equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a layout has a big tile to choose. */
    static boolean hasBigTile(String layout) {
        return "1+3".equals(layout) || "1+5".equals(layout);
    }

    static final int MAX_GROUPS = 10;

    /**
     * A group of cameras, made and named by the person: shown together as one grid, and a camera
     * may be in several. Its cameras are kept by name, in the order the Cameras list has them.
     */
    static final class Group {
        final String name;
        final List<String> cameras;

        Group(String name, List<String> cameras) {
            this.name = name;
            this.cameras = java.util.Collections.unmodifiableList(new ArrayList<>(cameras));
        }
    }

    /** The groups' line: "No groups", "1 group", "3 groups". */
    static String groupsSummary(List<Group> groups) {
        int count = groups == null ? 0 : groups.size();
        return count == 0 ? "No groups" : count == 1 ? "1 group" : count + " groups";
    }

    static List<Group> parseGroups(String json) {
        List<Group> groups = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return groups;
        }
        try {
            for (Object one : TinyJson.array(TinyJson.parse(json))) {
                Map<String, Object> entry = TinyJson.object(one);
                String name = TinyJson.string(entry, "name", "").trim();
                List<String> members = new ArrayList<>();
                Object listed = entry.get("cameras");
                if (listed != null) {
                    for (Object member : TinyJson.array(listed)) {
                        if (member instanceof String && !((String) member).trim().isEmpty()) {
                            members.add(((String) member).trim());
                        }
                    }
                }
                if (checkName(name) == null && findGroup(groups, name) == null
                        && groups.size() < MAX_GROUPS) {
                    groups.add(new Group(name, members));
                }
            }
        } catch (IllegalArgumentException unreadable) {
            return new ArrayList<>();
        }
        return groups;
    }

    static String formatGroups(List<Group> groups) {
        List<Object> out = new ArrayList<>();
        for (Group group : groups) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", group.name);
            entry.put("cameras", new ArrayList<Object>(group.cameras));
            out.add(entry);
        }
        return TinyJson.write(out);
    }

    private static String checkName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME
                || name.chars().anyMatch(Character::isISOControl)) {
            return "the name must be one line of 1 to " + MAX_NAME + " characters";
        }
        return null;
    }

    static Group findGroup(List<Group> groups, String name) {
        for (Group group : groups) {
            if (group.name.equalsIgnoreCase(name == null ? "" : name.trim())) {
                return group;
            }
        }
        return null;
    }

    /** Why a group cannot be saved, or null: its name free among groups and cameras, a camera in it. */
    static String groupProblem(List<Camera> cameras, List<Group> groups, String was, String name,
            List<String> members) {
        String problem = checkName(name);
        if (problem == null && find(cameras, name) != null) {
            problem = "a camera has that name";
        }
        if (problem == null && !name.equalsIgnoreCase(was == null ? "" : was)
                && findGroup(groups, name) != null) {
            problem = "another group has that name";
        }
        if (problem == null && (members == null || members.isEmpty())) {
            problem = "tick at least one camera";
        }
        if (problem == null && (was == null || was.isEmpty() || findGroup(groups, was) == null)
                && groups.size() >= MAX_GROUPS) {
            problem = "there are " + MAX_GROUPS + " groups already";
        }
        return problem;
    }

    /** The groups with one put in: {@code was} replaced in place, or a new one at the end. */
    static List<Group> withGroup(List<Group> groups, String was, Group group) {
        List<Group> next = new ArrayList<>();
        String key = was == null || was.isEmpty() ? group.name : was;
        boolean replaced = false;
        for (Group one : groups) {
            if (one.name.equalsIgnoreCase(key)) {
                next.add(group);
                replaced = true;
            } else {
                next.add(one);
            }
        }
        if (!replaced) {
            next.add(group);
        }
        return next;
    }

    static List<Group> withoutGroup(List<Group> groups, String name) {
        List<Group> next = new ArrayList<>();
        for (Group one : groups) {
            if (!one.name.equalsIgnoreCase(name)) {
                next.add(one);
            }
        }
        return next;
    }

    /**
     * The groups after a camera was renamed ({@code name} not empty) or removed ({@code name}
     * empty): the camera follows its new name in every group, or leaves them all.
     */
    static List<Group> afterCamera(List<Group> groups, String was, String name) {
        List<Group> next = new ArrayList<>();
        for (Group group : groups) {
            List<String> members = new ArrayList<>();
            for (String member : group.cameras) {
                if (!member.equalsIgnoreCase(was)) {
                    members.add(member);
                } else if (name != null && !name.isEmpty()) {
                    members.add(name);
                }
            }
            next.add(new Group(group.name, members));
        }
        return next;
    }

    /**
     * What a name shows, in the Cameras list's order: the camera of that name, or the cameras of
     * the group of that name that still exist; empty when the name is neither.
     */
    static List<Camera> resolve(List<Camera> cameras, List<Group> groups, String name) {
        List<Camera> shown = new ArrayList<>();
        Camera camera = find(cameras, name);
        if (camera != null) {
            shown.add(camera);
            return shown;
        }
        Group group = findGroup(groups, name);
        if (group == null) {
            return shown;
        }
        for (Camera one : cameras) {
            for (String member : group.cameras) {
                if (one.name.equalsIgnoreCase(member)) {
                    shown.add(one);
                    break;
                }
            }
        }
        return shown;
    }
}
