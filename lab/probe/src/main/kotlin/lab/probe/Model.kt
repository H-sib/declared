package lab.probe

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class Code { OK, REDACTED, SECURITY_EXCEPTION, UNSUPPORTED, ERROR }

/** Return this from a reading block to report a non-OK code together with a value. */
class Flagged(val code: Code, val value: Any? = null, val note: String? = null)

class Reading(
    val id: String,
    val name: String,
    val stage: Int,
    val code: Code,
    val value: Any?,
    val note: String? = null,
) {
    fun toJson(): JSONObject = jo(
        "id" to id,
        "name" to name,
        "stage" to stage,
        "code" to code.name,
        "value" to value,
        "note" to note,
    )
}

/** Runs one signal read and converts exceptions into result codes instead of crashing the run. */
fun reading(id: String, name: String, stage: Int, block: () -> Any?): Reading = try {
    val v = block()
    if (v is Flagged) Reading(id, name, stage, v.code, v.value, v.note) else Reading(id, name, stage, Code.OK, v)
} catch (e: SecurityException) {
    Reading(id, name, stage, Code.SECURITY_EXCEPTION, null, e.message)
} catch (e: Throwable) {
    val unsupported = e is NoSuchMethodError || e is NoClassDefFoundError ||
        e is NoSuchFieldError || e is UnsupportedOperationException
    Reading(id, name, stage, if (unsupported) Code.UNSUPPORTED else Code.ERROR, null, e.javaClass.simpleName + ": " + e.message)
}

fun wrap(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double -> v
    is Iterable<*> -> ja(v)
    is Map<*, *> -> JSONObject().also { m -> v.forEach { (k, x) -> m.put(k.toString(), wrap(x)) } }
    else -> v.toString()
}

fun jo(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, wrap(v))
    return o
}

fun ja(items: Iterable<*>): JSONArray {
    val a = JSONArray()
    for (i in items) a.put(wrap(i))
    return a
}

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

/** Global switches for the throwaway probe. */
object Cfg {
    /** When true, package names and other identifiers are replaced by short hashes (lab fixtures stay readable). */
    @Volatile
    var redact: Boolean = false
}

/** Redacts an identifier when redaction is on. Fixture packages (prefix "lab.") are never redacted. */
fun rd(s: String?): String? =
    if (s == null || !Cfg.redact || s.contains("lab.fixture.") || s.contains("lab.probe")) s
    else "h:" + sha256Hex(s.toByteArray()).take(10)
