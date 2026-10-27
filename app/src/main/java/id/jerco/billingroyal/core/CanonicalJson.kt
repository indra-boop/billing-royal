package id.jerco.billingroyal.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON kanonik untuk hash payload Idempotency-Key (T10): kunci objek diurutkan (leksikografis, rekursif), tanpa spasi,
 * string di-escape secara tetap, dan angka/boolean/null ditulis sebagaimana literalnya. Hasilnya tidak bergantung pada
 * urutan kunci, `toString()` objek Kotlin, atau versi serializer.
 */
object CanonicalJson {
    fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    fun sha256Hex(element: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(canonicalize(element).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) quote(e.content, out) else out.append(e.content)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    quote(k, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c.code < 0x20 || c.code in 0x7F..0x9F || c == '\u2028' || c == '\u2029' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
