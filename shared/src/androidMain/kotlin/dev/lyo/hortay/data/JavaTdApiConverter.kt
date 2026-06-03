package dev.lyo.hortay.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.drinkless.tdlib.TdApi
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection-based converter between Java `org.drinkless.tdlib.TdApi` objects
 * and kotlinx.serialization `JsonElement`. Enables the [AndroidTdSenderAdapter]
 * to bridge the existing JNI-based [TdClient] (Java TdApi) with the commonMain
 * [TdSender] interface (Kotlin TdApi via JSON).
 *
 * Both directions go through JSON as the interchange format — same wire format
 * that TDLib's tdjson C API uses, so field names and `@type` discriminators
 * match exactly.
 *
 * Field arrays are cached per class for amortised performance.
 */
internal object JavaTdApiConverter {

    private val fieldCache = ConcurrentHashMap<Class<*>, Array<Field>>()
    private val classCache = ConcurrentHashMap<String, Class<*>>()

    private fun instanceFields(clazz: Class<*>): Array<Field> =
        fieldCache.getOrPut(clazz) {
            clazz.fields
                .filter { !Modifier.isStatic(it.modifiers) }
                .toTypedArray()
        }

    private fun resolveClass(typeName: String): Class<*>? =
        classCache.getOrPut(typeName) {
            val javaName = typeName.replaceFirstChar { it.uppercaseChar() }
            runCatching {
                Class.forName("org.drinkless.tdlib.TdApi\$$javaName")
            }.getOrNull() ?: return null
        }

    /**
     * TDLib's tdjson wire format keys fields in snake_case; the Kotlin TdApi
     * mirror declares them via `@SerialName` (e.g. `chat_id`, `calling_codes`).
     * Java TdApi field names are camelCase (`chatId`), so both directions of the
     * round-trip must translate the key, or every multi-word field silently
     * drops to its default (empty feed, empty country list, …).
     */
    private val snakeCache = ConcurrentHashMap<String, String>()
    private fun camelToSnake(name: String): String = snakeCache.getOrPut(name) {
        buildString {
            for (c in name) {
                if (c.isUpperCase()) {
                    append('_')
                    append(c.lowercaseChar())
                } else {
                    append(c)
                }
            }
        }
    }

    // ── Java TdApi → JsonElement ────────────────────────────────────────

    fun toJson(obj: TdApi.Object): JsonElement = buildJsonObject(obj)

    private fun buildJsonObject(obj: TdApi.Object): JsonObject {
        val clazz = obj::class.java
        val typeName = clazz.simpleName.replaceFirstChar { it.lowercaseChar() }
        val map = LinkedHashMap<String, JsonElement>()
        map["@type"] = JsonPrimitive(typeName)
        for (field in instanceFields(clazz)) {
            map[camelToSnake(field.name)] = valueToJson(field.get(obj), field.type)
        }
        return JsonObject(map)
    }

    private fun valueToJson(value: Any?, declaredType: Class<*>): JsonElement = when {
        value == null -> JsonNull
        value is Boolean -> JsonPrimitive(value)
        value is Int -> JsonPrimitive(value)
        value is Long -> JsonPrimitive(value)
        value is Float -> JsonPrimitive(value)
        value is Double -> JsonPrimitive(value)
        value is String -> JsonPrimitive(value)
        value is ByteArray -> JsonPrimitive(android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP))
        value is TdApi.Object -> buildJsonObject(value)
        value is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        value is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        value is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
        value is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
        // Recurse so nested containers survive: TDLib ships fields typed
        // Array<Array<…>> / Array<IntArray> / Array<LongArray> (e.g. nested
        // vectors). The old per-element `when` fell through to
        // `elem.toString()` for any inner array, emitting a string literal
        // where the Kotlin mirror expects a JsonArray → decode threw on every
        // update carrying one, stalling the whole update pipeline.
        value is Array<*> -> JsonArray(value.map { elem -> valueToJson(elem, Any::class.java) })
        else -> JsonPrimitive(value.toString())
    }

    // ── JsonElement → Java TdApi ────────────────────────────────────────

    fun fromJson(element: JsonElement): TdApi.Object? {
        val obj = element.jsonObject
        val typeName = obj["@type"]?.jsonPrimitive?.content ?: return null
        val clazz = resolveClass(typeName) ?: return null
        val instance = clazz.getDeclaredConstructor().newInstance() as TdApi.Object
        for (field in instanceFields(clazz)) {
            val jsonValue = obj[camelToSnake(field.name)] ?: continue
            if (jsonValue is JsonNull) continue
            field.set(instance, jsonToValue(jsonValue, field.type, field.genericType))
        }
        return instance
    }

    private fun jsonToValue(
        element: JsonElement,
        targetType: Class<*>,
        genericType: java.lang.reflect.Type,
    ): Any? = when {
        element is JsonNull -> null
        targetType == Boolean::class.javaPrimitiveType || targetType == Boolean::class.java ->
            element.jsonPrimitive.boolean
        targetType == Int::class.javaPrimitiveType || targetType == Int::class.java ->
            element.jsonPrimitive.int
        targetType == Long::class.javaPrimitiveType || targetType == Long::class.java ->
            element.jsonPrimitive.long
        targetType == Float::class.javaPrimitiveType || targetType == Float::class.java ->
            element.jsonPrimitive.float
        targetType == Double::class.javaPrimitiveType || targetType == Double::class.java ->
            element.jsonPrimitive.double
        targetType == String::class.java ->
            element.jsonPrimitive.content
        targetType == ByteArray::class.java ->
            android.util.Base64.decode(element.jsonPrimitive.content, android.util.Base64.NO_WRAP)
        TdApi.Object::class.java.isAssignableFrom(targetType) ->
            fromJson(element)
        targetType == IntArray::class.java ->
            element.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        targetType == LongArray::class.java ->
            element.jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        targetType == DoubleArray::class.java ->
            element.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        targetType == BooleanArray::class.java ->
            element.jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray()
        targetType.isArray -> {
            val componentType = targetType.componentType!!
            val arr = element.jsonArray
            val result = java.lang.reflect.Array.newInstance(componentType, arr.size)
            for (i in arr.indices) {
                val elemType = if (genericType is java.lang.reflect.ParameterizedType) {
                    (genericType.actualTypeArguments.firstOrNull() as? Class<*>) ?: componentType
                } else componentType
                java.lang.reflect.Array.set(result, i, jsonToValue(arr[i], elemType, elemType))
            }
            result
        }
        else -> null
    }
}
