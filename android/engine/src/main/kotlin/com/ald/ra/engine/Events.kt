package com.ald.ra.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * One event. `t` is when it happened in the stream, `knownT` when a live system knows it (after API latency or
 * after the wait a rule needs). Same schema as the Python events.jsonl, so the two can be compared line by line.
 */
data class Event(val t: Double, val knownT: Double, val kind: String, val data: Map<String, Any?> = emptyMap()) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>("t" to JsonPrimitive(r2(t)), "known_t" to JsonPrimitive(r2(knownT)),
            "kind" to JsonPrimitive(kind)) + data.mapValues { toEl(it.value) })

    companion object {
        fun r2(v: Double) = Math.round(v * 100) / 100.0

        fun toEl(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Double -> JsonPrimitive(Math.round(v * 1e6) / 1e6)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toEl(it.value) })
            is Iterable<*> -> JsonArray(v.map { toEl(it) })
            else -> JsonPrimitive(v.toString())
        }
    }
}

/** Collects events; listeners (the UI) see each one as it's added. */
class EventLog {
    val events = mutableListOf<Event>()
    val listeners = mutableListOf<(Event) -> Unit>()

    fun add(t: Double, kind: String, knownT: Double = t, vararg data: Pair<String, Any?>) {
        val e = Event(t, knownT, kind, linkedMapOf(*data))
        events += e
        listeners.forEach { it(e) }
    }

    fun sorted() = events.sortedWith(compareBy({ it.knownT }, { it.t }))

    fun write(f: File) {
        f.parentFile?.mkdirs()
        f.writeText(sorted().joinToString("") { it.toJson().toString() + "\n" })
    }
}
