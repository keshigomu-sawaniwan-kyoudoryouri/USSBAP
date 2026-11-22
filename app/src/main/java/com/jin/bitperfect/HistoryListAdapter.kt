package com.jin.bitperfect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryListAdapter(
    private var items: List<StreamHistoryStore.Entry>,
    private val onItemClick: (StreamHistoryStore.Entry) -> Unit,
    private val onMenuAction: ((StreamHistoryStore.Entry, String) -> Unit)?
) : RecyclerView.Adapter<HistoryListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivArt: ImageView = view.findViewById(R.id.ivHistoryArt)
        val tvTitle: TextView = view.findViewById(R.id.tvHistoryTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvHistoryArtist)
        val btnMenu: View = view.findViewById(R.id.btnHistoryMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.tvTitle.text = entry.title
        holder.tvArtist.text = entry.uploader
        holder.ivArt.setImageResource(R.drawable.ic_default_art)
        holder.itemView.setOnClickListener { onItemClick(entry) }
        holder.btnMenu.setOnClickListener {
            onMenuAction?.invoke(entry, "PLAYLIST")
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<StreamHistoryStore.Entry>) {
        items = newList
        notifyDataSetChanged()
    }
}
