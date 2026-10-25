# LG webOS AI installation preflight — design contract

> **LG webOS 测试包 / Developer preview:** [webos-v0.1.0-preview.1](https://github.com/lesnolie/Tigris/releases/tag/webos-v0.1.0-preview.1) · [IPK 安装、启动、更新和卸载 / Setup guide](https://github.com/lesnolie/Tigris/blob/main/docs/webos-preview-install.md)。已提供打包 IPK，无需自行构建；未做实机测试，不是稳定版，不接管 Home。Prebuilt IPK available; hardware untested, no Home takeover.

> **Status (2026-10-25): ordinary webOS application preview available; Home Helper planned.** The repository includes a developer-preview webOS application which can be packaged locally as an IPK, plus a read-only LG CLI preflight script. It does **not** ship a stable released webOS IPK, a native Tigris Home Helper or the safety probe described below. An agent may report ordinary application installation only after verifying it on the device; it MUST NOT equate that with Home-key takeover. The probe requirements below remain a future design contract.

## Platform routing is mandatory

1. Identify the television platform from trusted TV device information or a successful native connection; **do not infer the OS solely from its brand, IP address or an open port**.
2. Android TV/Google TV: follow [AGENTS.md](../AGENTS.md), using ADB and Android-specific backup/verification.
3. LG **webOS**: use LG Developer Mode (for an unrooted TV), SSH (normally `prisoner@TV_IP:9922`), `ares-cli` and/or Luna services. **Do not issue `adb connect`, `adb shell`, `pm`, `am` or Android HOME commands for webOS.**
4. Already-rooted webOS TV: use the user's existing, authorized Root SSH/Homebrew environment. Never root, exploit, flash or install LG Developer Mode onto an already-rooted TV. Root is never a prerequisite to *installing* the standard edition.
5. If the device cannot be identified, report `platform_unknown` and request the required TV-side information before making changes.

## Unrooted webOS: preflight **before** installing Tigris

**Stage A — connection/read-only inventory.** Ask the user to turn on the official LG Developer Mode app and Key Server; pair using the official webOS CLI (`ares-setup-device` / `ares-novacom --getkey`). Treat the passphrase and downloaded SSH private key as secrets: do not print them in logs, commit them or send them to external services. Confirm the connection using `ares-shell` or authenticated SSH. On stock Developer Mode, the expected user is `prisoner` on port `9922`. Verify architecture/firmware, identify model/remote, inspect input-device metadata, local permissions and Luna client availability **without changing settings or opening input devices exclusively**. After user-authorized pairing, a read-only command may resemble:

```sh
ssh -p 9922 -i <paired-private-key-path> prisoner@<TV_IP> \
  'id; uname -m; ls -l /dev/input 2>&1; cat /proc/bus/input/devices 2>&1; command -v luna-send-pub || command -v luna-send || true'
```

*Missing `/dev/input` entries, a failed read, or denied access as `prisoner` is **not proof** that a native webOS app can or cannot handle Home: the helper may run under a different application identity and permission set. Likewise, discovering remote input devices alone does not prove that exclusive input capture works or that the helper can forward all non-Home buttons.*

**Stage B — disposable capability probe (future prerequisite).** Only after Stage A identifies a viable webOS target, install a minimal, verified **temporary native probe** using an isolated app/service identity (do not install the full Tigris desktop yet). The probe must be explicitly reviewed, permissions-scoped, tested to release all inputs on failure, and have a separate watchdog and strict timeout. It must detect the expected remote input source and relay destination, inspect access rights, test the necessary input operation from the **same runtime identity** as the future Helper, then report whether the input path and safe pass-through are compatible. Any potentially exclusive input test must have an independent release/recovery plan *before* it starts. Do not attempt unreviewed `EVIOCGRAB` or input-device redirection from an AI-generated ad-hoc shell script. If the probe cannot run safely, return `unverified`, not `compatible`.

**Stage C — install and live verification.** If Stage B reports a compatible candidate, install the actual Tigris webOS IPK and optional Tigris Helper, then check the helper watchdog, input pass-through, foreground launch, **physical short Home press** and its emergency exit. User physical remote action is necessary for final proof; a Luna launch response alone is insufficient. Verify that normal navigation, Back, volume and LG's original Home recovery still work. Boot and standby resumption are separate tests and require user consent. Enable persistent Home interception **only after** all safety checks pass.

## Decisions the AI installer must report

| Decision | What is established | Installer action |
|---|---|---|
| `compatible_and_verified` | Native probe succeeded; physical Home, other keys, watchdog and escape were verified | Enable optional Home interception; keep stock LG Home intact |
| `candidate_unverified` | Read-only inventory looks promising, but native probe or physical press not completed | Install normal Tigris only when a real IPK exists; leave Helper disabled |
| `not_supported` | Verified incompatibility or an explicit safe-probe rejection | Do **not** enable Helper; offer LG Quick Access shortcut (user must bind it) |
| `unknown` | No trusted SSH access, missing data or failed detection | Stop and explain what must be authorized/tested; don't guess |
| `helper_failed` | Probe or runtime failed, timed out or did not release safely | Stop Helper, invoke validated recovery, leave stock Home functional; report diagnostics |

**Quick Access is a fallback**, not a separate launcher edition: in the Tigris app the user can assign it to a number key via LG's own Quick Access UI if their remote supports it. The installer may explain the process, but must not claim to have silently bound the button.

## Safety and user reporting

- Do not modify, mount over, disable, replace, uninstall or kill `com.webos.app.home` as an installation shortcut.
- Do not auto-root or run an unknown root exploit, and do not enable persistent interception when compatibility is uncertain.
- An emergency exit to LG Home, helper stop/uninstall and watchdog-based input release must be independently tested; only after that may interception be enabled.
- Distinguish **SSH ready**, **probe compatible**, **Tigris installed**, **Home verified**, **boot verified**. Never collapse them into a single success flag.
- If Developer Mode expires, the installed developer apps may be removed, so inform the user and never imply permanent system-level replacement.
- Preserve a diagnostic report with the observed OS, firmware, architecture, permission checks, probe status and the exact tests performed, but **redact** private keys, passphrases, pairing secrets and user-identifying network details.
- No real LG television has been tested with Tigris's future webOS implementation at the time of writing.

### Background / implementation references

- LG Developer Mode and device setup: https://webostv.developer.lge.com/develop/getting-started/developer-mode-app
- LG `ares-cli`: https://webostv.developer.lge.com/develop/tools/cli-dev-guide
- No-root native-helper reference (different project; evidence is model/firmware-specific): https://github.com/Razdva122/clean-your-tv-lg-webos
- Reference input relay and recovery design: https://github.com/Razdva122/clean-your-tv-lg-webos/blob/main/native/prototype/relay.c

The basic CLI inventory and source-build installer are implemented at `webos/install.sh`. The native input-capture probe, persistent Home Helper, physical-remote acceptance tests and any privileged advanced routing described above are **future acceptance requirements**, not implemented functionality.
