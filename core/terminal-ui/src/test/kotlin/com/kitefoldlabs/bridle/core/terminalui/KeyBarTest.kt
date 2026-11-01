package com.kitefoldlabs.bridle.core.terminalui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.kitefoldlabs.bridle.core.terminal.Key
import com.kitefoldlabs.bridle.core.terminal.KeyAction
import com.kitefoldlabs.bridle.core.terminal.KeyBarLayout
import com.kitefoldlabs.bridle.core.terminal.ModifierState
import com.kitefoldlabs.bridle.core.terminal.Modifiers
import com.kitefoldlabs.bridle.core.terminal.StickyModifier
import com.kitefoldlabs.bridle.core.terminal.StickyState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyBarTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<KeyAction>()
    private val haptics = mutableListOf<HapticFeedbackType>()

    private fun show(
        layout: KeyBarLayout = KeyBarLayout.DEFAULT,
        sticky: StickyState = StickyState(),
    ) {
        val feedback =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics += hapticFeedbackType
                }
            }
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                MaterialTheme { KeyBar(layout, sticky, { actions += it }) }
            }
        }
    }

    private fun stateIs(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun `tapping a key sends its action with haptic feedback`() {
        show()
        rule.onNodeWithContentDescription("Escape").performClick()
        actions shouldContainExactly listOf(KeyAction.Press(Key.Escape))
        haptics shouldContainExactly listOf(HapticFeedbackType.VirtualKey)
    }

    @Test
    fun `swiping up sends the alternate`() {
        show()
        rule.onNodeWithContentDescription("Up arrow").performTouchInput { swipeUp() }
        actions shouldContainExactly listOf(KeyAction.Press(Key.PageUp))
        haptics shouldContainExactly listOf(HapticFeedbackType.VirtualKey)
    }

    @Test
    fun `swiping up on a key without an alternate taps it`() {
        show()
        rule.onNodeWithContentDescription("Escape").performTouchInput { swipeUp() }
        actions shouldContainExactly listOf(KeyAction.Press(Key.Escape))
    }

    @Test
    fun `dragging down cancels the press`() {
        show()
        rule.onNodeWithContentDescription("Up arrow").performTouchInput { swipeDown() }
        actions.shouldBeEmpty()
    }

    @Test
    fun `keys are buttons for accessibility, with the alternate as a custom action`() {
        show()
        val up = rule.onNodeWithContentDescription("Up arrow")
        up.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        up.performSemanticsAction(SemanticsActions.OnClick)
        up.performCustomAccessibilityActionWithLabel("Swipe up: Page up")
        rule.onNodeWithContentDescription("Tab").performCustomAccessibilityActionWithLabel("Swipe up: Shift Tab")
        actions shouldContainExactly
            listOf(KeyAction.Press(Key.Up), KeyAction.Press(Key.PageUp), KeyAction.Press(Key.Tab, Modifiers.SHIFT))
        rule
            .onNodeWithContentDescription(
                "Escape",
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
    }

    @Test
    fun `sticky keys show and announce their state`() {
        show(sticky = StickyState(ctrl = ModifierState.ONCE, alt = ModifierState.LOCKED))
        rule.onNodeWithContentDescription("Control").assert(stateIs("On for the next key"))
        rule.onNodeWithContentDescription("Alt").assert(stateIs("Locked")).performClick()
        actions shouldContainExactly listOf(KeyAction.Toggle(StickyModifier.ALT))
        rule
            .onNodeWithContentDescription(
                "Escape",
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `keys follow sticky changes`() {
        var sticky by mutableStateOf(StickyState())
        rule.setContent { MaterialTheme { KeyBar(KeyBarLayout.DEFAULT, sticky, { actions += it }) } }
        rule.onNodeWithContentDescription("Control").assert(stateIs("Off"))
        sticky = StickyState(ctrl = ModifierState.LOCKED)
        rule.onNodeWithContentDescription("Control").assert(stateIs("Locked"))
        rule.onNodeWithContentDescription("Up arrow").performTouchInput { swipeUp() }
        rule.onNodeWithContentDescription("Escape").performClick()
        actions shouldContainExactly listOf(KeyAction.Press(Key.PageUp), KeyAction.Press(Key.Escape))
    }

    @Test
    fun `released sticky keys read as off`() {
        show()
        rule.onNodeWithContentDescription("Control").assert(stateIs("Off"))
    }

    @Test
    fun `the Claude layout describes chords and macros`() {
        show(KeyBarLayout.CLAUDE)
        rule.onNodeWithContentDescription("Control C").performClick()
        rule.onNodeWithContentDescription("/compact, Enter").performClick()
        rule
            .onNodeWithContentDescription(
                "Escape",
            ).performCustomAccessibilityActionWithLabel("Swipe up: Escape, Escape")
        actions shouldContainExactly
            listOf(
                KeyAction.Chord("c", Modifiers.CTRL),
                KeyAction.Macro(listOf(KeyAction.Text("/compact"), KeyAction.Press(Key.Enter))),
                KeyAction.Macro(listOf(KeyAction.Press(Key.Escape), KeyAction.Press(Key.Escape))),
            )
    }
}
