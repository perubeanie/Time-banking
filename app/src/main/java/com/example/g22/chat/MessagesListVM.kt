package com.example.g22.chat

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.g22.Event
import com.example.g22.SnackbarMessage
import com.example.g22.addMessage
import com.example.g22.model.Conversation
import com.example.g22.model.Message
import com.example.g22.model.Status
import com.example.g22.utils.Duration
import com.google.android.gms.tasks.Task
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class MessagesListVM(application: Application) : AndroidViewModel(application) {
    private val db = FirebaseFirestore.getInstance()

    private var msgListListenerRegistration: ListenerRegistration? = null
    private var convStatusListenerRegistration: ListenerRegistration? = null

    private val _messageListLD: MutableLiveData<List<Message>> =
        MutableLiveData<List<Message>>().also {
            it.value = emptyList()
        }


    val messageListLD: LiveData<List<Message>> = _messageListLD

    var conversationId: MutableLiveData<String> = MutableLiveData<String>("")

    private val _conversationStatusLD: MutableLiveData<Status?> = MutableLiveData<Status?>()

    val conversationStatusLD = _conversationStatusLD

    // Snackbar handling
    private val _snackbarMessages = MutableLiveData<List<Event<SnackbarMessage>>>(emptyList())
    val snackbarMessages: LiveData<List<Event<SnackbarMessage>>>
        get() = _snackbarMessages

    fun observeMessages(receiver: String, timeSlotId: String) {
        val users = listOf<String>("${Firebase.auth.currentUser!!.uid}", receiver)

        msgListListenerRegistration?.remove()

        msgListListenerRegistration = db.collection("chats")
            .whereEqualTo("offer", timeSlotId)
            .whereIn("sender", users)
            .addSnapshotListener(Dispatchers.IO.asExecutor()) { value, error ->
                if (error != null) {
                    return@addSnapshotListener
                }

                if (value != null && !value.isEmpty) {
                    val result = value.toObjects(Message::class.java).filter {
                        users.contains(it.receiver)
                    }
                    if (result.isNotEmpty()) {
                        conversationId.postValue(result[0].conversationId)
                        _messageListLD.postValue(result.sortedBy { it.time.toString() })
                    } else {
                        _messageListLD.postValue(emptyList())
                    }
                } else {
                    _messageListLD.postValue(emptyList())
                }

            }
    }

    fun observeConversationStatus() {
        convStatusListenerRegistration?.remove()
        if (conversationId.value != "") {
            convStatusListenerRegistration = db.collection("conversations")
                .document(conversationId.value!!)
                .addSnapshotListener(Dispatchers.IO.asExecutor()) { value, error ->
                    if (error != null) {
                        return@addSnapshotListener
                    }

                    if (value != null) {
                        val tmpStatusStr = value.getString("status")

                        var tmpStatus = Status.CONFIRMED
                        if (tmpStatusStr == "CONFIRMED")
                            tmpStatus = Status.CONFIRMED
                        else if (tmpStatusStr == "REJECTED")
                            tmpStatus = Status.REJECTED
                        else if (tmpStatusStr == "REJECTED_BALANCE")
                            tmpStatus = Status.REJECTED_BALANCE
                        else
                            tmpStatus = Status.PENDING

                        _conversationStatusLD.postValue(tmpStatus)
                    }
                }
        }
    }

    fun resetNotifications() {
        viewModelScope.launch {
            firebaseResetNotifications(conversationId.value!!, Firebase.auth.currentUser!!.uid)
        }
    }

    private suspend fun firebaseResetNotifications(conversationId: String, userId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                db.runTransaction { transaction ->
                    val convRef = db.collection("conversations").document(conversationId)
                    val requestorUid = transaction.get(convRef).get("requestorUid")
                    transaction.update(
                        convRef,
                        if (requestorUid == userId) "requestorUnseen" else "receiverUnseen",
                        0
                    )
                }.await()

                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    fun resetConversationStatus() {
        _conversationStatusLD.value = null
    }

    fun createMessage(
        receiver: String,
        timeSlotId: String,
        message: String,
        offerTitle: String,
        receiverName: String
    ) {
        if (Firebase.auth.currentUser == null)
            return

        GlobalScope.launch {
            val res = firebaseCreateMessage(
                Firebase.auth.currentUser!!, timeSlotId, offerTitle, receiver, receiverName, message)

            if (res.isSuccess) {
                viewModelScope.launch {
                    if (conversationId.value!! == "")
                        conversationId.value = res.getOrThrow().id
                }
            } else {
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Unable to send the message!", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private suspend fun firebaseCreateMessage(user: FirebaseUser,
                                              timeSlotId: String,
                                              offerTitle: String,
                                              receiver: String,
                                              receiverName: String,
                                                message: String): Result<DocumentReference> {
        return withContext(Dispatchers.IO) {

            try {
                val ref = db.collection("conversations").document()
                db.runTransaction { transaction ->
                    var requestorUid = ""
                    var oldReceiverUnseen = 0
                    var oldRequestorUnseen = 0

                    val oldProposalsCounter = transaction.get(
                        db.collection("offers").document(timeSlotId),
                    ).getLong("proposalsCounter")

                    val userRef = db.collection("users").document(user.uid)
                    var currentUserName = transaction.get(userRef).get("fullname").toString()
                    if (conversationId.value!! != "") {
                        val updateNotRef =
                            db.collection("conversations").document(conversationId.value!!)
                        val queryResult = transaction.get(updateNotRef)
                        requestorUid = queryResult.get("requestorUid").toString()
                        oldReceiverUnseen = queryResult.get("receiverUnseen").toString().toInt()
                        oldRequestorUnseen =
                            queryResult.get("requestorUnseen").toString().toInt()
                    }

                    val chatRef = db.collection("chats").document()
                    transaction.set(
                        chatRef, Message(
                            chatRef.id,
                            timeSlotId,
                            receiver,
                            "${Firebase.auth.currentUser!!.uid}",
                            message,
                            null,
                            if (conversationId.value!! == "") ref.id else conversationId.value!!
                        )
                    )
                    if (messageListLD.value?.size == 0) {
                        transaction.set(
                            ref,
                            Conversation(
                                ref.id,
                                timeSlotId,
                                user.uid,
                                receiver,
                                offerTitle,
                                currentUserName,
                                receiverName,
                                1,
                                0,
                                Status.PENDING
                            )
                        )
                        transaction.update(
                            db.collection("offers").document(timeSlotId),
                            "proposalsCounter",
                            oldProposalsCounter?.plus(1)
                        )
                    } else {
                        val updateNotRef =
                            db.collection("conversations").document(conversationId.value!!)
                        val userToNotify =
                            if (receiver == requestorUid) "requestorUnseen" else "receiverUnseen"
                        transaction.update(
                            updateNotRef,
                            userToNotify,
                            if (userToNotify == "requestorUnseen") oldRequestorUnseen + 1 else oldReceiverUnseen + 1
                        )
                    }
                }.await()

                return@withContext Result.success(ref)
            } catch(e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    fun clearList() {
        _messageListLD.value = emptyList()
    }

    fun confirmRequest(offerId: String) {
        GlobalScope.launch {
            val res = firebaseConfirmRequest(conversationId.value!!)
            val result = res.getOrNull()
            if (res.isSuccess && result == true) {
                val rejRes = firebaseRejectOthers(offerId)
                if (rejRes.isSuccess) {
                    // TODO
                } else {
                    _snackbarMessages.addMessage("Error while rejecting other proposals!", Snackbar.LENGTH_LONG)
                }
            }
            else {
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Error while accepting offer!", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    fun rejectRequest() {
        GlobalScope.launch {
            val res = firebaseRejectRequest(
                conversationId.value!!,
                _messageListLD.value!!.first().offer
            )

            if (res.isSuccess) {
                viewModelScope.launch {
                    _conversationStatusLD.value = Status.REJECTED
                }
            } else {
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Error while rejecting request!", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    private suspend fun firebaseConfirmRequest(conversationId: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                var sufficientCredit = false
                db.runTransaction { transaction ->
                    val convRef = db.collection("conversations").document(conversationId)
                    val convResult = transaction.get(convRef)
                    val requestorUid: String =
                        convResult.getString("requestorUid") ?: throw Exception("Requestor unavailable")
                    val receiverUid: String =
                        convResult.getString("receiverUid") ?: throw Exception("Receiver unavailable")
                    val offerId: String = convResult.getString("offerId") ?: throw Exception("Invalid offer Id")

                    val requestorRef = db.collection("users").document(requestorUid)
                    val creditReq: Int =
                        transaction.get(requestorRef).get("credit").toString().toInt()

                    val receiverRef = db.collection("users").document(receiverUid)
                    val creditRec: Int =
                        transaction.get(receiverRef).get("credit").toString().toInt()

                    val offerRef = db.collection("offers").document(offerId)
                    val offerDuration =
                        transaction.get(offerRef).get("duration", Duration::class.java)?.minutes
                            ?: throw Exception("Cannot get offer's duration")
                    if (creditReq >= offerDuration) {
                        // the user has enough credits
                        transaction.update(requestorRef, "credit", creditReq - offerDuration)
                        transaction.update(convRef, "status", Status.CONFIRMED)
                        transaction.update(offerRef, "accepted", true)
                        transaction.update(receiverRef, "credit", creditRec + offerDuration)
                        sufficientCredit = true

                        // reject other conversations

                    } else {
                        val rejRef = db.collection("conversations")
                            .document(conversationId)
                        transaction.update(rejRef, "status", Status.REJECTED_BALANCE)
                    }
                }.await()

                return@withContext Result.success(sufficientCredit)
            } catch(e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun firebaseRejectOthers(timeslotId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val convs = db.collection("conversations")
                    .whereEqualTo("offerId", timeslotId)
                    .whereEqualTo("status", Status.PENDING)
                    .get().await()

                val jobs = emptyList<Task<Void>>().toMutableList()
                for (c in convs) {
                    val job = db.collection("conversations")
                        .document(c.id)
                        .update("status", Status.REJECTED)
                    jobs.add(job)
                }
                for (j in jobs) {
                    j.await()
                }

                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }


    private suspend fun firebaseRejectRequest(conversationId: String, offerId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                db.runTransaction { transaction ->
                    val convRef = db.collection("conversations")
                        .document(conversationId)

                    val offerRef = db.collection("offers").document(offerId)
                    val oldCounter = transaction.get(offerRef)
                        .getLong("proposalsCounter")

                    transaction.update(convRef,"status", Status.REJECTED)

                    transaction.update(offerRef, "proposalsCounter", oldCounter!!.minus(1))

                }
                    .addOnSuccessListener {
                        _conversationStatusLD.value = Status.REJECTED
                    }

                return@withContext Result.success(Unit)
            } catch(e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        convStatusListenerRegistration?.remove()
        msgListListenerRegistration?.remove()
    }

}