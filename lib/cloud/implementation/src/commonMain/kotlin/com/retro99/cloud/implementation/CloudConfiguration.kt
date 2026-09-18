package com.retro99.cloud.implementation

data class CloudConfiguration(
    val supabaseUrl: String,
    val publishableKey: String,
    val redirectScheme: String = "parrot",
    val redirectHost: String = "auth/callback",
) {
    val redirectUrl: String
        get() = "$redirectScheme://$redirectHost"

    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && publishableKey.isNotBlank()

    companion object {
        const val SUPABASE_URL_METADATA_KEY = "com.retro99.parrot.SUPABASE_URL"
        const val SUPABASE_PUBLISHABLE_KEY_METADATA_KEY =
            "com.retro99.parrot.SUPABASE_PUBLISHABLE_KEY"
        const val SUPABASE_URL_INFO_KEY = "ParrotSupabaseUrl"
        const val SUPABASE_PUBLISHABLE_KEY_INFO_KEY = "ParrotSupabasePublishableKey"
    }
}
