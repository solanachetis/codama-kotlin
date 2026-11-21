package core

/**
 * Answers JSON-RPC calls without a network. [routes] maps a method name to its reply; [fallback] answers the rest.
 * Every request body is kept in [bodies].
 */
class FakeTransport(
    private val routes: Map<String, HttpReply> = emptyMap(),
    private val fallback: HttpReply? = null,
) : RpcTransport {
    val bodies = mutableListOf<String>()

    constructor(single: HttpReply) : this(emptyMap(), single)

    override fun post(url: String, jsonBody: String): HttpReply {
        bodies += jsonBody
        val method = Regex("\"method\":\"(\\w+)\"").find(jsonBody)?.groupValues?.get(1)
        return routes[method] ?: fallback ?: error("no fake reply for $method")
    }

    companion object {
        fun ok(result: String) = HttpReply(200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
    }
}
