package com.kirdev.kiromindbot.feature.update.domain

import org.json.JSONObject

/** "1.2.3" / "v1.2" -> comparable triple. Pre-release suffixes ("-beta") are ignored. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    /** Must match the versionCode formula in app/build.gradle.kts. */
    val versionCode: Long get() = major * 10_000L + minor * 100L + patch

    companion object {
        private val pattern = Regex("^v?(\\d{1,3})(?:\\.(\\d{1,2}))?(?:\\.(\\d{1,2}))?(?:[-+].*)?$")

        fun parse(text: String): SemVer? = pattern.matchEntire(text.trim())?.let { m ->
            SemVer(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
        }
    }
}

/** A downloadable release: the APK and its published SHA-256. */
data class ReleaseInfo(
    val version: SemVer,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String
)

sealed interface UpdateCheck {
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * Reads GitHub's "latest release" JSON. Expected assets: exactly one `.apk` and either a
 * `<apk>.sha256` asset or a `SHA-256: <hex>` line in the release notes. Without a checksum the
 * release is rejected (fail closed). Only HTTPS download URLs on github.com hosts are accepted.
 */
object GitHubReleaseParser {
    private val hex64 = Regex("\\b([a-fA-F0-9]{64})\\b")

    /** Returns the release and, if the checksum lives in a separate asset, that asset's URL. */
    fun parse(json: JSONObject): Pair<ReleaseInfo?, String?> {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null to null
        val tag = json.optString("tag_name")
        val version = SemVer.parse(tag) ?: return null to null
        val assets = json.optJSONArray("assets") ?: return null to null
        val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val apk = list.singleOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null to null
        val apkUrl = apk.optString("browser_download_url")
        if (!isTrustedUrl(apkUrl)) return null to null
        val notes = json.optString("body")
        val inlineSum = Regex("SHA-?256\\s*[:=]\\s*([a-fA-F0-9]{64})", RegexOption.IGNORE_CASE).find(notes)?.groupValues?.get(1)
        val sumAsset = list.firstOrNull { it.optString("name").equals(apk.optString("name") + ".sha256", ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf(::isTrustedUrl)
        val release = ReleaseInfo(
            version = version, tag = tag, notes = notes.take(2_000),
            apkUrl = apkUrl, apkName = apk.optString("name"), apkSize = apk.optLong("size"),
            sha256 = inlineSum?.lowercase().orEmpty()
        )
        return release to (if (inlineSum == null) sumAsset else null)
    }

    /** First 64-hex token of a `.sha256` file ("<hex>  file.apk"). */
    fun parseChecksumFile(text: String): String? = hex64.find(text)?.groupValues?.get(1)?.lowercase()

    fun isTrustedUrl(url: String): Boolean =
        url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/") ||
            url.startsWith("https://release-assets.githubusercontent.com/")
}
