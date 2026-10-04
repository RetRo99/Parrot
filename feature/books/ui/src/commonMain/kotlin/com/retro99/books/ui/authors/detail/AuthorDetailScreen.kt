package com.retro99.books.ui.authors.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.general_back

@Composable
fun AuthorDetailScreen(
    authorUuid: String,
    authorName: String,
    onNavigateToBookDetail: (book: BookUiModel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorDetailViewModel = koinViewModel {
        parametersOf(authorUuid, authorName, onNavigateToBookDetail, onBack)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            else -> AuthorDetailScreenContent(
                viewState = viewState,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorDetailScreenContent(
    viewState: AuthorDetailViewState,
    intentDispatcher: IntentDispatcher<AuthorDetailIntent>,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            EmberTopBar(
                title = viewState.authorName,
                onBack = { intentDispatcher(AuthorDetailIntent.OnBackClicked) },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = viewState.isRefreshing,
            onRefresh = { intentDispatcher(AuthorDetailIntent.OnRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = viewState.books,
                    key = { it.uuid },
                ) { book ->
                    AuthorBookItem(
                        book = book,
                        onClick = { intentDispatcher(AuthorDetailIntent.OnBookClicked(book)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorBookItem(
    book: BookUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoilImage(
                data = book.coverUrl,
                cacheKey = book.uuid,
                modifier = Modifier
                    .size(width = 80.dp, height = 120.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = book.title,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (book.series.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = book.series.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    book.statusName?.let { status ->
                        StatusChip(status = status)
                    }

                    if (book.hasEbook) {
                        MediaTypeIndicator(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            label = stringResource(StringRes.books_media_ebook),
                        )
                    }

                    if (book.hasAudiobook) {
                        MediaTypeIndicator(
                            icon = Icons.Outlined.Headphones,
                            label = stringResource(StringRes.books_media_audio),
                        )
                    }

                    if (book.hasReadaloud) {
                        MediaTypeIndicator(
                            icon = Icons.Outlined.RecordVoiceOver,
                            label = stringResource(StringRes.books_media_readaloud),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
) {
    SuggestionChip(
        onClick = { },
        label = {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun MediaTypeIndicator(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

