package id.jerco.billingroyal.core

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * BigDecimal dari JSON number ATAU string. Teks asli dipakai langsung (tanpa konversi ke tipe floating point),
 * sehingga tidak ada kehilangan presisi. Saat encode, ditulis sebagai JSON number.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element !is JsonPrimitive || element is JsonNull) {
                throw SerializationException("Nilai uang/qty harus berupa angka atau string, bukan: $element")
            }
            element.content
        } else {
            decoder.decodeString()
        }
        return try {
            BigDecimal(text.trim())
        } catch (e: NumberFormatException) {
            throw SerializationException("Nilai uang/qty tidak valid: '$text'")
        }
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
        } else {
            encoder.encodeString(value.toPlainString())
        }
    }
}

/** Long yang toleran: menerima JSON number atau string angka (mis. id dari PHP/PDO yang berupa string). */
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val element = decoder.decodeJsonElement()
        if (element !is JsonPrimitive || element is JsonNull) {
            throw SerializationException("Angka bulat diharapkan, bukan: $element")
        }
        return element.content.trim().toLongOrNull()
            ?: try {
                BigDecimal(element.content.trim()).longValueExact()
            } catch (e: ArithmeticException) {
                throw SerializationException("Angka bulat tidak valid: '${element.content}'")
            } catch (e: NumberFormatException) {
                throw SerializationException("Angka bulat tidak valid: '${element.content}'")
            }
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}
