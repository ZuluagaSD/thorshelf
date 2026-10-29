#!/usr/bin/env python3
"""Build the bundled GBA catalog from local Internet Archive and No-Intro metadata.

No network requests or game files are used. Only a unique SHA1 + exact byte-size
match admits an edition; provider filenames never determine its identity.
"""

import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path


ITEM = "CentralArquivista-GameBoyAdvance"
LANGUAGES = set("en fr de es it ja ko zh nl pt ru pl sv da no fi cs hu tr el ar he uk ca".split())
REGIONS = set("USA|Europe|Japan|Germany|France|Spain|Italy|Netherlands|China|Korea|Australia|World|Taiwan|Canada|United Kingdom|Sweden|Norway|Denmark|Finland|Portugal|Russia|Brazil|Asia|Hong Kong|Greece".split("|"))
MAX_ROM_BYTES = 32 * 1024 * 1024


def safe_text(value, limit=256):
    return (isinstance(value, str) and bool(value) and len(value) <= limit
            and not any(unicodedata.category(c) in ("Cc", "Cf") for c in value))


def safe_filename(value):
    return (safe_text(value) and value.lower().endswith(".gba")
            and not any(character in value for character in "/\\:?#")
            and not value.startswith("."))


def language_group(value):
    """A compilation must support the chosen language in every component game."""
    parts = []
    for component in value.split("+"):
        codes = [code.strip().lower() for code in component.split(",")]
        if not codes or any(code not in LANGUAGES for code in codes):
            return None
        parts.append(codes)
    return [code for code in dict.fromkeys(parts[0]) if all(code in part for part in parts)]


def edition_metadata(title):
    region, languages = "", []
    def replace(match):
        nonlocal region, languages
        value = match[1]
        places = [part.strip() for part in value.split(",")]
        if all(place in REGIONS for place in places):
            if not region:
                region = ", ".join(places)
            return ""
        codes = language_group(value)
        if codes is not None:
            languages = codes
            return ""
        if re.fullmatch(r"Rev [A-Za-z0-9]+", value):
            return ""
        return match[0]
    # Keep other annotations (Virtual Console, Kiosk, Proto, etc.) in the group
    # identity: they are different releases, not interchangeable regional copies.
    group = re.sub(r"\s+", " ", re.sub(r"\(([^()]*)\)", replace, title)).strip()
    return group, region, ",".join(languages)


def dat_records(text):
    records = {}
    for block in re.split(r"(?m)^game \(\n", text)[1:]:
        title = re.search(r'^\tname ("(?:\\.|[^"\\])*")$', block, re.MULTILINE)
        roms = re.findall(r'\brom \( name ("(?:\\.|[^"\\])*") size (\d+) crc [A-Fa-f0-9]+ md5 [A-Fa-f0-9]+ sha1 ([A-Fa-f0-9]{40})\b', block)
        if title is None or len(roms) != 1:
            continue
        title = json.loads(title[1])
        filename, size, sha1 = roms[0]
        filename, size = json.loads(filename), int(size)
        if not safe_text(title) or not safe_filename(filename) or not 192 <= size <= MAX_ROM_BYTES:
            continue
        serial = re.search(r'^\tserial "([A-Z0-9]{4})"$', block, re.MULTILINE)
        value = (title, filename, serial[1] if serial else "")
        records.setdefault((sha1.lower(), size), set()).add(value)
    return {key: next(iter(values)) for key, values in records.items() if len(values) == 1}


def generate(manifest_path, dat_path, expected_count=3054):
    raw_manifest, raw_dat = Path(manifest_path).read_bytes(), Path(dat_path).read_bytes()
    if len(raw_manifest) > 16 * 1024 * 1024 or len(raw_dat) > 16 * 1024 * 1024:
        raise ValueError("Input metadata exceeds 16 MiB")
    manifest = json.loads(raw_manifest)
    if manifest.get("metadata", {}).get("identifier") != ITEM:
        raise ValueError("Unexpected Internet Archive item")
    dat = raw_dat.decode("utf-8")
    identities = dat_records(dat)
    rows, unknown, names, hashes = [], [], set(), set()
    for file in manifest.get("files", []):
        filename = file.get("name", "")
        if not filename.lower().endswith(".gba"):
            continue
        if not safe_filename(filename):
            raise ValueError("Unsafe GBA source filename")
        sha1 = file.get("sha1", "").lower()
        size = int(file.get("size", 0))
        match = identities.get((sha1, size)) if re.fullmatch(r"[a-f0-9]{40}", sha1) else None
        if match is None:
            unknown.append(filename)
            continue
        if filename in names or sha1 in hashes:
            raise ValueError("Duplicate source filename or ROM identity")
        names.add(filename)
        hashes.add(sha1)
        title, canonical_rom, serial = match
        group, region, languages = edition_metadata(title)
        # libretro filenames use the canonical ROM name, which can differ from
        # the DAT game title (notably Castlevania Advance Collection releases).
        artwork = re.sub(r'[&*/:`<>?\\|]', "_", canonical_rom[:-4])
        rows.append((sha1, str(size), filename, title, group, region, languages, artwork, serial))
    rows.sort(key=lambda row: (row[3].casefold(), row[3], row[0]))
    if expected_count is not None and len(rows) != expected_count:
        raise ValueError(f"Expected {expected_count} matched editions, found {len(rows)}")
    version = re.search(r'^\tversion "([^"\r\n]+)"$', dat, re.MULTILINE)
    lines = [
        "# thorshelf-gba-v1\t" + str(len(rows)),
        "# Internet Archive item: " + ITEM,
        "# No-Intro via libretro/libretro-database: " + (version[1] if version else "unknown"),
        "# Metadata attribution: No-Intro contributors; libretro database contributors (CC BY-SA 4.0).",
        "# See docs/GBA_CATALOG.md for source URLs, exclusions, limitations and regeneration.",
        "# manifest-sha256=" + hashlib.sha256(raw_manifest).hexdigest(),
        "# dat-sha256=" + hashlib.sha256(raw_dat).hexdigest(),
        "# Columns: sha1, size, source filename, canonical edition, group, region, languages, artwork basename, serial (TAB separated)",
    ]
    lines.extend("\t".join(row) for row in rows)
    return "\n".join(lines) + "\n", {"matched": len(rows), "excluded": unknown}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--dat", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-count", type=int, default=3054)
    args = parser.parse_args()
    output, report = generate(args.manifest, args.dat, args.expected_count)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")
    print(json.dumps(report, indent=2))
