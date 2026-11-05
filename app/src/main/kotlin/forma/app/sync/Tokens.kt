package forma.app.sync

import kotlinx.serialization.Serializable

/**
 * «Токены ИИ»: what is left on the coach's DeepSeek key and what it went on, as the coach function says (it holds the
 * key; the app never does). [balance] and [limit] are the seller's units; a DeepSeek token costs [rate] of one.
 */
@Serializable
data class TokenUsage(
    val balance: Double? = null,
    val limit: Double = 0.0,
    val rate: Double = 0.1,
    val people: List<TokenPerson> = emptyList(),
    val log: List<TokenLog> = emptyList(),
) {
    /** Tokens left and the key's whole size, in DeepSeek tokens. */
    val tokensLeft: Double? get() = balance?.let { it / rate }
    val tokensAll: Double get() = limit / rate
}

/** Who of the two spent how much: all along and in the last 7 days (units). */
@Serializable
data class TokenPerson(
    val name: String,
    val me: Boolean = false,
    val requests: Int = 0,
    val units: Double = 0.0,
    val weekRequests: Int = 0,
    val weekUnits: Double = 0.0,
)

/** One of the key's last requests, as the seller logs it. */
@Serializable
data class TokenLog(
    val at: String? = null,
    val model: String? = null,
    val input: Int? = null,
    val output: Int? = null,
    val cached: Int? = null,
    val units: Double? = null,
    val ms: Int? = null,
    val status: String? = null,
    val error: String? = null,
)

/** The two the DeepSeek key is for (as the coach function's DEEPSEEK_USERS): only they see «Токены ИИ». */
val TOKEN_PEOPLE = setOf("f61a2185-036b-49fa-9252-df4b17a0ec3e", "a19c13a6-7279-45dc-ada8-0958e59fef52")
