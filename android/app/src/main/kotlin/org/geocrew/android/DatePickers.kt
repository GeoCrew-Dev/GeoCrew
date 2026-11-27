package org.geocrew.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.geocrew.core.project.Numbers

/** The date a date field's [text] says (2026-11-27, typed full-width too), or null. */
fun dateOf(text: String): LocalDate? = runCatching { LocalDate.parse(Numbers.plain(text)) }.getOrNull()

/**
 * The moment a date-and-time field's [text] says, or null: ISO 8601 with its
 * offset (as GeoCrew stores it, in UTC), or without one, as some files keep
 * local times, which are then taken as in [zone].
 */
fun instantOf(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    val t = text.trim()
    return runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t).atZone(zone).toInstant() }.getOrNull()
}

/** [date] at [time] where the phone is, as a date-and-time field keeps it: ISO 8601 in UTC. */
fun storedDateTime(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): String =
    ZonedDateTime.of(date, time, zone).toInstant().toString()

/**
 * A date to choose, [initial] picked to begin with (today without one).
 * Material's own date dialog, made for it; on a phone in landscape a date to
 * type, in a dialog that keeps its buttons in sight beside the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val start = initial ?: LocalDate.now()
    val typed = isShortLandscape()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = if (typed) DisplayMode.Input else DisplayMode.Picker,
    )
    // Turned on its side while open, the calendar gives way to the date typed.
    LaunchedEffect(typed) { if (typed) state.displayMode = DisplayMode.Input }
    val confirm = @Composable {
        TextButton(
            onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            enabled = state.selectedDateMillis != null,
        ) { Text(stringResource(R.string.ok)) }
    }
    val dismiss = @Composable { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    if (typed) {
        AppDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pick_date)) },
            confirmButton = confirm,
            dismissButton = dismiss,
            twoPane = true,
        ) {
            DatePicker(state = state, title = null, headline = null, showModeToggle = false)
        }
    } else {
        DatePickerDialog(onDismissRequest = onDismiss, confirmButton = confirm, dismissButton = dismiss) {
            DatePicker(state = state)
        }
    }
}

/**
 * A time of day to choose, [initial] to begin with, on a 24-hour clock; on a
 * phone in landscape as hours and minutes to type, the buttons beside them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val typed = isShortLandscape()
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_time)) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        twoPane = typed,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (typed) TimeInput(state = state) else TimePicker(state = state)
        }
    }
}
