package com.retro99.catalogue.ui.add

import platform.UIKit.UIDevice

actual fun catalogueDeviceName(): String =
    if (UIDevice.currentDevice.userInterfaceIdiom == platform.UIKit.UIUserInterfaceIdiomPad) "this tablet" else "this iPhone"
