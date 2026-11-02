package com.kitefoldlabs.bridle.feature

import androidx.test.core.app.ApplicationProvider
import com.kitefoldlabs.bridle.feature.onboarding.OnboardingStore
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks first-run onboarding as seen before the activity starts, for tests that walk the app's
 * tabs. Order it after the Hilt rule and before the compose rule that launches the activity.
 */
class OnboardedRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                OnboardingStore(ApplicationProvider.getApplicationContext()).complete()
                base.evaluate()
            }
        }
}
