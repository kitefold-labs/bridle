package com.kitefoldlabs.bridle.feature

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.app.ActivityOptionsCompat

/**
 * Answers every activity-result launch (document pickers) with the next queued result for its
 * contract input type, so screens that use the Storage Access Framework can be driven in tests.
 */
class FakeResults : ActivityResultRegistry() {
    /** Results by launch order. A `null` entry means the user cancelled the picker. */
    val queue = ArrayDeque<Any?>()
    val launched = mutableListOf<Any?>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launched += input
        @Suppress("UNCHECKED_CAST")
        dispatchResult(requestCode, queue.removeFirstOrNull() as O)
    }
}

/** Records opened links instead of starting a browser. */
class FakeUris : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
    }
}

/** Provides [results] and [uris] to [content]. */
@Composable
fun TestHost(
    results: FakeResults = FakeResults(),
    uris: FakeUris = FakeUris(),
    content: @Composable () -> Unit,
) {
    val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = results
        }
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides owner,
        LocalUriHandler provides uris,
        content = content,
    )
}
