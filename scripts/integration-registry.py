#!/usr/bin/env python3
# Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
# Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
"""Writes IntegrationRegistry.java from the integration folders.

Each folder under app/src/main/integrations/ is one integration: its integration.json names the
class whose DEFINITION the app ships. The registry is generated at build time, so adding an
integration means adding its folder and touching no file of the app itself.

    integration-registry.py <integrations dir> <output dir>

Fails with the reason when a folder is not a valid integration, so a broken contribution stops
the build instead of shipping half.
"""
import json
import os
import re
import sys

ID_SHAPE = re.compile(r"^[a-z][a-z0-9]{1,15}$")
CLASS_SHAPE = re.compile(r"^[A-Z][A-Za-z0-9]{2,60}Integration$")
REQUIRED = ("id", "class", "name", "codeowners", "kind", "audience")
PACKAGE = "org.spazio17.muralis.integrations"
# What an integration may name of the platform: collections, text, the engine. Anything else
# (the network or files directly, reflection, threads, processes, Android) goes through the
# engine or not at all. A guard against mistakes and casual misuse, not a sandbox: review is
# what protects the app.
ALLOWED = re.compile(r"^(java\.util\.(List|ArrayList|Map|HashMap|LinkedHashMap|Set|HashSet|"
                     r"LinkedHashSet|Collections|Arrays|Iterator|Locale|Objects|Optional|"
                     r"Comparator|function\.[A-Za-z]+|regex\.(Pattern|Matcher))|"
                     r"java\.io\.IOException|java\.io\.UnsupportedEncodingException|"
                     r"java\.net\.URLEncoder|java\.nio\.charset\.StandardCharsets|"
                     r"java\.math\.(BigDecimal|BigInteger|RoundingMode)|java\.time(\.[A-Za-z]+)*|"
                     r"org\.spazio17\.muralis\.(Integrations|TinyJson))(\.[A-Za-z_]+)*$")
# A test may also throw what a failing request throws.
ALLOWED_IN_TESTS = re.compile(r"^java\.io\.(FileNotFoundException|IOException)(\.[A-Za-z_]+)*$")
QUALIFIED = re.compile(r"\b((?:java|javax|android|androidx|dalvik|kotlin|org|com|sun)"
                       r"\.[A-Za-z0-9_.]*[A-Za-z0-9_*])")
FORBIDDEN = re.compile(r"(\bClass\s*\.\s*forName|getClass\s*\(|\bRuntime\b|ProcessBuilder|"
                       r"\bThread\b|\bSystem\s*\.|ClassLoader|reflect|\bnative\b|getDeclared|"
                       r"setAccessible|loadClass|\.\s*class\b|Executor|\bvar\b)")


def code_only(text):
    """The source as the compiler reads it, without comments and string or char contents:
    unicode escapes decoded first (Java decodes them before anything else)."""
    text = re.sub(r"\\u+([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), text)
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        elif c in "\"'":
            quote, i = c, i + 1
            while i < n and text[i] != quote and text[i] != "\n":
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append(quote + quote)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def fail(message):
    sys.stderr.write("integration-registry: " + message + "\n")
    sys.exit(1)


def read(folder, path):
    manifest = os.path.join(path, "integration.json")
    if not os.path.isfile(manifest):
        fail(folder + ": no integration.json")
    try:
        with open(manifest, encoding="utf-8") as handle:
            data = json.load(handle)
    except ValueError as broken:
        fail(folder + "/integration.json is not JSON: " + str(broken))
    for key in REQUIRED:
        if key not in data:
            fail(folder + "/integration.json has no " + key)
    if data["id"] != folder or not ID_SHAPE.match(folder):
        fail(folder + ": the id must be the folder's name, lowercase letters and digits")
    if not isinstance(data["class"], str) or not CLASS_SHAPE.match(data["class"]):
        fail(folder + ": the class must be a name ending in Integration")
    if not os.path.isfile(os.path.join(path, data["class"] + ".java")):
        fail(folder + ": no " + data["class"] + ".java in the folder")
    if data["kind"] not in ("local", "cloud"):
        fail(folder + ": kind must be local or cloud")
    if data["audience"] not in ("home", "business", "both"):
        fail(folder + ": audience must be home, business or both")
    if not isinstance(data["codeowners"], list) or not data["codeowners"]:
        fail(folder + ": codeowners must list at least one GitHub name")
    if not os.path.isfile(os.path.join(path, "README.md")):
        fail(folder + ": no README.md, the integration's help page")
    if not all(isinstance(owner, str) and owner.startswith("@")
               for owner in data["codeowners"]):
        fail(folder + ": codeowners are GitHub names such as @someone")
    for name in sorted(os.listdir(path)):
        source = os.path.join(path, name)
        if os.path.islink(source):
            fail(folder + "/" + name + " is a link; an integration's files are its own")
        if os.path.isdir(source):
            # Every file at the folder's top, where this check reads it; the build compiles
            # whatever is below, so nothing may hide in a subfolder.
            fail(folder + "/" + name + " is a folder; an integration keeps its files at its top")
        if not name.endswith(".java"):
            continue
        test = name.endswith("Test.java")
        with open(source, encoding="utf-8") as handle:
            code = code_only(handle.read())
        if not re.search(r"^package " + re.escape(PACKAGE) + ";", code, re.M):
            fail(folder + "/" + name + " must be in package " + PACKAGE)
        for line in code.split("\n"):
            if line.strip().startswith("package "):
                continue
            for name_used in QUALIFIED.findall(line):
                if not ALLOWED.match(name_used) and not (test and ALLOWED_IN_TESTS.match(name_used)):
                    fail(folder + "/" + name + " uses " + name_used
                         + "; an integration speaks only to the engine (Integrations, TinyJson)")
            # A test prints its verdict; nothing else of System.
            forbidden = FORBIDDEN.search(re.sub(r"System\s*\.\s*out\s*\.\s*println", "", line)
                                         if test else line)
            if forbidden:
                fail(folder + "/" + name + " uses " + forbidden.group(1)
                     + "; an integration speaks only to the engine")
    return data


def main():
    if len(sys.argv) != 3:
        fail("usage: integration-registry.py <integrations dir> <output dir>")
    source, output = sys.argv[1], sys.argv[2]
    entries = []
    for folder in sorted(os.listdir(source)):
        path = os.path.join(source, folder)
        if os.path.islink(path) or not os.path.isdir(path):
            # A file beside the folders would be compiled into the app as part of none.
            fail(folder + " is not an integration's folder; every file lives in one")
        entries.append(read(folder, path))
    if not entries:
        fail("no integrations in " + source)
    target = os.path.join(output, "org", "spazio17", "muralis")
    os.makedirs(target, exist_ok=True)
    lines = [
        "// Generated by scripts/integration-registry.py from app/src/main/integrations/;",
        "// do not edit. Add an integration by adding its folder.",
        "package org.spazio17.muralis;",
        "",
        "final class IntegrationRegistry {",
        "    private IntegrationRegistry() {",
        "    }",
        "",
        "    /** Every integration this build carries, in the order of their folders. */",
        "    static final Integrations.Definition[] ALL = {",
    ]
    classes = [entry["class"] for entry in entries]
    if len(set(classes)) != len(classes):
        fail("two integrations name the same class")
    lines += ["            %s.%s.DEFINITION," % (PACKAGE, entry["class"]) for entry in entries]
    lines += [
        "    };",
        "",
        "    /** The folders' ids, which each definition's id must equal. */",
        "    static final String[] IDS = {",
    ]
    lines += ['            "%s",' % entry["id"] for entry in entries]
    lines += [
        "    };",
        "",
        "    /** Who each is for, by id: home, business or both (integration.json's audience). */",
        "    static final String[][] AUDIENCES = {",
    ]
    lines += ['            {"%s", "%s"},' % (entry["id"], entry["audience"]) for entry in entries]
    lines += ["    };", "}", ""]
    with open(os.path.join(target, "IntegrationRegistry.java"), "w", encoding="utf-8") as out:
        out.write("\n".join(lines))


if __name__ == "__main__":
    main()
