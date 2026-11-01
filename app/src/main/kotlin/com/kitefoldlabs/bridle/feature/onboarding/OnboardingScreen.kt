package com.kitefoldlabs.bridle.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitefoldlabs.bridle.R
import kotlinx.coroutines.launch

/**
 * Shows the first-run introduction over [content] until it is finished. The content stays
 * composed underneath, so the call-to-action callbacks can navigate straight away.
 */
@Composable
fun OnboardingGate(
    onGenerateKey: () -> Unit,
    onAddHost: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Box(if (visible) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        if (visible) {
            OnboardingScreen(
                onGenerateKey = {
                    viewModel.finish()
                    onGenerateKey()
                },
                onAddHost = {
                    viewModel.finish()
                    onAddHost()
                },
                onSkip = viewModel::finish,
            )
        }
    }
}

@Composable
fun OnboardingScreen(
    onGenerateKey: () -> Unit,
    onAddHost: () -> Unit,
    onSkip: () -> Unit,
) {
    val pages = OnboardingPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
            }
            HorizontalPager(pager, modifier = Modifier.weight(1f)) { index -> Page(pages[index]) }
            Text(
                stringResource(R.string.onboarding_page, pager.currentPage + 1, pages.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
            )
            if (pager.currentPage < pages.lastIndex) {
                Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_next)) }
            } else {
                Button(onClick = onGenerateKey, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_generate_key))
                }
                OutlinedButton(onClick = onAddHost, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_add_host))
                }
            }
        }
    }
}

@Composable
private fun Page(page: OnboardingPage) {
    val (icon, title, body) = content(page)
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

private fun content(page: OnboardingPage): Triple<ImageVector, Int, Int> =
    when (page) {
        OnboardingPage.WELCOME ->
            Triple(
                Icons.Filled.Terminal,
                R.string.onboarding_welcome_title,
                R.string.onboarding_welcome_body,
            )
        OnboardingPage.KEYS -> Triple(Icons.Filled.Key, R.string.onboarding_keys_title, R.string.onboarding_keys_body)
        OnboardingPage.TMUX ->
            Triple(
                Icons.Filled.Timelapse,
                R.string.onboarding_tmux_title,
                R.string.onboarding_tmux_body,
            )
    }
