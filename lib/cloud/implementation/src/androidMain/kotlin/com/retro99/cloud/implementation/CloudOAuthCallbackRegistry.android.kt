package com.retro99.cloud.implementation

import java.net.URLDecoder

internal actual fun String.decodeCloudOAuthUrlComponent(): String {
    return URLDecoder.decode(this, "UTF-8")
}
