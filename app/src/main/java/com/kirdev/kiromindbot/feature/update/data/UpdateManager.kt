package com.kirdev.kiromindbot.feature.update.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.kirdev.kiromindbot.feature.update.domain.GitHubReleaseParser
import com.kirdev.kiromindbot.feature.update.domain.ReleaseInfo
import com.kirdev.kiromindbot.feature.update.domain.SemVer
import com.kirdev.kiromindbot.feature.update.domain.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Self-update from GitHub Releases of [REPO]. Nothing is installed silently:
 * the APK is downloaded over HTTPS, its SHA-256, package name, version and signing certificate
 * are checked against the installed app, and only then Android's own installer asks the user.
 */
class UpdateManager(private val context: Context, private val currentVersion: SemVer) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Result(val check: UpdateCheck) : State
        data class Downloading(val release: ReleaseInfo, val percent: Int) : State
        data class ReadyToInstall(val release: ReleaseInfo, val file: File) : State
        data class Failed(val reason: String) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow

    private val prefs = context.getSharedPreferences("kiromind_updates", Context.MODE_PRIVATE)

    /** Automatic checks run at most once a day; manual checks always run. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < DAY) return
        check()
    }

    suspend fun check(): UpdateCheck {
        stateFlow.value = State.Checking
        val result = try {
            val json = getJson("https://api.github.com/repos/$REPO/releases/latest")
            val (release, sumUrl) = GitHubReleaseParser.parse(json)
            when {
                release == null -> UpdateCheck.Failed("в последнем релизе нет подходящего APK или версии")
                release.version <= currentVersion -> UpdateCheck.UpToDate
                else -> {
                    val sum = release.sha256.ifEmpty { sumUrl?.let { GitHubReleaseParser.parseChecksumFile(getText(it)) }.orEmpty() }
                    if (sum.isEmpty()) UpdateCheck.Failed("у релиза ${release.tag} нет SHA-256 — установка запрещена")
                    else UpdateCheck.Available(release.copy(sha256 = sum))
                }
            }
        } catch (e: HttpStatus) {
            if (e.code == 404) UpdateCheck.Failed("в репозитории пока нет релизов") else UpdateCheck.Failed("GitHub ответил ${e.code}")
        } catch (e: IOException) {
            UpdateCheck.Failed("нет связи с GitHub")
        } catch (e: org.json.JSONException) {
            UpdateCheck.Failed("непонятный ответ GitHub")
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        stateFlow.value = State.Result(result)
        return result
    }

    /** Downloads and verifies; ends in [State.ReadyToInstall] or [State.Failed]. */
    suspend fun download(release: ReleaseInfo) {
        stateFlow.value = State.Downloading(release, 0)
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "update.apk")
        try {
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                val connection = open(release.apkUrl)
                try {
                    if (connection.responseCode != 200) throw HttpStatus(connection.responseCode)
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    if (total > MAX_APK_BYTES) throw VerifyFailure("APK слишком большой")
                    connection.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                                if (done > MAX_APK_BYTES) throw VerifyFailure("APK слишком большой")
                                if (total > 0) stateFlow.value = State.Downloading(release, (done * 100 / total).toInt())
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != release.sha256) throw VerifyFailure("контрольная сумма не совпадает — файл повреждён или подменён")
                verifyPackage(file, release)
            }
            stateFlow.value = State.ReadyToInstall(release, file)
        } catch (e: VerifyFailure) {
            file.delete(); stateFlow.value = State.Failed(e.reason)
        } catch (e: HttpStatus) {
            file.delete(); stateFlow.value = State.Failed("GitHub ответил ${e.code}")
        } catch (e: IOException) {
            file.delete(); stateFlow.value = State.Failed("загрузка прервалась")
        }
    }

    /** Same package, higher version, same signing key as the installed app. */
    private fun verifyPackage(file: File, release: ReleaseInfo) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw VerifyFailure("файл не является APK")
        if (archive.packageName != context.packageName) throw VerifyFailure("APK другого приложения (${archive.packageName})")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw VerifyFailure("версия в APK не новее установленной")
        if (archive.longVersionCode != release.version.versionCode) throw VerifyFailure("версия в APK не совпадает с тегом ${release.tag}")
        if (signers(archive) != signers(installed)) {
            throw VerifyFailure("APK подписан другим ключом. Если вы ставили отладочную сборку, удалите её и установите релиз вручную один раз")
        }
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    /** Android 8+: the user must allow this app to install updates once. */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the verified APK to Android's installer; the system shows its own confirmation. */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input -> session.openWrite("update.apk", 0, file.length()).use { input.copyTo(it); session.fsync(it) } }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        if (!GitHubReleaseParser.isTrustedUrl(url) && !url.startsWith("https://api.github.com/")) throw VerifyFailure("недоверенный адрес загрузки")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KiroMindBot/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw HttpStatus(c.responseCode)
            // Redirect targets must stay on GitHub's HTTPS hosts.
            if (!c.url.toString().startsWith("https://")) throw VerifyFailure("небезопасная переадресация")
            c.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun getJson(url: String) = JSONObject(getText(url))

    private class HttpStatus(val code: Int) : IOException("HTTP $code")
    private class VerifyFailure(val reason: String) : IOException(reason)

    companion object {
        const val REPO = "kirdev07/KiroMind-Bot__Kirdev.07"
        private const val KEY_LAST_CHECK = "last_check"
        private const val DAY = 24 * 3600_000L
        private const val MAX_APK_BYTES = 300L shl 20
    }
}

/** Receives the installer's status; asks the user to confirm when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
