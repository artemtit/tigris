#!/usr/bin/env python3
"""Regression tests for README compatibility form parsing and sanitization."""
import importlib.util
import unittest
from pathlib import Path

SOURCE = Path(__file__).with_name("update-tv-compatibility.py")
SPEC = importlib.util.spec_from_file_location("tv_compatibility_docs", SOURCE)
compat = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compat)

FORM = """### 品牌 / Brand

Sony

### 型号 / Model

XR-55A80J

### Android 版本 / Android version

Android 12 (API 31)

### Tigris 版本 / Tigris version

v2.0.2

### 安装包 / APK type

Standard / 标准版

### 安装结果 / Installation result

Installed and launches / 已安装且启动

### 实际现象、功能测试和日志 / Observations, feature tests and logs

HDMI tested.
"""


class TVCompatibilityDocsTest(unittest.TestCase):
    def issue(self, body=FORM, number=12):
        return {
            "number": number,
            "title": "[TV] Sony XR-55A80J",
            "body": body,
            "labels": [],
            "state": "open",
        }

    def test_parses_issue_form_with_expected_enums(self):
        form = compat.form_fields(FORM)
        self.assertEqual("Sony", form["brand"])
        self.assertEqual("XR-55A80J", form["model"])
        self.assertEqual("Installed and launches", form["outcome"])

    def test_parses_windows_line_endings(self):
        form = compat.form_fields(FORM.replace("\n", "\r\n"))
        self.assertEqual("Sony", form["brand"])

    def test_skips_partial_or_free_form_issues(self):
        self.assertIsNone(compat.form_fields("Tigris won't install on my TV"))
        incomplete = FORM.replace("Sony\n", "_No response_\n", 1)
        self.assertIsNone(compat.form_fields(incomplete))
        self.assertTrue(compat.build([
            {"number": 4, "title": "generic issue", "body": "Please add support", "labels": []}
        ], True).startswith("No structured community reports yet. [Submit a report]"))

    def test_structured_form_without_tv_prefix_or_label_is_included(self):
        issue = self.issue(number=14)
        issue["title"] = "雷鸟电视使用Tigris桌面问题"
        self.assertIn("[#14]", compat.build([issue], False))
        self.assertIn("Open", compat.build([issue], True))

    def test_closed_issue_does_not_imply_a_verified_fix(self):
        issue = self.issue(number=15)
        issue["state"] = "closed"
        self.assertIn("已关闭（未验证修复）", compat.build([issue], False))
        self.assertIn("Closed (fix unverified)", compat.build([issue], True))

    def test_both_languages_are_reported_not_verified(self):
        zh = compat.build([self.issue()], False)
        en = compat.build([self.issue()], True)
        self.assertIn("已安装且能启动（自报）", zh)
        self.assertIn("Installed and launched (reported)", en)
        self.assertIn("https://github.com/lesnolie/Tigris/issues/12", zh)
        self.assertIn("v2.0.2", en)

    def test_malicious_or_multiline_model_is_escaped(self):
        self.assertEqual("&lt;img src=x&gt; &#124; &#91;h&#93;",
                         compat.clean("<img src=x> | [h]"))

    def test_invalid_dropdown_is_rejected(self):
        bad = FORM.replace("Installed and launches / 已安装且启动", "Supported across all TV brands")
        self.assertIsNone(compat.form_fields(bad))

    def test_markers_exist_once_in_both_readmes(self):
        for name in ("README.md", "README.en.md"):
            content = Path(name).read_text(encoding="utf-8")
            self.assertEqual(1, content.count(compat.START), name)
            self.assertEqual(1, content.count(compat.END), name)


if __name__ == "__main__":
    unittest.main()
