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
        val turkishChars = charArrayOf('ç', 'Ç', 'ğ', 'Ğ', 'ı', 'İ', 'ö', 'Ö', 'ş', 'Ş', 'ü', 'Ü')
        val englishChars = charArrayOf('c', 'C', 'g', 'G', 'i', 'I', 'o', 'O', 's', 'S', 'u', 'U')
        
        var safeName = name
        for (i in turkishChars.indices) {
            safeName = safeName.replace(turkishChars[i], englishChars[i])
        }
        
        return safeName.take(50)
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
    }

    fun findLocalUri(context: Context, trackName: String): String? {
        val safeName = getSafeFileName(trackName)
        
        uriCache[safeName]?.let { return it }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("%$safeName%")
                
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        uriCache[safeName] = uri
                        return uri
                    }
                }
                
                context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        uriCache[safeName] = uri
                        return uri
                    }
                }
            } else {
                val dirMusic = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melodify")
                val dirDown = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Melodify")
                
                listOf(dirMusic, dirDown).forEach { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            if (file.name.contains(safeName, ignoreCase = true)) {
                                uriCache[safeName] = file.absolutePath
                                return file.absolutePath
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findLocalUri error", e)
        }
        return null
    }
    
    fun invalidateCache() {
        uriCache.clear()
    }
}