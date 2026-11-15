package com.tvku.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tvku.core.designsystem.R as DsR
import com.tvku.core.designsystem.component.TvkuEmptyState
import com.tvku.core.designsystem.component.TvkuLogo
import com.tvku.core.designsystem.theme.TvkuTheme

/**
 * Home shell. The channel catalog is connected in milestone M7; until then the
 * screen states plainly that no channels are available rather than showing
 * placeholder channels.
 */
@Composable
fun HomeRoute() {
    HomeScreen()
}

@Composable
internal fun HomeScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(TvkuTheme.colors.background)
            .safeDrawingPadding(),
    ) {
        TvkuLogo(
            modifier = Modifier.padding(horizontal = TvkuTheme.spacing.pageMargin, vertical = TvkuTheme.spacing.md),
            symbolSize = 32.dp,
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            TvkuEmptyState(
                icon = painterResource(DsR.drawable.ic_tvku_tv),
                title = stringResource(R.string.home_catalog_unavailable_title),
                message = stringResource(R.string.home_catalog_unavailable_body),
            )
        }
    }
}



@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    TvkuTheme { HomeScreen() }
}
