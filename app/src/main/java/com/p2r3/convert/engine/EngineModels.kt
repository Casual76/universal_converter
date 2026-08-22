package com.p2r3.convert.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One selectable format, as advertised by a single engine handler. */
@Serializable
data class FormatOption(
    val id: Int,
    /** Long, human readable description, e.g. "Portable Network Graphics". */
    val name: String,
    /** Short formal name, e.g. "PNG". */
    val format: String,
    val extension: String,
    val mime: String,
    val categories: List<String> = emptyList(),
    val from: Boolean,
    val to: Boolean,
    val lossless: Boolean = false,
    val handler: String
) {
    /** Category used for grouping in the UI, falling back to the MIME family. */
    val group: String
        get() = categories.firstOrNull() ?: mime.substringBefore('/')

    /** Text the format search matches against. */
    val searchIndex: String = "$format $name $extension $mime $handler".lowercase()
}

/** Everything the engine reports back while it works. */
sealed interface EngineEvent {
    data class Ready(val formats: List<FormatOption>, val cached: Boolean, val handlers: Int) : EngineEvent
    data class Log(val level: String, val message: String) : EngineEvent
    data class Searching(val job: String) : EngineEvent
    /** Throttled heartbeat from the pathfinder while it explores candidates. */
    data class Search(val job: String, val explored: Int, val candidate: List<String>) : EngineEvent
    data class Attempt(val job: String, val attempt: Int, val path: List<String>) : EngineEvent
    data class Step(
        val job: String,
        val step: Int,
        val steps: Int,
        val handler: String,
        val from: String,
        val to: String
    ) : EngineEvent
    data class HandlerInit(val job: String, val handler: String) : EngineEvent
    data class HandlerReady(val job: String, val handler: String) : EngineEvent
    data class DeadEnd(val job: String, val path: List<String>, val reason: String) : EngineEvent
    data class OutputBegin(val job: String, val index: Int, val name: String, val size: Long) : EngineEvent
    data class OutputEnd(val job: String, val index: Int) : EngineEvent
    data class Done(val job: String, val count: Int, val path: List<String>) : EngineEvent
    data class Failed(val job: String, val message: String) : EngineEvent
    data class Fatal(val message: String) : EngineEvent
    data object Loaded : EngineEvent
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Parses one JSON event emitted by the headless engine. */
fun parseEngineEvent(raw: String): EngineEvent? {
    val root = runCatching { lenientJson.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null
    fun string(key: String) = root[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun int(key: String) = root[key]?.jsonPrimitive?.int ?: 0
    fun strings(key: String) = root[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    return when (string("type")) {
        "ready" -> EngineEvent.Ready(
            formats = root["formats"]?.let { lenientJson.decodeFromJsonElement<List<FormatOption>>(it) }.orEmpty(),
            cached = root["cached"]?.jsonPrimitive?.boolean ?: false,
            handlers = int("handlers")
        )
        "log" -> EngineEvent.Log(string("level"), string("message"))
        "searching" -> EngineEvent.Searching(string("job"))
        "search" -> EngineEvent.Search(string("job"), int("explored"), strings("candidate"))
        "attempt" -> EngineEvent.Attempt(string("job"), int("attempt"), strings("path"))
        "step" -> EngineEvent.Step(
            string("job"), int("step"), int("steps"), string("handler"), string("from"), string("to")
        )
        "engine-init" -> EngineEvent.HandlerInit(string("job"), string("handler"))
        "engine-ready" -> EngineEvent.HandlerReady(string("job"), string("handler"))
        "dead-end" -> EngineEvent.DeadEnd(string("job"), strings("path"), string("reason"))
        "output-begin" -> EngineEvent.OutputBegin(
            string("job"), int("index"), string("name"), root["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        )
        "output-end" -> EngineEvent.OutputEnd(string("job"), int("index"))
        "done" -> EngineEvent.Done(string("job"), int("count"), strings("path"))
        "failed" -> EngineEvent.Failed(string("job"), string("message"))
        "fatal" -> EngineEvent.Fatal(string("message"))
        "loaded" -> EngineEvent.Loaded
        else -> null
    }
}

/** Header sent on the binary port right before each output payload. */
@Serializable
data class OutputHeader(val job: String, val index: Int, val name: String, val size: Long)

/** A file produced by a conversion, already sitting in the app's cache. */
data class ConvertedFile(val name: String, val path: String, val size: Long)

/** Outcome of a conversion request. */
sealed interface ConversionResult {
    data class Success(val files: List<ConvertedFile>, val path: List<String>) : ConversionResult
    data class Failure(val reason: FailureReason, val detail: String = "") : ConversionResult
}

enum class FailureReason { NoPath, Cancelled, Download, Engine }
