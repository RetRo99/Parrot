package com.retro99.books.ui.detail

import com.retro99.library.domain.grouping.LibraryGroupMemberSelection
import com.retro99.library.domain.projection.LibraryBookGroup

internal fun LibraryBookGroup.toManualMergeSelections(): List<LibraryGroupMemberSelection> =
    members.map { member ->
        LibraryGroupMemberSelection(
            sourceKey = member.sourceKey,
            expectedGroupId = groupId,
        )
    }
