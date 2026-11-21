package core

import com.solana.publickey.SolanaPublicKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** An HTTP answer: status code and body. */
data class HttpReply(val status: Int, val body: String)

/** Sends one JSON-RPC request. Swappable so tests run without a network. */
fun interface RpcTransport {
    fun post(url: String, jsonBody: String): HttpReply
}

/** Plain HTTPS POST, 15 s timeouts. */
object HttpTransport : RpcTransport {
    override fun post(url: String, jsonBody: String): HttpReply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return HttpReply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

/** The RPC server refused or failed. [rateLimited]: it asked to slow down (HTTP 429), so another server may help. */
class RpcException(message: String, val rateLimited: Boolean) : Exception(message)

/**
 * The few JSON-RPC calls a wallet app needs. Calls block: run them off the main thread. Every read asks for
 * "confirmed" data, so a change the app just made shows up without waiting for finalization.
 */
class SolanaRpc(val endpoint: String, private val transport: RpcTransport = HttpTransport) {
    private val confirmed = buildJsonObject { put("commitment", "confirmed") }
    private val base64Confirmed = buildJsonObject { put("commitment", "confirmed"); put("encoding", "base64") }

    private fun call(method: String, vararg params: JsonElement): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", JsonArray(params.toList()))
        }.toString()
        val reply = transport.post(endpoint, body)
        if (reply.status == 429) throw RpcException("$method: rate limited (HTTP 429)", rateLimited = true)
        val json = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
            ?: throw RpcException("$method: HTTP ${reply.status}", rateLimited = false)
        (json["error"] as? JsonObject)?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.intOrNull
            val message = (error["message"] as? JsonPrimitive)?.content.orEmpty()
            throw RpcException("$method: $message", rateLimited = code == 429)
        }
        if (reply.status !in 200..299) throw RpcException("$method: HTTP ${reply.status}", rateLimited = false)
        return json["result"] ?: throw RpcException("$method: no result", rateLimited = false)
    }

    private fun str(s: String) = JsonPrimitive(s)
    private fun JsonElement.value() = jsonObject["value"] ?: JsonNull
    private fun base64Data(account: JsonElement): ByteArray =
        Base64.getDecoder().decode(account.jsonObject["data"]!!.jsonArray[0].jsonPrimitive.content)

    fun balance(address: SolanaPublicKey): Long = call("getBalance", str(address.base58()), confirmed).value().jsonPrimitive.long

    fun latestBlockhash(): SolanaPublicKey =
        SolanaPublicKey.from(call("getLatestBlockhash", confirmed).value().jsonObject["blockhash"]!!.jsonPrimitive.content)

    /** The account's data, or null if it doesn't exist. */
    fun accountData(address: SolanaPublicKey): ByteArray? {
        val value = call("getAccountInfo", str(address.base58()), base64Confirmed).value()
        return if (value is JsonNull) null else base64Data(value)
    }

    /** The data of each address, in order (null when missing), in calls of at most 100 addresses. */
    fun multipleAccountsData(addresses: List<SolanaPublicKey>): List<ByteArray?> = addresses.chunked(100).flatMap { chunk ->
        val keys = buildJsonArray { chunk.forEach { add(str(it.base58())) } }
        call("getMultipleAccounts", keys, base64Confirmed).value().jsonArray.map { if (it is JsonNull) null else base64Data(it) }
    }

    /** Every token account [owner] holds under [tokenProgram], with its raw data. */
    fun tokenAccounts(owner: SolanaPublicKey, tokenProgram: SolanaPublicKey): List<Pair<SolanaPublicKey, ByteArray>> {
        val filter = buildJsonObject { put("programId", tokenProgram.base58()) }
        return call("getTokenAccountsByOwner", str(owner.base58()), filter, base64Confirmed).value().jsonArray.map { item ->
            SolanaPublicKey.from(item.jsonObject["pubkey"]!!.jsonPrimitive.content) to base64Data(item.jsonObject["account"]!!)
        }
    }

    fun minimumBalanceForRentExemption(size: Int): Long =
        call("getMinimumBalanceForRentExemption", JsonPrimitive(size), confirmed).jsonPrimitive.long

    fun requestAirdrop(address: SolanaPublicKey, lamports: Long): String =
        call("requestAirdrop", str(address.base58()), JsonPrimitive(lamports), confirmed).jsonPrimitive.content

    /** Submits a signed transaction once (the network simulates it first). Returns its signature. */
    fun sendTransaction(signed: ByteArray): String {
        val options = buildJsonObject {
            put("encoding", "base64")
            put("skipPreflight", false)
            put("preflightCommitment", "confirmed")
        }
        return call("sendTransaction", str(Base64.getEncoder().encodeToString(signed)), options).jsonPrimitive.content
    }

    /** "processed", "confirmed", "finalized", "failed: …", or null while unknown. */
    fun status(signature: String): String? {
        val s = call("getSignatureStatuses", buildJsonArray { add(str(signature)) }).value().jsonArray.firstOrNull()
        if (s == null || s is JsonNull) return null
        val err = s.jsonObject["err"]
        if (err != null && err !is JsonNull) return "failed: $err"
        return (s.jsonObject["confirmationStatus"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    }
}
