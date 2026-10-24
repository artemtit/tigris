package com.leslie.tvhome

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.PersistableBundle
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Checks official stable releases; APK identity and signature are verified before installation. */
object Updates {
    data class Product(val name: String, val repo: String, val pkg: String)
    val products = listOf(Product("Tigris 桌面", "lesnolie/Tigris", "com.leslie.tvhome"),
        Product("MiracastReceiver 投屏", "weekdayjast/MiracastReceiver", "com.weekd.miracastreceiver"))
    private const val MIRACAST_CERT = "ea7f4568203d294bd37acac966d6d707f79147549d24c2244f58a6fbc27a6291"
    val executor = Executors.newSingleThreadExecutor()
    private const val CHANNEL = "software_updates"
    fun prefs(c: Context) = c.getSharedPreferences("software_updates", Context.MODE_PRIVATE)
    fun installed(c: Context, p: Product) = runCatching { c.packageManager.getPackageInfo(p.pkg, 0).versionName ?: "0.0" }.getOrNull()
    fun schedule(c: Context) {
        val scheduler = c.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(24001) != null) return
        scheduler.schedule(JobInfo.Builder(24001, ComponentName(c, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(6 * 60 * 60 * 1000L).build())
    }
    private fun connection(address: String): HttpURLConnection {
        require(address.startsWith("https://"))
        return (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "Tigris-Updater")
        }
    }
    fun check(c: Context): List<String> = products.map { p ->
        try {
            val con = connection("https://api.github.com/repos/${p.repo}/releases/latest")
            val release = try { con.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { con.disconnect() }
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
            val version = release.getString("tag_name")
            val current = installed(c, p)
            val update = current == null || UpdatePolicy.newer(version, current)
            val assets = release.getJSONArray("assets")
            val home = c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.packageName), 0).isNotEmpty()
            val items = (0 until assets.length()).map { assets.getJSONObject(it) }
            val asset = if (p.pkg == c.packageName) {
                // New releases carry the version in their filename; preserve legacy release support.
                val preferred = ReleaseAssetNames.select(version, !home, items.map { it.getString("name") })
                items.firstOrNull { it.getString("name") == preferred }
            } else {
                items.firstOrNull { it.getString("name").endsWith("-universal.apk") }
            } ?: error("发布中没有适用安装包")
            val url = asset.getString("browser_download_url")
            check(url.startsWith("https://github.com/${p.repo}/releases/download/"))
            val status = when { current == null -> "未安装 · 可安装 $version"; update -> "$current → $version"; else -> "$current · 无需更新（正式版 $version）" }
            prefs(c).edit().putString(p.pkg + ".status", status).putBoolean(p.pkg + ".available", update)
                .putString(p.pkg + ".url", url).putString(p.pkg + ".version", version)
                .putString(p.pkg + ".digest", asset.optString("digest"))
                .putLong(p.pkg + ".size", asset.getLong("size")).apply()
            if (update && current != null) {
                val key = p.pkg + ".notified"
                if (prefs(c).getString(key, "") != version) {
                    notify(c, p, "有新版本 $version，打开更新中心查看")
                    prefs(c).edit().putString(key, version).apply()
                }
            }
            "${p.name}：$status"
        } catch (e: Exception) {
            val status = "检查失败：${e.message ?: "网络不可用"}，稍后重试"
            prefs(c).edit().putString(p.pkg + ".status", status).putBoolean(p.pkg + ".available", false).apply()
            "${p.name}：$status"
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun install(c: Context, p: Product) {
        check(c.packageManager.canRequestPackageInstalls()) { "请先允许 Tigris 安装未知应用" }
        val sp = prefs(c)
        val existing = sp.getInt(p.pkg + ".session", -1)
        if (existing >= 0 && c.packageManager.packageInstaller.getSessionInfo(existing) != null)
            error("已有安装等待完成，请查看软件更新通知并确认")
        check(sp.getBoolean(p.pkg + ".available", false)) { "请先检查更新" }
        val file = File(c.cacheDir, p.pkg + "-update.apk")
        try {
            val url = sp.getString(p.pkg + ".url", "")!!
            check(url.startsWith("https://github.com/${p.repo}/releases/download/"))
            val size = sp.getLong(p.pkg + ".size", 0)
            check(size in 1..250_000_000L) { "安装包大小异常" }
            val con = connection(url)
            try {
                con.inputStream.use { input -> file.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        check(total <= size) { "下载大小超出发布信息" }; out.write(buffer, 0, n) }
                    check(total == size) { "下载不完整" }
                } }
            } finally { con.disconnect() }
            val digest = sp.getString(p.pkg + ".digest", "").orEmpty()
            if (digest.startsWith("sha256:")) {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val bytes = ByteArray(65536)
                    while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) } }
                check(hex(md.digest()) == digest.removePrefix("sha256:")) { "安装包校验失败" }
            }
            val pm = c.packageManager
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无法读取安装包")
            check(archive.packageName == p.pkg) { "安装包身份不符" }
            check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "电视系统版本不支持" }
            val old = runCatching { pm.getPackageInfo(p.pkg, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            if (old != null) check(archive.longVersionCode > old.longVersionCode) { "没有更高版本，无需安装" }
            fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
            val signatures = certs(archive)
            check(signatures.isNotEmpty()) { "缺少安装包签名" }
            if (old != null) check(signatures == certs(old)) { "签名不同，无法保留配置升级；未卸载旧版" }
            else check(p.pkg == products[1].pkg && signatures == setOf(MIRACAST_CERT)) { "不受信任的投屏签名" }
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(p.pkg)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sp.edit().putInt(p.pkg + ".session", id).apply()
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val intent = Intent(c, UpdateResult::class.java).putExtra("product", p.pkg)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(c, id, intent, flags)
                    session.commit(pending.intentSender)
                }
                sp.edit().putString(p.pkg + ".status", "安装已提交，可能需要在电视上确认").apply()
            } catch (e: Exception) { installer.abandonSession(id); sp.edit().remove(p.pkg + ".session").apply(); throw e }
        } finally { file.delete() }
    }
    fun notify(c: Context, p: Product, message: String, action: Intent? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "软件更新", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = action ?: Intent(c, UpdateActivity::class.java)
        val pending = PendingIntent.getActivity(c, p.pkg.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(p.pkg.hashCode(), Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_toggles)
            .setContentTitle(p.name).setContentText(message).setContentIntent(pending).setAutoCancel(true).build())
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updates.executor.execute {
            try {
                Updates.check(this)
                if (Updates.prefs(this).getBoolean("auto", false)) for (p in Updates.products) {
                    if (Updates.installed(this, p) != null && Updates.prefs(this).getBoolean(p.pkg + ".available", false)) {
                        runCatching { Updates.install(this, p) }.onFailure { Updates.notify(this, p, it.message ?: "更新失败") }
                    }
                }
            } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}

class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Updates.products.find { it.pkg == intent.getStringExtra("product") } ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Updates.notify(context, p, "请确认安装更新", confirmation)
            if (UpdateActivity.visible) runCatching { context.startActivity(confirmation) }
        } else {
            val message = if (status == PackageInstaller.STATUS_SUCCESS) "安装成功" else "安装未完成：${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            Updates.prefs(context).edit().remove(p.pkg + ".session").putString(p.pkg + ".status", message).putBoolean(p.pkg + ".available", false).apply()
            Updates.notify(context, p, message)
        }
    }
}
