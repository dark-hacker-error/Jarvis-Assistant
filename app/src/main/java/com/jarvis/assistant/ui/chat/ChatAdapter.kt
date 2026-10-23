package com.jarvis.assistant.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemChatTurnBinding

class ChatAdapter : ListAdapter<ChatTurn, ChatAdapter.ChatViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatTurnBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(private val binding: ItemChatTurnBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(turn: ChatTurn) {
            binding.tvTimestamp.text = turn.formattedTime
            binding.tvUserTranscript.text = turn.userTranscript
            binding.tvJarvisResponse.text = turn.jarvisResponse
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean =
            oldItem == newItem
    }
}
