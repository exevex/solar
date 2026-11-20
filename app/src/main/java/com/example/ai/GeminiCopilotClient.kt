package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Direct, opt-in Gemini request. API keys are passed in memory and never persisted or logged. */
object GeminiCopilotClient {
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    data class Turn(val question: String, val answer: String)

    suspend fun ask(apiKey: String, context: String, history: List<Turn>, question: String): String =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Enter a Gemini API key to use the science copilot." }
            require(question.isNotBlank()) { "Enter a question first." }

            val contents = JSONArray()
            for (turn in history.takeLast(8)) {
                contents.put(message("user", turn.question))
                contents.put(message("model", turn.answer))
            }
            contents.put(message("user", """
                You are the Solar Sandbox science copilot. Answer clearly and distinguish established physics from this app's approximations. Do not claim the simulation is an authoritative scientific instrument. Use only the supplied live state as app context; do not invent measurements. Never trigger actions or claim you changed the simulation.

                LIVE SIMULATION CONTEXT:
                $context

                USER QUESTION:
                $question
            """.trimIndent()))

            val payload = JSONObject()
                .put("contents", contents)
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.35)
                    .put("maxOutputTokens", 900))

            val connection = (URI(ENDPOINT).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching {
                        JSONObject(response).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(
                        if (detail.isNotBlank()) "Gemini request failed ($status): $detail"
                        else "Gemini request failed with HTTP $status. Check the key and connection."
                    )
                }
                extractAnswer(response)
            } finally {
                connection.disconnect()
            }
        }

    internal fun extractAnswer(response: String): String {
        val candidates = JSONObject(response).optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini returned no answer. Try again or rephrase the question.")
        val candidate = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini returned no answer. Try again.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini returned an empty answer. Try again.")
        val answer = buildString {
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(text)
                }
            }
        }.trim()
        if (answer.isBlank()) throw IllegalStateException("Gemini returned an empty answer. Try again.")
        return answer
    }

    private fun message(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))
}
