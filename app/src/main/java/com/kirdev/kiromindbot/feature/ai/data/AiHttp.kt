package com.kirdev.kiromindbot.feature.ai.data

import com.kirdev.kiromindbot.feature.engine.domain.AiFailure
import com.kirdev.kiromindbot.feature.engine.domain.AiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Prompt shared by every provider so the bot sounds the same whichever one is active. */
object AiPrompt {
    const val SYSTEM =
        "Ты — KiroMind Bot, дружелюбный бот в Telegram. Тебя спрашивают, когда в базе ответов владельца нет подходящего ответа. " +
            "Отвечай кратко, 1–3 предложения, простым текстом без Markdown, на языке собеседника. " +
            "Не выдумывай факты о владельце бота и его делах; если не знаешь — так и скажи."

    /** User turn with optional history (only present when the owner allowed sharing context). */
    fun userText(request: AiRequest): String {
        if (request.history.isEmpty()) return request.text
        return "Предыдущие реплики собеседника:\n" + request.history.takeLast(5).joinToString("\n") { "- $it" } +
            "\n\n" + request.text
    }
}

/**
 * Minimal JSON-over-HTTPS client for providers without an SDK in this app.
 * Refuses non-HTTPS URLs; errors become [AiFailure] with a short reason and never include
 * the URL, headers, key or message text.
 */
object AiHttp {
    fun requireHttps(url: String) {
        if (!url.startsWith("https://")) throw AiFailure("адрес должен начинаться с https://")
    }

    /** Retries once after a short pause on transient server errors (500/502/503), common on free tiers. */
    suspend fun send(
        provider: String,
        url: String,
        headers: Map<String, String>,
        body: JSONObject?,
        timeoutSeconds: Int = 20
    ): JSONObject = try {
        sendOnce(provider, url, headers, body, timeoutSeconds)
    } catch (e: TransientFailure) {
        kotlinx.coroutines.delay(2_000)
        try { sendOnce(provider, url, headers, body, timeoutSeconds) } catch (again: TransientFailure) { throw AiFailure(again.reason) }
    }

    private class TransientFailure(val reason: String) : Exception(reason)

    private suspend fun sendOnce(
        provider: String,
        url: String,
        headers: Map<String, String>,
        body: JSONObject?,
        timeoutSeconds: Int
    ): JSONObject = runInterruptible(Dispatchers.IO) {
        requireHttps(url)
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = 10_000
                readTimeout = timeoutSeconds * 1000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        } catch (e: IOException) {
            throw AiFailure("нет связи с $provider")
        }
        try {
            body?.let { b -> connection.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in setOf(500, 502, 503)) throw TransientFailure(describe(provider, code))
            if (code !in 200..299) throw AiFailure(describe(provider, code))
            JSONObject(text)
        } catch (e: IOException) {
            throw AiFailure("нет связи с $provider")
        } catch (e: JSONException) {
            throw AiFailure("непонятный ответ $provider")
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun describe(provider: String, code: Int): String = when (code) {
        400 -> "запрос отклонён (400) — проверьте модель"
        401 -> "ключ не принят (401)"
        403 -> "у ключа нет доступа (403)"
        404 -> "модель или адрес не найдены (404)"
        429 -> "лимит провайдера, попробуйте позже (429)"
        in 500..599 -> "сервис временно недоступен ($code)"
        else -> "ошибка ($code)"
    }
}
