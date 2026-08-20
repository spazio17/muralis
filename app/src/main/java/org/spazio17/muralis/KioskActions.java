/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

final class KioskActions {
    static final String UI_CONTROL = "org.spazio17.muralis.action.UI_CONTROL";
    /**
     * Signature-level permission guarding {@link #UI_CONTROL}, declared in the manifest.
     *
     * <p>Needed because a context-registered receiver was implicitly <em>exported</em> before
     * Android 14, and {@code RECEIVER_NOT_EXPORTED} only exists from API 33. On the API 26 hardware
     * this app actually targets, any installed app could otherwise broadcast this action and
     * repoint, blank or dim the kiosk. Sending with a signature permission, and registering the
     * receiver with the same one, closes that on every version rather than only the newest.
     */
    static final String PERMISSION_UI_CONTROL = "org.spazio17.muralis.permission.UI_CONTROL";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_BRIGHTNESS = "brightness_percent";
    static final String EXTRA_URL = "url";

    private KioskActions() {
    }
}
