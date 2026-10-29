import json
import tempfile
import unittest
from pathlib import Path

from generate_gba_catalog import ITEM, edition_metadata, generate


class GenerateGbaCatalogTest(unittest.TestCase):
    def fixture(self, directory, files, games):
        manifest = Path(directory) / "manifest.json"
        dat = Path(directory) / "games.dat"
        manifest.write_text(json.dumps({"metadata": {"identifier": ITEM}, "files": files}))
        blocks = ['clrmamepro (\n\tversion "test"\n)\n']
        for title, rom, sha1, size in games:
            blocks.append('game (\n\tname ' + json.dumps(title) + '\n\tserial "ABCE"\n\trom ( name '
                          + json.dumps(rom) + f' size {size} crc DEADBEEF md5 {"a" * 32} sha1 {sha1} )\n)\n')
        dat.write_text("".join(blocks))
        return manifest, dat

    def test_hash_and_size_join_replaces_provider_title_and_keeps_exact_transport_name(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest, dat = self.fixture(directory, [{"name": "Old Name (US).gba", "size": "1048576", "sha1": "a" * 40}],
                                        [("New Name (USA) (En)", "New Name & Friends (USA) (En).gba", "A" * 40, 1048576)])
            output, report = generate(manifest, dat, 1)
            row = next(line for line in output.splitlines() if not line.startswith("#")).split("\t")
            self.assertEqual(row[2:7], ["Old Name (US).gba", "New Name (USA) (En)", "New Name", "USA", "en"])
            self.assertEqual(row[7], "New Name _ Friends (USA) (En)")
            self.assertEqual(report, {"matched": 1, "excluded": []})
            self.assertEqual(generate(manifest, dat, 1)[0], output)

    def test_wrong_size_unknown_hash_and_ambiguous_dat_identity_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            files = [{"name": "Wrong size.gba", "size": "2097152", "sha1": "a" * 40},
                     {"name": "Unknown.gba", "size": "1048576", "sha1": "b" * 40},
                     {"name": "Ambiguous.gba", "size": "1048576", "sha1": "c" * 40}]
            games = [("Known (USA)", "Known.gba", "A" * 40, 1048576),
                     ("One (USA)", "One.gba", "C" * 40, 1048576),
                     ("Two (USA)", "Two.gba", "C" * 40, 1048576)]
            manifest, dat = self.fixture(directory, files, games)
            output, report = generate(manifest, dat, 0)
            self.assertEqual(len(report["excluded"]), 3)
            self.assertEqual(report["matched"], 0)
            self.assertTrue(output.startswith("# thorshelf-gba-v1\t0"))

    def test_duplicate_identity_unsafe_filename_and_count_change_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            game = [("Tiny (USA)", "Tiny (USA).gba", "A" * 40, 1048576)]
            for filenames in [["One.gba", "Two.gba"], ["../Tiny.gba"]]:
                files = [{"name": name, "size": "1048576", "sha1": "a" * 40} for name in filenames]
                manifest, dat = self.fixture(directory, files, game)
                with self.assertRaises(ValueError):
                    generate(manifest, dat, 1)
            manifest, dat = self.fixture(directory, [{"name": "Tiny.gba", "size": "1048576", "sha1": "a" * 40}], game)
            with self.assertRaisesRegex(ValueError, "Expected 3054"):
                generate(manifest, dat)

    def test_language_evidence_and_grouping_preserve_unknown_and_distinct_releases(self):
        self.assertEqual(edition_metadata("Tiny (USA) (Rev 1)"), ("Tiny", "USA", ""))
        self.assertEqual(edition_metadata("Tiny (Japan)"), ("Tiny", "Japan", ""))
        self.assertEqual(edition_metadata("Ratatouille (Australia, Greece) (En)"),
                         ("Ratatouille", "Australia, Greece", "en"))
        self.assertEqual(edition_metadata("Tiny (Europe) (En,Fr,De+En)"), ("Tiny", "Europe", "en"))
        self.assertEqual(edition_metadata("Tiny (Europe) (Fr+En)"), ("Tiny", "Europe", ""))
        self.assertEqual(edition_metadata("Tiny (USA, Europe) (En,Fr) (Virtual Console)"),
                         ("Tiny (Virtual Console)", "USA, Europe", "en,fr"))


if __name__ == "__main__":
    unittest.main()
