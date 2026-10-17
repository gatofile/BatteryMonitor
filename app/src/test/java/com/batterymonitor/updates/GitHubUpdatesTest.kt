package com.batterymonitor.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File
import java.io.IOException
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitHubUpdatesTest {
    private fun release(tag: String = "v1.10.0"): JSONObject {
        val name = "BatteryMonitor-$tag.apk"
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("state", "uploaded")
                .put("size", 20).put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url", "$RELEASES$tag/$name")))
    }
    private fun rejected(json: JSONObject) { assertThrows(Exception::class.java) { parseRelease(json.toString(), "1.9.2") } }

    @Test fun `numeric version comparison handles minor major and equal versions`() {
        assertTrue(isNewerVersion("v1.10.0", "1.9.2"))
        assertTrue(isNewerVersion("v2.0.0", "1.99.99"))
        assertTrue(isNewerVersion("1.9.3", "1.9.2"))
        assertFalse(isNewerVersion("v1.10.0", "1.10.0"))
        assertFalse(isNewerVersion("v1.9.2", "1.10.0"))
        assertThrows(IOException::class.java) { isNewerVersion("latest", "1.9.2") }
        assertThrows(IOException::class.java) { isNewerVersion("v1.10.0-beta", "1.9.2") }
    }
    @Test fun `selects only the versioned APK ignoring the source zip`() {
        val json = release()
        json.getJSONArray("assets").put(JSONObject().put("name", "BatteryMonitor-v1.10.0-project.zip"))
        val result = parseRelease(json.toString(), "1.9.2")!!
        assertEquals("1.10.0", result.version)
        assertEquals("${RELEASES}v1.10.0/BatteryMonitor-v1.10.0.apk", result.url)
        assertEquals(64, result.sha256.length)
    }
    @Test fun `equal or older release is current even if assets were removed`() {
        val json = release().put("assets", JSONArray())
        assertNull(parseRelease(json.toString(), "1.10.0"))
        assertNull(parseRelease(json.toString(), "2.0.0"))
    }
    @Test fun `unfinished releases never appear as ready updates`() {
        rejected(release().put("draft", true))
        rejected(release().put("prerelease", true))
        rejected(release().put("assets", JSONArray()))
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("state", "starter") })
    }
    @Test fun `invalid checksum size and foreign downloads are rejected`() {
        listOf("", "sha256:1234", "md5:" + "a".repeat(32)).forEach { digest ->
            rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("digest", digest) })
        }
        listOf(0L, -1L, 101L * 1024 * 1024).forEach { size ->
            rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("size", size) })
        }
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/app.apk") })
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "${RELEASES}v1.9.2/BatteryMonitor-v1.9.2.apk") })
    }
    @Test fun `redirects require HTTPS and exact trusted host names`() {
        assertTrue(allowedUpdateUrl(URL("https://release-assets.githubusercontent.com/asset?token=example")))
        assertFalse(allowedUpdateUrl(URL("http://github.com/file")))
        assertFalse(allowedUpdateUrl(URL("https://github.com.evil.example/file")))
        assertFalse(allowedUpdateUrl(URL("https://github.com@evil.example/file")))
        assertFalse(allowedUpdateUrl(URL("https://github.com:444/file")))
    }
    @Test fun `corrupted APK checksum is rejected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "digest-test").apply { writeText("abc") }
        verifyDigest(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        file.appendText("tampered")
        assertThrows(IOException::class.java) { verifyDigest(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") }
    }
    @Test fun `APK identity downgrade and missing signing identity are rejected`() {
        fun info(name: String, code: Int) = PackageInfo().apply { packageName = name; versionCode = code; versionName = "1.10.0" }
        val installed = info("com.batterymonitor", 17)
        val release = parseRelease(release().toString(), "1.9.2")!!
        assertThrows(IOException::class.java) { validatePackage(installed, info("other.app", 18), release) }
        assertThrows(IOException::class.java) { validatePackage(installed, info("com.batterymonitor", 17), release) }
        assertThrows(IOException::class.java) { validatePackage(installed, info("com.batterymonitor", 18), release) }
    }
    @Test fun `matching signing identity is accepted and a different key is rejected`() {
        fun info(code: Int, signature: String) = PackageInfo().apply {
            packageName = "com.batterymonitor"; versionCode = code; versionName = "1.10.0"
            signingInfo = SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(signature))) }
        }
        val installed = info(17, "aabb")
        val release = parseRelease(release().toString(), "1.9.2")!!
        validatePackage(installed, info(18, "aabb"), release)
        assertThrows(IOException::class.java) { validatePackage(installed, info(18, "ccdd"), release) }
        assertThrows(IOException::class.java) { validatePackage(installed, info(18, "aabb").apply { versionName = "2.0.0" }, release) }
    }
    @Test fun `installer refuses files outside its update path and provider is private`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = GitHubUpdates(context)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val paths = provider.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS")!!
        paths.use { while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name == "cache-path") assertEquals("updates/", it.getAttributeValue(null, "path"))
        } }
        assertThrows(IllegalArgumentException::class.java) { repository.installIntent(File(context.cacheDir, "other.apk")) }
        assertThrows(IllegalArgumentException::class.java) { repository.installIntent(File(context.filesDir, "BatteryMonitor.apk")) }
    }
}
