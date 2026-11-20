package com.mellow.app.ui

import com.mellow.core.designsystem.component.TabItem
import com.mellow.core.designsystem.icon.MellowIcons

internal enum class Tab(val label: String) {
    Home("Home"),
    Library("Library"),
    Search("Search"),
}

internal val TabItems: List<TabItem> = listOf(
    TabItem(Tab.Home.label, MellowIcons.Home, MellowIcons.HomeSelected),
    TabItem(Tab.Library.label, MellowIcons.Library, MellowIcons.LibrarySelected),
    TabItem(Tab.Search.label, MellowIcons.Search, MellowIcons.SearchSelected),
)
