/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.json;

/** The host tests' stand-in for Android's org.json, enough for Automations. */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }
}
