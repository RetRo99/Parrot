package com.retro99.parrot.navigation

data class RootNavigationState(
    val backStack: List<RootDestination> = listOf(RootDestination.Splash),
    val homeEntry: RootHomeEntry? = null,
    val failedExistingServerLoginIds: Set<String> = emptySet(),
)

data class RootHomeEntry(
    val id: Long,
    val sourceScreen: String,
    val entryPoint: String,
)
