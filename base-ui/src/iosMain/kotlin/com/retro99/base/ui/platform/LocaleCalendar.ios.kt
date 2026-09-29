package com.retro99.base.ui.platform

import kotlinx.datetime.DayOfWeek
import platform.Foundation.NSCalendar

// NSCalendar numbers weekdays from 1 (Sunday) to 7 (Saturday).
actual fun firstDayOfWeek(): DayOfWeek = when (NSCalendar.currentCalendar.firstWeekday.toInt()) {
    2 -> DayOfWeek.MONDAY
    3 -> DayOfWeek.TUESDAY
    4 -> DayOfWeek.WEDNESDAY
    5 -> DayOfWeek.THURSDAY
    6 -> DayOfWeek.FRIDAY
    7 -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}
