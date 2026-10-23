package com.jarvis.assistant.ui.memory

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.databinding.ItemMemoryBinding

class MemoryAdapter(
    private val onEdit: (MemoryEntity) -> Unit,
    private val onDelete: (MemoryEntity) -> Unit
) : ListAdapter<MemoryEntity, MemoryAdapter.MemoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryViewHolder {
        val binding = ItemMemoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MemoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MemoryViewHolder(private val binding: ItemMemoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MemoryEntity) {
            binding.tvMemoryFact.text = item.rawText
            binding.tvMemoryDate.text = item.formattedDate
            binding.tvMemoryCategory.text = item.category.uppercase()

            val context = binding.root.context
            val colorRes = when (item.category) {
                MemoryEntity.CATEGORY_PREFERENCES -> R.color.primary_cyan
                MemoryEntity.CATEGORY_ROUTINES -> R.color.neon_blue
                MemoryEntity.CATEGORY_PERSONAL -> R.color.purple_glow
                MemoryEntity.CATEGORY_CONTACTS -> R.color.status_green
                else -> R.color.status_amber
            }
            val color = ContextCompat.getColor(context, colorRes)
            binding.tvMemoryCategory.setTextColor(color)

            binding.btnEditMemory.setOnClickListener { onEdit(item) }
            binding.btnDeleteMemory.setOnClickListener { onDelete(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MemoryEntity>() {
        override fun areItemsTheSame(oldItem: MemoryEntity, newItem: MemoryEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MemoryEntity, newItem: MemoryEntity): Boolean =
            oldItem == newItem
    }
}
