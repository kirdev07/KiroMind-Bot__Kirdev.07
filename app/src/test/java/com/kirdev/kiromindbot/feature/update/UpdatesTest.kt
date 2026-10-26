package com.kirdev.kiromindbot.feature.update

import com.kirdev.kiromindbot.feature.update.domain.GitHubReleaseParser
import com.kirdev.kiromindbot.feature.update.domain.SemVer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemVerTest {
    @Test fun parsesAndCompares() {
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1.0"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(2, 0, 1), SemVer.parse("v2.0.1-beta"))
        assertNull(SemVer.parse("latest"))
        assertTrue(SemVer.parse("v1.0.10")!! > SemVer.parse("v1.0.9")!!)
        assertTrue(SemVer.parse("v1.1.0")!! > SemVer.parse("1.0.99")!!)
    }

    @Test fun versionCodeMatchesGradleFormula() {
        assertEquals(10_000L, SemVer(1, 0, 0).versionCode)
        assertEquals(10_203L, SemVer(1, 2, 3).versionCode)
    }
}

class GitHubReleaseParserTest {
    private val sum = "a".repeat(64)

    private fun release(tag: String = "v1.0.1", notes: String = "", vararg assets: Pair<String, String>, draft: Boolean = false) =
        JSONObject().put("tag_name", tag).put("body", notes).put("draft", draft).put("prerelease", false)
            .put("assets", org.json.JSONArray().apply {
                assets.forEach { (name, url) -> put(JSONObject().put("name", name).put("browser_download_url", url).put("size", 1000)) }
            })

    private val apk = "KiroMind-Bot-1.0.1.apk" to "https://github.com/kirdev07/KiroMind-Bot__Kirdev.07/releases/download/v1.0.1/KiroMind-Bot-1.0.1.apk"
    private val sumAsset = "KiroMind-Bot-1.0.1.apk.sha256" to "https://github.com/kirdev07/KiroMind-Bot__Kirdev.07/releases/download/v1.0.1/KiroMind-Bot-1.0.1.apk.sha256"

    @Test fun checksumInNotes() {
        val (r, sumUrl) = GitHubReleaseParser.parse(release(notes = "Исправления\nSHA-256: ${sum.uppercase()}", assets = arrayOf(apk)))
        assertEquals(SemVer(1, 0, 1), r!!.version)
        assertEquals(sum, r.sha256)
        assertNull(sumUrl)
    }

    @Test fun checksumAsSeparateAsset() {
        val (r, sumUrl) = GitHubReleaseParser.parse(release(assets = arrayOf(apk, sumAsset)))
        assertEquals("", r!!.sha256)
        assertEquals(sumAsset.second, sumUrl)
        assertEquals(sum, GitHubReleaseParser.parseChecksumFile("$sum  KiroMind-Bot-1.0.1.apk\n"))
    }

    @Test fun rejectsUnsafeOrAmbiguous() {
        assertNull(GitHubReleaseParser.parse(release(assets = arrayOf(apk), draft = true)).first)
        assertNull(GitHubReleaseParser.parse(release(tag = "nightly", assets = arrayOf(apk))).first)
        assertNull(GitHubReleaseParser.parse(release(assets = arrayOf(apk, "second.apk" to apk.second))).first)
        assertNull(GitHubReleaseParser.parse(release(assets = arrayOf("x.apk" to "http://github.com/x.apk"))).first)
        assertNull(GitHubReleaseParser.parse(release(assets = arrayOf("x.apk" to "https://evil.example/x.apk"))).first)
    }
}
