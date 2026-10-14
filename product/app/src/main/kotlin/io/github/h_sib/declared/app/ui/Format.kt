package io.github.h_sib.declared.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** A moment in the phone's own language and time zone, never the machine form (`2026-10-08T21:37:13.688423Z`) a person cannot read. */
internal fun formatMoment(at: Instant): String = dateTime.format(at)
