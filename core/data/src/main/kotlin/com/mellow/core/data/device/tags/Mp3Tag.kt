package com.mellow.core.data.device.tags

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Changes what an MP3 file says about itself (its title, artist, album and picture) and
 * nothing else about it.
 *
 * An MP3 keeps that in a tag at the very front of the file, called ID3v2: a ten-byte
 * header, then a row of frames, each with a four-letter name and a length, then some empty
 * padding. The sound starts where the tag ends. The title is the frame named TIT2, the
 * artist TPE1, the album TALB and the cover APIC. Many files also carry a second, much
 * older tag in their last 128 bytes (ID3v1), with room for thirty plain letters of each
 * name; it is kept in step, so the file does not say two different things.
 *
 * The rule here is to understand as little as possible and to guess at nothing. Frames are
 * never opened or re-encoded: every frame that is not being replaced is copied across
 * exactly as it was found, byte for byte and in the same order, and so is every byte of
 * the sound.
 *
 * And a file is only touched if all of it is understood. Anything unexpected in the tag
 * (a version of the format not handled here, a flag asking for the tag to be decoded some
 * special way, a frame that runs past the end, stray bytes where padding should be) and
 * the answer is a refusal with the reason, never an attempt. A song left alone is better
 * than a song changed by a guess.
 *
 * Nothing here writes to a song. Each function reads one file and writes another, so the
 * result can be checked before it replaces anything. See [check].
 */
internal object Mp3Tag {

    /** Why a file was left alone, in a few words for a log. Never shown as it is. */
    class Unsupported(val why: String) : Exception(why)

    /**
     * What to change. A null leaves that part of the song as it is; so does a blank name,
     * because a song is not given no title by clearing a box.
     *
     * @property picture the new cover, as a JPEG.
     */
    class Changes(title: String? = null, artist: String? = null, album: String? = null, val picture: ByteArray? = null) {
        val title: String? = title?.trim()?.takeIf { it.isNotEmpty() }
        val artist: String? = artist?.trim()?.takeIf { it.isNotEmpty() }
        val album: String? = album?.trim()?.takeIf { it.isNotEmpty() }

        val changesNames: Boolean get() = title != null || artist != null || album != null
        val isNothing: Boolean get() = !changesNames && picture == null
    }

    /** A part of what a song says about itself, for taking that part back from an earlier copy. */
    enum class Part { Names, Picture }

    /**
     * The two tags of a file exactly as they were: the one at the front, whole, and the
     * old one at the end if there is one. Enough to put either part of the song back, or
     * with [writeAsItWas] the whole file.
     */
    class Kept(val front: ByteArray, val tail: ByteArray?)

    /**
     * Writes to [target] a copy of [source] with [changes] made.
     *
     * The copy keeps the tag version the file had, every frame that is not being
     * replaced, and the sound. A file that had no tag is given one.
     *
     * @throws Unsupported if the file is not an MP3 whose tag is fully understood.
     */
    @Throws(Unsupported::class, IOException::class)
    fun writeWithChanges(source: File, target: File, changes: Changes) {
        if (changes.picture != null && !isJpeg(changes.picture)) throw Unsupported("the picture is not a JPEG")
        RandomAccessFile(source, "r").use { input ->
            val tag = read(input)
            val version = if (tag.version == 0) NewTagVersion else tag.version
            write(input, tag, version, replacements(version, changes), tailFor(tag, changes), target)
        }
    }

    /**
     * Writes to [target] a copy of [source] with [parts] of it as they are in [original],
     * and the rest as it is now. A part the original did not have (a song that came with
     * no picture) is taken out.
     */
    @Throws(Unsupported::class, IOException::class)
    fun writeWithParts(source: File, target: File, original: Kept, parts: Set<Part>) {
        RandomAccessFile(source, "r").use { input ->
            val tag = read(input)
            val was = parse(original.front)
            val version = if (tag.version == 0) NewTagVersion else tag.version
            // Frames can only be carried between tags of one kind.
            if (was.version != 0 && was.version != version) throw Unsupported("the tag is of another version than the one kept")

            val replace = LinkedHashMap<String, List<ByteArray>>()
            val names = parts.flatMap { if (it == Part.Names) NameFrames else listOf(PictureFrame) }
            for (name in names) replace[name] = was.frames.filter { it.name == name }.map(was::bytes)

            val tail = when {
                tag.tail == null -> null
                Part.Names in parts && original.tail != null -> original.tail
                else -> tag.tail
            }
            write(input, tag, version, replace, tail, target)
        }
    }

    /** Writes to [target] the sound of [source] between the two tags of [original]: the file exactly as it was. */
    @Throws(Unsupported::class, IOException::class)
    fun writeAsItWas(source: File, target: File, original: Kept) {
        RandomAccessFile(source, "r").use { input ->
            val tag = read(input)
            target.outputStream().buffered(Chunk).use { out ->
                out.write(original.front)
                copy(input, tag.soundStart, tag.soundEnd, out)
                original.tail?.let(out::write)
            }
        }
        // What was handed over has to be something this code would accept.
        RandomAccessFile(target, "r").use { read(it) }
    }

    /** Both tags of [file], exactly as they are. */
    @Throws(Unsupported::class, IOException::class)
    fun keep(file: File): Kept = RandomAccessFile(file, "r").use { input ->
        val tag = read(input)
        val front = ByteArray(tag.soundStart.toInt())
        input.seek(0)
        input.readFully(front)
        Kept(front, tag.tail)
    }

    /**
     * Which parts of a song differ between two readings of its front tag: its names, if
     * the title, artist or album frames are not the same, and its picture likewise.
     */
    @Throws(Unsupported::class)
    fun differing(now: ByteArray, then: ByteArray): Set<Part> {
        val a = parse(now)
        val b = parse(then)
        fun same(names: List<String>): Boolean {
            val x = a.frames.filter { it.name in names }.map(a::bytes)
            val y = b.frames.filter { it.name in names }.map(b::bytes)
            return x.size == y.size && x.indices.all { x[it].contentEquals(y[it]) }
        }
        return buildSet {
            if (!same(NameFrames)) add(Part.Names)
            if (!same(listOf(PictureFrame))) add(Part.Picture)
        }
    }

    /** How long the front tag is that begins with these ten bytes, header included. Zero if they do not begin a tag. */
    @Throws(Unsupported::class)
    fun frontLength(head: ByteArray): Int = if (head.size >= HeaderSize && startsTag(head)) HeaderSize + sizeOf(head) else 0

    /**
     * A fingerprint of the sound in [file], both tags left out. Two files with the same
     * one play the same.
     *
     * @param withTail count the old tag at the end as sound, as an earlier version of this did.
     */
    @Throws(Unsupported::class, IOException::class)
    fun soundPrint(file: File, withTail: Boolean = false): String = RandomAccessFile(file, "r").use { input ->
        val tag = read(input)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(Chunk)
        var left = (if (withTail) input.length() else tag.soundEnd) - tag.soundStart
        input.seek(tag.soundStart)
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) break
            digest.update(buffer, 0, count)
            left -= count
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether [written] is [original] with [changes] made and nothing else: the same
     * sound, every other frame the same and in the same order, and for each thing changed
     * exactly one frame, holding the new value. Reads both files from scratch, as a player
     * would, rather than trusting the code that wrote them.
     */
    fun check(original: File, written: File, changes: Changes): Boolean = try {
        RandomAccessFile(original, "r").use { before ->
            RandomAccessFile(written, "r").use { after ->
                val old = read(before)
                val new = read(after)
                val version = if (old.version == 0) NewTagVersion else old.version
                val replaced = replacements(version, changes)
                val oldKept = old.frames.filterNot { it.name in replaced }
                val newKept = new.frames.filterNot { it.name in replaced }
                new.version == version &&
                    oldKept.size == newKept.size &&
                    oldKept.indices.all { old.bytes(oldKept[it]).contentEquals(new.bytes(newKept[it])) } &&
                    replaced.all { (name, frames) ->
                        val found = new.frames.filter { it.name == name }
                        found.size == frames.size && found.indices.all { new.bytes(found[it]).contentEquals(frames[it]) }
                    } &&
                    old.soundEnd - old.soundStart == new.soundEnd - new.soundStart &&
                    sameTail(old.tail, new.tail, tailFor(old, changes))
            }
        } && soundPrint(original) == soundPrint(written)
    } catch (_: Unsupported) {
        false
    } catch (_: IOException) {
        false
    }

    private fun sameTail(old: ByteArray?, new: ByteArray?, expected: ByteArray?): Boolean =
        if (old == null) new == null else new != null && expected != null && new.contentEquals(expected)

    // ── Reading ─────────────────────────────────────────────────────────────

    private class Frame(val name: String, val start: Int, val length: Int)

    /**
     * @property version 3 or 4, for ID3v2.3 and ID3v2.4. Zero when there is no tag.
     * @property body everything in the tag after its ten-byte header.
     */
    private open class Front(val version: Int, val body: ByteArray, val frames: List<Frame>) {
        fun bytes(frame: Frame): ByteArray = body.copyOfRange(frame.start, frame.start + frame.length)
    }

    /**
     * A whole file's layout.
     *
     * @property soundStart where the front tag ends.
     * @property soundEnd where the sound ends: the start of the old tag at the end, or the end of the file.
     * @property tail the old tag at the end, all 128 bytes of it, if the file has one.
     */
    private class Tag(front: Front, val soundStart: Long, val soundEnd: Long, val tail: ByteArray?) :
        Front(front.version, front.body, front.frames)

    private fun read(file: RandomAccessFile): Tag {
        val length = file.length()
        if (length < HeaderSize) throw Unsupported("too short to be a song")
        val head = ByteArray(HeaderSize)
        file.seek(0)
        file.readFully(head)

        val front: Front
        val soundStart: Long
        if (startsTag(head)) {
            val size = sizeOf(head)
            if (HeaderSize + size.toLong() > length) throw Unsupported("tag longer than the file")
            val whole = ByteArray(HeaderSize + size)
            file.seek(0)
            file.readFully(whole)
            front = parse(whole)
            soundStart = whole.size.toLong()
        } else {
            front = Front(version = 0, body = ByteArray(0), frames = emptyList())
            soundStart = 0
        }

        // The sound has to begin where the tag says it ends. With no tag, right at the
        // start, or this is not an MP3 at all.
        if (soundStart + 2 > length) throw Unsupported("no sound after the tag")
        val first = ByteArray(2)
        file.seek(soundStart)
        file.readFully(first)
        if (!startsSound(first[0], first[1])) {
            throw Unsupported(if (front.version == 0) "does not begin like an MP3" else "no sound where the tag ends")
        }

        // The old tag, if there is one: the last 128 bytes, opening with the letters TAG.
        var tail: ByteArray? = null
        if (length - TailSize >= soundStart + 2) {
            val last = ByteArray(TailSize)
            file.seek(length - TailSize)
            file.readFully(last)
            if (last[0] == 'T'.code.toByte() && last[1] == 'A'.code.toByte() && last[2] == 'G'.code.toByte()) tail = last
        }
        return Tag(front, soundStart, soundEnd = if (tail == null) length else length - TailSize, tail = tail)
    }

    /** Reads a front tag held in memory: all of it, header included. Nothing at all counts as "no tag". */
    private fun parse(whole: ByteArray): Front {
        if (whole.isEmpty()) return Front(version = 0, body = ByteArray(0), frames = emptyList())
        if (whole.size < HeaderSize || !startsTag(whole)) throw Unsupported("not a tag")
        val version = whole[3].toInt()
        val size = sizeOf(whole)
        if (HeaderSize + size != whole.size) throw Unsupported("tag length")
        val body = whole.copyOfRange(HeaderSize, whole.size)

        val frames = ArrayList<Frame>()
        var at = 0
        while (at + HeaderSize <= size && body[at].toInt() != 0) {
            for (letter in 0 until 4) {
                val c = body[at + letter].toInt().toChar()
                if (c !in 'A'..'Z' && c !in '0'..'9') throw Unsupported("frame name")
            }
            val inside = if (version == 4) {
                syncsafe(body, at + 4) ?: throw Unsupported("frame length")
            } else {
                val plain = ((body[at + 4].toLong() and 0xFF) shl 24) or ((body[at + 5].toLong() and 0xFF) shl 16) or
                    ((body[at + 6].toLong() and 0xFF) shl 8) or (body[at + 7].toLong() and 0xFF)
                if (plain > MaxTag) throw Unsupported("frame length")
                plain.toInt()
            }
            if (inside <= 0 || at + HeaderSize + inside > size) throw Unsupported("frame runs past the tag")
            frames += Frame(String(body, at, 4, Charsets.ISO_8859_1), at, HeaderSize + inside)
            at += HeaderSize + inside
        }
        // What is left has to be padding, which is zeros.
        for (index in at until size) if (body[index].toInt() != 0) throw Unsupported("stray bytes after the frames")
        return Front(version, body, frames)
    }

    private fun startsTag(head: ByteArray): Boolean =
        head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()

    /** The length a tag's header gives for it, after checking the header is of a kind handled here. */
    private fun sizeOf(head: ByteArray): Int {
        val version = head[3].toInt()
        if (version != 3 && version != 4) throw Unsupported("tag version 2.$version")
        // Each flag asks for the tag to be read in a special way. None is handled.
        if (head[5].toInt() != 0) throw Unsupported("tag flags")
        val size = syncsafe(head, 6) ?: throw Unsupported("tag length")
        if (size > MaxTag) throw Unsupported("tag length")
        return size
    }

    /** Every frame of MP3 sound opens with eleven set bits. */
    private fun startsSound(first: Byte, second: Byte): Boolean =
        first == 0xFF.toByte() && (second.toInt() and 0xE0) == 0xE0

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /** Four bytes holding seven bits each, which is how ID3 writes lengths. Null if a byte uses its eighth. */
    private fun syncsafe(bytes: ByteArray, at: Int): Int? {
        var value = 0
        for (index in at until at + 4) {
            val b = bytes[index].toInt() and 0xFF
            if (b >= 0x80) return null
            value = (value shl 7) or b
        }
        return value
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Writes the file out again. Each frame whose name is in [replace] gives way to the
     * frames listed for it there, which go where the first of the old ones stood, or after
     * all the others if there was none. Everything else is copied as it is.
     */
    private fun write(
        input: RandomAccessFile,
        tag: Tag,
        version: Int,
        replace: Map<String, List<ByteArray>>,
        tail: ByteArray?,
        target: File,
    ) {
        val pieces = ArrayList<ByteArray>()
        val placed = HashSet<String>()
        for (frame in tag.frames) {
            val instead = replace[frame.name]
            if (instead == null) {
                pieces += tag.bytes(frame)
            } else if (placed.add(frame.name)) {
                pieces += instead
            }
        }
        for ((name, frames) in replace) if (name !in placed) pieces += frames

        val framed = pieces.sumOf { it.size.toLong() }
        if (framed + Padding > MaxTag) throw Unsupported("the tag would be too large")

        target.outputStream().buffered(Chunk).use { out ->
            // A tag has to hold at least one frame. With none left, the file has no tag.
            if (pieces.isNotEmpty()) {
                out.write(header(version, (framed + Padding).toInt()))
                for (piece in pieces) out.write(piece)
                out.write(ByteArray(Padding))
            }
            copy(input, tag.soundStart, tag.soundEnd, out)
            tail?.let(out::write)
        }
    }

    /** The frames that [changes] put in place of the old ones, by frame name. */
    private fun replacements(version: Int, changes: Changes): Map<String, List<ByteArray>> {
        val replace = LinkedHashMap<String, List<ByteArray>>()
        changes.title?.let { replace[TitleFrame] = listOf(textFrame(version, TitleFrame, it)) }
        changes.artist?.let { replace[ArtistFrame] = listOf(textFrame(version, ArtistFrame, it)) }
        changes.album?.let { replace[AlbumFrame] = listOf(textFrame(version, AlbumFrame, it)) }
        changes.picture?.let { replace[PictureFrame] = listOf(pictureFrame(version, it)) }
        return replace
    }

    private fun header(version: Int, size: Int): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
        version.toByte(), 0,
        0,
        ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
    )

    private fun frame(version: Int, name: String, inside: ByteArray): ByteArray {
        val n = inside.size
        val length = if (version == 4) {
            byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
        } else {
            byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        }
        return name.toByteArray(Charsets.ISO_8859_1) + length + byteArrayOf(0, 0) + inside
    }

    /**
     * A name, written the way each version of the tag expects text. The newer version
     * takes UTF-8. The older one knows two alphabets: plain western letters, used when the
     * name is made of nothing else, and UTF-16 with a mark in front saying which way round
     * its bytes go, for everything else.
     */
    private fun textFrame(version: Int, name: String, value: String): ByteArray {
        val inside = when {
            version == 4 -> byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            isPlain(value) -> byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            else -> byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        }
        return frame(version, name, inside)
    }

    /** An APIC frame: plain text encoding, a JPEG, "front cover", no caption, then the picture. */
    private fun pictureFrame(version: Int, jpeg: ByteArray): ByteArray =
        frame(version, PictureFrame, byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, FrontCover, 0) + jpeg)

    /** Whether every letter is one the oldest text encoding has. */
    private fun isPlain(value: String): Boolean = value.all { it.code in 0x20..0x7E || it.code in 0xA0..0xFF }

    /**
     * The old tag at the end as it should be after [changes], or as it is if they do not
     * touch a name. It holds thirty plain letters of each: a longer name is cut short, as
     * that tag has always done, and one in letters it has no room for is left blank
     * rather than wrong, since anything that reads the file prefers the full tag anyway.
     */
    private fun tailFor(tag: Tag, changes: Changes): ByteArray? {
        val tail = tag.tail ?: return null
        if (!changes.changesNames) return tail
        val next = tail.copyOf()
        fun put(value: String?, at: Int) {
            if (value == null) return
            val letters = if (isPlain(value)) value.toByteArray(Charsets.ISO_8859_1) else ByteArray(0)
            for (index in 0 until TailField) next[at + index] = if (index < letters.size) letters[index] else 0
        }
        put(changes.title, TailTitle)
        put(changes.artist, TailTitle + TailField)
        put(changes.album, TailTitle + TailField * 2)
        return next
    }

    private fun copy(input: RandomAccessFile, from: Long, until: Long, out: OutputStream) {
        val buffer = ByteArray(Chunk)
        var left = until - from
        input.seek(from)
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) throw IOException("the file ended early")
            out.write(buffer, 0, count)
            left -= count
        }
    }

    private const val TitleFrame = "TIT2"
    private const val ArtistFrame = "TPE1"
    private const val AlbumFrame = "TALB"
    private const val PictureFrame = "APIC"
    private val NameFrames = listOf(TitleFrame, ArtistFrame, AlbumFrame)

    private const val HeaderSize = 10
    private const val FrontCover: Byte = 3

    /** The old tag: 128 bytes, the letters TAG, then thirty bytes each for title, artist and album. */
    private const val TailSize = 128
    private const val TailTitle = 3
    private const val TailField = 30

    /** A file with no tag is given the older, most widely read version. */
    private const val NewTagVersion = 3

    /** Empty room left after the frames, as tags customarily have. */
    private const val Padding = 1024

    /** No real tag comes near this. Past it, something is wrong with what was read. */
    private const val MaxTag = 64 * 1024 * 1024

    private const val Chunk = 64 * 1024
}
