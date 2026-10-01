package com.retro99.base

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDateComponents

/**
 * iOS implementation of formatCurrentTime.
 * Uses NSDateFormatter to format time according to the user's locale and
 * 12/24 hour preference set in system settings.
 */
actual fun formatCurrentTime(): String {
    val formatter = NSDateFormatter()
    formatter.timeStyle = NSDateFormatterShortStyle
    formatter.dateStyle = NSDateFormatterNoStyle
    return formatter.stringFromDate(NSDate())
}

actual fun formatMediumDate(year: Int, month: Int, day: Int): String {
    val components = NSDateComponents().apply {
        this.year = year.toLong()
        this.month = month.toLong()
        this.day = day.toLong()
        this.hour = 12
    }
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    val date = requireNotNull(calendar.dateFromComponents(components))
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
    }
    return formatter.stringFromDate(date)
}
