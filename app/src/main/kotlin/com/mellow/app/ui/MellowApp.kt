package com.mellow.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mellow.app.AppContainer
import com.mellow.core.designsystem.component.BarAction
import com.mellow.core.designsystem.component.GlassTabBar
import com.mellow.core.designsystem.component.GlassToast
import com.mellow.core.designsystem.component.SelectionBar
import com.mellow.core.designsystem.component.SelectionState
import com.mellow.core.designsystem.component.TabBarHeight
import com.mellow.core.designsystem.component.rememberCoverAccent
import com.mellow.core.designsystem.glass.GlassActivity
import com.mellow.core.designsystem.glass.GlassScene
import com.mellow.core.designsystem.icon.MellowIcons
import com.mellow.core.designsystem.light.LightField
import com.mellow.core.designsystem.light.LocalLighting
import com.mellow.core.designsystem.theme.MellowMotion
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.home.HomeScreen
import com.mellow.feature.home.HomeViewModel
import com.mellow.feature.library.LibraryScreen
import com.mellow.feature.library.LibrarySheets
import com.mellow.feature.library.LibraryViewModel
import com.mellow.feature.player.MiniPlayer
import com.mellow.feature.player.MiniPlayerHeight
import com.mellow.feature.player.NowPlayingScreen
import com.mellow.feature.player.PlayerViewModel
import com.mellow.feature.search.SearchBar
import com.mellow.feature.search.SearchBarHeight
import com.mellow.feature.search.SearchScreen
import com.mellow.feature.search.SearchViewModel
import com.mellow.feature.settings.SettingsScreen
import com.mellow.feature.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The whole app on one screen, in three depths:
 *
 *  1. the lit room, with the tab pages sliding over it side by side: together, the backdrop;
 *  2. the glass floating over that, which refracts the backdrop: the mini player and tab
 *     bar (or, while songs are selected, the bar of things to do with them), the search
 *     bar that rides in with its page, and any sheet that is asking a question;
 *  3. the Now Playing sheet, which rises over both and can be dragged back down.
 *
 * This is also the one place the features meet: Home, Library, Search, Settings and Player
 * know nothing of each other. What they share passes through here: the selection every list
 * picks into, and the Library's ability to act on it from any page.
 *
 * Everything here is built once and kept. The pages either side of the current one stay
 * composed, and so does the player once a track is loaded, so a swipe or a tap only ever
 * moves things that already exist. Whatever is covered or off screen is told so, and stops
 * animating until it is seen again.
 */
@Composable
fun MellowApp(container: AppContainer) {
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container.library, container.playlists, container.playback))
    val libraryViewModel: LibraryViewModel =
        viewModel(factory = LibraryViewModel.factory(container.library, container.playlists, container.playback))
    val searchViewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container.library, container.playback))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container.library, container.playlists))
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(container.playback))
    val player by playerViewModel.uiState.collectAsStateWithLifecycle()
    val library by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val hasTrack = player.track != null

    // Feed the light: how loud the music is, and what colour its cover is.
    val lighting = LocalLighting.current
    DisposableEffect(lighting, container) {
        lighting.energySource = container.playback::audioLevel
        onDispose { lighting.energySource = { 0f } }
    }
    val accent = rememberCoverAccent(player.track?.artwork?.uri)
    LaunchedEffect(accent) { lighting.setAccent(accent) }

    // The permission can be granted from Settings while the app is away.
    LifecycleResumeEffect(container) {
        container.library.refreshAccess()
        onPauseOrDispose {}
    }

    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { Tab.entries.size })
    val onLibraryPage = pager.currentPage == Tab.Library.ordinal

    // Settings is reached from the Library tab and takes its place until it is left.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // The songs picked out by pressing and holding, on whichever page. Every list picks
    // into this one selection, and the bar at the bottom acts on it.
    val selection = remember { SelectionState() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(selection.active) {
        // The bar of actions comes up where the keyboard would be.
        if (selection.active) focusManager.clearFocus()
    }

    // What the Library says it has done, shown for a moment.
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(ToastMillis)
            toast = null
        }
    }
    LaunchedEffect(libraryViewModel) {
        libraryViewModel.events.collect { event ->
            if (event.selectionUsed) selection.clear()
            toast = event.message
        }
    }

    // Deleting files. Android asks the user itself; the ids are held until it answers,
    // and survive the trip even if the system rebuilds the activity meanwhile.
    var deleting by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val confirmDeletion = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) libraryViewModel.onFilesDeleted(deleting.toSet())
        deleting = emptyList()
    }
    // Writing to song files (a cover going into its song). Android asks the user about
    // that as well; the Library says when there is something to ask and hears the answer.
    val confirmWrite = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        libraryViewModel.onWriteAnswered(result.resultCode == Activity.RESULT_OK)
    }
    val writeQuestion = libraryViewModel.writeQuestion
    LaunchedEffect(writeQuestion) {
        if (writeQuestion != null) {
            // Taken before it is put, so that a rebuilt screen does not put it twice.
            libraryViewModel.onWriteQuestionShown()
            confirmWrite.launch(IntentSenderRequest.Builder(writeQuestion).build())
        }
    }

    fun deleteSelectedFiles() {
        val request = libraryViewModel.deleteFilesRequest(selection.keys) ?: return
        deleting = ArrayList(selection.keys)
        confirmDeletion.launch(IntentSenderRequest.Builder(request).build())
    }

    // 0 = sheet fully down, 1 = fully up. Read in draw blocks only, so dragging it never recomposes.
    // Whether it is open is saved separately, so it survives rotation and theme changes.
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val sheet = remember { Animatable(if (sheetOpen) 1f else 0f) }
    val sheetOnScreen by remember { derivedStateOf { sheet.value > 0f } }
    fun moveSheet(target: Float, velocity: Float = 0f) {
        sheetOpen = target > 0.5f
        scope.launch { sheet.animateTo(target, MellowMotion.settle(), initialVelocity = velocity) }
    }

    // Nothing to show in the sheet once the queue is gone.
    LaunchedEffect(hasTrack) {
        if (!hasTrack) {
            sheetOpen = false
            sheet.snapTo(0f)
        }
    }

    // One back button, one decision: it undoes whatever is frontmost. Kept in a single
    // handler because the order matters and several handlers would each have to know
    // about the others.
    BackHandler(
        enabled = libraryViewModel.sheet != null || sheetOpen || selection.active ||
            (onLibraryPage && (settingsOpen || library.open != null)),
    ) {
        when {
            libraryViewModel.sheet != null -> libraryViewModel.dismissSheet()
            sheetOpen -> moveSheet(0f)
            selection.active -> selection.clear()
            settingsOpen -> settingsOpen = false
            else -> libraryViewModel.closePlaylist()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        val widthPx = constraints.maxWidth.toFloat()

        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val chromeHeight = TabBarHeight + ChromeBottomGap + if (hasTrack) MiniPlayerHeight + ChromeGap else 0.dp
        val pagePadding = PaddingValues(top = statusBar, bottom = navigationBar + chromeHeight + 20.dp)
        // Search starts lower: its bar floats where the top of the page would be.
        val searchPadding = PaddingValues(
            top = statusBar + SearchBarTopGap + SearchBarHeight + ChromeGap,
            bottom = navigationBar + chromeHeight + 20.dp,
        )

        // While the sheet is anywhere on screen, the room under it holds still: there are
        // then two full scenes to draw, and only the one in front needs to be alive.
        val roomAlive = !sheetOnScreen

        GlassScene(
            modifier = Modifier
                .fillMaxSize()
                // Fully covered by the sheet: do not draw it at all.
                .graphicsLayer { alpha = if (sheet.value >= 0.999f) 0f else 1f },
            active = roomAlive,
            backdrop = {
                // The room and its pages are a scene of their own inside the big one.
                // Glass that belongs to a page (a button, a switch) looks through to the
                // lit room; the glass floating over the app looks through to all of it,
                // pages and their glass included.
                GlassScene(
                    modifier = Modifier.fillMaxSize(),
                    // Only soft light is behind a page's own glass, so there is nothing to bend.
                    refract = false,
                    active = roomAlive,
                    backdrop = { LightField(Modifier.fillMaxSize(), active = roomAlive) },
                ) {
                    // Pages sit side by side and follow the finger. All of them stay
                    // composed, so a swipe never has to build the page it is revealing.
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = Tab.entries.size - 1,
                    ) { page ->
                        // A page beside the one on screen is built and kept, but its own
                        // glass holds still until part of it can be seen.
                        val onScreen by remember(page) {
                            derivedStateOf { abs(pager.currentPage + pager.currentPageOffsetFraction - page) < 1f }
                        }
                        GlassActivity(onScreen) {
                            when (Tab.entries[page]) {
                                Tab.Home -> HomeScreen(homeViewModel, selection, pagePadding)
                                Tab.Library -> AnimatedContent(
                                    targetState = settingsOpen,
                                    transitionSpec = {
                                        // Settings comes in from the right and leaves the way it came.
                                        val direction = if (targetState) 1 else -1
                                        (fadeIn(MellowMotion.glide()) + slideInHorizontally(MellowMotion.glide()) { direction * it / 6 }) togetherWith
                                            (fadeOut(MellowMotion.glide()) + slideOutHorizontally(MellowMotion.glide()) { -direction * it / 6 })
                                    },
                                    label = "libraryOrSettings",
                                ) { showSettings ->
                                    if (showSettings) {
                                        SettingsScreen(settingsViewModel, pagePadding, onBack = { settingsOpen = false })
                                    } else {
                                        LibraryScreen(libraryViewModel, selection, pagePadding, onOpenSettings = { settingsOpen = true })
                                    }
                                }
                                Tab.Search -> SearchScreen(searchViewModel, selection, searchPadding)
                            }
                        }
                    }
                }
            },
        ) {
            // The search bar floats over its page rather than sitting in it, so results
            // scroll beneath it. It is moved by exactly as much as the page is, so the two
            // arrive and leave as one piece.
            val searchOffset = { Tab.Search.ordinal - (pager.currentPage + pager.currentPageOffsetFraction) }
            // A whole page away it is off screen. Its glass then holds still, and starts
            // up again in the very frame the page begins to come back.
            val searchOnScreen by remember { derivedStateOf { abs(searchOffset()) < 1f } }
            GlassActivity(searchOnScreen) {
                SearchBar(
                    viewModel = searchViewModel,
                    active = pager.currentPage == Tab.Search.ordinal,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = ChromeMargin, end = ChromeMargin, top = SearchBarTopGap)
                        .fillMaxWidth()
                        .graphicsLayer { translationX = searchOffset() * widthPx }
                        // And it is not drawn at all.
                        .drawWithContent { if (abs(searchOffset()) < 1f) drawContent() },
                )
            }

            // The bottom of the screen holds one of two things: the usual bars, or, while
            // songs are selected, what can be done with them.
            AnimatedVisibility(
                visible = !selection.active,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically(MellowMotion.glide()) { it / 3 },
                exit = fadeOut() + slideOutVertically(MellowMotion.glide()) { it / 3 },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = ChromeMargin, end = ChromeMargin, bottom = ChromeBottomGap)
                        // The chrome sinks away as the sheet rises over it.
                        .graphicsLayer {
                            val p = sheet.value
                            alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
                            translationY = p * 40.dp.toPx()
                        },
                ) {
                    AnimatedVisibility(
                        visible = hasTrack,
                        enter = expandVertically(MellowMotion.glide(), expandFrom = Alignment.Bottom, clip = false) +
                            scaleIn(MellowMotion.glide(), initialScale = 0.86f) + fadeIn(),
                        exit = shrinkVertically(MellowMotion.glide(), shrinkTowards = Alignment.Bottom, clip = false) +
                            scaleOut(MellowMotion.glide(), targetScale = 0.86f) + fadeOut(),
                    ) {
                        MiniPlayer(
                            state = player,
                            onExpand = { moveSheet(1f) },
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onNext = playerViewModel::next,
                            modifier = Modifier.padding(bottom = ChromeGap),
                        )
                    }
                    GlassTabBar(
                        tabs = TabItems,
                        selectedIndex = pager.currentPage,
                        // Tracks the pages as they are dragged, not just where they land.
                        position = { pager.currentPage + pager.currentPageOffsetFraction },
                        onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = MellowMotion.glide()) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // The count is held while the bar slides away, so it never reads "0 songs".
            val lastCount = remember { LastCount() }
            if (selection.count > 0) lastCount.value = selection.count
            AnimatedVisibility(
                visible = selection.active,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically(MellowMotion.glide()) { it / 3 },
                exit = fadeOut() + slideOutVertically(MellowMotion.glide()) { it / 3 },
            ) {
                SelectionBar(
                    title = if (lastCount.value == 1) "1 song selected" else "${lastCount.value} songs selected",
                    // Only what makes sense here: taking songs out of a playlist is offered
                    // inside that playlist, and deleting files where Android allows asking.
                    actions = buildList {
                        val inPlaylist = onLibraryPage && library.open != null
                        // Four cells are narrow; the labels shorten and the icons carry it.
                        val crowded = inPlaylist && libraryViewModel.canDeleteFiles
                        add(
                            BarAction(
                                icon = MellowIcons.AddToPlaylist,
                                label = if (crowded) "Add" else "Add to playlist",
                                onClick = { libraryViewModel.askToAdd(selection.keys) },
                                description = "Add to playlist",
                            ),
                        )
                        add(
                            BarAction(
                                icon = MellowIcons.Rename,
                                label = "Edit",
                                onClick = { libraryViewModel.askToEdit(selection.keys) },
                                description = if (selection.count == 1) "Edit this song's name and cover" else "Change the cover of these songs",
                            ),
                        )
                        if (inPlaylist) {
                            add(
                                BarAction(
                                    icon = MellowIcons.RemoveFromPlaylist,
                                    label = "Remove",
                                    onClick = { libraryViewModel.removeFromOpen(selection.keys) },
                                    description = "Remove from this playlist",
                                ),
                            )
                        }
                        if (libraryViewModel.canDeleteFiles) {
                            add(
                                BarAction(
                                    icon = MellowIcons.Delete,
                                    label = if (crowded) "Delete" else "Delete from phone",
                                    onClick = ::deleteSelectedFiles,
                                    description = "Delete from phone",
                                    destructive = true,
                                ),
                            )
                        }
                    },
                    onClose = selection::clear,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = ChromeMargin, end = ChromeMargin, bottom = ChromeBottomGap),
                )
            }

            GlassToast(
                message = toast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Clear of whichever bar is at the bottom, the taller one included.
                    .padding(start = 24.dp, end = 24.dp, bottom = maxOf(chromeHeight, SelectionBarRoom) + 12.dp),
            )

            // Last, so a sheet covers everything else that floats.
            LibrarySheets(libraryViewModel)
        }

        // Built as soon as there is a track and parked below the screen, so opening it
        // only has to slide it up.
        if (hasTrack) {
            val topRadius = MellowTheme.radii.sheet
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = sheet.value
                        translationY = (1f - p) * heightPx
                        alpha = if (p <= 0f) 0f else 1f
                        // Square once it fills the screen; rounded while it is a sheet.
                        val radius = topRadius * (1f - p).coerceIn(0f, 1f)
                        shape = RoundedCornerShape(topStart = radius, topEnd = radius)
                        clip = true
                    }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch { sheet.snapTo((sheet.value - delta / heightPx).coerceIn(0f, 1f)) }
                        },
                        onDragStopped = { velocity ->
                            // A flick decides; otherwise whichever end is nearer.
                            val down = velocity > FlickVelocity || (velocity > -FlickVelocity && sheet.value < 0.6f)
                            moveSheet(if (down) 0f else 1f, velocity = -velocity / heightPx)
                        },
                    ),
            ) {
                NowPlayingScreen(playerViewModel, visible = sheetOnScreen, onCollapse = { moveSheet(0f) })
            }
        }
    }
}

/** Holds a number between frames without being state: writing it never causes a redraw. */
private class LastCount {
    var value = 1
}

private val ChromeMargin = 14.dp
private val ChromeGap = 10.dp
private val ChromeBottomGap = 8.dp
private val SearchBarTopGap = 8.dp

/** Roughly how much of the bottom of the screen the selection bar takes, for keeping other things clear of it. */
private val SelectionBarRoom = 134.dp
private const val FlickVelocity = 1400f
private const val ToastMillis = 2600L
