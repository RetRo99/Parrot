package com.retro99.server.implementation.source

import platform.UIKit.UIDevice

/** iOS: iPhone names itself "This iPhone", iPad "This tablet". */
internal actual fun platformSelfReferenceLabel(): String? =
    when (UIDevice.currentDevice.userInterfaceIdiom) {
        platform.UIKit.UIUserInterfaceIdiomPad -> "This tablet"
        else -> "This iPhone"
    }

