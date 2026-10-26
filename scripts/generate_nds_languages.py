#!/usr/bin/env python3
"""Build exact-edition DS languages from locally downloaded public metadata only.

Inputs (no game files):
  catalog: https://raw.githubusercontent.com/0x1a4-dev/DSiDrop/main/public/data/nds.json
  nointro: https://raw.githubusercontent.com/libretro/libretro-database/master/
           metadat/no-intro/Nintendo%20-%20Nintendo%20DS.dat
  gametdb: dstdb.xml from https://www.gametdb.com/dstdb.zip?LANG=EN

This script intentionally does not fetch data. Exact No-Intro names identify editions;
their four-character serials join GameTDB's explicit <languages> field. Translated
descriptions and locale/title fields are never treated as game language support.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


CODES = set("en fr de es it ja ko zh nl pt ru pl sv da no fi cs hu tr el ar he uk ca".split())
ALIASES = {"se": "sv", "dk": "da", "gr": "el", "nb": "no", "zhcn": "zh", "zhtw": "zh"}
COUNTRY_LOCALES = {"france", "germany", "spain", "italy", "japan", "korea", "china"}


def languages(raw):
    result = []
    for token in (raw or "").lower().split(","):
        code = ALIASES.get(token.strip(), token.strip())
        if code not in CODES:
            return None
        if code not in result:
            result.append(code)
    return ",".join(result)


def unique(values):
    """Do not choose an arbitrary record when a source contains a conflicting key."""
    output, conflicts = {}, set()
    for key, value in values:
        if key in output and output[key] != value:
            conflicts.add(key)
        output[key] = value
    return {key: value for key, value in output.items() if key not in conflicts}


def generate(catalog_path, nointro_path, gametdb_path):
    raw_catalog = Path(catalog_path).read_bytes()
    raw_nointro = Path(nointro_path).read_bytes()
    raw_gametdb = Path(gametdb_path).read_bytes()
    dat = raw_nointro.decode("utf-8")
    identities = []
    for block in re.split(r"(?m)^game \(\n", dat)[1:]:
        name = re.search(r'^\tname "(.*)"$', block, re.MULTILINE)
        serial = re.search(r'^\tserial "([A-Z0-9]{4})"$', block, re.MULTILINE)
        if name and serial:
            identities.append((name[1], serial[1]))
    identities = unique(identities)
    xml = ET.fromstring(raw_gametdb)
    supported = unique((game.findtext("id"), languages(game.findtext("languages")))
                       for game in xml.findall("game"))
    rows = []
    for entry in json.loads(raw_catalog)["roms"]:
        name = entry.get("n")
        if not isinstance(name, str) or not name or len(name) > 1024:
            continue
        if any(ord(char) < 32 for char in name) or entry.get("l"):
            continue
        groups = re.findall(r"[\(\[]([^()\[\]]+)[\)\]]", name)
        # These are already covered by explicit filename languages or our conservative
        # country-edition rules. In particular, do not import dubious EN,JA expansion
        # for a Japanese edition without an explicit language list in the source index.
        if any(group.lower().strip() in COUNTRY_LOCALES or languages(group) for group in groups):
            continue
        serial = identities.get(name)
        codes = supported.get(serial)
        if serial and codes:
            rows.append((name, (codes, serial)))
    rows = unique(rows)
    version = re.search(r'^\tversion "([^"\r\n]+)"$', dat, re.MULTILINE)
    stamp = xml.find("DSTDB")
    lines = [
        "# Exact DS edition languages: No-Intro filename -> game serial -> GameTDB languages.",
        "# Supplement only: explicit language and known single-country editions are excluded.",
        "# Public metadata only; source snapshot versions and hashes below. No game files or descriptions included.",
        "# Catalog https://raw.githubusercontent.com/0x1a4-dev/DSiDrop/main/public/data/nds.json",
        "# No-Intro via libretro/libretro-database, version " + (version[1] if version else "unknown"),
        "# https://github.com/libretro/libretro-database/blob/master/metadat/no-intro/Nintendo%20-%20Nintendo%20DS.dat",
        "# GameTDB https://www.gametdb.com/dstdb.zip?LANG=EN version " +
        (stamp.get("version", "unknown") if stamp is not None else "unknown"),
        "# Input SHA256 catalog=" + hashlib.sha256(raw_catalog).hexdigest(),
        "# Input SHA256 nointro=" + hashlib.sha256(raw_nointro).hexdigest(),
        "# Input SHA256 gametdb=" + hashlib.sha256(raw_gametdb).hexdigest(),
        "# Columns: exact catalog filename<TAB>language codes<TAB>GameTDB DS ID",
    ]
    lines.extend(name + "\t" + codes + "\t" + serial
                 for name, (codes, serial) in sorted(rows.items()))
    return "\n".join(lines) + "\n", len(rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--nointro", required=True, type=Path)
    parser.add_argument("--gametdb", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output, count = generate(args.catalog, args.nointro, args.gametdb)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")
    print(f"Wrote {count} exact edition language records to {args.output}")
