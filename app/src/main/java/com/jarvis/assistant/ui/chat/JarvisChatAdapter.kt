package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(turn: ChatTurn) {
            val context = binding.root.context

            // User Bubble
            if (turn.userTranscript.isNotBlank()) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                binding.tvUserMessage.text = turn.userTranscript

                binding.btnUserCopy.setOnClickListener {
                    copyToClipboard(context, "User message", turn.userTranscript)
                }

                binding.btnUserEdit.setOnClickListener {
                    onEditUserMessage?.invoke(turn.userTranscript)
                }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            // Assistant Bubble
            if (turn.jarvisResponse.isNotBlank()) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE
                binding.tvAssistantMessage.text = turn.jarvisResponse

                binding.btnAssistantCopy.setOnClickListener {
                    copyToClipboard(context, "Jarvis response", turn.jarvisResponse)
                }

                binding.btnAssistantPlay.setOnClickListener {
                    Toast.makeText(context, "Playing response...", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
            }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
