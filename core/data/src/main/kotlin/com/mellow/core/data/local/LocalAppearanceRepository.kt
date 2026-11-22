package com.mellow.core.data.local

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.mellow.core.data.AppearanceRepository
import com.mellow.core.model.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the chosen [Appearance] in the app's own settings, and has the phone hold the app
 * to it.
 *
 * From Android 12 the phone can be told that this one app is light or dark whatever the
 * rest of the phone is. Everything then follows without being asked: the colours the app
 * picks, the screen shown while it starts, the icons in the status bar. The phone keeps
 * that instruction itself, so it holds from the first frame of the next launch.
 *
 * Older phones have no such instruction. There the choice is only kept here, and the
 * app's screen applies it to itself as it is made (see the main activity).
 *
 * The choice is read as this is made, on whatever thread that is: it is a few bytes, and
 * the first screen cannot be drawn in the right colours without it.
 */
class LocalAppearanceRepository(context: Context) : AppearanceRepository {

    private val context = context.applicationContext
    private val settings = this.context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(
        settings.getString(Key, null)?.let { saved -> Appearance.entries.firstOrNull { it.name == saved } } ?: Appearance.Automatic,
    )
    override val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    init {
        // The phone's copy and this one are written together and should never differ. If
        // they ever do (a phone updated from an Android too old to have kept one), this
        // one is right. Left alone when there is nothing to hold the app to.
        if (_appearance.value != Appearance.Automatic) holdTo(_appearance.value)
    }

    override fun choose(appearance: Appearance) {
        if (appearance == _appearance.value) return
        settings.edit().putString(Key, appearance.name).apply()
        _appearance.value = appearance
        holdTo(appearance)
    }

    private fun holdTo(appearance: Appearance) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val phone = context.getSystemService(UiModeManager::class.java) ?: return
        phone.setApplicationNightMode(
            when (appearance) {
                Appearance.Automatic -> UiModeManager.MODE_NIGHT_AUTO
                Appearance.Light -> UiModeManager.MODE_NIGHT_NO
                Appearance.Dark -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        const val Key = "appearance"
    }
}
