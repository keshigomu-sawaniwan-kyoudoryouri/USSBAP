package com.jin.bitperfect

data class Playlist(
    val id: Long,
    var name: String,
    val songs: MutableList<MusicItem>
)
