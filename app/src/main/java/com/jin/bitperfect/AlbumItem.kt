package com.jin.bitperfect

data class AlbumItem(
    val name: String,
    val songs: List<MusicItem>
) {
    // アルバムの代表的なジャケット画像（最初の曲から取得）
    val representativeSong: MusicItem? = songs.firstOrNull()
}

data class ArtistItem(
    val name: String,
    val songs: List<MusicItem>
) {
    val representativeSong: MusicItem? = songs.firstOrNull()
}

data class LibraryCategory(
    val name: String,
    val subtitle: String,
    val action: String
)
