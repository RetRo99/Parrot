package com.retro99.base.ui.platform

import kotlinx.datetime.DayOfWeek
import java.util.Calendar

actual fun firstDayOfWeek(): DayOfWeek = when (Calendar.getInstance().firstDayOfWeek) {
    Calendar.MONDAY -> DayOfWeek.MONDAY
    Calendar.TUESDAY -> DayOfWeek.TUESDAY
    Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
    Calendar.THURSDAY -> DayOfWeek.THURSDAY
    Calendar.FRIDAY -> DayOfWeek.FRIDAY
    Calendar.SATURDAY -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}
