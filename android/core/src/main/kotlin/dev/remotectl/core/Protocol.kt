package dev.remotectl.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64

/** JSON codec shared with the Rust agent: snake_case keys, tolerant of new fields. */
@OptIn(ExperimentalSerializationApi::class)
internal val wireJson = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
    explicitNulls = false
}

private val b64 = Base64.getUrlEncoder().withoutPadding()
private val b64d = Base64.getUrlDecoder()
fun b64Encode(bytes: ByteArray): String = b64.encodeToString(bytes)
fun b64Decode(s: String): ByteArray = b64d.decode(s)

@Serializable data class DiskInfo(val mount: String, val total: Long, val available: Long)
@Serializable data class Battery(val percent: Int, val charging: Boolean)

@Serializable
data class Stats(
    val hostname: String,
    val os: String,
    val uptimeSecs: Long,
    val cpuPercent: Float,
    val cpuCores: List<Float>,
    val loadAvg: List<Double>,
    val memTotal: Long,
    val memUsed: Long,
    val swapTotal: Long,
    val swapUsed: Long,
    val disks: List<DiskInfo>,
    val netRxBytes: Long,
    val netTxBytes: Long,
    val battery: Battery? = null,
)

@Serializable data class ProcessInfo(val pid: Int, val name: String, val cpuPercent: Float, val memBytes: Long)
@Serializable data class AppInfo(val id: String, val name: String, val running: Boolean)

/** Phone -> agent. */
sealed class Req(private val type: String) {
    data class Auth(val pairCode: String?) : Req("auth")
    data object Ping : Req("ping")
    data class StatsStart(val intervalMs: Long) : Req("stats_start")
    data object StatsStop : Req("stats_stop")
    data object Processes : Req("processes")
    data object Apps : Req("apps")
    data class AppLaunch(val appId: String) : Req("app_launch")
    data class AppQuit(val appId: String, val force: Boolean) : Req("app_quit")
    data object Lock : Req("lock")
    data class TermOpen(val termId: Int, val cols: Int, val rows: Int) : Req("term_open")
    data class TermInput(val termId: Int, val data: ByteArray) : Req("term_input")
    data class TermResize(val termId: Int, val cols: Int, val rows: Int) : Req("term_resize")
    data class TermClose(val termId: Int) : Req("term_close")

    fun toJson(id: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        when (val r = this@Req) {
            is Auth -> if (r.pairCode != null) put("pair_code", r.pairCode)
            is StatsStart -> put("interval_ms", r.intervalMs)
            is AppLaunch -> put("app_id", r.appId)
            is AppQuit -> { put("app_id", r.appId); put("force", r.force) }
            is TermOpen -> { put("term_id", r.termId); put("cols", r.cols); put("rows", r.rows) }
            is TermInput -> { put("term_id", r.termId); put("data", b64Encode(r.data)) }
            is TermResize -> { put("term_id", r.termId); put("cols", r.cols); put("rows", r.rows) }
            is TermClose -> put("term_id", r.termId)
            else -> {}
        }
    }
}

/** Agent -> phone. */
sealed interface Res {
    data class AuthOk(val hostname: String, val os: String, val terminalEnabled: Boolean) : Res
    data class AuthErr(val reason: String) : Res
    data object Pong : Res
    data object Done : Res
    data class Error(val message: String) : Res
    data class StatsRes(val stats: Stats) : Res
    data class ProcessesRes(val processes: List<ProcessInfo>) : Res
    data class AppsRes(val apps: List<AppInfo>) : Res
    data class TermData(val termId: Int, val data: ByteArray) : Res
    data class TermExit(val termId: Int, val code: Int?) : Res
    data class Unknown(val type: String) : Res
}

@Serializable private data class ProcessesBody(val processes: List<ProcessInfo>)
@Serializable private data class AppsBody(val apps: List<AppInfo>)

/** Parses one decrypted frame into (request id, response). Pushes use id 0. */
fun parseFrame(bytes: ByteArray): Pair<Long, Res> {
    val obj = wireJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    val id = obj["id"]?.jsonPrimitive?.long ?: 0L
    val res: Res = when (val type = obj["type"]?.jsonPrimitive?.content) {
        "auth_ok" -> Res.AuthOk(
            obj["hostname"]!!.jsonPrimitive.content,
            obj["os"]!!.jsonPrimitive.content,
            obj["terminal_enabled"]!!.jsonPrimitive.boolean,
        )
        "auth_err" -> Res.AuthErr(obj["reason"]!!.jsonPrimitive.content)
        "pong" -> Res.Pong
        "done" -> Res.Done
        "error" -> Res.Error(obj["message"]!!.jsonPrimitive.content)
        "stats" -> Res.StatsRes(wireJson.decodeFromJsonElement<Stats>(obj))
        "processes" -> Res.ProcessesRes(wireJson.decodeFromJsonElement<ProcessesBody>(obj).processes)
        "apps" -> Res.AppsRes(wireJson.decodeFromJsonElement<AppsBody>(obj).apps)
        "term_data" -> Res.TermData(obj["term_id"]!!.jsonPrimitive.int, b64Decode(obj["data"]!!.jsonPrimitive.content))
        "term_exit" -> Res.TermExit(obj["term_id"]!!.jsonPrimitive.int, obj["code"]?.jsonPrimitive?.content?.toIntOrNull())
        else -> Res.Unknown(type ?: "?")
    }
    return id to res
}
