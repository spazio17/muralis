/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.json;

/** The host tests' stand-in for Android's org.json, enough for Automations. */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }
}
