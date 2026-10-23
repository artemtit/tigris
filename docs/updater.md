# 更新中心

入口：主屏幕设置 → 软件更新与投屏。默认自动检查，自动下载安装尝试可单独开启。

只读取 lesnolie/Tigris 与 weekdayjast/MiracastReceiver 的 GitHub latest 正式发布，不读取任意仓库、不执行远程脚本。Tigris 自动选择 Standard/Guard；投屏用 universal APK，保留独立应用数据和官方签名。

启动检查按六小时限频，JobScheduler 每六小时在联网时检查，实际时机由系统调度；关机、休眠、强制停止、无网络时不能保证准时。未安装投屏组件时由用户从更新中心安装，不因后台检查自动新增应用。

下载限制 250 MB，校验发布大小与可用的 SHA-256 digest；安装前确认包名、minSdk、versionCode 递增，以及已安装证书一致。首次投屏安装固定验证官方 v1.2.7 证书 SHA-256：ea7f4568203d294bd37acac966d6d707f79147549d24c2244f58a6fbc27a6291。签名轮换需维护者审核，不能通过卸载绕过。

使用 Android PackageInstaller，安卓是最终安装/签名验证方。自动模式提交安装时请求无需用户操作；系统不批准静默更新时发通知让用户确认。电视需允许 Tigris 安装未知应用。失败信息保留在更新中心，不宣称提交成功等于安装成功。活跃安装会话阻止重复下载。发布签名必须固定，否则 Tigris 自更新会被拒绝且保留原配置。

MiracastReceiver 以官方 APK 独立安装，本仓库不复制或重新签名其实现。上游：https://github.com/weekdayjast/MiracastReceiver （MIT）。
