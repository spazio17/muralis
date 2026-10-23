/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.json;

/** The host tests' stand-in for Android's org.json, enough for Automations. */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }
}
