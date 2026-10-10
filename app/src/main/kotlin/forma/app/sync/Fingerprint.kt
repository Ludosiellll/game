package forma.app.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Short hash of a row's content. The phone and the server format the same data differently
 * (key order in jsonb, 60 vs 60.0), so the text is normalised first: sorted keys, plain numbers.
 */
internal fun contentHash(e: JsonElement): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalText(e).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}

internal fun canonicalText(e: JsonElement): String = buildString { write(e) }

private fun StringBuilder.write(e: JsonElement) {
    when (e) {
        is JsonObject -> {
            append('{')
            e.keys.sorted().forEachIndexed { i, k ->
                if (i > 0) append(',')
                append(JsonPrimitive(k).toString()).append(':')
                write(e.getValue(k))
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            e.forEachIndexed { i, x ->
                if (i > 0) append(',')
                write(x)
            }
            append(']')
        }
        JsonNull -> append("null")
        is JsonPrimitive -> if (e.isString) append(e.toString()) else append(plainNumber(e.content))
    }
}

/** 60, 60.0 and 6E+1 are the same number; booleans pass through. */
private fun plainNumber(s: String): String {
    val n = s.toBigDecimalOrNull() ?: return s
    return if (n.signum() == 0) "0" else n.stripTrailingZeros().toPlainString()
}
