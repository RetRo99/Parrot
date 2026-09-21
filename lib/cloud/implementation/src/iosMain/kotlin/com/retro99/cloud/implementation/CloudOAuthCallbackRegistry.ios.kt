package com.retro99.cloud.implementation

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.stringByRemovingPercentEncoding

@OptIn(BetaInteropApi::class)
internal actual fun String.decodeCloudOAuthUrlComponent(): String {
    return NSString.create(string = this).stringByRemovingPercentEncoding() ?: this
}
