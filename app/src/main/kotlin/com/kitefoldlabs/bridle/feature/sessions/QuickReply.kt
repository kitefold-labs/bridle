package com.kitefoldlabs.bridle.feature.sessions

import androidx.annotation.StringRes
import com.kitefoldlabs.bridle.R
import com.kitefoldlabs.bridle.core.tmux.TmuxController

/**
 * One-tap answers for Claude Code, sent with `tmux send-keys` without opening a terminal. Keys use
 * tmux key names; commands are typed literally and submitted with Enter.
 */
enum class QuickReply(
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    /** A tmux key name, or the command to type when [isCommand]. */
    private val input: String,
    private val isCommand: Boolean = false,
) {
    YES(R.string.reply_yes, R.string.reply_yes_description, "y"),
    NO(R.string.reply_no, R.string.reply_no_description, "n"),
    ESCAPE(R.string.reply_escape, R.string.reply_escape_description, "Escape"),
    SHIFT_TAB(R.string.reply_shift_tab, R.string.reply_shift_tab_description, "BTab"),
    CTRL_C(R.string.reply_ctrl_c, R.string.reply_ctrl_c_description, "C-c"),
    COMPACT(R.string.reply_compact, R.string.reply_compact_description, "/compact", isCommand = true),
    CLEAR(R.string.reply_clear, R.string.reply_clear_description, "/clear", isCommand = true),
    ;

    /** Sends this reply to tmux [session]. */
    suspend fun send(
        tmux: TmuxController,
        session: String,
    ) {
        if (isCommand) tmux.sendText(session, input) else tmux.sendKeys(session, input)
    }
}
