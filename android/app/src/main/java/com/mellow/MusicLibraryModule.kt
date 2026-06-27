package com.mellow

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import java.util.concurrent.Executors

/**
 * Reads the device's music library from MediaStore and hands it to JS.
 *
 * Generated spec base class (`NativeMusicLibrarySpec`) comes from codegen off
 * `specs/NativeMusicLibrary.ts`; we only implement the declared methods.
 *
 * Note: this does NOT request the audio permission — JS does that (via
 * PermissionsAndroid) before calling, so the UI can drive the prompt. Without
 * the grant MediaStore simply returns nothing and we resolve an empty list.
 */
class MusicLibraryModule(reactContext: ReactApplicationContext) :
  NativeMusicLibrarySpec(reactContext) {

  // MediaStore queries hit the disk — keep them off the JS/native-modules thread.
  private val executor = Executors.newSingleThreadExecutor()

  // Album-art is exposed under this (long-lived, undocumented but stable) uri,
  // keyed by album id. Empty string when a track has no album art.
  private val albumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

  override fun getName(): String = NAME

  override fun getSongs(promise: Promise) {
    executor.execute {
      try {
        promise.resolve(querySongs())
      } catch (e: Exception) {
        promise.reject("E_MUSIC_QUERY", "Failed to read the music library", e)
      }
    }
  }

  private fun querySongs(): WritableArray {
    val out = Arguments.createArray()

    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
    )
    // Real tracks only — drops ringtones, notifications, alarms, podcasts.
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

    reactApplicationContext.contentResolver.query(
      collection, projection, selection, null, sortOrder,
    )?.use { cursor ->
      val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
      val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
      val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
      val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
      val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
      val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idCol)
        val albumId = cursor.getLong(albumIdCol)
        val contentUri = ContentUris.withAppendedId(collection, id)
        val artworkUri = ContentUris.withAppendedId(albumArtBase, albumId)

        val song = Arguments.createMap().apply {
          putString("id", id.toString())
          putString("title", cursor.getString(titleCol) ?: "")
          putString("artist", cursor.getString(artistCol) ?: "")
          putString("album", cursor.getString(albumCol) ?: "")
          putDouble("durationMs", cursor.getLong(durationCol).toDouble())
          putString("url", contentUri.toString())
          putString("artwork", artworkUri.toString())
        }
        out.pushMap(song)
      }
    }

    return out
  }

  companion object {
    const val NAME = "MusicLibrary"
  }
}
