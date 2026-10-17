package com.batterymonitor.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal const val RELEASES = "https://github.com/gatofile/BatteryMonitor/releases/download/"
private const val LATEST = "https://api.github.com/repos/gatofile/BatteryMonitor/releases/latest"
private const val MAX_APK = 100L * 1024 * 1024

data class AppRelease(val version: String, val url: String, val bytes: Long, val sha256: String)

internal fun versionParts(value: String): List<Int>? {
    if (!Regex("v?\\d+\\.\\d+\\.\\d+").matches(value)) return null
    return value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
}

internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = versionParts(candidate) ?: throw IOException("The release version could not be read.")
    val current = versionParts(installed) ?: throw IOException("The installed version could not be read.")
    return next.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

internal fun parseRelease(json: String, installed: String): AppRelease? {
    val data = JSONObject(json)
    if (data.optBoolean("draft") || data.optBoolean("prerelease")) throw IOException("No stable release is available.")
    val tag = data.getString("tag_name")
    if (!isNewerVersion(tag, installed)) return null
    val name = "BatteryMonitor-${tag.let { if (it.startsWith('v')) it else "v$it" }}.apk"
    val assets = data.getJSONArray("assets")
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
        .singleOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
        ?: throw IOException("The new release does not have a compatible APK yet. Try again later.")
    val url = asset.getString("browser_download_url")
    if (url != "$RELEASES$tag/$name") throw IOException("The update download address is invalid.")
    val size = asset.getLong("size")
    if (size !in 1..MAX_APK) throw IOException("The update file size is invalid.")
    val digest = asset.optString("digest").removePrefix("sha256:")
    if (!Regex("[a-fA-F0-9]{64}").matches(digest)) throw IOException("The release checksum is not ready. Try again later.")
    return AppRelease(tag.removePrefix("v"), url, size, digest.lowercase())
}

internal fun allowedUpdateUrl(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
    (url.port == -1 || url.port == 443) && url.host in setOf("api.github.com", "github.com",
        "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

private fun connect(address: String): HttpURLConnection {
    var url = URL(address)
    repeat(6) {
        if (!allowedUpdateUrl(url)) throw IOException("The update server redirected to an unsupported address.")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "BatteryMonitor-Android")
            setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
        }
        val status = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
        if (status in listOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            url = URL(url, location ?: throw IOException("The update download is unavailable."))
        } else if (status == 200) return connection else {
            connection.disconnect()
            throw IOException(when (status) {
                403, 429 -> "GitHub is temporarily limiting update checks. Try again later."
                404 -> "No published update was found. Try again later."
                else -> "GitHub could not be reached (HTTP $status). Try again later."
            })
        }
    }
    throw IOException("Too many update redirects.")
}

internal fun verifyDigest(file: File, expected: String) {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    }
    val actual = hash.digest().joinToString("") { "%02x".format(it) }
    if (actual != expected.lowercase()) throw IOException("Update verification failed. Download the update again.")
}

internal fun validatePackage(installed: PackageInfo, candidate: PackageInfo, release: AppRelease) {
    if (candidate.packageName != installed.packageName || candidate.longVersionCode <= installed.longVersionCode ||
        candidate.versionName != release.version) throw IOException("The APK is not a newer compatible BatteryMonitor update.")
    val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    val incoming = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    if (current.isEmpty() || current != incoming) throw IOException("The update signature does not match this installation.")
}

class GitHubUpdates(private val context: Context) {
    @Suppress("DEPRECATION")
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val installedVersion: String get() = installed().versionName ?: "Unknown"

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = connect(LATEST)
        try {
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1024 * 1024) throw IOException("GitHub returned an invalid release response.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            parseRelease(bytes.toString(Charsets.UTF_8), installedVersion)
        } finally { connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    suspend fun download(release: AppRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(folder, "update.part")
        val apk = File(folder, "BatteryMonitor.apk")
        val connection = connect(release.url)
        try {
            var total = 0L
            var previous = -1
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > release.bytes || total > MAX_APK) throw IOException("The downloaded APK has an invalid size.")
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.bytes).toInt()
                    if (percent != previous) { previous = percent; progress(percent) }
                }
            } }
            if (total != release.bytes) throw IOException("The download was interrupted. Please try again.")
            // Verify both the GitHub digest and Android signing identity before sharing the file.
            verifyDigest(temporary, release.sha256)
            val candidate = context.packageManager.getPackageArchiveInfo(temporary.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw IOException("The downloaded APK could not be read.")
            validatePackage(installed(), candidate, release)
            if (apk.exists() && !apk.delete()) throw IOException("The previous update file could not be replaced.")
            if (!temporary.renameTo(apk)) throw IOException("The update file could not be saved.")
            apk
        } finally { connection.disconnect(); temporary.delete() }
    }

    fun installIntent(apk: File): Intent {
        require(apk.canonicalFile == File(context.cacheDir, "updates/BatteryMonitor.apk").canonicalFile && apk.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
