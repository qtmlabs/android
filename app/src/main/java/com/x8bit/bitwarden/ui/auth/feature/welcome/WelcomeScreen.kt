package com.x8bit.bitwarden.ui.auth.feature.welcome

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.ui.platform.base.util.EventsEffect
import com.x8bit.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.x8bit.bitwarden.ui.platform.components.button.BitwardenTextButton
import com.x8bit.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.x8bit.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.x8bit.bitwarden.ui.platform.util.isPortrait

/**
 * Top level composable for the welcome screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    onNavigateToCreateAccount: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { state.pages.size })

    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            is WelcomeEvent.UpdatePager -> {
                pagerState.animateScrollToPage(event.index)
            }

            WelcomeEvent.NavigateToCreateAccount -> onNavigateToCreateAccount()
            WelcomeEvent.NavigateToLogin -> onNavigateToLogin()
        }
    }

    BitwardenScaffold(
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        WelcomeScreenContent(
            state = state,
            pagerState = pagerState,
            onPagerSwipe = remember(viewModel) {
                { viewModel.trySendAction(WelcomeAction.PagerSwipe(it)) }
            },
            onDotClick = remember(viewModel) {
                { viewModel.trySendAction(WelcomeAction.DotClick(it)) }
            },
            onCreateAccountClick = remember(viewModel) {
                { viewModel.trySendAction(WelcomeAction.CreateAccountClick) }
            },
            onLoginClick = remember(viewModel) {
                { viewModel.trySendAction(WelcomeAction.LoginClick) }
            },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WelcomeScreenContent(
    state: WelcomeState,
    pagerState: PagerState,
    onPagerSwipe: (Int) -> Unit,
    onDotClick: (Int) -> Unit,
    onCreateAccountClick: () -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLandscape = !LocalConfiguration.current.isPortrait
    val horizontalPadding = if (isLandscape) 128.dp else 16.dp

    LaunchedEffect(pagerState.currentPage) {
        onPagerSwipe(pagerState.currentPage)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.weight(1f))

        HorizontalPager(state = pagerState) { index ->
            if (isLandscape) {
                WelcomeCardLandscape(
                    state = state.pages[index],
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            } else {
                WelcomeCardPortrait(
                    state = state.pages[index],
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        IndicatorDots(
            selectedIndexProvider = { state.index },
            totalCount = state.pages.size,
            onDotClick = onDotClick,
            modifier = Modifier
                .padding(bottom = 32.dp)
                .height(44.dp),
        )

        BitwardenFilledButton(
            label = stringResource(id = R.string.create_account),
            onClick = onCreateAccountClick,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .fillMaxWidth(),
        )

        BitwardenTextButton(
            label = stringResource(id = R.string.log_in),
            onClick = onLoginClick,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 32.dp),
        )

        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun WelcomeCardLandscape(
    state: WelcomeState.WelcomeCard,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Image(
            painter = rememberVectorPainter(id = state.imageRes),
            contentDescription = null,
            modifier = Modifier.size(132.dp),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 40.dp),
        ) {
            Text(
                text = stringResource(id = state.titleRes),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text(
                text = stringResource(id = state.messageRes),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun WelcomeCardPortrait(
    state: WelcomeState.WelcomeCard,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Image(
            painter = rememberVectorPainter(id = state.imageRes),
            contentDescription = null,
            modifier = Modifier.size(200.dp),
        )

        Text(
            text = stringResource(id = state.titleRes),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(
                    top = 48.dp,
                    bottom = 16.dp,
                ),
        )

        Text(
            text = stringResource(id = state.messageRes),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun IndicatorDots(
    selectedIndexProvider: () -> Int,
    totalCount: Int,
    onDotClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        items(totalCount) { index ->
            val color = animateColorAsState(
                targetValue = if (index == selectedIndexProvider()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
                label = "dotColor",
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.value)
                    .clickable { onDotClick(index) },
            )
        }
    }
}
