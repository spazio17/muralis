/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.util.List;
import java.util.Map;

/** TinyJson's package-private calls, opened to the host tests' org.json stand-in. */
public final class HostJson {
    private HostJson() {
    }

    public static Object parse(String text) {
        return TinyJson.parse(text);
    }

    public static Map<String, Object> object(Object value) {
        return TinyJson.object(value);
    }

    public static List<Object> array(Object value) {
        return TinyJson.array(value);
    }

    public static String write(Object value) {
        return TinyJson.write(value);
    }
}
