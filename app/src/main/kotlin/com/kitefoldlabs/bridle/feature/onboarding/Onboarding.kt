package com.kitefoldlabs.bridle.feature.onboarding

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers that the first-run introduction was seen (app-private; not part of any backup). */
@Singleton
class OnboardingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        val completed: Boolean get() = prefs.getBoolean(KEY_DONE, false)

        fun complete() = prefs.edit { putBoolean(KEY_DONE, true) }

        private companion object {
            const val FILE = "onboarding"
            const val KEY_DONE = "done"
        }
    }

/** The three intro pages. */
enum class OnboardingPage { WELCOME, KEYS, TMUX }

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val store: OnboardingStore,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(!store.completed)

        /** True while the introduction should be shown. */
        val visible: StateFlow<Boolean> = mutable.asStateFlow()

        fun finish() {
            store.complete()
            mutable.value = false
        }
    }
