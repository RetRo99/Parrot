package com.retro99.books.ui.positions

import com.retro99.base.ui.BaseIntent

sealed interface PositionsIntent : BaseIntent {
    data object OnBackClicked : PositionsIntent
    data class OnRowClicked(val copyKey: String) : PositionsIntent
    data object OnUseThisPositionClicked : PositionsIntent
    data class OnTargetToggled(val copyKey: String) : PositionsIntent
    data object OnApplyClicked : PositionsIntent
    data object OnSheetDismissed : PositionsIntent
}
