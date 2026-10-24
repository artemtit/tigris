#!/usr/bin/env python3
"""Download and verify the versioned Tigris APK for manual ADB / T-Cast installs.

Usage: python3 tools/download-release.py [standard|guard] [--version v2.0.5]
Prints the local APK filename on success. The file's basename matches the
actual GitHub Release asset (including the version in new releases).
Requires only Python 3's standard library. Does not touch the TV or uninstall.
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import tempfile
import urllib.request

API = "https://api.github.com/repos/lesnolie/Tigris/releases"
PUBLIC = "https://github.com/lesnolie/Tigris/releases/download"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Tigris-release-downloader"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def select_release_assets(release, flavor, version):
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not re.fullmatch(r"v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[A-Za-z0-9._-]+)?", tag):
        raise ValueError("Invalid release tag")
    if version != "latest" and version != tag:
        raise ValueError("Release tag mismatch")
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("Only stable releases are supported")
    assets = {a["name"]: a["browser_download_url"] for a in release["assets"]}
    prefix = "Tigris-guard" if flavor == "guard" else "Tigris"
    for name in (f"{prefix}-{tag}.apk", f"{prefix}.apk"):
        if name in assets:
            apk_name = name
            break
    else:
        raise ValueError("No APK found for the requested flavor")
    for name in (f"SHA256SUMS-{tag}.txt", "SHA256SUMS.txt"):
        if name in assets:
            checksum_name = name
            break
    else:
        raise ValueError("No SHA256SUMS file available")
    for name in (apk_name, checksum_name):
        if assets[name] != f"{PUBLIC}/{tag}/{name}":
            raise ValueError("Unexpected release URL")
    return apk_name, assets[apk_name], assets[checksum_name]


def verify(apk_bytes, checksum_text, name):
    expected = [s.split()[0].lower() for s in checksum_text.splitlines()
                if len(s.split()) == 2 and s.split()[1].lstrip("*") == name]
    if expected != [hashlib.sha256(apk_bytes).hexdigest()]:
        raise ValueError("SHA-256 mismatch: will not save the APK")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("flavor", choices=("standard", "guard"), nargs="?", default="standard")
    p.add_argument("--version", default="latest", help="Stable tag, e.g. v2.0.5 (default: latest)")
    opts = p.parse_args()
    if opts.version != "latest" and not re.fullmatch(r"v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[A-Za-z0-9._-]+)?", opts.version):
        p.error("Invalid version tag")
    api = f"{API}/latest" if opts.version == "latest" else f"{API}/tags/{opts.version}"
    release = json.loads(fetch(api))
    name, apk_url, checksum_url = select_release_assets(release, opts.flavor, opts.version)
    apk_bytes = fetch(apk_url)
    verify(apk_bytes, fetch(checksum_url).decode("utf-8"), name)
    target = pathlib.Path(name)
    # Write only after validation, without corrupting a previous download if interrupted.
    with tempfile.NamedTemporaryFile(dir=".", prefix=".tigris-download-", delete=False) as out:
        tmp = pathlib.Path(out.name)
        try:
            out.write(apk_bytes)
            out.flush()
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
    tmp.replace(target)
    print(name)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as e:
        print(f"Download failed: {e}", file=sys.stderr)
        sys.exit(1)
