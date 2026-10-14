package io.github.h_sib.declared.app

import androidx.activity.ComponentActivity
import io.github.h_sib.declared.core.flow.AppState
import kotlinx.coroutines.flow.StateFlow

/** Release builds carry no conformance hook. The debug source set has the real one (see src/debug). */
@Suppress("UNUSED_PARAMETER")
object Conformance {
    fun attach(activity: ComponentActivity, state: StateFlow<AppState>) = Unit

    /** A release build has no lab hook: the window follows the person's setting. */
    fun allowCapture(activity: ComponentActivity): Boolean = false

    /** A release build never opens sections by itself. */
    fun expandAll(activity: ComponentActivity): Boolean = false
}
