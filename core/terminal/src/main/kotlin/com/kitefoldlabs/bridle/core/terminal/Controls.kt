package com.kitefoldlabs.bridle.core.terminal

/** C0 controls, DEL and C1 controls: characters that are never printable in a terminal. */
internal fun isTerminalControl(c: Char): Boolean = c < ' ' || c in '\u007f'..'\u009f'
