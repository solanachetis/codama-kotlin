package io.github.solanachetis.codamademo

import android.util.Base64
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The few JSON-RPC calls the demo needs, against a public endpoint. */
class SolanaRpc(private val endpoint: String) {

    private suspend fun call(method: String, params: JSONArray): Any = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val json = JSONObject(stream.bufferedReader().readText())
            json.optJSONObject("error")?.let { error("$method: ${it.optString("message")}") }
            json.get("result")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun balance(address: SolanaPublicKey): Long =
        (call("getBalance", JSONArray().put(address.base58())) as JSONObject).getLong("value")

    suspend fun latestBlockhash(): SolanaPublicKey {
        val value = (call("getLatestBlockhash", JSONArray()) as JSONObject).getJSONObject("value")
        return SolanaPublicKey.from(value.getString("blockhash"))
    }

    /** The account's data, or null if it doesn't exist. */
    suspend fun accountData(address: SolanaPublicKey): ByteArray? {
        val result = call("getAccountInfo", JSONArray().put(address.base58()).put(JSONObject().put("encoding", "base64"))) as JSONObject
        val value = result.optJSONObject("value") ?: return null
        return Base64.decode(value.getJSONArray("data").getString(0), Base64.DEFAULT)
    }

    suspend fun requestAirdrop(address: SolanaPublicKey, lamports: Long): String =
        call("requestAirdrop", JSONArray().put(address.base58()).put(lamports)) as String

    /** "processed", "confirmed", "finalized", "failed: …", or null while unknown. */
    suspend fun status(signature: String): String? {
        val result = call("getSignatureStatuses", JSONArray().put(JSONArray().put(signature))) as JSONObject
        val s = result.getJSONArray("value").optJSONObject(0) ?: return null
        if (!s.isNull("err")) return "failed: ${s.get("err")}"
        return s.optString("confirmationStatus").ifEmpty { null }
    }
}
