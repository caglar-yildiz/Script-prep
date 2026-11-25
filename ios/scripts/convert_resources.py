#!/usr/bin/env python3
"""Copies the Android app's texts and icons into the iOS project.

Android string resources become Localizable.strings and Localizable.stringsdict files (same keys,
so both apps say the same thing in every language), and the vector icons become template images
in the asset catalog. Run it again after changing app/src/main/res:

    python3 ios/scripts/convert_resources.py
"""
import json
import os
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
APP = os.path.join(ROOT, "ios", "MemoEverything")
RESOURCES = os.path.join(APP, "Resources")
ASSETS = os.path.join(RESOURCES, "Assets.xcassets")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

LANGUAGES = {"values": "en", "values-tr": "tr", "values-es": "es", "values-de": "de",
             "values-fr": "fr", "values-pt": "pt-BR", "values-it": "it"}

# Wording that names Android or Google Play, said the iOS way in every language.
IOS_TEXT = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "ios_strings.json"),
                          encoding="utf-8"))


def android_text(element):
    """The text of a string resource with Android's escapes resolved."""
    raw = "".join(element.itertext())
    raw = raw.strip()
    if len(raw) >= 2 and raw[0] == '"' and raw[-1] == '"':
        raw = raw[1:-1]
    out = []
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            out.append({"n": "\n", "t": "\t", "'": "'", '"': '"', "@": "@", "?": "?", "\\": "\\"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def ios_format(text):
    """%1$s → %1$@ and %s → %@; numbers keep their form."""
    text = re.sub(r"%(\d+\$)?s", lambda m: "%" + (m.group(1) or "") + "@", text)
    return text


def ios_licenses(text):
    """The Android list names PdfBox, ML Kit and Kotlin; the iOS app uses Firebase and Google Sign-In."""
    kept = [line for line in text.split("\n")
            if not any(name in line for name in ("PdfBox", "ML Kit", "Kotlin"))]
    return "\n".join(kept + ["Firebase iOS SDK (Google), Apache License 2.0.",
                               "Google Sign-In for iOS (Google), Apache License 2.0."])


def quote(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def plist_escape(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def convert_strings():
    defaults = {}
    for folder, language in LANGUAGES.items():
        path = os.path.join(RES, folder, "strings.xml")
        tree = ET.parse(path)
        strings = {}
        plurals = {}
        for element in tree.getroot():
            name = element.get("name")
            if element.tag == "string":
                strings[name] = ios_format(android_text(element))
            elif element.tag == "plurals":
                plurals[name] = {item.get("quantity"): ios_format(android_text(item)) for item in element}
            elif element.tag == "string-array":
                for index, item in enumerate(element):
                    strings["%s_%d" % (name, index)] = ios_format(android_text(item))
        if folder == "values":
            defaults = {"strings": strings, "plurals": plurals}
        else:
            # Untranslated strings (translatable="false") fall back to English.
            for key, value in defaults["strings"].items():
                strings.setdefault(key, value)
            for key, value in defaults["plurals"].items():
                plurals.setdefault(key, value)
        code = language.split("-")[0]
        strings["licenses_text"] = ios_licenses(strings["licenses_text"])
        # iOS wording and the few iOS-only strings (Cancel, OK) in every language.
        for key, translations in IOS_TEXT.items():
            strings[key] = translations.get(code, translations["en"])
        target = os.path.join(RESOURCES, language + ".lproj")
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "Localizable.strings"), "w", encoding="utf-8") as out:
            out.write("/* Generated from app/src/main/res/%s/strings.xml by ios/scripts/convert_resources.py. */\n\n"
                      % folder)
            for key in sorted(strings):
                out.write("%s = %s;\n" % (quote(key), quote(strings[key])))
        with open(os.path.join(target, "Localizable.stringsdict"), "w", encoding="utf-8") as out:
            out.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                      '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
                      '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0">\n<dict>\n')
            for key in sorted(plurals):
                forms = plurals[key]
                out.write("\t<key>%s</key>\n\t<dict>\n" % key)
                out.write("\t\t<key>NSStringLocalizedFormatKey</key>\n\t\t<string>%#@count@</string>\n")
                out.write("\t\t<key>count</key>\n\t\t<dict>\n")
                out.write("\t\t\t<key>NSStringFormatSpecTypeKey</key>\n\t\t\t<string>NSStringPluralRuleType</string>\n")
                out.write("\t\t\t<key>NSStringFormatValueTypeKey</key>\n\t\t\t<string>d</string>\n")
                for quantity in ("zero", "one", "two", "few", "many", "other"):
                    if quantity in forms:
                        out.write("\t\t\t<key>%s</key>\n\t\t\t<string>%s</string>\n"
                                  % (quantity, plist_escape(forms[quantity])))
                out.write("\t\t</dict>\n\t</dict>\n")
            out.write("</dict>\n</plist>\n")


def color(value):
    value = value.lstrip("#")
    if len(value) == 8:
        value = value[2:]
    return "#" + value


def convert_icons():
    drawables = os.path.join(RES, "drawable")
    for name in sorted(os.listdir(drawables)):
        if not name.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(drawables, name)).getroot()
        if root.tag != "vector":
            continue
        icon = name[:-4]
        width = root.get(ANDROID_NS + "viewportWidth")
        height = root.get(ANDROID_NS + "viewportHeight")
        template = icon.startswith("ic_")
        paths = []
        for path in root.iter("path"):
            data = path.get(ANDROID_NS + "pathData")
            fill = path.get(ANDROID_NS + "fillColor")
            rule = "evenodd" if path.get(ANDROID_NS + "fillType") == "evenOdd" else "nonzero"
            paint = "#000000" if template else color(fill or "#000000")
            paths.append('<path fill="%s" fill-rule="%s" d="%s"/>' % (paint, rule, data))
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">%s</svg>\n'
               % (width, height, width, height, "".join(paths)))
        folder = os.path.join(ASSETS, icon + ".imageset")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, icon + ".svg"), "w", encoding="utf-8") as out:
            out.write(svg)
        contents = {
            "images": [{"filename": icon + ".svg", "idiom": "universal"}],
            "info": {"author": "xcode", "version": 1},
            "properties": {"preserves-vector-representation": True,
                           "template-rendering-intent": "template" if template else "original"},
        }
        with open(os.path.join(folder, "Contents.json"), "w", encoding="utf-8") as out:
            json.dump(contents, out, indent=2)
            out.write("\n")


if __name__ == "__main__":
    convert_strings()
    convert_icons()
    print("Strings and icons written to", RESOURCES)
