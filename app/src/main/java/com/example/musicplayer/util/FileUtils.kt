package com.example.musicplayer.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object FileUtils {
    private const val TAG = "FileUtils"
    
    // Önbellek: trackName -> uri
    private val uriCache = ConcurrentHashMap<String, String>()

    fun getSafeFileName(name: String): String {
        return name.take(50).replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
    }

    fun findLocalUri(context: Context, trackName: String): String? {
        val safeName = getSafeFileName(trackName)
        
        // Önbellekten kontrol et
        uriCache[safeName]?.let { return it }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("%$safeName%")
                
                // Müzik klasöründe ara
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        uriCache[safeName] = uri
                        return uri
                    }
                }
                
                // İndirilenler klasöründe ara
                context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        uriCache[safeName] = uri
                        return uri
                    }
                }
            } else {
                val mp3 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify/$safeName.mp3")
                if (mp3.exists()) {
                    uriCache[safeName] = mp3.absolutePath
                    return mp3.absolutePath
                }
                val mp4 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify/$safeName.mp4")
                if (mp4.exists()) {
                    uriCache[safeName] = mp4.absolutePath
                    return mp4.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findLocalUri error", e)
        }
        return null
    }
    
    // Önbelleği temizle (yeni dosya indirildiğinde çağır)
    fun invalidateCache() {
        uriCache.clear()
    }
}