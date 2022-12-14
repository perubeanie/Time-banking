package com.example.g22.TimeSlotList

import android.content.IntentSender
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.g22.R
import com.example.g22.custom_format
import com.example.g22.model.Conversation
import com.example.g22.model.Message
import com.example.g22.model.TimeSlot
import com.example.g22.toAdvertisementList
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class MessageAdapter(private var data: List<Message>): RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {
    class MessageViewHolder(v: View): RecyclerView.ViewHolder(v) {
        private val messageCard: CardView = v.findViewById(R.id.message_item_card)
        private val messageTV : TextView = v.findViewById(R.id.message_item_textview)
        private val timeTV : TextView = v.findViewById(R.id.message_time_textview)
        private val cl : ConstraintLayout = v.findViewById(R.id.external_cl_message_item)

        fun bind(message: String, time: String, sender: String) {
            messageTV.text = message
            timeTV.text = time
            val cs = ConstraintSet()
            cs.clone(cl)
            cs.clear(R.id.message_item_card, ConstraintSet.START)
            cs.clear(R.id.message_item_card, ConstraintSet.END)
            cs.clear(R.id.message_time_textview, ConstraintSet.START)
            cs.clear(R.id.message_time_textview, ConstraintSet.END)
            if(sender == "${Firebase.auth.currentUser!!.uid}") {
                cs.connect(
                    R.id.message_item_card,
                    ConstraintSet.END,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.END
                )
                cs.connect(
                    R.id.message_time_textview,
                    ConstraintSet.END,
                    R.id.message_item_card,
                    ConstraintSet.END
                )
            }
            else
            {
                cs.connect(
                    R.id.message_item_card,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START
                )
                cs.connect(
                    R.id.message_time_textview,
                    ConstraintSet.START,
                    R.id.message_item_card,
                    ConstraintSet.START
                )
            }
            cs.applyTo(cl)
        }

        fun unbind() {

        }
    }

    private lateinit var navController: NavController

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val vg = LayoutInflater
            .from(parent.context)
            .inflate(R.layout.message_item, parent, false)
        navController = parent.findNavController()
        return MessageViewHolder(vg)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = data[position]
        val date = item.time

        holder.bind(item.text, date!!.custom_format(), item.sender)
//        holder.bind(item.text, "${date!!.date.toString().padStart(2, '0')}" +
//                "/${(date.month + 1).toString().padStart(2, '0')}" +
//                "/${(date.year - 100).toString().padStart(2, '0')} - " +
//                "${date.hours.toString().padStart(2, '0')}" +
//                ":${date.minutes.toString().padStart(2, '0')}", item.sender)
    }

    override fun getItemCount(): Int = data.size

    override fun onViewRecycled(holder: MessageViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    fun updateList(messageList: List<Message>) {
        val diffs = DiffUtil.calculateDiff(MessageListCallback(data, messageList))
        data = messageList
        diffs.dispatchUpdatesTo(this)
    }

    fun addMessage(message: Message) {
        data = data.plus(message)
        notifyItemInserted(itemCount)
    }

    class MessageListCallback(
        private val oldList: List<Message>,
        private val newList: List<Message>
    ): DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].messageId == newList[newItemPosition].messageId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val same = oldList[oldItemPosition].sender == newList[newItemPosition].sender &&
                    oldList[oldItemPosition].receiver == newList[newItemPosition].receiver &&
                    oldList[oldItemPosition].text == newList[newItemPosition].text &&
                    oldList[oldItemPosition].offer == newList[newItemPosition].offer &&
                    oldList[oldItemPosition].conversationId == newList[newItemPosition].conversationId &&
                    oldList[oldItemPosition].time == newList[newItemPosition].time
            return same

        }
    }


}