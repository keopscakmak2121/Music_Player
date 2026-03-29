package com.example.musicplayer.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── Entities ───────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_songs")
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val videoId: String, // Bu alan hem YouTube ID hem de Yerel Dosya Yolu tutuyor
    val title: String,
    val author: String,
    val thumbnail: String,
    val duration: Int,
    val addedAt: Long = System.currentTimeMillis()
)

// ─── DAOs ────────────────────────────────────────────────────

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: Long)
}

@Dao
interface PlaylistSongDao {
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun getSongsInPlaylist(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Insert
    suspend fun insertSong(song: PlaylistSongEntity)

    @Delete
    suspend fun deleteSong(song: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE videoId = :videoId")
    suspend fun deleteSongByVideoId(videoId: String)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun isSongInPlaylist(playlistId: Long, videoId: String): Int

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE videoId = :videoId")
    suspend fun isSongInAnyPlaylist(videoId: String): Int
}

// ─── Database ────────────────────────────────────────────────

@Database(entities = [PlaylistEntity::class, PlaylistSongEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "melodify_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
