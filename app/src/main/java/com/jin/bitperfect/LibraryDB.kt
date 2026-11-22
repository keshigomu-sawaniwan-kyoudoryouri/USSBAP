package com.jin.bitperfect

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class LibraryDB(context: Context) : SQLiteOpenHelper(context, "library.db", null, 1) {

    companion object {
        const val TABLE = "files"
        const val COL_URI = "uri"
        const val COL_TITLE = "title"
        const val COL_ARTIST = "artist"
        const val COL_ALBUM = "album"
        const val COL_TRACK = "track_number"
        const val COL_DURATION = "duration"
        const val COL_MODIFIED = "last_modified"
        const val COL_SIZE = "file_size"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE (" +
            "$COL_URI TEXT PRIMARY KEY, $COL_TITLE TEXT, $COL_ARTIST TEXT, $COL_ALBUM TEXT, " +
            "$COL_TRACK INTEGER DEFAULT 0, $COL_DURATION INTEGER DEFAULT 0, " +
            "$COL_MODIFIED INTEGER DEFAULT 0, $COL_SIZE INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE"); onCreate(db)
    }

    fun saveFile(item: MusicItem, lastModified: Long, size: Long) {
        val cv = ContentValues().apply {
            put(COL_URI, item.contentUri.toString())
            put(COL_TITLE, item.title)
            put(COL_ARTIST, item.artist)
            put(COL_ALBUM, item.album)
            put(COL_TRACK, item.trackNumber)
            put(COL_DURATION, item.duration)
            put(COL_MODIFIED, lastModified)
            put(COL_SIZE, size)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteFile(uri: String) {
        writableDatabase.delete(TABLE, "$COL_URI = ?", arrayOf(uri))
    }

    fun getCachedInfo(uri: String): Pair<Long, Long>? {
        val c = readableDatabase.query(TABLE, arrayOf(COL_MODIFIED, COL_SIZE),
            "$COL_URI = ?", arrayOf(uri), null, null, null)
        c.use {
            if (it.moveToFirst()) {
                return Pair(it.getLong(0), it.getLong(1))
            }
        }
        return null
    }

    fun loadAll(): List<MusicItem> {
        val items = mutableListOf<MusicItem>()
        val c = readableDatabase.query(TABLE, null, null, null, null, null, "$COL_ALBUM, $COL_TRACK")
        c.use {
            while (it.moveToNext()) {
                items.add(MusicItem(
                    id = it.getString(c.getColumnIndexOrThrow(COL_URI)).hashCode().toLong(),
                    title = it.getString(c.getColumnIndexOrThrow(COL_TITLE)),
                    artist = it.getString(c.getColumnIndexOrThrow(COL_ARTIST)),
                    album = it.getString(c.getColumnIndexOrThrow(COL_ALBUM)),
                    trackNumber = it.getInt(c.getColumnIndexOrThrow(COL_TRACK)),
                    duration = it.getLong(c.getColumnIndexOrThrow(COL_DURATION)),
                    contentUri = Uri.parse(it.getString(c.getColumnIndexOrThrow(COL_URI))),
                    albumArtUri = null
                ))
            }
        }
        return items
    }
}
