package com.jin.bitperfect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MusicListAdapter(
    private var items: List<Any>,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<MusicListAdapter.ViewHolder>() {

    var onMenuAction: ((MusicItem, String) -> Unit)? = null
    var onSelectionChanged: ((List<MusicItem>) -> Unit)? = null
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAlbumArt: ImageView = view.findViewById(R.id.ivAlbumArt)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvArtist)
        val btnItemMenu: View = view.findViewById(R.id.btnItemMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_music, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val secure = item is MusicItem && item.format.startsWith("SECURE_")
        holder.itemView.setBackgroundColor(if (item is MusicItem && item.id in selectedIds) 0x6636D9FF.toInt() else 0x00000000)
        
        holder.ivAlbumArt.setImageResource(R.drawable.ic_default_art)

        val targetMusicItem = when (item) {
            is MusicItem -> {
                holder.tvTitle.text = item.title
                holder.tvArtist.text = item.artist
                holder.btnItemMenu.visibility = if (selectionMode) View.GONE else View.VISIBLE
                holder.btnItemMenu.setOnClickListener { showPopupMenu(it, item) }
                item
            }
            is AlbumItem -> {
                holder.tvTitle.text = item.name
                holder.tvArtist.text = context.getString(R.string.songs_count, item.songs.size)
                holder.btnItemMenu.visibility = View.GONE
                item.representativeSong
            }
            is ArtistItem -> {
                holder.tvTitle.text = item.name
                holder.tvArtist.text = context.getString(R.string.songs_count, item.songs.size)
                holder.btnItemMenu.visibility = View.GONE
                item.representativeSong
            }
            is LibraryCategory -> {
                holder.tvTitle.text = item.name
                holder.tvArtist.text = item.subtitle
                holder.btnItemMenu.visibility = View.GONE
                null
            }
            else -> null
        }

        if (targetMusicItem != null) {
            AlbumArtLoader.load(context, targetMusicItem.albumArtUri ?: targetMusicItem.contentUri, holder.ivAlbumArt)
        }

        holder.itemView.setOnClickListener {
            if (selectionMode && secure && item is MusicItem) {
                if (!selectedIds.add(item.id)) selectedIds.remove(item.id)
                notifyItemChanged(position); onSelectionChanged?.invoke(selectedMusicItems())
            } else onItemClick(item)
        }
        holder.itemView.setOnLongClickListener {
            if (secure && item is MusicItem) { setSelectionMode(true); selectedIds.add(item.id); notifyDataSetChanged(); onSelectionChanged?.invoke(selectedMusicItems()); true } else false
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<Any>) {
        items = newList
        selectedIds.retainAll(newList.filterIsInstance<MusicItem>().map { it.id }.toSet())
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) { selectionMode = enabled; if (!enabled) selectedIds.clear(); notifyDataSetChanged(); onSelectionChanged?.invoke(selectedMusicItems()) }
    fun selectAllSecure() { if (!selectionMode) selectionMode = true; selectedIds.clear(); selectedIds.addAll(items.filterIsInstance<MusicItem>().filter { it.format.startsWith("SECURE_") }.map { it.id }); notifyDataSetChanged(); onSelectionChanged?.invoke(selectedMusicItems()) }
    fun selectedMusicItems(): List<MusicItem> = items.filterIsInstance<MusicItem>().filter { it.id in selectedIds }

    private fun showPopupMenu(view: View, item: MusicItem) {
        val popup = android.widget.PopupMenu(view.context, view)
        popup.menu.add(0, MENU_ID_NEXT, 0, view.context.getString(R.string.menu_play_next))
        popup.menu.add(0, MENU_ID_ADD, 1, view.context.getString(R.string.menu_add_to_queue))
        popup.menu.add(0, MENU_ID_PLAYLIST, 2, view.context.getString(R.string.menu_add_to_playlist))
        if (item.format.startsWith("SECURE_")) popup.menu.add(0, MENU_ID_DELETE, 3, "削除")
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_ID_NEXT -> onMenuAction?.invoke(item, "NEXT")
                MENU_ID_ADD -> onMenuAction?.invoke(item, "ADD")
                MENU_ID_PLAYLIST -> onMenuAction?.invoke(item, "PLAYLIST")
                MENU_ID_DELETE -> onMenuAction?.invoke(item, "DELETE")
            }
            true
        }
        popup.show()
    }

    companion object {
        private const val MENU_ID_NEXT = 1
        private const val MENU_ID_ADD = 2
        private const val MENU_ID_PLAYLIST = 3
        private const val MENU_ID_DELETE = 4
    }

}
