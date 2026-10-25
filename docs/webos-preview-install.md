# LG webOS 0.1.0 测试版：下载与使用

**未做 LG 电视实机测试，仅供自愿测试。不是稳定版，不接管遥控器 Home，不替换 LG 原厂桌面。**

这是独立 webOS IPK；不需要 Android APK、ADB 或 Root。非 Root 和已有 Root 的能力模块包含在同一个应用中，Root 模块只按需检查已有权限，不会给电视 Root。应用列表、HDMI、天气等能力可能被具体固件限制。

## 1. 下载

从 [webOS 测试版 Release](https://github.com/lesnolie/Tigris/releases/tag/webos-v0.1.0-preview.1) 下载 `org.tigris.webos_0.1.0_all.ipk` 和 `SHA256SUMS-webos-v0.1.0-preview.1.txt`，放到同一文件夹。这个包已打好，**无需克隆源码或运行 ares-package**。IPK 不是 U 盘双击安装包，需要下面的 Developer Mode 方式。

在下载目录校验，结果应与校验文件一致：

- Windows PowerShell：`Get-FileHash .\org.tigris.webos_0.1.0_all.ipk -Algorithm SHA256`
- macOS：`shasum -a 256 org.tigris.webos_0.1.0_all.ipk`
- Linux：`sha256sum -c SHA256SUMS-webos-v0.1.0-preview.1.txt`

## 2. 电视与电脑准备

1. 电视与电脑连到同一个局域网。在 LG 应用商店安装 **Developer Mode**，使用 LG 开发者账号登录，启用 Dev Mode Status；若电视要求重启，由你在电视上确认。重新打开 Developer Mode，启用 **Key Server**，记下电视 IP。
2. 电脑安装 Node.js，然后在终端运行下面命令。Windows PowerShell、macOS Terminal、Linux 均可。

```sh
npm install -g @webos-tools/cli@3.2.6
ares-setup-device
```

在交互菜单选 add，设备名填 `myTV`，IP 填电视 IP，端口 `9922`，用户 `prisoner`；其余按 Developer Mode 提示。`myTV` 是电脑保存的设备别名，不是 IP。

```sh
ares-setup-device --list
ares-novacom --device myTV --getkey
ares-device --system-info --device myTV
```

`--getkey` 提示时，在本机输入电视 Developer Mode 显示的 passphrase。不要公开配对码或私钥。连接失败先检查同一网络、IP、Dev Mode、Key Server、防火墙和配对；不要换成 ADB。

## 3. 安装与启动

在 IPK 所在目录执行：

```sh
ares-install --device myTV org.tigris.webos_0.1.0_all.ipk
ares-launch --device myTV org.tigris.webos
```

安装后也可从 LG 的应用列表打开 Tigris；若电视支持 Quick Access，可自行设置快捷启动。**Home 键仍进入 LG 原厂主页，这是本测试版预期行为，不是安装失败。** 同一应用内的高级 Root 功能默认不使用，普通安装无需开启。

## 4. 请测试并反馈

请 [提交 Issue](https://github.com/lesnolie/Tigris/issues)，写明 LG 型号、webOS / 固件版本及测试包 `webos-v0.1.0-preview.1`。逐项报告通过 / 失败 / 未测试：安装和启动、方向键/OK/返回、设置弹窗焦点、应用列表和启动、HDMI 切换、手动开启后的城市天气、退出后 LG Home 是否正常。方便时再测待机唤醒、重启后从应用列表重新打开。不要把能安装写成 Home 接管成功。

报错请附命令错误文本或短视频，删除 IP、配对码、私钥等隐私。API 被固件拒绝、空列表或启动失败请按实际报告；当前未承诺任何 LG 型号兼容。

## 5. 更新、到期与卸载

更新时下载新的 webOS 测试 IPK，校验后用同样的 `ares-install` 命令覆盖，再启动；无需先卸载，测试版数据保留不作保证。不要安装 Android APK，也不要为了更新关闭原厂 Home。

Developer Mode 有期限，请在到期前使用电视中的 Extend 续期。开发者模式失效后，开发安装的应用可能被移除；按官方流程重新启用、配对并安装。这不是 Tigris 自动更新或永久安装。

仅卸载 Tigris：

```sh
ares-install --device myTV --remove org.tigris.webos
```

无需恢复 LG Home：本包从未修改它。若设过 Quick Access，去电视设置中自行移除关联。

## 可选：自行从源码打包

已下载 IPK 的用户跳过此节。源码开发者在对应 Release 标签的仓库根目录执行：

```sh
npm install -g @webos-tools/cli@3.2.6
ares-package webos/app
```

输出 `org.tigris.webos_0.1.0_all.ipk`，再按上面安装。打包工具可能提示未声明 requiredACG；打包成功不是固件 API 授权或实机验证通过，也不是 LG 商店审核通过。不包含任何独立 Home Helper。

官方参考：[Developer Mode](https://webostv.developer.lge.com/develop/getting-started/developer-mode-app) · [webOS CLI](https://webostv.developer.lge.com/develop/tools/cli-dev-guide)

## English quick guide

**Untested on physical LG TVs. Developer preview, not a stable release or Home replacement.** Download the prebuilt IPK and checksum from the release above; no source build is needed. Enable LG Developer Mode and Key Server, install Node.js and CLI 3.2.6, add `myTV` (TV IP, port 9922, user prisoner), retrieve its key with `ares-novacom --device myTV --getkey`, then run the install/launch commands above from your download folder. Home intentionally opens LG Home. Renew Developer Mode before expiry. Update with `ares-install` using the new IPK; uninstall with `ares-install --device myTV --remove org.tigris.webos`. Report model, firmware and individual installation, remote, app/HDMI, optional weather and stock-Home results in Issues. Never share pairing secrets. Root is not required and no Home Helper is shipped.
