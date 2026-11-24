package com.mellow.core.data.device

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.mellow.core.data.device.tags.Mp3Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * Changes what a song's own file says about it (its title, artist, album and picture), so
 * that the change is the song's to Android and to every app, not something only Mellow
 * knows.
 *
 * These are the user's music files and there is one of each, so the order of work is
 * built around never being able to lose one:
 *
 *  1. The song is copied into Mellow's own storage. The song itself is not touched.
 *  2. A second copy is made there with the change in it ([Mp3Tag]).
 *  3. That copy is checked three ways: its tag reads back with everything else as it was,
 *     its sound is the same sound to the last byte, and Android's own reader opens it and
 *     finds the new names, the new picture and the same playing time.
 *  4. Only then is it written over the song, with a note left on disk first saying a write
 *     is under way. Android refuses this step unless the user has just allowed it.
 *  5. The song is read back and compared with the copy. If it differs in any way, the
 *     first copy is written back, so the song is as it was.
 *  6. The note and the copies are removed.
 *
 * If the app is killed in the middle of step 4, the note and both copies are still there
 * the next time it runs: [unfinished] lists such songs and [finish] completes them.
 *
 * The first time a song is changed, what it said about itself before is put aside before
 * anything is written, so any part of it, or all of it, can be returned later exactly
 * ([changedParts], [putBack]).
 *
 * One song at a time. Every function suspends and does its work off the main thread.
 */
internal class SongFiles(context: Context) {

    enum class Outcome {
        /** The song now holds what was asked. */
        Written,

        /** Android would not let the file be written: the user has not allowed it. Nothing was changed. */
        NotAllowed,

        /** The song could not be opened: it is no longer on the phone, most likely. */
        CannotRead,

        /** Not a kind of file, or not a tag, that is handled here. Nothing was changed. */
        NotSupported,

        /** Something went wrong. The song was left, or put back, as it was. */
        Failed,
    }

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val work = File(this.context.filesDir, "song-writes")
    private val originals = File(this.context.filesDir, "song-originals")
    private val oneAtATime = Mutex()

    /** Makes [changes] inside this song. */
    suspend fun write(trackId: Long, changes: Mp3Tag.Changes): Outcome = change(trackId) { current, next ->
        Mp3Tag.writeWithChanges(current, next, changes)
        if (!Mp3Tag.check(current, next, changes)) throw IOException("the copy did not come out as it should")
        if (!readsAsTheSameSong(next, like = current, changes)) throw IOException("Android could not read the copy")
        stageOriginal(trackId, current)
    }

    /**
     * The picture inside this song's file, as it is stored there, or null if it has none
     * or the file cannot be read. Only reads: the file is opened as any player opens it.
     */
    suspend fun picture(trackId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, address(trackId))
            reader.embeddedPicture
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                reader.release()
            } catch (_: Exception) {
            }
        }
    }

    /** True if this song has been changed here and what it said before is kept. Cheap; see [changedParts] for which parts. */
    fun hasOriginal(trackId: Long): Boolean = originalTag(trackId).exists()

    /**
     * Which parts of this song are, right now, not what they were before it was first
     * changed here. Reads the front of the song's file, so not for calling on a whole
     * library; it is asked about the few songs an edit sheet is open on.
     */
    suspend fun changedParts(trackId: Long): Set<Mp3Tag.Part> {
        if (!hasOriginal(trackId)) return emptySet()
        return withContext(Dispatchers.IO) {
            try {
                val then = originalTag(trackId).readBytes()
                val now = resolver.openInputStream(address(trackId))?.use { input ->
                    val head = ByteArray(TagHeader)
                    if (input.readNBytesInto(head) < TagHeader) return@use ByteArray(0)
                    val whole = ByteArray(Mp3Tag.frontLength(head))
                    if (whole.isEmpty()) return@use whole
                    head.copyInto(whole)
                    if (input.readNBytesInto(whole, TagHeader) < whole.size) throw IOException("the tag is cut short")
                    whole
                } ?: return@withContext emptySet()
                Mp3Tag.differing(now, then)
            } catch (_: Mp3Tag.Unsupported) {
                emptySet()
            } catch (_: IOException) {
                emptySet()
            } catch (_: SecurityException) {
                emptySet()
            }
        }
    }

    /**
     * Returns [parts] of this song to what they were before it was first changed here.
     * When that is everything that differs, the file comes out exactly as it was, to the
     * byte, and what was kept of it is let go.
     */
    suspend fun putBack(trackId: Long, parts: Set<Mp3Tag.Part>): Outcome {
        if (!hasOriginal(trackId)) return Outcome.NotSupported
        var whole = false
        val outcome = change(trackId) { current, next ->
            // Only into the song it was taken from. Ids can come to mean another file.
            val print = originalPrint(trackId).takeIf(File::exists)?.readText()?.trim()
            if (print != null && print != Mp3Tag.soundPrint(current) && print != Mp3Tag.soundPrint(current, withTail = true)) {
                throw Mp3Tag.Unsupported("not the song this was kept from")
            }
            val now = Mp3Tag.keep(current)
            // An original kept before the old tag at the end was looked after has none
            // put aside. Nothing then had touched it, so the one on the file is it.
            val tail = originalTail(trackId).takeIf(File::exists)?.readBytes()?.takeIf { it.isNotEmpty() }
            val original = Mp3Tag.Kept(originalTag(trackId).readBytes(), if (originalTail(trackId).exists()) tail else now.tail)

            whole = Mp3Tag.differing(now.front, original.front).all { it in parts }
            if (whole) Mp3Tag.writeAsItWas(current, next, original) else Mp3Tag.writeWithParts(current, next, original, parts)
            if (Mp3Tag.soundPrint(current) != Mp3Tag.soundPrint(next)) throw IOException("the sound changed")
            if (!readsAsTheSameSong(next, like = current, changes = null)) throw IOException("Android could not read the copy")
        }
        // Returned whole, or no longer returnable: either way it is not kept any more.
        if ((outcome == Outcome.Written && whole) || outcome == Outcome.NotSupported || outcome == Outcome.CannotRead) {
            originalTag(trackId).delete()
            originalTail(trackId).delete()
            originalPrint(trackId).delete()
        }
        return outcome
    }

    /** Songs a write to which was cut short, by the app being killed. They may not be whole until [finish] is run. */
    fun unfinished(): List<Long> =
        work.listFiles { file -> file.name.endsWith(JournalSuffix) }
            ?.mapNotNull { it.name.removeSuffix(JournalSuffix).toLongOrNull() }
            .orEmpty()

    /** Completes a write that was cut short, or failing that puts the song back as it was. */
    suspend fun finish(trackId: Long): Outcome = oneAtATime.withLock {
        withContext(Dispatchers.IO) { finishLocked(trackId) }
    }

    /**
     * Steps 1 to 6 of the class comment. [build] is step 2 and 3: it is given the copy of
     * the song as it is and writes the copy as it should be, throwing if that cannot be
     * done or the result does not check out.
     */
    private suspend fun change(trackId: Long, build: (current: File, next: File) -> Unit): Outcome = oneAtATime.withLock {
        withContext(Dispatchers.IO) {
            work.mkdirs()
            // A write to this song that was cut short comes first: it may not be whole.
            if (journal(trackId).exists()) {
                val settled = finishLocked(trackId)
                if (settled != Outcome.Written) return@withContext settled
            }
            val song = address(trackId)
            val current = File(work, "$trackId$CurrentSuffix")
            val next = File(work, "$trackId$NextSuffix")
            try {
                if (!copyIn(song, current)) return@withContext Outcome.CannotRead
                try {
                    build(current, next)
                } catch (_: Mp3Tag.Unsupported) {
                    return@withContext Outcome.NotSupported
                }
                val outcome = replace(trackId, song, with = next, fallback = current)
                if (outcome == Outcome.Written) keepStagedOriginal(trackId)
                outcome
            } catch (_: IOException) {
                Outcome.Failed
            } finally {
                // While a journal stands, the copies are what the song can be mended from,
                // and the original set aside for it has to wait with them.
                if (!journal(trackId).exists()) {
                    current.delete()
                    next.delete()
                    dropStagedOriginal(trackId)
                }
            }
        }
    }

    /** Steps 4 and 5: writes [with] over the song and makes sure it arrived, or returns the song to [fallback]. */
    private fun replace(trackId: Long, song: Uri, with: File, fallback: File): Outcome {
        val print = print(with)
        // From here until the journal is deleted the song may be half written. The
        // journal says so, and names what it should end up as.
        writeWhole(journal(trackId), "${with.length()}\n$print\n".toByteArray())
        try {
            send(with, song)
        } catch (_: SecurityException) {
            // Refused before a byte was written. The song is as it was.
            journal(trackId).delete()
            return Outcome.NotAllowed
        } catch (_: IOException) {
            return giveBack(trackId, song, fallback)
        }
        val arrived = try {
            holds(song, with.length(), print)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!arrived) return giveBack(trackId, song, fallback)
        journal(trackId).delete()
        return Outcome.Written
    }

    /** The write did not take. Returns the song to how it was; if even that fails, the journal stays for [finish]. */
    private fun giveBack(trackId: Long, song: Uri, fallback: File): Outcome {
        try {
            send(fallback, song)
            if (holds(song, fallback.length(), print(fallback))) journal(trackId).delete()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        return Outcome.Failed
    }

    private fun finishLocked(trackId: Long): Outcome {
        val journal = journal(trackId)
        val next = File(work, "$trackId$NextSuffix")
        val current = File(work, "$trackId$CurrentSuffix")
        val song = address(trackId)
        fun tidy() {
            journal.delete()
            next.delete()
            current.delete()
        }
        return try {
            val lines = journal.readLines()
            val length = lines.getOrNull(0)?.toLongOrNull()
            val print = lines.getOrNull(1)
            // The write itself may have finished, and only the tidying up been lost.
            if (length != null && print != null && holds(song, length, print)) {
                keepStagedOriginal(trackId)
                tidy()
                return Outcome.Written
            }
            // Forward if the finished copy is intact, otherwise back to the original.
            val from = when {
                length != null && next.exists() && next.length() == length && print(next) == print -> next
                current.exists() -> current
                else -> null
            }
            if (from == null) {
                tidy()
                return Outcome.Failed
            }
            send(from, song)
            if (!holds(song, from.length(), print(from))) return Outcome.Failed
            // Gone forward, the song has its change and the original is worth keeping.
            // Gone back, it is as it was and there is nothing to keep.
            if (from === next) keepStagedOriginal(trackId) else dropStagedOriginal(trackId)
            tidy()
            Outcome.Written
        } catch (_: SecurityException) {
            Outcome.NotAllowed
        } catch (_: FileNotFoundException) {
            // The song has gone from the phone. There is nothing left to mend.
            tidy()
            Outcome.CannotRead
        } catch (_: IOException) {
            Outcome.Failed
        }
    }

    // ── What the song said before, kept so it can be returned ───────────────

    /**
     * Sets aside both of [current]'s tags, before the song is written to, unless they are
     * kept already (what is kept is what the song came with, not what it was last given).
     * It only counts as kept once the write has gone through: see [keepStagedOriginal].
     */
    private fun stageOriginal(trackId: Long, current: File) {
        if (hasOriginal(trackId)) {
            val print = originalPrint(trackId).takeIf(File::exists)?.readText()?.trim()
            if (originalTail(trackId).exists()) {
                // Kept already, and from this very song: nothing to do.
                if (print == null || print == Mp3Tag.soundPrint(current)) return
            } else if (print == null || print == Mp3Tag.soundPrint(current, withTail = true)) {
                // Kept by an earlier version of this, which neither put the old tag at the
                // end aside nor left it out of the fingerprint. Nothing has touched that
                // tag until now, so the one on the file is still the song's own: both are
                // brought up to date here, before a name can change it.
                writeWhole(originalPrint(trackId), Mp3Tag.soundPrint(current).toByteArray())
                writeWhole(originalTail(trackId), Mp3Tag.keep(current).tail ?: ByteArray(0))
                return
            }
            // What is kept under this id came from some other file that once had it. It
            // is of no use to this song, which is about to need an original of its own.
            originalTag(trackId).delete()
            originalTail(trackId).delete()
            originalPrint(trackId).delete()
        }
        originals.mkdirs()
        val kept = Mp3Tag.keep(current)
        writeWhole(staged(originalPrint(trackId)), Mp3Tag.soundPrint(current).toByteArray())
        // Written even when there is none, empty: that is how "had no old tag" is told from "not recorded".
        writeWhole(staged(originalTail(trackId)), kept.tail ?: ByteArray(0))
        writeWhole(staged(originalTag(trackId)), kept.front)
    }

    private fun keepStagedOriginal(trackId: Long) {
        if (!staged(originalTag(trackId)).exists()) return
        staged(originalPrint(trackId)).renameTo(originalPrint(trackId))
        staged(originalTail(trackId)).renameTo(originalTail(trackId))
        // The tag last: its presence is what says an original is kept.
        staged(originalTag(trackId)).renameTo(originalTag(trackId))
    }

    private fun dropStagedOriginal(trackId: Long) {
        staged(originalTag(trackId)).delete()
        staged(originalTail(trackId)).delete()
        staged(originalPrint(trackId)).delete()
    }

    private fun originalTag(trackId: Long) = File(originals, "$trackId.tag")

    private fun originalTail(trackId: Long) = File(originals, "$trackId.tail")

    private fun originalPrint(trackId: Long) = File(originals, "$trackId.print")

    private fun staged(file: File) = File(file.parentFile, file.name + ".staged")

    // ── Reading and writing files ───────────────────────────────────────────

    private fun address(trackId: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

    private fun journal(trackId: Long) = File(work, "$trackId$JournalSuffix")

    /** Copies the song into [into]. False if it cannot be opened. */
    private fun copyIn(song: Uri, into: File): Boolean = try {
        val stream = resolver.openInputStream(song)
        if (stream == null) {
            false
        } else {
            stream.use { input -> FileOutputStream(into).use { out -> input.copyTo(out, Chunk) } }
            true
        }
    } catch (_: FileNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Writes [file] over the song, whole. Throws [SecurityException] before writing anything if Android does not allow it. */
    private fun send(file: File, song: Uri) {
        val descriptor = resolver.openFileDescriptor(song, "rwt") ?: throw IOException("the song could not be opened")
        descriptor.use { open ->
            FileOutputStream(open.fileDescriptor).use { out ->
                file.inputStream().use { it.copyTo(out, Chunk) }
                out.flush()
                try {
                    out.fd.sync()
                } catch (_: IOException) {
                    // Not every storage honours this. What matters is what reads back, and that is checked.
                }
            }
        }
    }

    /** Whether the song on the phone is, right now, exactly [length] bytes with this fingerprint. */
    private fun holds(song: Uri, length: Long, print: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        val stream = resolver.openInputStream(song) ?: throw FileNotFoundException()
        stream.use { input ->
            val buffer = ByteArray(Chunk)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
            }
        }
        return read == length && digest.digest().hex() == print
    }

    private fun print(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(Chunk)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Fills [into] from [from] on, as far as the stream goes. Returns how far it is filled. */
    private fun java.io.InputStream.readNBytesInto(into: ByteArray, from: Int = 0): Int {
        var at = from
        while (at < into.size) {
            val count = read(into, at, into.size - at)
            if (count < 0) break
            at += count
        }
        return at
    }

    /** Writes a small file so that it is either all there or not there: to one side first, then moved into place. */
    private fun writeWhole(file: File, content: ByteArray) {
        val aside = File(file.parentFile, file.name + ".part")
        FileOutputStream(aside).use { out ->
            out.write(content)
            out.flush()
            out.fd.sync()
        }
        if (!aside.renameTo(file)) throw IOException("could not write ${file.name}")
    }

    /**
     * Asks Android's own reader, the one every player on the phone uses, what it makes of
     * [file]: it has to open it, give the same playing time as [like], and find in it
     * whatever [changes] were meant to put there, read the way Android reads them.
     */
    private fun readsAsTheSameSong(file: File, like: File, changes: Mp3Tag.Changes?): Boolean {
        val was = facts(like) ?: return false
        val now = facts(file) ?: return false
        if (changes != null) {
            if (changes.picture != null && (now.picture == null || !now.picture.contentEquals(changes.picture))) return false
            if (changes.title != null && now.title?.trim() != changes.title) return false
            if (changes.artist != null && now.artist?.trim() != changes.artist) return false
            if (changes.album != null && now.album?.trim() != changes.album) return false
        }
        if (was.durationMs == null || now.durationMs == null) return was.durationMs == now.durationMs
        return abs(was.durationMs - now.durationMs) <= max(DurationSlackMs, was.durationMs / 50)
    }

    private class Facts(val durationMs: Long?, val picture: ByteArray?, val title: String?, val artist: String?, val album: String?)

    /** What Android reads out of a file, or null if it cannot read the file at all. */
    private fun facts(file: File): Facts? {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            Facts(
                durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                picture = reader.embeddedPicture,
                title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                reader.release()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val JournalSuffix = ".journal"
        const val CurrentSuffix = ".orig"
        const val NextSuffix = ".new"
        const val Chunk = 64 * 1024
        const val TagHeader = 10
        const val DurationSlackMs = 1_500L
    }
}
