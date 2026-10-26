package com.kirdev.kiromindbot.feature.ai

import com.kirdev.kiromindbot.feature.ai.data.*
import com.kirdev.kiromindbot.feature.engine.domain.AiFailure
import com.kirdev.kiromindbot.feature.engine.domain.AiRequest
import com.kirdev.kiromindbot.feature.security.SecretRead
import com.kirdev.kiromindbot.feature.security.SecretStore
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private class Captured { var url = ""; var headers = emptyMap<String, String>(); var body: JSONObject? = null }

private fun fakeHttp(c: Captured, response: String): suspend (String, Map<String, String>, JSONObject?) -> JSONObject =
    { u, h, b -> c.url = u; c.headers = h; c.body = b; JSONObject(response) }

class GeminiTest {
    @Test fun requestAndReply() = runBlocking {
        val c = Captured()
        val p = GeminiProvider({ "KEY123456789012345678" }, { "gemini-flash-latest" },
            fakeHttp(c, """{"candidates":[{"content":{"parts":[{"text":"думаю","thought":true},{"text":"Восемь планет."}]}}]}"""))
        assertEquals("Восемь планет.", p.complete(AiRequest("Сколько планет?")))
        assertTrue(c.url.endsWith("/models/gemini-flash-latest:generateContent"))
        assertEquals("KEY123456789012345678", c.headers["x-goog-api-key"])
        assertFalse(c.url.contains("KEY"))   // key goes in a header, not the URL
        assertFalse(c.body.toString().contains("KEY"))
        assertTrue(c.body!!.getJSONObject("systemInstruction").toString().contains("KiroMind"))
    }

    @Test fun blockedPromptIsFailure() {
        val e = assertThrows(AiFailure::class.java) { GeminiProvider.parse(JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")) }
        assertTrue(e.reason.contains("фильтрами"))
    }
}

class YandexTest {
    @Test fun requestAndReply() = runBlocking {
        val c = Captured()
        val p = YandexGptProvider({ "AQVNkey000000000000000" }, { "b1gfolder" }, { "yandexgpt-lite/latest" },
            fakeHttp(c, """{"result":{"alternatives":[{"message":{"role":"assistant","text":"Привет!"},"status":"ALTERNATIVE_STATUS_FINAL"}]}}"""))
        assertEquals("Привет!", p.complete(AiRequest("Привет")))
        assertEquals("gpt://b1gfolder/yandexgpt-lite/latest", c.body!!.getString("modelUri"))
        assertEquals("Api-Key AQVNkey000000000000000", c.headers["Authorization"])
        assertEquals("b1gfolder", c.headers["x-folder-id"])
    }

    @Test fun folderRequired() {
        val p = YandexGptProvider({ "k" }, { " " }, { "m" }, fakeHttp(Captured(), "{}"))
        val e = assertThrows(AiFailure::class.java) { runBlocking { p.complete(AiRequest("x")) } }
        assertTrue(e.reason.contains("каталога"))
    }

    @Test fun contentFilter() {
        assertThrows(AiFailure::class.java) {
            YandexGptProvider.parse(JSONObject("""{"result":{"alternatives":[{"message":{"text":""},"status":"ALTERNATIVE_STATUS_CONTENT_FILTER"}]}}"""))
        }
    }
}

class OpenAiCompatibleTest {
    @Test fun requestAndReply() = runBlocking {
        val c = Captured()
        val p = OpenAiCompatibleProvider({ "gsk_key0000000000000000" }, { "https://api.groq.com/openai/v1/" }, { "llama-3.3-70b-versatile" },
            fakeHttp(c, """{"choices":[{"message":{"role":"assistant","content":" Ответ "},"finish_reason":"stop"}]}"""))
        assertEquals("Ответ", p.complete(AiRequest("Вопрос", listOf("раньше"))))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", c.url)
        assertEquals("Bearer gsk_key0000000000000000", c.headers["Authorization"])
        assertTrue(c.body!!.getJSONArray("messages").getJSONObject(1).getString("content").contains("раньше"))
    }

    @Test fun cleartextRefused() {
        val e = assertThrows(AiFailure::class.java) { OpenAiCompatibleProvider.endpoint("http://192.168.1.5:8080/v1", "models") }
        assertTrue(e.reason.contains("https"))
    }
}

class AiRouterTest {
    private val store = object : SecretStore {
        val map = mutableMapOf("gemini_api_key" to "G-KEY-000000000000000000")
        override suspend fun save(name: String, secret: String) = map.put(name, secret).let { true }
        override suspend fun read(name: String) = map[name]?.let { SecretRead.Value(it) } ?: SecretRead.Missing
        override suspend fun delete(name: String) { map.remove(name) }
    }

    @Test fun followsSelectedProviderAndKeys() = runBlocking {
        var prefs = AppPreferences(aiProvider = "GEMINI")
        val router = AiRouter(store) { prefs }
        assertEquals("Gemini", router.name)
        prefs = prefs.copy(aiProvider = "YANDEX")
        assertEquals("YandexGPT", router.name)
        prefs = prefs.copy(aiProvider = "SOMETHING_OLD")
        assertEquals(AiKind.GEMINI, router.active)
        router.refreshKeys()
        assertEquals(setOf(AiKind.GEMINI), router.keys.value)
        router.deleteKey(AiKind.GEMINI)
        assertTrue(router.keys.value.isEmpty())
    }

    @Test fun badKeysRejectedBeforeAnyRequest() = runBlocking {
        val router = AiRouter(store) { AppPreferences() }
        assertNotNull(router.saveKey(AiKind.GEMINI, "short"))
        assertNotNull(router.saveKey(AiKind.CLAUDE, "x".repeat(40)))
        assertNotNull(router.saveKey(AiKind.OPENAI, "has space inside the key value"))
        assertFalse(store.map.containsKey("openai_compat_api_key"))
    }
}

class ModelListTest {
    @Test fun openAiListHidesNonChatAndInactive() {
        val ids = OpenAiCompatibleProvider.parseModels(JSONObject("""{"data":[
            {"id":"llama-3.1-8b-instant","active":true},{"id":"whisper-large-v3"},{"id":"old-model","active":false},
            {"id":"openai/gpt-oss-20b"},{"id":"llama-guard-4-12b"}]}"""))
        assertEquals(listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b"), ids)
    }

    @Test fun geminiListKeepsTextModelsOnly() {
        val ids = GeminiProvider.parseModels(JSONObject("""{"models":[
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""))
        assertEquals(listOf("gemini-2.5-flash"), ids)
    }
}
