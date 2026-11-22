package com.mellow.core.data

import com.mellow.core.model.Appearance
import kotlinx.coroutines.flow.StateFlow

/** The look the user has chosen for the app, kept from one run to the next. */
interface AppearanceRepository {

    /** The choice as it stands. Known from the first moment: it is read before anything is drawn. */
    val appearance: StateFlow<Appearance>

    /** The user chose. It is written down and takes effect at once. */
    fun choose(appearance: Appearance)
}
