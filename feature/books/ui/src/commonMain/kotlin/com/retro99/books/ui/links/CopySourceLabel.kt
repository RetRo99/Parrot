package com.retro99.books.ui.links

import androidx.compose.runtime.Composable
import com.retro99.base.server.ServerType
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.CopySource
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.library_home_this_device
import resources.translations.link_source_library

/** The name of a source in a sentence, such as "These books are both from …". */
@Composable
internal fun CopySource.label(): String = when (this) {
    CopySource.Library -> stringResource(StringRes.link_source_library)
    CopySource.Storyteller -> ServerType.Storyteller.displayName
    CopySource.Audiobookshelf -> ServerType.Audiobookshelf.displayName
}

/** The same wording the home badge uses. */
@Composable
internal fun BookHome.label(): String = when (this) {
    BookHome.ThisDevice -> stringResource(StringRes.library_home_this_device)
    BookHome.ParrotCloud -> ServerType.ParrotCloud.displayName
    BookHome.Storyteller -> ServerType.Storyteller.displayName
    BookHome.Audiobookshelf -> ServerType.Audiobookshelf.displayName
}
