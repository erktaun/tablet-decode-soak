package com.ald.ra.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A request for one ingredient check: a JPEG crop of one base, plus where it came from. */
class CheckRequest(val baseId: Int, val t: Double, val jpeg: ByteArray?)

/** Probabilities per ingredient name; latency is what the call took (or took when it was first made, if cached). */
data class CheckResult(val probs: Map<String, Double>, val latencyS: Double, val tokensIn: Int, val costUsd: Double, val cached: Boolean)

/** Ingredient checker. `check` may block (it's called off the camera thread in the app). Null = no answer (offline). */
interface Checker {
    fun check(req: CheckRequest): CheckResult?
}

/**
 * OpenAI Decisions API (POST /v1/decisions, gpt-6-luna): one predicate question per menu ingredient, answered with
 * a probability. Same request shape as src/ingtrack/decisions.py. Responses are cached on disk by request hash,
 * so replays cost nothing and are deterministic.
 */
class DecisionsChecker(
    private val apiKey: String,
    private val menu: Menu,
    private val baseName: String,
    private val cacheDir: File?,
    private val model: String = "gpt-6-luna",
    private val url: String = "https://api.openai.com/v1/decisions",
    private val pricePerMIn: Double = 0.10,
    timeoutS: Long = 30,
) : Checker {
    private val http = OkHttpClient.Builder().callTimeout(timeoutS, TimeUnit.SECONDS).build()

    val context = "Overhead camera crop of one $baseName being assembled in a restaurant kitchen. Judge only the " +
        "$baseName in the centre of the image; grey areas are masked out. Hands, arms, gloves and utensils are not " +
        "ingredients. Small, low-resolution image: only answer yes for ingredients you can clearly identify."

    private fun qName(name: String) = name.replace(Regex("[^A-Za-z0-9]+"), "_")

    fun questions() = buildJsonArray {
        for (ing in menu.ingredients) add(buildJsonObject {
            put("name", qName(ing.name))
            put("type", "predicate")
            val not = ing.confusableWith?.let { " Not $it." } ?: ""
            put("instructions", "${ing.name} (${ing.look}) is visibly on the $baseName right now.$not")
        })
    }

    override fun check(req: CheckRequest): CheckResult? {
        val jpeg = req.jpeg ?: return null
        val qs = questions()
        val body = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "input_text"); put("text", context) })
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
                        })
                    })
                })
            })
            put("questions", qs)
        }
        val key = sha(JsonArray(listOf(JsonPrimitive(model), JsonPrimitive(context), qs)).toString().toByteArray() + jpeg)
        val cf = cacheDir?.let { File(it, "$key.json") }
        if (cf != null && cf.exists()) return parse(json.parseToJsonElement(cf.readText()).jsonObject, cached = true)
        val t0 = System.nanoTime()
        val resp = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        val lat = (System.nanoTime() - t0) / 1e9
        resp.use {
            if (!it.isSuccessful) throw RuntimeException("Decisions API ${it.code}: ${it.body?.string()?.take(300)}")
            val j = json.parseToJsonElement(it.body!!.string()).jsonObject
            val names = menu.ingredients.associateBy({ qName(it.name) }, { it.name })
            val rec = buildJsonObject {
                put("probs", buildJsonObject {
                    for (a in j["answers"]!!.jsonArray) {
                        val o = a.jsonObject
                        put(names[o["name"]!!.jsonPrimitive.content] ?: o["name"]!!.jsonPrimitive.content, o["probability"]!!.jsonPrimitive.double)
                    }
                })
                put("tokens_in", j["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
                put("latency_s", Math.round(lat * 1000) / 1000.0)
            }
            cf?.let { f -> f.parentFile.mkdirs(); f.writeText(rec.toString()) }
            return parse(rec, cached = false)
        }
    }

    private fun parse(o: JsonObject, cached: Boolean): CheckResult {
        val tokens = o["tokens_in"]!!.jsonPrimitive.int
        return CheckResult(o["probs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double },
            o["latency_s"]!!.jsonPrimitive.double, tokens, tokens * pricePerMIn / 1e6, cached)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * Answers recorded by the Python pipeline (outputs/<scene>/decision_calls.jsonl), looked up by crop time.
 * Lets the Kotlin engine be compared with Python on the same answers, without video decoding or API calls.
 */
class RecordedChecker(callsJsonl: File) : Checker {
    private val byT = callsJsonl.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }
        .associateBy { Math.round(it["t"]!!.jsonPrimitive.double * 10) }

    override fun check(req: CheckRequest): CheckResult? {
        val o = byT[Math.round(req.t * 10)] ?: return null
        return CheckResult(o["probs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }, o["latency_s"]!!.jsonPrimitive.double,
            o["tokens_in"]!!.jsonPrimitive.int, o["cost_usd"]!!.jsonPrimitive.double, cached = true)
    }
}
