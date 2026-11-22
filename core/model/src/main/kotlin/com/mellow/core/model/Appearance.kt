package com.mellow.core.model

/**
 * Which look the app wears: its light theme or its dark one.
 *
 * It starts on [Automatic] and stays there unless the user says otherwise. Whatever is
 * chosen holds for everything that is Mellow's on the phone: its screens and its widgets.
 */
enum class Appearance {
    /** Whichever the phone is in, changing when the phone does. */
    Automatic,
    Light,
    Dark,
}
