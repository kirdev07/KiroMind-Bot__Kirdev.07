package com.kirdev.kiromindbot.feature.telegram.data

import com.kirdev.kiromindbot.feature.telegram.api.BotIdentity
import com.kirdev.kiromindbot.feature.telegram.api.LocalFiles
import com.kirdev.kiromindbot.feature.telegram.api.MediaFile
import com.kirdev.kiromindbot.feature.telegram.api.MediaUnavailableException
import com.kirdev.kiromindbot.feature.telegram.api.OutgoingAction
import com.kirdev.kiromindbot.feature.telegram.api.TelegramBotApi
import com.kirdev.kiromindbot.feature.telegram.api.TelegramNetworkException
import com.kirdev.kiromindbot.feature.telegram.api.TgUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Bot API over HTTPS only (cleartext is disabled app-wide). The token lives only in
 * this object's memory and in the request URL; exceptions are rethrown without URLs.
 */
private const val CRLF = "\r\n"

class HttpTelegramBotApi(private val token: String, private val files: LocalFiles? = null) : TelegramBotApi {

    override suspend fun getMe(): BotIdentity = TelegramJson.identity(call("getMe", JSONObject(), 15))

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        val body = JSONObject().put("timeout", timeoutSeconds)
            .put("allowed_updates", org.json.JSONArray().put("message"))
        offset?.let { body.put("offset", it) }
        return TelegramJson.updates(call("getUpdates", body, timeoutSeconds + 15))
    }

    override suspend fun send(action: OutgoingAction): String? {
        val file = (action as? OutgoingAction.Media)?.file
        if (action is OutgoingAction.Media && file is MediaFile.Local) return upload(action, file)
        val (method, body) = TelegramJson.request(action)
        val result = call(method, "application/json; charset=utf-8", 30) { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return (action as? OutgoingAction.Media)?.let { TelegramJson.sentFileId(result, it.method) }
    }

    /** multipart/form-data upload, streamed from the content URI without loading it into memory. */
    private suspend fun upload(action: OutgoingAction.Media, file: MediaFile.Local): String? {
        val source = files?.open(file.uri) ?: throw MediaUnavailableException("файл недоступен")
        source.stream.use { input ->
            if ((source.size ?: 0L) > action.method.maxUploadBytes) {
                throw MediaUnavailableException("файл больше ${action.method.maxUploadBytes shr 20} МБ")
            }
            val boundary = "KiroMind" + System.nanoTime()
            val result = call(action.method.method, "multipart/form-data; boundary=$boundary", 120) { out ->
                fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) =
                    text("--$boundary$CRLF" + "Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF$value$CRLF")
                field("chat_id", action.chatId.toString())
                action.caption?.let { field("caption", it) }
                action.replyTo?.let { field("reply_parameters", JSONObject().put("message_id", it).put("allow_sending_without_reply", true).toString()) }
                // Quotes and line breaks in the file name would break the multipart header.
                val safeName = file.fileName.replace(Regex("[\"\r\n\\\\]"), "_").ifBlank { "file" }
                text("--$boundary$CRLF" +
                    "Content-Disposition: form-data; name=\"${action.method.field}\"; filename=\"$safeName\"$CRLF" +
                    "Content-Type: ${file.mimeType ?: "application/octet-stream"}$CRLF$CRLF")
                input.copyTo(out, 64 * 1024)
                text("$CRLF--$boundary--$CRLF")
            }
            return TelegramJson.sentFileId(result, action.method)
        }
    }

    private suspend fun call(method: String, body: JSONObject, readTimeoutSeconds: Int): Any =
        call(method, "application/json; charset=utf-8", readTimeoutSeconds) { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

    private suspend fun call(method: String, contentType: String, readTimeoutSeconds: Int, write: (java.io.OutputStream) -> Unit): Any =
        runInterruptible(Dispatchers.IO) {
            val connection = try {
                (URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = readTimeoutSeconds * 1000
                    doOutput = true
                    setChunkedStreamingMode(64 * 1024)
                    setRequestProperty("Content-Type", contentType)
                }
            } catch (e: IOException) {
                throw TelegramNetworkException(e::class.java.simpleName)
            }
            try {
                connection.outputStream.use(write)
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw TelegramNetworkException("EmptyResponse")
                TelegramJson.result(text)
            } catch (e: IOException) {
                throw TelegramNetworkException(e::class.java.simpleName)
            } catch (e: org.json.JSONException) {
                throw TelegramNetworkException("BadResponse")
            } finally {
                connection.disconnect()
            }
        }
}
