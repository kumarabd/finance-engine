package org.nighthawklabs.treasure.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.nighthawklabs.treasure.data.ApiJson

sealed interface Api<out T> {
    data class Ok<T>(val value: T) : Api<T>
    data object Unauthorized : Api<Nothing>
    /** The router has no instance for this user yet. */
    data object NotProvisioned : Api<Nothing>
    /** Couldn't reach the engine, or it is busy or restarting (offline, 408, 429, 502-504). The same request is safe to resend. */
    data class Retry(val message: String) : Api<Nothing>
    /** The engine answered 500: probably transient, but possibly a bug that will repeat. Resend a few times, then give up. */
    data class ServerError(val message: String) : Api<Nothing>
    /** The engine understood and said no; [code] is its machine-readable reason (invalid_input, conflict, ...). */
    data class Failed(val code: String?, val message: String) : Api<Nothing>
}

/** A short, human line for a failed call; null when it succeeded. */
val Api<*>.problem: String?
    get() = when (this) {
        is Api.Ok -> null
        Api.Unauthorized -> "Session expired. Sign in again."
        Api.NotProvisioned -> "Your finance service isn't set up yet."
        is Api.Retry -> "Couldn't reach Treasure: $message"
        is Api.ServerError -> "Treasure had a problem ($message). Try again in a moment."
        is Api.Failed -> message
    }

fun <T, U> Api<T>.map(f: (T) -> U): Api<U> = when (this) {
    is Api.Ok -> Api.Ok(f(value))
    Api.Unauthorized -> Api.Unauthorized
    Api.NotProvisioned -> Api.NotProvisioned
    is Api.Retry -> this
    is Api.ServerError -> this
    is Api.Failed -> this
}

/** This result re-typed, when it isn't a success; null for a success. */
@Suppress("UNCHECKED_CAST")
fun <U> Api<*>.failure(): Api<U>? = if (this is Api.Ok) null else this as Api<U>

/** One POST per engine operation. Returns null when the network failed before any response. */
fun interface Transport {
    suspend fun post(url: String, token: String, body: String, timeoutMs: Int): Pair<Int, String>?
}

/** What the stores talk to; tests swap in a fake. */
interface EngineApi {
    suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>): Api<O>
}

suspend inline fun <reified I, reified O> EngineApi.call(op: String, input: I): Api<O> =
    call(op, input, serializer<I>(), serializer<O>())

/** Every operation is `POST <router>/finance/api/v1/operations/<name>` with a JSON object body. */
class FinanceApi(private val baseUrl: String, private val transport: Transport = UrlTransport) {
    suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>, token: String): Api<O> {
        val body = runCatching { ApiJson.encodeToString(inSer, input) }.getOrElse { return Api.Failed(null, "Bad request") }
        val (code, text) = transport.post("${baseUrl.trimEnd('/')}/operations/$op", token, body, 30_000)
            ?: return Api.Retry("network error")
        return classify(code, text) { ApiJson.decodeFromString(outSer, it) }
    }

    companion object {
        fun <T> classify(code: Int, text: String, parse: (String) -> T): Api<T> = when {
            code in 200..299 -> runCatching { Api.Ok(parse(text)) }.getOrElse { Api.Retry("unreadable response") }
            code == 401 || code == 403 -> Api.Unauthorized
            code == 404 && text.contains("no_tenant") -> Api.NotProvisioned
            code == 408 || code == 429 || code in 502..504 -> Api.Retry("server returned $code")
            code >= 500 -> Api.ServerError("server returned $code")
            else -> {
                val (c, m) = errorOf(text)
                Api.Failed(c, m ?: "The server returned $code.")
            }
        }

        // Engine errors are {"error":{"code","message"}}; the router's are {"error":"..."}.
        fun errorOf(text: String): Pair<String?, String?> = runCatching {
            when (val e = ApiJson.parseToJsonElement(text).jsonObject["error"]) {
                is JsonObject -> e["code"]?.jsonPrimitive?.contentOrNull to e["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> null to e.contentOrNull
                else -> null to null
            }
        }.getOrDefault(null to null)
    }
}

/**
 * The engine for ONE user: fetches the Clerk token, and retries once with a fresh one on 401.
 *
 * It is bound to the user it was created for. The auth state is shared, so a sync still running for user A after the phone
 * switched to user B would otherwise pick up B's token and write A's data into B's account. Every call checks that the
 * signed-in user is still [owner], and fails as unauthorized (sending nothing) when it isn't.
 */
class Engine(
    private val api: FinanceApi,
    val owner: String,
    private val currentUser: () -> String?,
    private val token: suspend (skipCache: Boolean) -> String?,
) : EngineApi {
    override suspend fun <I, O> call(op: String, input: I, inSer: KSerializer<I>, outSer: KSerializer<O>): Api<O> {
        if (currentUser() != owner) return Api.Unauthorized
        val t = token(false) ?: return Api.Unauthorized
        if (currentUser() != owner) return Api.Unauthorized
        val r = api.call(op, input, inSer, outSer, t)
        if (r is Api.Unauthorized && currentUser() == owner) {
            token(true)?.let { fresh -> if (currentUser() == owner) return api.call(op, input, inSer, outSer, fresh) }
        }
        return r
    }
}
