package com.jin.bitperfect

import android.net.Uri

data class MusicItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int, // アルバム内の曲順用
    val duration: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    val format: String = ""
)
