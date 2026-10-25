package com.mrarron.wristclaude.phone

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mrarron.wristclaude.core.ReleaseInfo
import com.mrarron.wristclaude.core.Updates
import com.mrarron.wristclaude.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer companion (the app is sideloaded, so nothing else updates it),
 * downloads the signed APK with DownloadManager into app storage and hands it to the system installer.
 * Android only installs it over this app if it's signed with the same release key.
 */
object AppUpdater {
    private const val PREFS = "updates"
    private const val K_LAST_CHECK = "last_check"
    private const val K_NOTIFIED = "notified_version"
    private const val K_DOWNLOAD_ID = "download_id"
    private const val K_DOWNLOAD_VERSION = "download_version"
    private const val APK_TYPE = "application/vnd.android.package-archive"

    enum class Download { NONE, RUNNING, DONE, FAILED }

    data class State(
        val installed: String = "",
        val latest: ReleaseInfo? = null,
        val checking: Boolean = false,
        val error: String? = null,
        val lastCheck: Long = 0L,
        val download: Download = Download.NONE,
        /** Last version the watch app reported (versionCode scheme); null until it says. */
        val watchVersion: Int? = null,
    ) {
        val updateAvailable get() = latest != null && Updates.isNewer(latest.version, installed)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val mutex = Mutex()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    fun installedVersionCode(ctx: Context): Int =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    fun init(ctx: Context) {
        val installed = installedVersion(ctx)
        val p = prefs(ctx)
        // An APK for this version (or older) has been installed already: drop it.
        val dlVersion = p.getString(K_DOWNLOAD_VERSION, null)
        if (dlVersion != null && !Updates.isNewer(dlVersion, installed)) clearDownload(ctx)
        _state.value = State(installed = installed, lastCheck = p.getLong(K_LAST_CHECK, 0L))
        refreshDownload(ctx)
    }

    fun onWatchVersion(code: Int?) {
        _state.value = _state.value.copy(watchVersion = code)
    }

    /** Daily check (app start, watch app open); [force] = the "Check now" button. */
    suspend fun check(ctx: Context, force: Boolean = false) {
        val enabled = SettingsStore.current().updateCheck
        if (!force && (!enabled || !Updates.dueForCheck(System.currentTimeMillis(), _state.value.lastCheck))) return
        if (!mutex.tryLock()) return
        try {
            _state.value = _state.value.copy(checking = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(Updates.LATEST_URL)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "WristAssistant/" + _state.value.installed)
                        .build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) error("GitHub said ${r.code}")
                        Updates.parseLatest(r.body.string())
                    }
                }
            }
            val now = System.currentTimeMillis()
            prefs(ctx).edit { putLong(K_LAST_CHECK, now) }
            result.onSuccess { latest ->
                _state.value = _state.value.copy(checking = false, latest = latest, lastCheck = now)
                if (latest != null && _state.value.updateAvailable && enabled &&
                    prefs(ctx).getString(K_NOTIFIED, null) != latest.version
                ) {
                    if (Notifications.postUpdate(ctx, latest.version)) prefs(ctx).edit { putString(K_NOTIFIED, latest.version) }
                }
            }.onFailure {
                _state.value = _state.value.copy(checking = false, error = "Couldn't check for updates. Try again later.", lastCheck = now)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun apkFile(ctx: Context, version: String) =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "wrist-assistant-companion-$version.apk")

    /** Starts downloading the latest APK. Progress shows in the system's download notification. */
    fun download(ctx: Context) {
        val latest = _state.value.latest?.takeIf { _state.value.updateAvailable } ?: return
        clearDownload(ctx)
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(latest.apkUrl.toUri())
            .setTitle("Wrist Assistant ${latest.version}")
            .setDescription("Phone app update")
            .setMimeType(APK_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, apkFile(ctx, latest.version).name)
        val id = runCatching { dm.enqueue(req) }.getOrElse {
            _state.value = _state.value.copy(download = Download.FAILED); return
        }
        prefs(ctx).edit { putLong(K_DOWNLOAD_ID, id); putString(K_DOWNLOAD_VERSION, latest.version) }
        _state.value = _state.value.copy(download = Download.RUNNING)
    }

    /** Re-reads the download's status (the UI calls this on resume). */
    fun refreshDownload(ctx: Context): Download {
        val id = prefs(ctx).getLong(K_DOWNLOAD_ID, -1L)
        val d = if (id < 0) Download.NONE else when (status(ctx, id)) {
            DownloadManager.STATUS_SUCCESSFUL -> if (downloadedFile(ctx) != null) Download.DONE else Download.FAILED
            DownloadManager.STATUS_FAILED, null -> Download.FAILED
            else -> Download.RUNNING
        }
        _state.value = _state.value.copy(download = d)
        return d
    }

    private fun status(ctx: Context, id: Long): Int? {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
            }
        }.getOrNull()
    }

    private fun downloadedFile(ctx: Context): File? {
        val v = prefs(ctx).getString(K_DOWNLOAD_VERSION, null) ?: return null
        return apkFile(ctx, v).takeIf { it.isFile && it.length() > 0 }
    }

    /** Opens the system installer for the downloaded APK, or null if there's nothing to install. */
    fun installIntent(ctx: Context): Intent? {
        val f = downloadedFile(ctx) ?: return null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Called when DownloadManager reports a finished download. */
    fun onDownloadComplete(ctx: Context, id: Long) {
        val p = prefs(ctx)
        if (id < 0 || id != p.getLong(K_DOWNLOAD_ID, -1L)) return
        if (refreshDownload(ctx) == Download.DONE) {
            Notifications.postUpdateReady(ctx, p.getString(K_DOWNLOAD_VERSION, null).orEmpty())
        }
    }

    private fun clearDownload(ctx: Context) {
        val p = prefs(ctx)
        val id = p.getLong(K_DOWNLOAD_ID, -1L)
        if (id >= 0) runCatching { ctx.getSystemService(DownloadManager::class.java).remove(id) }
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.listFiles { f -> f.name.startsWith("wrist-assistant-companion-") }
            ?.forEach { it.delete() }
        p.edit { remove(K_DOWNLOAD_ID); remove(K_DOWNLOAD_VERSION) }
        _state.value = _state.value.copy(download = Download.NONE)
    }
}

/** DownloadManager's "finished" broadcast. Anyone could send it, so the id and status are checked first. */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        AppUpdater.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
    }
}
