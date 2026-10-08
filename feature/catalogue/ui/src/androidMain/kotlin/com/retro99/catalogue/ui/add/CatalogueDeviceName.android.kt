package com.retro99.catalogue.ui.add

import android.content.res.Resources

actual fun catalogueDeviceName(): String =
    if (Resources.getSystem().configuration.smallestScreenWidthDp >= 600) "this tablet" else "this phone"
