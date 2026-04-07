package com.example.musicplayer

import android.content.Context
import android.content.SharedPreferences
import com.example.musicplayer.model.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object RecentlyPlayedManager {
    
    private const val PREFS_NAME = "recently_played"
    private const val KEY_RECENT_TRACKS = "recent_tracks"
    private const val MAX_SIZE = 20
    
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun addTrack(track: Track) {
        val tracks = getTracks().toMutableList()
        
        // Aynı şarkı varsa önce çıkar
        tracks.removeAll { it.id == track.id }
        
        // Başa ekle
        tracks.add(0, track)
        
        // Max boyutu koru
        while (tracks.size > MAX_SIZE) {
            tracks.removeAt(tracks.size - 1)
        }
        
        saveTracks(tracks)
    }
    
    fun getTracks(): List<Track> {
        val json = prefs.getString(KEY_RECENT_TRACKS, "[]")
        val type = object : TypeToken<List<Track>>() {}.type
        return gson.fromJson(json, type)
    }
    
    fun clearAll() {
        saveTracks(emptyList())
    }
    
    private fun saveTracks(tracks: List<Track>) {
        val json = gson.toJson(tracks)
        prefs.edit().putString(KEY_RECENT_TRACKS, json).apply()
    }
}