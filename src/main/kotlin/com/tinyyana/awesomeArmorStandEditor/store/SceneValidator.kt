package com.tinyyana.awesomeArmorStandEditor.store

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

/** One validation error: an RFC 6901 JSON pointer into the input document plus a short English message. */
data class SceneError(val pointer: String, val message: String)

/** Thrown by [SceneValidator.parseStrict] before parsing when the text nests deeper than [SceneValidator.MAX_DEPTH]. */
class JsonTooDeepException : IllegalArgumentException(SceneValidator.TOO_DEEP)

/**
 * Scene v3 validation, mirroring the website's `src/core/validate.ts` so both sides report the same
 * first pointer for the same document:
 *
 *  0. structure — nesting deeper than [MAX_DEPTH] (checked on the text before parsing, and again on the
 *     tree) is reported at the root; any number that is not finite (`1e999` parses to Infinity) is
 *     reported at that number; if either reports anything, stop;
 *  1. schema layer — `scene.v3.schema.json` (bundled in the jar) interpreted by [SchemaInterpreter];
 *     if it reports anything, stop;
 *  2. semantic layer — unique localId, a display carries the content of its kind, animation tracks
 *     point at existing elements, unique emitter ids.
 *
 * Errors are sorted in document order, depth first (an object before its children), so the first error
 * is the one nearest the top of the file. An unknown property is reported at the property itself.
 * Pure: no Bukkit, only Gson.
 */
object SceneValidator {

    const val SCHEMA_RESOURCE = "scene.v3.schema.json"

    /** Maximum JSON nesting (an object or array counts one level; `{}` is depth 1). Same constant on the website. */
    const val MAX_DEPTH = 64
    const val TOO_DEEP = "nesting too deep (more than $MAX_DEPTH levels)"
    const val NOT_FINITE = "must be a finite number"

    private val schema: JsonObject by lazy {
        val stream = SceneValidator::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)
            ?: error("$SCHEMA_RESOURCE is missing from the plugin jar")
        stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    /** The bundled schema text (for docs / debugging). */
    fun schemaText(): String = schema.toString()

    fun validate(doc: JsonElement): List<SceneError> {
        if (treeTooDeep(doc, 0)) return listOf(SceneError("", TOO_DEEP))
        val nonFinite = mutableListOf<SceneError>()
        collectNonFinite(doc, "", nonFinite)
        if (nonFinite.isNotEmpty()) return sortByDocumentOrder(nonFinite, doc)
        val schemaErrors = SchemaInterpreter(schema).validate(doc)
        if (schemaErrors.isNotEmpty()) return sortByDocumentOrder(schemaErrors.distinct(), doc)
        return sortByDocumentOrder(semanticErrors(doc.asJsonObject), doc)
    }

    /** Parse then validate; malformed JSON is reported at the document root. */
    fun validateText(text: String): List<SceneError> {
        val doc = try {
            parseStrict(text)
        } catch (e: JsonTooDeepException) {
            return listOf(SceneError("", TOO_DEEP))
        } catch (e: Exception) {
            return listOf(SceneError("", "invalid JSON: ${e.message}"))
        }
        return validate(doc)
    }

    /**
     * Maximum nesting depth of JSON [text], by a character scan that skips string contents (and the
     * escapes inside them). Stops counting once [limit] is exceeded. Malformed text is the parser's
     * problem; this only has to bound the depth before Gson recurses into it.
     */
    fun textDepthExceeds(text: String, limit: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > limit) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    /** Same measure as [textDepthExceeds] on a parsed tree: `{}` / `[]` is depth 1. */
    private fun treeTooDeep(node: JsonElement, parentDepth: Int): Boolean {
        val depth = when (node) {
            is JsonObject, is JsonArray -> parentDepth + 1
            else -> return false
        }
        if (depth > MAX_DEPTH) return true
        return when (node) {
            is JsonObject -> node.entrySet().any { treeTooDeep(it.value, depth) }
            is JsonArray -> node.any { treeTooDeep(it, depth) }
            else -> false
        }
    }

    /** Every number that is not a finite double (Gson keeps `1e999` lazily; as a double it is Infinity). */
    private fun collectNonFinite(node: JsonElement, ptr: String, out: MutableList<SceneError>) {
        when (node) {
            is JsonArray -> node.forEachIndexed { i, child -> collectNonFinite(child, "$ptr/$i", out) }
            is JsonObject -> for ((key, child) in node.entrySet()) collectNonFinite(child, "$ptr/${escapeToken(key)}", out)
            is JsonPrimitive -> if (node.isNumber && !node.asDouble.isFinite()) out += SceneError(ptr, NOT_FINITE)
            else -> {}
        }
    }

    /**
     * Strict RFC 8259 parse (like JavaScript's JSON.parse): Gson's default parser is lenient and would
     * accept single quotes, unquoted keys, NaN or trailing garbage that the website rejects. Text that
     * nests deeper than [MAX_DEPTH] is refused with [JsonTooDeepException] before Gson sees it.
     */
    fun parseStrict(text: String): JsonElement {
        if (textDepthExceeds(text)) throw JsonTooDeepException()
        val reader = JsonReader(StringReader(text))
        reader.strictness = Strictness.STRICT
        val element = JsonParser.parseReader(reader)
        // parseReader leaves the reader lenient-agnostic about what follows; anything after the value is an error.
        reader.strictness = Strictness.STRICT
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException("unexpected data after the JSON value")
        return element
    }

    // --- semantic layer (only on schema-valid documents) ---

    private fun semanticErrors(scene: JsonObject): List<SceneError> {
        val errors = mutableListOf<SceneError>()
        val firstSeen = HashMap<Int, Int>()
        val localIds = HashSet<Int>()
        val content = linkedMapOf("ITEM" to "item", "BLOCK" to "block", "TEXT" to "text")

        scene.getAsJsonArray("elements").forEachIndexed { i, node ->
            val el = node.asJsonObject
            val localId = el.get("localId").asInt
            val prior = firstSeen[localId]
            if (prior != null) {
                errors += SceneError(pointerOf("elements", i, "localId"), "duplicate localId $localId (first used by /elements/$prior)")
            } else {
                firstSeen[localId] = i
            }
            localIds += localId

            if (el.get("type").asString != "display") return@forEachIndexed
            val kind = el.get("kind").asString
            val own = content.getValue(kind)
            if (!el.has(own) && !el.has("payload")) {
                errors += SceneError(pointerOf("elements", i), "a $kind display needs \"$own\" (or the legacy \"payload\")")
            }
            for ((k, field) in content) {
                if (k != kind && el.has(field)) {
                    errors += SceneError(pointerOf("elements", i, field), "\"$field\" is only valid when kind is $k")
                }
            }
        }

        val emitterIds = HashMap<Int, Int>()
        scene.getAsJsonArray("emitters")?.forEachIndexed { i, node ->
            val id = node.asJsonObject.get("id").asInt
            val prior = emitterIds[id]
            if (prior != null) {
                errors += SceneError(pointerOf("emitters", i, "id"), "duplicate emitter id $id (first used by /emitters/$prior)")
            } else {
                emitterIds[id] = i
            }
        }

        scene.getAsJsonObject("animation")?.getAsJsonArray("tracks")?.forEachIndexed { i, node ->
            val target = node.asJsonObject.get("elementLocalId").asInt
            if (target !in localIds) {
                errors += SceneError(pointerOf("animation", "tracks", i, "elementLocalId"), "no element with localId $target")
            }
        }
        return errors
    }

    // --- ordering ---

    /** pointer -> preorder index of every node in the document (document order, depth first). */
    private fun documentOrder(root: JsonElement): Map<String, Int> {
        val order = HashMap<String, Int>()
        var n = 0
        fun walk(node: JsonElement, ptr: String) {
            order[ptr] = n++
            when (node) {
                is JsonArray -> node.forEachIndexed { i, child -> walk(child, "$ptr/$i") }
                is JsonObject -> for ((key, child) in node.entrySet()) walk(child, "$ptr/${escapeToken(key)}")
                else -> {}
            }
        }
        walk(root, "")
        return order
    }

    private fun orderIndex(order: Map<String, Int>, pointer: String): Double {
        var p = pointer
        while (true) {
            order[p]?.let { return it + if (p == pointer) 0.0 else 0.5 }
            val cut = p.lastIndexOf('/')
            if (cut < 0) return Double.MAX_VALUE
            p = p.substring(0, cut)
        }
    }

    internal fun sortByDocumentOrder(errors: List<SceneError>, doc: JsonElement): List<SceneError> {
        val order = documentOrder(doc)
        return errors.withIndex()
            .sortedWith(compareBy<IndexedValue<SceneError>> { orderIndex(order, it.value.pointer) }.thenBy { it.index })
            .map { it.value }
    }

    /** RFC 6901 escaping of one reference token. */
    fun escapeToken(token: String): String = token.replace("~", "~0").replace("/", "~1")

    fun pointerOf(vararg tokens: Any): String = tokens.joinToString("") { "/" + escapeToken(it.toString()) }
}

/**
 * A small JSON Schema (draft 2020-12) interpreter covering exactly the keywords the scene schema uses:
 * type, enum, const, required, properties, additionalProperties(false), items, minItems, maxItems,
 * minLength, maxLength (code points), pattern, minimum, maximum, exclusiveMinimum, local `$ref`,
 * if/then/else, dependentSchemas, not, anyOf. Errors map the way the website maps ajv errors:
 * `required` and `not` at the object, `additionalProperties` at the unknown property itself,
 * everything else at the failing value.
 *
 * Interpreting the bundled schema (instead of hand-coding the rules) keeps the plugin and the website
 * on one source of truth: a schema change cannot silently leave the Kotlin validator behind.
 */
internal class SchemaInterpreter(private val root: JsonObject) {

    private val patternCache = HashMap<String, Regex>()

    fun validate(doc: JsonElement): List<SceneError> {
        val out = mutableListOf<SceneError>()
        check(root, doc, "", out)
        return out
    }

    private fun isValid(schema: JsonObject, data: JsonElement, ptr: String): Boolean {
        val scratch = mutableListOf<SceneError>()
        check(schema, data, ptr, scratch)
        return scratch.isEmpty()
    }

    private fun resolve(ref: String): JsonObject {
        require(ref.startsWith("#/")) { "only local refs are supported: $ref" }
        var node: JsonElement = root
        for (raw in ref.removePrefix("#/").split('/')) {
            val token = raw.replace("~1", "/").replace("~0", "~")
            node = node.asJsonObject.get(token) ?: error("unresolvable \$ref $ref")
        }
        return node.asJsonObject
    }

    private fun check(schema: JsonObject, data: JsonElement, ptr: String, out: MutableList<SceneError>) {
        schema.get("\$ref")?.let { check(resolve(it.asString), data, ptr, out) }

        schema.get("type")?.let { t ->
            val types = if (t.isJsonArray) t.asJsonArray.map { it.asString } else listOf(t.asString)
            if (types.none { matchesType(it, data) }) out += SceneError(ptr, "must be ${types.joinToString(" or ")}")
        }
        schema.getAsJsonArray("enum")?.let { allowed ->
            if (allowed.none { jsonEquals(it, data) }) {
                out += SceneError(ptr, "must be one of ${allowed.joinToString(", ") { it.toString() }}")
            }
        }
        schema.get("const")?.let { c -> if (!jsonEquals(c, data)) out += SceneError(ptr, "must be $c") }

        if (data is JsonPrimitive && data.isString) checkString(schema, data.asString, ptr, out)
        if (data is JsonPrimitive && data.isNumber) checkNumber(schema, data.asBigDecimal, ptr, out)
        if (data is JsonArray) checkArray(schema, data, ptr, out)
        if (data is JsonObject) checkObject(schema, data, ptr, out)

        schema.getAsJsonObject("if")?.let { cond ->
            val branch = if (isValid(cond, data, ptr)) schema.getAsJsonObject("then") else schema.getAsJsonObject("else")
            branch?.let { check(it, data, ptr, out) }
        }
        schema.getAsJsonObject("not")?.let { negated ->
            if (isValid(negated, data, ptr)) out += SceneError(ptr, schema.get("\$comment")?.asString ?: "must NOT be valid")
        }
        schema.getAsJsonArray("anyOf")?.let { options ->
            if (options.none { isValid(it.asJsonObject, data, ptr) }) out += SceneError(ptr, "must match a schema in anyOf")
        }
    }

    private fun checkString(schema: JsonObject, s: String, ptr: String, out: MutableList<SceneError>) {
        // JSON Schema counts characters as code points (ajv: ucs2length), not UTF-16 units.
        val length = s.codePointCount(0, s.length)
        schema.get("minLength")?.let { if (length < it.asInt) out += SceneError(ptr, "must NOT have fewer than ${it.asInt} characters") }
        schema.get("maxLength")?.let { if (length > it.asInt) out += SceneError(ptr, "must NOT have more than ${it.asInt} characters") }
        schema.get("pattern")?.let { p ->
            val regex = patternCache.getOrPut(p.asString) { jsRegex(p.asString) }
            if (!regex.containsMatchIn(s)) out += SceneError(ptr, "must match pattern \"${p.asString}\"")
        }
    }

    /**
     * ECMAScript `$` (no m flag) matches only at the very end; Java's also matches before a final line
     * terminator, which would let "minecraft:stone" + newline through. The schema's patterns only use
     * `$` as the final anchor, so swap that one for `\z`.
     */
    private fun jsRegex(pattern: String): Regex {
        val fixed = if (pattern.endsWith("$") && !pattern.endsWith("\\$")) pattern.dropLast(1) + "\\z" else pattern
        return Regex(fixed)
    }

    private fun checkNumber(schema: JsonObject, v: BigDecimal, ptr: String, out: MutableList<SceneError>) {
        schema.get("minimum")?.let { if (v < it.asBigDecimal) out += SceneError(ptr, "must be >= ${it.asBigDecimal}") }
        schema.get("maximum")?.let { if (v > it.asBigDecimal) out += SceneError(ptr, "must be <= ${it.asBigDecimal}") }
        schema.get("exclusiveMinimum")?.let { if (v <= it.asBigDecimal) out += SceneError(ptr, "must be > ${it.asBigDecimal}") }
    }

    private fun checkArray(schema: JsonObject, arr: JsonArray, ptr: String, out: MutableList<SceneError>) {
        schema.get("minItems")?.let { if (arr.size() < it.asInt) out += SceneError(ptr, "must have at least ${it.asInt} items") }
        schema.get("maxItems")?.let { if (arr.size() > it.asInt) out += SceneError(ptr, "must have at most ${it.asInt} items") }
        schema.getAsJsonObject("items")?.let { itemSchema ->
            arr.forEachIndexed { i, child -> check(itemSchema, child, "$ptr/$i", out) }
        }
    }

    private fun checkObject(schema: JsonObject, obj: JsonObject, ptr: String, out: MutableList<SceneError>) {
        schema.getAsJsonArray("required")?.forEach { req ->
            if (!obj.has(req.asString)) out += SceneError(ptr, "missing required property \"${req.asString}\"")
        }
        val props = schema.getAsJsonObject("properties")
        val additional = schema.get("additionalProperties")
        for ((key, value) in obj.entrySet()) {
            val childPtr = "$ptr/${SceneValidator.escapeToken(key)}"
            val propSchema = props?.getAsJsonObject(key)
            when {
                propSchema != null -> check(propSchema, value, childPtr, out)
                additional is JsonPrimitive && additional.isBoolean && !additional.asBoolean ->
                    out += SceneError(childPtr, "unknown property \"$key\"")
                additional is JsonObject -> check(additional, value, childPtr, out)
            }
        }
        schema.getAsJsonObject("dependentSchemas")?.let { deps ->
            for ((key, dep) in deps.entrySet()) if (obj.has(key)) check(dep.asJsonObject, obj, ptr, out)
        }
    }

    private fun matchesType(type: String, data: JsonElement): Boolean = when (type) {
        "object" -> data is JsonObject
        "array" -> data is JsonArray
        "string" -> data is JsonPrimitive && data.isString
        "boolean" -> data is JsonPrimitive && data.isBoolean
        "number" -> data is JsonPrimitive && data.isNumber
        // JSON Schema: 5.0 is an integer (a mathematical integer, whatever the spelling).
        "integer" -> data is JsonPrimitive && data.isNumber && isIntegral(data.asBigDecimal)
        "null" -> data.isJsonNull
        else -> error("unsupported type $type")
    }

    private fun isIntegral(v: BigDecimal): Boolean = v.signum() == 0 || v.stripTrailingZeros().scale() <= 0

    /** JSON equality where numbers compare by value (2 == 2.0), as in JavaScript. */
    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && a.isNumber && b.isNumber) {
            return a.asBigDecimal.compareTo(b.asBigDecimal) == 0
        }
        if (a is JsonArray && b is JsonArray) {
            return a.size() == b.size() && (0 until a.size()).all { jsonEquals(a[it], b[it]) }
        }
        if (a is JsonObject && b is JsonObject) {
            return a.keySet() == b.keySet() && a.keySet().all { jsonEquals(a.get(it), b.get(it)) }
        }
        return a == b
    }
}
