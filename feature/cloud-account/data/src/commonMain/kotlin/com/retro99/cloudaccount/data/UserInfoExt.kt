package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.model.CloudAccount
import io.github.jan.supabase.auth.user.UserInfo

internal fun UserInfo.toCloudAccount(): CloudAccount {
    return CloudAccount(
        id = id,
        email = email,
    )
}
