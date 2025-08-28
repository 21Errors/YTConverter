package com.example.ytconverter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// RecyclerView Adapter for the conversion queue
class ConversionQueueAdapter(
    private val items: MutableList<ConversionItem>,
    private val onCancelClick: (ConversionItem) -> Unit
) : RecyclerView.Adapter<ConversionQueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.itemTitle)
        val statusText: TextView = view.findViewById(R.id.itemStatus)
        val progressText: TextView = view.findViewById(R.id.itemProgress)
        val cancelButton: Button = view.findViewById(R.id.cancelButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversion_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleText.text = item.title
        holder.statusText.text = item.status.name
        holder.progressText.text = item.progress

        // Set status color
        when (item.status) {
            ConversionStatus.WAITING -> holder.statusText.setTextColor(0xFF808080.toInt())
            ConversionStatus.CONVERTING -> holder.statusText.setTextColor(0xFF2196F3.toInt())
            ConversionStatus.COMPLETED -> holder.statusText.setTextColor(0xFF4CAF50.toInt())
            ConversionStatus.FAILED -> holder.statusText.setTextColor(0xFFFF5722.toInt())
            ConversionStatus.CANCELLED -> holder.statusText.setTextColor(0xFFFF9800.toInt())
        }

        // Show/hide cancel button
        holder.cancelButton.visibility = if (item.status == ConversionStatus.WAITING || item.status == ConversionStatus.CONVERTING) {
            View.VISIBLE
        } else {
            View.GONE
        }

        holder.cancelButton.setOnClickListener {
            onCancelClick(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateItem(position: Int) {
        notifyItemChanged(position)
    }

    fun notifyDataChanged() {
        notifyDataSetChanged()
    }
}