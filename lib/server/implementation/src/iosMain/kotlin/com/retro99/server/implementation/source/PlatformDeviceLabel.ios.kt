package com.retro99.server.implementation.source

import platform.UIKit.UIDevice

internal actual fun platformDeviceLabel(): String? = UIDevice.currentDevice.model
