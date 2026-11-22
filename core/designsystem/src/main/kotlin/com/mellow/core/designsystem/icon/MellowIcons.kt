package com.mellow.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.ArrowsClockwise
import com.adamglin.phosphoricons.bold.CaretDown
import com.adamglin.phosphoricons.bold.CaretLeft
import com.adamglin.phosphoricons.bold.Check
import com.adamglin.phosphoricons.bold.DotsSixVertical
import com.adamglin.phosphoricons.bold.DotsThree
import com.adamglin.phosphoricons.bold.FloppyDisk
import com.adamglin.phosphoricons.bold.FolderOpen
import com.adamglin.phosphoricons.bold.GearSix
import com.adamglin.phosphoricons.bold.Image
import com.adamglin.phosphoricons.bold.ListPlus
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.MinusCircle
import com.adamglin.phosphoricons.bold.PencilSimple
import com.adamglin.phosphoricons.bold.Plus
import com.adamglin.phosphoricons.bold.Queue
import com.adamglin.phosphoricons.bold.Repeat
import com.adamglin.phosphoricons.bold.RepeatOnce
import com.adamglin.phosphoricons.bold.RowsPlusBottom
import com.adamglin.phosphoricons.bold.RowsPlusTop
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.bold.Trash
import com.adamglin.phosphoricons.bold.X
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.Playlist
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.fill.VinylRecord
import com.adamglin.phosphoricons.regular.House as HouseOutline
import com.adamglin.phosphoricons.regular.MagnifyingGlass as MagnifyingGlassOutline
import com.adamglin.phosphoricons.regular.VinylRecord as VinylRecordOutline

/**
 * Every icon the app uses, named for what it means here rather than what it depicts.
 *
 * The drawings come from Phosphor. Screens import this object and nothing from Phosphor,
 * so the icon set can be swapped, or one icon redrawn, by editing this file alone.
 *
 * Weight carries state: transport glyphs are Fill because they are the heaviest things on
 * the player; tabs are Regular at rest and Fill (or Bold) when selected; small toggles are
 * Bold so they hold up at 22dp.
 */
object MellowIcons {
    val Play: ImageVector get() = PhosphorIcons.Fill.Play
    val Pause: ImageVector get() = PhosphorIcons.Fill.Pause
    val Next: ImageVector get() = PhosphorIcons.Fill.SkipForward
    val Previous: ImageVector get() = PhosphorIcons.Fill.SkipBack

    val Shuffle: ImageVector get() = PhosphorIcons.Bold.Shuffle
    val Repeat: ImageVector get() = PhosphorIcons.Bold.Repeat
    val RepeatOne: ImageVector get() = PhosphorIcons.Bold.RepeatOnce

    val Collapse: ImageVector get() = PhosphorIcons.Bold.CaretDown

    /** Empties a text field. */
    val Clear: ImageVector get() = PhosphorIcons.Bold.X

    /** Stands in for a cover on music that has none. */
    val NoCover: ImageVector get() = PhosphorIcons.Fill.MusicNotes

    /** Stands in for the cover of a playlist with nothing in it yet. */
    val EmptyPlaylist: ImageVector get() = PhosphorIcons.Fill.Playlist

    val Back: ImageVector get() = PhosphorIcons.Bold.CaretLeft

    /** Makes something new. */
    val Add: ImageVector get() = PhosphorIcons.Bold.Plus

    /** The tick on a selected item. */
    val Selected: ImageVector get() = PhosphorIcons.Bold.Check

    /** Leaves selection, or closes whatever is open. */
    val Close: ImageVector get() = PhosphorIcons.Bold.X

    /** Put these at the front of what is coming: a row slipped in at the top. */
    val PlayNext: ImageVector get() = PhosphorIcons.Bold.RowsPlusTop

    /** Put these at the back of the queue: a row added underneath. */
    val AddToQueue: ImageVector get() = PhosphorIcons.Bold.RowsPlusBottom

    /** What is lined up to play. */
    val Queue: ImageVector get() = PhosphorIcons.Bold.Queue

    /** The rest of what can be done, kept in a sheet. */
    val More: ImageVector get() = PhosphorIcons.Bold.DotsThree

    /** Take hold here to move a row. */
    val DragHandle: ImageVector get() = PhosphorIcons.Bold.DotsSixVertical

    val AddToPlaylist: ImageVector get() = PhosphorIcons.Bold.ListPlus

    /** Takes something out of a playlist. The file is untouched. */
    val RemoveFromPlaylist: ImageVector get() = PhosphorIcons.Bold.MinusCircle

    /** Deletes for good. */
    val Delete: ImageVector get() = PhosphorIcons.Bold.Trash

    val Rename: ImageVector get() = PhosphorIcons.Bold.PencilSimple

    /** A picture: changing a cover, or choosing one from the gallery. */
    val Picture: ImageVector get() = PhosphorIcons.Bold.Image

    val Settings: ImageVector get() = PhosphorIcons.Bold.GearSix

    /** Looks again for what is on the phone. */
    val Reload: ImageVector get() = PhosphorIcons.Bold.ArrowsClockwise

    /** Writes something to a file to keep. */
    val SaveFile: ImageVector get() = PhosphorIcons.Bold.FloppyDisk

    /** Brings something in from a file. */
    val OpenFile: ImageVector get() = PhosphorIcons.Bold.FolderOpen

    val Home: ImageVector get() = PhosphorIcons.Regular.HouseOutline
    val HomeSelected: ImageVector get() = PhosphorIcons.Fill.House
    val Library: ImageVector get() = PhosphorIcons.Regular.VinylRecordOutline
    val LibrarySelected: ImageVector get() = PhosphorIcons.Fill.VinylRecord
    val Search: ImageVector get() = PhosphorIcons.Regular.MagnifyingGlassOutline
    val SearchSelected: ImageVector get() = PhosphorIcons.Bold.MagnifyingGlass
}
