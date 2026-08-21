# R8 rules for the release build.
#
# The app's own code needs almost nothing kept: there is no reflection anywhere in
# app/src/main/java (no Class.forName, no getDeclaredMethod, no newInstance), and every
# Android entry point is named in AndroidManifest.xml, which AGP turns into keep rules
# automatically. So what follows is about the one dependency and about the things that
# must stay legible in a crash report.

# ---------------------------------------------------------------- Eclipse Paho MQTT
#
# Kept wholesale rather than trimmed. Paho's ClientComms/CommsCallback machinery hands
# instances between threads through interfaces it resolves at runtime, and its own
# published ProGuard guidance is to keep the package. A wall panel that must run
# unattended for months is the wrong place to discover that R8 removed a callback path
# that only executes on reconnect.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep interface org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# Paho references javax.net.ssl and a logging facade that are not all present on Android.
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# ------------------------------------------------------------------ crash legibility
#
# Keep line numbers and source file names so a stack trace from a panel on a wall is
# actionable. Without this a NullPointerException reports a method name and nothing else.
# The mapping file that makes these readable is written to app/build/outputs/mapping/
# release/ — upload it to Play with each release, or the deobfuscated traces are lost.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------- WebView bridge
#
# There is deliberately no addJavascriptInterface in this app, so there is nothing here
# to keep. If one is ever added, its methods need @JavascriptInterface AND a keep rule —
# R8 will otherwise strip a method only ever called from JavaScript. Noted because that
# failure is silent and only shows up on the device.

# --------------------------------------------------------------------- device admin
#
# KioskDeviceAdminReceiver is instantiated by the platform from the manifest, which AGP
# already keeps. res/xml/device_admin.xml is data, not code, so it needs no rule.
