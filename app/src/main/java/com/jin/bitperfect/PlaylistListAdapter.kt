package com.jin.bitperfect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistListAdapter(
    private var playlists: List<Playlist>,
    private val onItemClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistListAdapter.ViewHolder>() {

    var onMenuAction: ((Playlist, String) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPlaylistName: TextView = view.findViewById(R.id.tvPlaylistName)
        val tvPlaylistCount: TextView = view.findViewById(R.id.tvPlaylistCount)
        val btnPlaylistMenu: View = view.findViewById(R.id.btnPlaylistMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pl = playlists[position]
        holder.tvPlaylistName.text = pl.name
        holder.tvPlaylistCount.text = if (pl.songs.size == 1) "1 song" else "${pl.songs.size} songs"
        holder.btnPlaylistMenu.setOnClickListener {
            val popup = android.widget.PopupMenu(it.context, it)
            popup.menu.add("Rename")
            popup.menu.add("Delete")
            popup.setOnMenuItemClickListener { mi ->
                when (mi.title) {
                    "Rename" -> onMenuAction?.invoke(pl, "RENAME")
                    "Delete" -> onMenuAction?.invoke(pl, "DELETE")
                }
                true
            }
            popup.show()
        }
        holder.itemView.setOnClickListener { onItemClick(pl) }
    }

    override fun getItemCount() = playlists.size

    fun updateList(newList: List<Playlist>) {
        playlists = newList
        notifyDataSetChanged()
    }
}
