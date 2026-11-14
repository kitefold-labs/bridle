package com.kitefoldlabs.bridle.core.terminal

/**
 * How the remote program asked to be told about the mouse, from xterm's private modes.
 *
 * Bridle only ever sends wheel events (for a scroll gesture on the alternate screen), so the
 * three tracking modes 1000, 1002 and 1003 are not told apart.
 */
public enum class MouseReporting {
    /** No mouse mode is on: scroll gestures fall back to cursor keys. */
    OFF,

    /** Tracking is on without `CSI ? 1006 h`: the classic three-byte `CSI M b x y` encoding. */
    X10,

    /** Tracking is on with SGR encoding (`CSI ? 1006 h`): `CSI < b ; x ; y M`, any size. */
    SGR,
}
