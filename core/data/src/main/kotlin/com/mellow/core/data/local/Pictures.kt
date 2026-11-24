package com.mellow.core.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The pictures the user has chosen as covers, for playlists and for songs.
 *
 * A chosen picture is copied into the app's own storage, because the original belongs to
 * the gallery and may be moved or deleted. Each is stored once under a name of its own;
 * whatever uses it remembers the name.
 */
internal class Pictures(context: Context) {

    private val context = context.applicationContext
    private val folder = File(this.context.filesDir, "covers")

    fun file(name: String): File = File(folder, name)

    /** Where a stored picture can be loaded from, as the rest of the app passes covers around. */
    fun address(name: String): String = Uri.fromFile(file(name)).toString()

    fun bytes(name: String): ByteArray? = file(name).takeIf(File::exists)?.readBytes()

    /**
     * Stores a JPEG and returns the name it was given. The name is new every time: the
     * image cache keys on the address, so reusing a name would keep showing the picture
     * it replaced.
     */
    fun store(jpeg: ByteArray, kind: String): String {
        folder.mkdirs()
        val name = "$kind-${System.currentTimeMillis()}-${jpeg.size}.jpg"
        file(name).writeBytes(jpeg)
        return name
    }

    fun delete(name: String?) {
        if (name != null) file(name).delete()
    }

    /** Deletes every stored picture of [kind] whose name is not in [keep]. */
    fun prune(kind: String, keep: Set<String>) {
        folder.listFiles { file -> file.name.startsWith("$kind-") && file.name !in keep }?.forEach(File::delete)
    }

    /**
     * Reads a picture the user picked and returns it as a JPEG no larger than a cover is
     * ever shown. A photo straight off a camera is many times that, and would be decoded
     * in full every time its cover scrolled into view. Null if it cannot be read as an image.
     */
    fun read(source: Uri): ByteArray? {
        val resolver = context.contentResolver
        return shrink(
            // Also turns the picture the right way up, which the older route does not.
            modern = { ImageDecoder.createSource(resolver, source) },
            older = { options -> resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) } },
        )
    }

    /** The same for a picture already in hand, such as the one inside a song's file. */
    fun read(picture: ByteArray): ByteArray? = shrink(
        modern = { ImageDecoder.createSource(ByteBuffer.wrap(picture)) },
        older = { options -> BitmapFactory.decodeByteArray(picture, 0, picture.size, options) },
    )

    /**
     * Decodes a picture no larger than [MaxSide] and encodes it as a JPEG. [older] is
     * asked twice before Android 9: once only for the picture's size, then for the picture.
     */
    private fun shrink(
        modern: () -> ImageDecoder.Source,
        older: (BitmapFactory.Options) -> Bitmap?,
    ): ByteArray? = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(modern()) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MaxSide) {
                    val scale = MaxSide / longest.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            older(bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MaxSide) sample *= 2
            older(BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bitmap?.let {
            ByteArrayOutputStream().use { out ->
                it.compress(Bitmap.CompressFormat.JPEG, Quality, out)
                it.recycle()
                out.toByteArray()
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        /** The largest a cover is drawn, with room to spare on a dense screen. */
        const val MaxSide = 1080
        const val Quality = 88
    }
}
