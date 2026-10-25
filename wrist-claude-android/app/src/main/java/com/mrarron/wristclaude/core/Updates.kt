package com.mrarron.wristclaude.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A published companion release: version "2.3.0", the signed APK and its release page. */
data class ReleaseInfo(val version: String, val apkUrl: String, val pageUrl: String)

/**
 * Update check for the sideloaded companion: GitHub's "latest release" of the repo, whose
 * `wrist-assistant-companion-<version>.apk` asset is built and signed by release.yml.
 * Pure Kotlin so it can be unit-tested.
 */
object Updates {
    const val REPO = "MrArron/Wrist-Assistant"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    /** Only APKs from this repo's own release downloads are fetched. */
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    /** "v2.3.0" or "2.3.0" -> [2, 3, 0]; null for anything else (pre-release suffixes included). */
    fun parseVersion(v: String): List<Int>? {
        val parts = v.trim().removePrefix("v").split('.')
        if (parts.size != 3) return null
        return parts.map { p -> p.toIntOrNull()?.takeIf { it >= 0 && p.all(Char::isDigit) } ?: return null }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return true
        for (i in 0..2) if (r[i] != l[i]) return r[i] > l[i]
        return false
    }

    /** The versionCode scheme in build.gradle.kts and the watch's APP_VERSION_CODE: 2.3.0 -> 230. */
    fun versionCode(v: String): Int? = parseVersion(v)?.let { (a, b, c) -> a * 100 + b * 10 + c }

    /** 230 -> "2.3.0" (for messages only). */
    fun versionName(code: Int): String = "${code / 100}.${code / 10 % 10}.${code % 10}"

    fun dueForCheck(now: Long, lastCheck: Long): Boolean = lastCheck <= 0 || now - lastCheck >= CHECK_EVERY_MS || now < lastCheck

    /** Parses GitHub's release JSON. Null for drafts, pre-releases, odd tags or a release without the companion APK. */
    fun parseLatest(json: String): ReleaseInfo? {
        val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        fun str(obj: JsonObject, k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        if ((o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = str(o, "tag_name") ?: return null
        val version = tag.removePrefix("v")
        if (parseVersion(version) == null) return null
        val wanted = "wrist-assistant-companion-$version.apk"
        val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { str(it, "name") == wanted }
            ?.let { str(it, "browser_download_url") }
            ?.takeIf { it.startsWith(DOWNLOAD_PREFIX, ignoreCase = true) && it.endsWith("/$wanted") }
            ?: return null
        val page = str(o, "html_url")?.takeIf { it.startsWith("https://github.com/$REPO/", ignoreCase = true) } ?: RELEASES_PAGE
        return ReleaseInfo(version, apk, page)
    }
}
