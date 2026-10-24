#!/usr/bin/env python3
"""Offline tests for the versioned APK downloader (no TV/network needed)."""
import hashlib
import importlib.util
import unittest
from pathlib import Path

path = Path(__file__).with_name("download-release.py")
spec = importlib.util.spec_from_file_location("release_downloader", path)
dl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dl)


def release(version="v2.0.6", legacy=False):
    asset_names = (["Tigris.apk", "Tigris-guard.apk", "SHA256SUMS.txt"] if legacy else
                   [f"Tigris-{version}.apk", f"Tigris-guard-{version}.apk", f"SHA256SUMS-{version}.txt"])
    return {"tag_name": version, "draft": False, "prerelease": False,
            "assets": [{"name": name, "browser_download_url": f"{dl.PUBLIC}/{version}/{name}"}
                       for name in asset_names]}


class ReleaseAssetTests(unittest.TestCase):
    def test_versioned_standard_and_guard(self):
        self.assertEqual("Tigris-v2.0.6.apk", dl.select_release_assets(release(), "standard", "latest")[0])
        self.assertEqual("Tigris-guard-v2.0.6.apk", dl.select_release_assets(release(), "guard", "latest")[0])

    def test_historical_legacy_assets(self):
        self.assertEqual("Tigris.apk", dl.select_release_assets(release("v2.0.5", True), "standard", "v2.0.5")[0])
        self.assertEqual("Tigris-guard.apk", dl.select_release_assets(release("v2.0.5", True), "guard", "latest")[0])

    def test_wrong_tag_or_preview_rejected(self):
        with self.assertRaises(ValueError):
            dl.select_release_assets(release(), "standard", "v2.0.5")
        preview = release()
        preview["prerelease"] = True
        with self.assertRaises(ValueError):
            dl.select_release_assets(preview, "standard", "latest")

    def test_untrusted_download_urls_rejected(self):
        bad = release()
        bad["assets"][0]["browser_download_url"] = "https://example.com/fake.apk"
        with self.assertRaises(ValueError):
            dl.select_release_assets(bad, "standard", "latest")

    def test_checksum_verification_matches_exact_filename(self):
        apk = b"APK contents"
        digest = hashlib.sha256(apk).hexdigest()
        dl.verify(apk, f"{digest}  Tigris-v2.0.6.apk\n", "Tigris-v2.0.6.apk")
        with self.assertRaises(ValueError):
            dl.verify(apk, f"{digest}  Tigris-v2.0.5.apk\n", "Tigris-v2.0.6.apk")
        with self.assertRaises(ValueError):
            dl.verify(b"tampered", f"{digest}  Tigris-v2.0.6.apk\n", "Tigris-v2.0.6.apk")


if __name__ == "__main__":
    unittest.main()
