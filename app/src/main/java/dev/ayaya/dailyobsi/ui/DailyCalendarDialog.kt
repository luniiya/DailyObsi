package dev.ayaya.dailyobsi.ui

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCalendarDialog(
    availableDates: Set<LocalDate>,
    displayedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val availableEpochDays = availableDates.mapTo(mutableSetOf()) { it.toEpochDay() }
    val selectable = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
            utcMillisToDate(utcTimeMillis).toEpochDay() in availableEpochDays
    }
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = null,
        initialDisplayedMonthMillis = displayedDate.atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
        selectableDates = selectable,
    )
    LaunchedEffect(picker.selectedDateMillis) {
        picker.selectedDateMillis?.let { millis -> onSelect(utcMillisToDate(millis)) }
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    ) {
        DatePicker(
            state = picker,
            title = null,
            headline = null,
            showModeToggle = false,
        )
    }
}

private fun utcMillisToDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
