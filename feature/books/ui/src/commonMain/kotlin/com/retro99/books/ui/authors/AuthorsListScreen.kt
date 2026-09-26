package com.retro99.books.ui.authors

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.ParrotEmptyState
import com.retro99.books.ui.authors.model.AuthorListUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.authors_empty_subtitle
import resources.translations.authors_empty_title

@Composable
fun AuthorsListScreen(
    onNavigateToAuthorDetail: (author: AuthorListUiModel) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AuthorsListViewModel = koinViewModel { parametersOf(onNavigateToAuthorDetail) },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        AuthorsListScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorsListScreenContent(
    viewState: AuthorsListViewState,
    intentDispatcher: IntentDispatcher<AuthorsListIntent>,
    modifier: Modifier = Modifier,
) {
    when {
        viewState.isLoading -> LoadingScreen()
        viewState.authors.isEmpty() -> EmptyAuthorsState(modifier = modifier)
        else -> PullToRefreshBox(
            isRefreshing = viewState.isRefreshing,
            onRefresh = { intentDispatcher(AuthorsListIntent.OnRefresh) },
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    items = viewState.authors,
                    key = { _, author -> author.uuid },
                ) { index, author ->
                    AnimatedAuthorItem(
                        author = author,
                        index = index,
                        onClick = {
                            intentDispatcher(AuthorsListIntent.OnAuthorClicked(author))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAuthorsState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ParrotEmptyState(
            title = stringResource(StringRes.authors_empty_title),
            message = stringResource(StringRes.authors_empty_subtitle),
            icon = Icons.Filled.Person,
        )
    }
}

@Composable
private fun AnimatedAuthorItem(
    author: AuthorListUiModel,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible.value = true
    }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * 50,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = index * 50,
            ),
            initialOffsetY = { it / 4 },
        ),
    ) {
        AuthorItem(
            author = author,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun AuthorItem(
    author: AuthorListUiModel,
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
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Author avatar placeholder
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // Author info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = author.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                author.fileAs?.let { fileAs ->
                    if (fileAs != author.name) {
                        Text(
                            text = fileAs,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Chevron indicator
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

