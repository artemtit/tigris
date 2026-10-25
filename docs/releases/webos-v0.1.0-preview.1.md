# Tigris LG webOS 0.1.0 — 测试版 / Developer preview

**未做任何 LG 电视实机测试。不是稳定版，不替换 LG 原厂桌面，不接管遥控器 Home。**

这是独立的 LG webOS 测试包，不是 Android v2.0.7 APK。非 Root 和已有 Root 的只读能力模块均在同一 IPK 内；不需要 Root，不提供 Home Helper。包含应用/输入源入口、遥控器导航和默认关闭的天气。具体固件可能拒绝相关 API。

下载附件 `org.tigris.webos_0.1.0_all.ipk`，按校验文件核对 SHA-256，然后按 **[完整安装与使用说明](https://github.com/lesnolie/Tigris/blob/webos-v0.1.0-preview.1/docs/webos-preview-install.md)** 操作。也附带离线说明 `webos-preview-install.md`。

电脑先安装 Node.js 和官方 CLI；电视启用 Developer Mode + Key Server。配置 myTV 为设备别名（电视 IP、9922、prisoner）并获取配对密钥后，在下载目录运行：

```sh
npm install -g @webos-tools/cli@3.2.6
ares-setup-device
ares-novacom --device myTV --getkey
ares-device --system-info --device myTV
ares-install --device myTV org.tigris.webos_0.1.0_all.ipk
ares-launch --device myTV org.tigris.webos
```

这是已打包的 IPK，无需运行 ares-package，也不能按 Android APK / ADB 方式安装。Home 回 LG 原厂主页是预期行为。Developer Mode 请在到期前续期，否则开发安装的应用可能被移除。

更新：校验新 IPK 后用 ares-install 覆盖。卸载：`ares-install --device myTV --remove org.tigris.webos`。

请 LG 用户在 [Issues](https://github.com/lesnolie/Tigris/issues) 提供型号、webOS / 固件版本，分别反馈安装/启动、方向键/OK/返回、设置、应用/HDMI、可选天气、LG Home、待机和重启结果；未测试就写未测试，不公开配对密钥。打包与自动化测试不等于实机兼容。

**English:** Untested on physical LG TVs. Prebuilt Developer Mode IPK; not a stable release, Android APK or Home replacement. Follow the linked bilingual guide for pairing, checksum verification, installation, launch, renewal, update and uninstall. Please report your TV model, firmware and per-feature results. No Root or native Home Helper is installed.
