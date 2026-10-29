# GBA catalog provenance

The bundled `app/src/main/resources/gba-catalog.tsv` contains **3,054 editions**.
Every row joins one raw `.gba` file in Internet Archive's
[Central Arquivista collection](https://archive.org/details/CentralArquivista-GameBoyAdvance)
to one No-Intro record using the **published SHA1 and exact byte size**. The source
manifest contains 3,057 GBA files; three unmatched files are excluded. No game
content is included in the application or used to generate this metadata.

## Inputs and attribution

- File manifest: [Internet Archive metadata API](https://archive.org/metadata/CentralArquivista-GameBoyAdvance),
  inspected October 29, 2026. Source names are preserved solely for locating each file.
- Canonical edition names, ROM identities, serials and explicit language/region
  annotations: **No-Intro contributors**, distributed by **libretro database
  contributors** in
  [Nintendo - Game Boy Advance.dat](https://github.com/libretro/libretro-database/blob/master/metadat/no-intro/Nintendo%20-%20Game%20Boy%20Advance.dat),
  version **2026.10.07**. This derived catalog's No-Intro/libretro metadata is
  attributed and shared under **[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)**,
  following the [libretro database license](https://github.com/libretro/libretro-database/blob/master/COPYING).
  Changes include joining a download manifest, selecting matching editions,
  deriving groups and extracting explicit language/region fields.
- Optional covers: [libretro-thumbnails GBA artwork](https://github.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance).
  Artwork remains remote and retains its separate rights and attribution. The
  generator records the canonical **ROM filename** with libretro filename
  sanitation, which can differ from the DAT's display title. Missing artwork
  falls back to the application's title tile; a generated path is not a claim
  that a cover has been verified available.

The TSV header records SHA256 hashes of both exact input files. A filename alone
never establishes ROM identity. Published source hashes are metadata evidence;
the app must also hash the downloaded bytes against the catalog before saving.

## Generation

Download the two public metadata inputs separately, then run:

```sh
python3 scripts/generate_gba_catalog.py \
  --manifest /path/to/CentralArquivista-GameBoyAdvance.json \
  --dat /path/to/Nintendo-Game-Boy-Advance.dat \
  --output app/src/main/resources/gba-catalog.tsv
python3 -m unittest discover -s scripts -p 'test_generate_gba_catalog.py'
```

The generator performs no network requests. It rejects unsafe paths, duplicate
source filenames/identities and an unexpected item identifier. Ambiguous DAT
identities and unmatched SHA1/size pairs are excluded. The default expected count
is 3,054; any changed count requires review and an explicit `--expected-count`.
The runtime checks the resource version, declared count, unique identities,
field limits, valid paths, language codes, strict UTF-8 and a 2 MiB input limit.
After a reviewed update, update `GbaCatalog.BUNDLED_COUNT` and this document too.

Excluded from this snapshot:

- `Megaman - Battle Network 2 (US) (Debug Version).gba`
- `Pocket Monsters Diamond - Tomodachi to Issho ni! - Present Campaign Senyou Cartridge (JP) [b].gba`
- `Pocket Monsters Pearl - Tomodachi to Issho ni! - Present Campaign Senyou Cartridge (JP) [b].gba`

## Browsing semantics and limits

Edition detail URLs use the raw-ROM SHA1, so provider renaming does not change
their identity. Browsing loads the bundled immutable resource without a network
request. A source change cannot silently change which ROM a displayed edition
downloads; refreshing this mapping requires a reviewed application update.

Groups remove only explicit region/language annotations and revision suffixes
from otherwise identical canonical titles. Other annotations, such as Virtual
Console, Kiosk and Prototype, remain visible and distinct. Localized titles are
not merged through fuzzy matching. This snapshot retains all 3,054 verified
matches, including labeled non-retail editions; it is not a complete worldwide
GBA collection or an editorial selection of recommended games.

Language filtering uses explicit DAT filename language annotations only. A USA,
Europe, Spain or Japan region does not imply a supported language. Unknown
languages remain unknown and appear when the language filter is cleared. For
compilation annotations such as `(En,Fr,De+En)`, only languages shared by every
component are listed (`en` in that example). These conservative rules produce
922 entries with explicit language metadata in this snapshot.

Region filtering uses every explicit region in an edition's name. A Spain
edition therefore appears under Europe without asserting that its game is in
Spanish. Separate regional editions and revisions retain separate SHA1 identities.
