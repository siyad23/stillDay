import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("release_assets", Path(__file__).with_name("create-update-manifest.py"))
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.apk = self.root / "input.apk"
        self.apk.write_bytes(b"verified APK fixture")
        self.output = self.root / "release"
        self.badging = "package: name='dev.stillday.app' versionCode='13' versionName='1.0.11'\nsdkVersion:'26'\n"

    def generate(self, **kwargs):
        with patch.object(assets.subprocess, "run") as verify, patch.object(assets.subprocess, "check_output", return_value=self.badging):
            result = assets.create_assets(self.apk, self.output, self.root, **kwargs)
            verify.assert_called_once()
            self.assertIn("verify", verify.call_args.args[0])
            return result

    def test_existing_asset_name_and_checksums_match_exact_bytes(self):
        manifest = self.generate(release_tag="v1.0.11", apk_name="Stillday-debug.apk")
        self.assertEqual("Stillday-debug.apk", manifest["apkName"])
        self.assertEqual(13, manifest["versionCode"])
        self.assertEqual(self.apk.read_bytes(), (self.output / manifest["apkName"]).read_bytes())
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), manifest["sha256"])
        self.assertEqual(self.apk.stat().st_size, manifest["sizeBytes"])
        self.assertEqual(manifest, json.loads((self.output / "update.json").read_text(encoding="utf-8")))
        for line in (self.output / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
            digest, filename = line.split("  ", 1)
            self.assertEqual(digest, hashlib.sha256((self.output / filename).read_bytes()).hexdigest())

    def test_default_asset_name_is_unchanged(self):
        self.assertEqual("Stillday.apk", self.generate()["apkName"])

    def test_tag_mismatch_still_stops_publication(self):
        with self.assertRaisesRegex(ValueError, "v1.0.11"):
            self.generate(release_tag="v1.0.12")
        self.assertFalse(self.output.exists())

    def test_asset_name_cannot_escape_release_directory(self):
        for name in ["../other.apk", "folder/app.apk", "app.json", "..\\app.apk"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.generate(apk_name=name)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
