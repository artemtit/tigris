#!/usr/bin/env python3
"""Propose bilingual README updates from structured GitHub TV compatibility issues.

Only Issue Form fields are parsed. Free-form notes are intentionally excluded.
Output is a *community self-report* table, NEVER a maintainer-verified verdict.
GitHub Actions opens a PR for review rather than committing to main.
"""
import html
import json
import os
import re
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPOSITORY", "lesnolie/Tigris")
BASE = "https://api.github.com/repos/" + REPO
START = "<!-- TV_COMPATIBILITY_AUTO_START -->"
END = "<!-- TV_COMPATIBILITY_AUTO_END -->"
KEYS = {
    "brand": "品牌 / Brand",
    "model": "型号 / Model",
    "android": "Android 版本 / Android version",
    "version": "Tigris 版本 / Tigris version",
    "apk": "安装包 / APK type",
    "outcome": "安装结果 / Installation result",
}
OUTCOMES = {
    "Installed and launches": ("已安装且能启动（自报）", "Installed and launched (reported)"),
    "Installed but crashes": ("已安装但崩溃（自报）", "Installed but crashes (reported)"),
    "Installation rejected": ("无法安装（自报）", "Install rejected (reported)"),
    "Not tested": ("尚未测试", "Not tested"),
}
APKS = {
    "Standard": ("标准版", "Standard"),
    "Guard": ("守护版", "Guard"),
    "Custom fork": ("自定义 Fork", "Custom fork"),
}


def request_issues():
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise RuntimeError("GITHUB_TOKEN missing: refusing to overwrite the table")
    output = []
    page = 1
    while True:
        req = urllib.request.Request(
            BASE + f"/issues?state=all&per_page=100&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": "Bearer " + token,
                "User-Agent": "Tigris-tv-compat-readme-sync",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(req, timeout=25) as resp:
            batch = json.load(resp)
        if not isinstance(batch, list):
            raise RuntimeError("Unexpected GitHub Issues response")
        output.extend(i for i in batch if "pull_request" not in i)
        if len(batch) < 100:
            break
        page += 1
    return output


def form_fields(body):
    # Only matching GitHub Issue Form headings are used.
    fields = {}
    for title, value in re.findall(r"(?ms)^### ([^\n]+)\n(.*?)(?=^### |\Z)", (body or "").replace("\r\n", "\n")):
        fields[title.strip()] = value.strip()
    values = {key: fields.get(label, "") for key, label in KEYS.items()}
    if any(not val or val in {"_No response_", "No response"} for val in values.values()):
        return None
    outcome = values["outcome"].split(" / ", 1)[0]
    apk = values["apk"].split(" / ", 1)[0]
    if outcome not in OUTCOMES or apk not in APKS:
        return None
    values["outcome"] = outcome
    values["apk"] = apk
    return values


def clean(value, limit=70):
    # Never render arbitrary user-supplied Markdown/HTML as links or table markup.
    value = re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", str(value))
    value = re.sub(r"\s+", " ", value).strip()[:limit]
    value = html.escape(value, quote=False)
    return value.replace("|", "&#124;").replace("[", "&#91;").replace("]", "&#93;").replace("`", "&#96;")


def build(issues, english):
    rows = []
    for issue in issues:
        # GitHub's Issue Form does not force a title prefix or attach a label.
        # A complete, valid structured form is sufficient, even for custom titles.
        record = form_fields(issue.get("body"))
        if not record:
            continue
        no = issue["number"]
        locale = 1 if english else 0
        device = clean(record["brand"]) + " " + clean(record["model"])
        platform = clean(record["android"])
        version = clean(record["version"]) + " / " + APKS[record["apk"]][locale]
        outcome = OUTCOMES[record["outcome"]][locale]
        # This is GitHub issue state, not evidence that the bug has been fixed.
        issue_state = (("已关闭（未验证修复）", "Closed (fix unverified)") if
                       issue.get("state") == "closed" else ("未关闭", "Open"))[locale]
        link = f"[#{no}](https://github.com/{REPO}/issues/{no})"
        rows.append((no, f"| {device} | {platform} | {version} | {outcome} | {issue_state} | {link} |"))
    rows.sort(key=lambda item: item[0], reverse=True)
    if not rows:
        return ("No structured community reports yet. [Submit a report](https://github.com/"
                + REPO + "/issues/new/choose)."
                if english else
                "尚无新的结构化社区反馈。[提交电视兼容性反馈](https://github.com/"
                + REPO + "/issues/new/choose)。")
    header = ("| TV model | Android | Tigris / APK | Installation result | Issue state | Source |\n"
              "|---|---|---|---|---|---|" if english else
              "| 品牌与型号 | Android | Tigris / 安装包 | 安装结果 | Issue 状态 | 来源 |\n"
              "|---|---|---|---|---|---|")
    return header + "\n" + "\n".join(row for _, row in rows)


def update(path, content):
    original = Path(path).read_text(encoding="utf-8")
    if original.count(START) != 1 or original.count(END) != 1:
        raise RuntimeError(f"{path}: missing or duplicated markers")
    a = original.index(START) + len(START)
    b = original.index(END)
    if a > b:
        raise RuntimeError(f"{path}: markers out of order")
    result = original[:a] + "\n" + content + "\n" + original[b:]
    if result != original:
        Path(path).write_text(result, encoding="utf-8")
        print(f"Updated {path}")
    else:
        print(f"No change: {path}")


if __name__ == "__main__":
    issues = request_issues()
    for path, lang in (("README.md", False), ("README.en.md", True)):
        update(path, build(issues, lang))
