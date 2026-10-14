package io.github.h_sib.declared.sensors

import io.github.h_sib.declared.core.sensor.ReadResult

/**
 * Translates platform exceptions into the sealed [ReadResult], so nothing above this module ever catches an
 * Android exception. A SecurityException is a fact about this phone ("Android refused"); anything else is
 * reported as a failure and never as an empty, green result (DESIGN.md principle 2).
 */
internal inline fun <T> guarded(block: () -> T): ReadResult<T> = try {
    ReadResult.Ok(block())
} catch (e: SecurityException) {
    ReadResult.Refused("${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}")
} catch (e: Exception) {
    ReadResult.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}")
}
