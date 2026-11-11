package kr.carlog.pleos

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal

/** UTF-8 cannot preserve isolated UTF-16 surrogate code units. */
internal fun hasValidUnicodeScalars(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            Character.isHighSurrogate(char) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(char) -> return false
            else -> index++
        }
    }
    return true
}

/** Android's JSONObject accepts duplicate names, so check the token stream before parsing. */
internal fun requireUnambiguousJson(text: String, supportedVersions: Set<Int>? = null, checkEventSignalNumbers: Boolean = false) {
    try {
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun value(depth: Int) {
                require(depth <= 64) { "JSON nesting is too deep" }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val names = mutableSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(hasValidUnicodeScalars(name)) { "Invalid Unicode in JSON field" }
                            require(names.add(name)) { "Duplicate JSON field" }
                            if (depth == 0 && name == "schemaVersion" && supportedVersions != null && reader.peek() == JsonToken.NUMBER) {
                                val token = reader.nextString()
                                require(token.length <= 256) { "Schema version is too long" }
                                val number = try { BigDecimal(token) }
                                catch (e: NumberFormatException) { throw IllegalArgumentException("Invalid schema version", e) }
                                require(supportedVersions.any { number.compareTo(BigDecimal.valueOf(it.toLong())) == 0 }) { "Unsupported schema version" }
                            } else if (checkEventSignalNumbers && depth == 2 && name == "value" && reader.peek() == JsonToken.NUMBER) {
                                val token = reader.nextString()
                                require(token.length <= 1024) { "Signal value is too long" }
                                val number = token.toDoubleOrNull()
                                require(number != null && number.isFinite()) { "Invalid signal value" }
                                if (number == 0.0) {
                                    val exact = try { BigDecimal(token) }
                                    catch (e: NumberFormatException) { throw IllegalArgumentException("Invalid signal value", e) }
                                    require(exact.signum() == 0) { "Signal value underflows" }
                                }
                            } else value(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) value(depth + 1)
                        reader.endArray()
                    }
                    JsonToken.STRING -> require(hasValidUnicodeScalars(reader.nextString())) { "Invalid Unicode in JSON string" }
                    JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> throw IllegalArgumentException("Invalid JSON value")
                }
            }
            value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
        }
    } catch (e: IOException) {
        throw IllegalArgumentException("Invalid JSON", e)
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("Invalid JSON", e)
    }
}
