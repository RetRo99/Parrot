package com.retro99.catalogue.ui.downloads

import androidx.compose.runtime.*
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityPostNotification
import platform.UIKit.UIAccessibilitySpeechAttributeQueueAnnouncement
import platform.Foundation.NSAttributedString
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun catalogueAnnouncementSender(): (String) -> Unit = remember {
    { text -> UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification,
        NSAttributedString.create(string = text, attributes = mapOf(UIAccessibilitySpeechAttributeQueueAnnouncement to true))) }
}
