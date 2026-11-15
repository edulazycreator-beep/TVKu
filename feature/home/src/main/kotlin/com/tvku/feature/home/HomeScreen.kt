package com.tvku.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.tvku.core.data.catalog.CatalogState
import com.tvku.core.designsystem.R as DsR
import com.tvku.core.designsystem.component.TvkuEmptyState
import com.tvku.core.designsystem.component.TvkuLogo
import com.tvku.core.designsystem.component.TvkuTextButton
import com.tvku.core.designsystem.theme.TvkuTheme
import com.tvku.core.model.Channel
import com.tvku.core.model.ChannelCategory
import com.tvku.core.player.external.OfficialPlatformLauncher
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.Locale

@Composable
fun HomeRoute(onWatch: (channelId: String) -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cannotOpen = stringResource(R.string.home_cannot_open_link)
    HomeScreen(
        state = state,
        onRetry = viewModel::refresh,
        onWatch = onWatch,
        onOpenOfficial = { channel ->
            val link = channel.officialLink ?: return@HomeScreen
            if (!OfficialPlatformLauncher.open(context, link)) Toast.makeText(context, cannotOpen, Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    onWatch: (String) -> Unit,
    onOpenOfficial: (Channel) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(TvkuTheme.colors.background)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvkuTheme.spacing.pageMargin, vertical = TvkuTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvkuLogo(symbolSize = 32.dp)
            Spacer(Modifier.weight(1f))
            if (state.isRefreshing && !state.isEmpty) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TvkuTheme.colors.textTertiary)
            }
        }

        when {
            state.isEmpty && state.isRefreshing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvkuTheme.colors.accent)
            }
            state.isEmpty -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TvkuEmptyState(
                    icon = painterResource(DsR.drawable.ic_tvku_tv),
                    title = stringResource(R.string.home_catalog_unavailable_title),
                    message = stringResource(
                        if (state.problem == CatalogState.Problem.INVALID_CATALOG) R.string.home_catalog_invalid_body
                        else R.string.home_catalog_offline_body,
                    ),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetry,
                )
                state.problemDetail?.let { detail ->
                    Text(
                        stringResource(R.string.home_error_detail, detail),
                        style = TvkuTheme.typography.bodySmall,
                        color = TvkuTheme.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = TvkuTheme.spacing.xl).widthIn(max = 420.dp),
                    )
                }
            }
            else -> ChannelList(state, onRetry, onWatch, onOpenOfficial)
        }
    }
}

@Composable
private fun ChannelList(
    state: HomeUiState,
    onRetry: () -> Unit,
    onWatch: (String) -> Unit,
    onOpenOfficial: (Channel) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = TvkuTheme.spacing.xl),
        // Test tags double as resource ids so UiAutomator end-to-end tests can find rows.
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
    ) {
        if (state.problem != null && state.showingCachedCopy) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TvkuTheme.spacing.pageMargin, vertical = TvkuTheme.spacing.xs)
                        .background(TvkuTheme.colors.surface, TvkuTheme.shapes.medium)
                        .padding(start = TvkuTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_showing_saved_list),
                        style = TvkuTheme.typography.bodySmall,
                        color = TvkuTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TvkuTextButton(stringResource(R.string.home_retry), onClick = onRetry)
                }
            }
        }
        if (state.countries.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.home_section_watch_in_app),
                    stringResource(R.string.home_section_watch_in_app_hint, state.playableCount),
                )
            }
        }
        state.countries.forEach { section ->
            item(key = "country-${section.countryCode}") { CountryHeader(section.countryCode, section.channels.size) }
            items(section.channels, key = { it.id }) { channel ->
                val stream = channel.streams.firstOrNull()
                ChannelRow(
                    channel = channel,
                    badge = if (stream != null) stream.qualityLabel ?: stringResource(R.string.home_badge_verified_stream)
                    else stringResource(R.string.home_badge_youtube_live),
                    actionDescription = stringResource(R.string.home_action_watch, channel.name),
                    onClick = { onWatch(channel.id) },
                    testTag = if (stream != null) "channel-stream" else "channel-youtube",
                )
            }
        }
        if (state.officialPlatforms.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_section_official), stringResource(R.string.home_section_official_hint)) }
            items(state.officialPlatforms, key = { it.id }) { channel ->
                val platform = channel.officialLink?.platformName.orEmpty()
                ChannelRow(
                    channel = channel,
                    badge = stringResource(R.string.home_badge_open_in, platform),
                    actionDescription = stringResource(R.string.home_action_open, channel.name, platform),
                    onClick = { onOpenOfficial(channel) },
                    external = true,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(Modifier.padding(start = TvkuTheme.spacing.pageMargin, end = TvkuTheme.spacing.pageMargin, top = TvkuTheme.spacing.lg, bottom = TvkuTheme.spacing.xs)) {
        Text(title, style = TvkuTheme.typography.titleLarge, color = TvkuTheme.colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(hint, style = TvkuTheme.typography.bodySmall, color = TvkuTheme.colors.textSecondary)
    }
}

@Composable
private fun CountryHeader(countryCode: String, count: Int) {
    val name = remember(countryCode) {
        runCatching { Locale.Builder().setRegion(countryCode).build().displayCountry }.getOrNull()?.ifBlank { null } ?: countryCode
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = TvkuTheme.spacing.pageMargin, end = TvkuTheme.spacing.pageMargin, top = TvkuTheme.spacing.md, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = TvkuTheme.typography.titleSmall, color = TvkuTheme.colors.accent, modifier = Modifier.weight(1f))
        Text(count.toString(), style = TvkuTheme.typography.labelMedium, color = TvkuTheme.colors.textTertiary)
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    badge: String,
    actionDescription: String,
    onClick: () -> Unit,
    external: Boolean = false,
    testTag: String = "channel-link",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(role = Role.Button, onClickLabel = actionDescription, onClick = onClick)
            .padding(horizontal = TvkuTheme.spacing.pageMargin, vertical = TvkuTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelMonogram(channel.name)
        Spacer(Modifier.width(TvkuTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = TvkuTheme.typography.titleMedium, color = TvkuTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${categoryLabel(channel.category)} · $badge",
                style = TvkuTheme.typography.bodySmall,
                color = TvkuTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (external) {
            Icon(painterResource(R.drawable.ic_open_external), contentDescription = null, tint = TvkuTheme.colors.textTertiary, modifier = Modifier.size(20.dp))
        } else {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = TvkuTheme.colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** Neutral initials tile: TVKu does not show broadcaster logos it has no right to use. */
@Composable
private fun ChannelMonogram(name: String) {
    val initials = remember(name) {
        name.split(' ', '-', '(').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    }
    Box(
        Modifier.size(52.dp).background(TvkuTheme.colors.surfaceElevated, TvkuTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = TvkuTheme.typography.titleMedium, color = TvkuTheme.colors.textPrimary)
    }
}

@Composable
private fun categoryLabel(category: ChannelCategory): String = stringResource(
    when (category) {
        ChannelCategory.NEWS -> R.string.category_news
        ChannelCategory.SPORTS -> R.string.category_sports
        ChannelCategory.ENTERTAINMENT -> R.string.category_entertainment
        ChannelCategory.MOVIES -> R.string.category_movies
        ChannelCategory.MUSIC -> R.string.category_music
        ChannelCategory.DOCUMENTARY -> R.string.category_documentary
        ChannelCategory.KIDS -> R.string.category_kids
        ChannelCategory.EDUCATION -> R.string.category_education
        ChannelCategory.RELIGIOUS -> R.string.category_religious
        ChannelCategory.GENERAL -> R.string.category_general
    },
)
