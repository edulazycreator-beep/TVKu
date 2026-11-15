package com.tvku.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvku.core.designsystem.component.TvkuLogo
import com.tvku.core.designsystem.component.TvkuPrimaryButton
import com.tvku.core.designsystem.component.TvkuTextButton
import com.tvku.core.designsystem.theme.TvkuTheme
import kotlinx.coroutines.launch

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { if (finished) onFinished() }
    OnboardingScreen(onComplete = viewModel::complete)
}

@Composable
internal fun OnboardingScreen(onComplete: () -> Unit) {
    val pages = OnboardingPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(TvkuTheme.colors.background)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvkuTheme.spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            // Skipping jumps to the privacy page: the disclosure is never skipped.
            if (pagerState.currentPage < OnboardingPage.Privacy.ordinal) {
                TvkuTextButton(stringResource(R.string.onboarding_skip), onClick = {
                    scope.launch { pagerState.animateScrollToPage(OnboardingPage.Privacy.ordinal) }
                })
            } else {
                Spacer(Modifier.height(TvkuTheme.spacing.minTouchTarget))
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
            OnboardingPageContent(pages[index])
        }

        Column(
            Modifier.fillMaxWidth().padding(TvkuTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageIndicator(count = pages.size, current = pagerState.currentPage)
            Spacer(Modifier.height(TvkuTheme.spacing.lg))
            TvkuPrimaryButton(
                text = stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next),
                onClick = {
                    if (isLast) onComplete() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Box(Modifier.fillMaxSize().padding(horizontal = TvkuTheme.spacing.xl), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (page.icon == null) {
                TvkuLogo(symbolSize = 56.dp)
            } else {
                Box(
                    Modifier.size(88.dp).background(TvkuTheme.colors.surfaceElevated, TvkuTheme.shapes.extraLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(page.icon), contentDescription = null, tint = TvkuTheme.colors.accent, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(TvkuTheme.spacing.xl))
            Text(
                stringResource(page.title),
                style = TvkuTheme.typography.headlineMedium,
                color = TvkuTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(TvkuTheme.spacing.sm))
            Text(
                stringResource(page.body),
                style = TvkuTheme.typography.bodyLarge,
                color = TvkuTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    val description = stringResource(R.string.onboarding_page_indicator, current + 1, count)
    Row(
        Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 22.dp else 6.dp, tween(TvkuTheme.motion.durationMedium, easing = TvkuTheme.motion.emphasized), label = "indicatorWidth")
            val color by animateColorAsState(if (selected) TvkuTheme.colors.accent else TvkuTheme.colors.outline, label = "indicatorColor")
            Box(Modifier.height(6.dp).width(width).background(color, TvkuTheme.shapes.pill))
        }
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingPreview() {
    TvkuTheme { OnboardingScreen(onComplete = {}) }
}
