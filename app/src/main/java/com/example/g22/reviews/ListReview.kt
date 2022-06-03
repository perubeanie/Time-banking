package com.example.g22.reviews

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RatingBar
import android.widget.TextView
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.g22.R
import com.example.g22.model.Review
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView

class ReviewAdapter(private var context: Context, private var data: List<Review>): RecyclerView.Adapter<ReviewAdapter.ReviewViewHolder>() {
    class ReviewViewHolder(v: View): RecyclerView.ViewHolder(v) {
        private val reviewerName : TextView = v.findViewById(R.id.reviewer_name_review_item)
        private val reviewDescription : TextView = v.findViewById(R.id.comment_review_item)
        private val ratingBar : RatingBar = v.findViewById(R.id.ratingBar_review_item)
        private val skill : TextView = v.findViewById(R.id.skill_review_item)
        private val reviewerImage : CircleImageView = v.findViewById(R.id.reviewer_image_review_item)

        fun bind(reviewerId: String, reviewer: String, description: String, rating: String, timeSlotTitle: String, context: Context) {
            reviewerName.text = reviewer
            reviewDescription.text = description
            ratingBar.rating = rating.toFloat()
            this.skill.text = timeSlotTitle
            val storage = Firebase.storage("gs://time-banking-9318d.appspot.com").reference
            storage.child("$reviewerId.jpg").downloadUrl.addOnSuccessListener {
                val imageURL = it.toString()
                Glide.with(context)
                    .load(imageURL)
                    .into(reviewerImage)
            }
        }

        fun unbind() {
        }
    }

    private lateinit var navController: NavController

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReviewViewHolder {
        val vg = LayoutInflater
            .from(parent.context)
            .inflate(R.layout.review_item, parent, false)
        navController = parent.findNavController()
        return ReviewViewHolder(vg)
    }

    override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) {
        val item = data[position]

        holder.bind(item.reviewerId, item.reviewer, item.description, item.rating, item.timeSlotTitle, context)
    }

    override fun getItemCount(): Int = data.size

    override fun onViewRecycled(holder: ReviewViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    fun updateList(reviewList: List<Review>) {
        data = reviewList
        notifyDataSetChanged()
    }
}