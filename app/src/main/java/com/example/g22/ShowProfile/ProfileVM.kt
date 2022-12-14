package com.example.g22.ShowProfile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.g22.*
import com.example.g22.model.Profile
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.*

class ProfileVM(application: Application) : AndroidViewModel(application) {

    // Firebase
    private val db = FirebaseFirestore.getInstance()
    private val storage = Firebase.storage("gs://time-banking-9318d.appspot.com").reference

    private var profileListenerRegistration: ListenerRegistration? = null
    private var otherProfileListenerRegistration: ListenerRegistration? = null

    // Live data
    // PERSISTENT: Profile data (always kept synchronized with the database snapshot)
    private val _profileLD: MutableLiveData<Profile> = MutableLiveData<Profile>()
    // PERSISTENT: Profile picture (always kept synchronized with the persistent state)
    private val _profileImageLD: MutableLiveData<String> = MutableLiveData<String>(String.NullImagePath())

    private val _otherProfileLD = MutableLiveData<Profile>()
    private val _otherProfileImageLD: MutableLiveData<String> = MutableLiveData(String.NullImagePath())

    // EDIT-MODE: For temporary skills during edit mode
    private val _editSkillsLD = MutableLiveData<List<String>>()

    val profileLoadedLD = MutableLiveData<Boolean>(false)

    val editProfileLoadedLD = MutableLiveData<Boolean>(false)

    /**
     * Exposed functionalities to activities / fragments
     */

    val profileLD: LiveData<Profile> = _profileLD
    val profileImageLD: LiveData<String> = _profileImageLD
    val editSkillsLD: LiveData<List<String>> = _editSkillsLD
    val otherProfileLD: LiveData<Profile> = _otherProfileLD
    val otherProfileImageLD: LiveData<String> = _otherProfileImageLD

    val hasBeenModifiedProfileImageLD: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)

    // Snackbar handling
    private val _snackbarMessages = MutableLiveData<List< Event<SnackbarMessage> >>(emptyList())
    val snackbarMessages: LiveData<List< Event<SnackbarMessage> >>
        get() = _snackbarMessages

    fun addMessage(msg: String, duration: Int) {
        _snackbarMessages.addMessage(msg, duration)
    }


    init {
        Firebase.auth.addAuthStateListener {
            // It gets called when subscribed and whenever a login/logout happens
            observeRemoteUser()
            if (it.currentUser != null)
                downloadProfileImage(null)
        }
    }

    // LiveData public handling
    fun addSkill(skill: String) {
        _editSkillsLD.value = _editSkillsLD.value?.plus(skill)
    }

    fun removeSkill(skill: String) {
        _editSkillsLD.value = _editSkillsLD.value?.minus(skill)
    }

    fun updateEditingProfileData(profile: Profile) {
        _editSkillsLD.value = profile.skills
    }

    // Save the updated profile to database
    fun saveEditedProfile(fullname: String, email: String, nickname: String, location: String,
                          phone: String, description: String) {
        if (Firebase.auth.currentUser == null)
            return

        // Prepare profile object
        val tmp = _profileLD.value?.copy() ?: return
        tmp.fullname = fullname
        tmp.email = email
        tmp.nickname = nickname
        tmp.phone = phone
        tmp.location = location
        tmp.skills = _editSkillsLD.value?.map { it.lowercase() } ?: emptyList()
        tmp.description = description

        // We use global scope to ensure the db writing independently from the view model lifecycle
        GlobalScope.launch {
            val result = firestoreSetProfile(tmp)

            if (result.isSuccess) {

                // Cause the update of the profile picture
                viewModelScope.launch {
                    _profileImageLD.value = String.LoadingImagePath()
                }

                val resultUpload = uploadNewProfileImageToFirebase()

                viewModelScope.launch {
                    _snackbarMessages.addMessage("Profile updated successfully!", Snackbar.LENGTH_LONG)
                }

                if (resultUpload.isFailure) {

                    // Cause the snackbar to be visualized
                    viewModelScope.launch {
                        _profileImageLD.value = LOCAL_PERSISTENT_PROFILE_PICTURE_PATH
                        _snackbarMessages.addMessage("Error while uploading profile image!", Snackbar.LENGTH_LONG)
                    }

                }
                // Cause the update of the profile picture
                viewModelScope.launch {
                    _profileImageLD.value = LOCAL_PERSISTENT_PROFILE_PICTURE_PATH
                }
            } else {

                // Cause the snackbar to be visualized
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Error while updating the profile!", Snackbar.LENGTH_LONG)
                }

            }
        }
    }

    private fun observeRemoteUser() {
        if (Firebase.auth.currentUser != null) {
            profileListenerRegistration?.remove()

            val uid = Firebase.auth.currentUser!!.uid
            profileListenerRegistration = db.collection("users")
                .document(uid)
                .addSnapshotListener(Dispatchers.IO.asExecutor()) { value, e ->
                    if (e != null) {
                        _snackbarMessages.postMessage("Error while retrieving user from database!", Snackbar.LENGTH_LONG)
                        return@addSnapshotListener
                    }
                    if (value != null && value.exists()) {
                        // Profile found
                        val profile = value.toObject(Profile::class.java)
                        _profileLD.postValue(profile!!)
                    } else {
                        // Create profile
                        createProfileFromAuthUser()
                    }
                    profileLoadedLD.postValue(true)
                }
        } else {
            _profileLD.value = Profile()
            _profileImageLD.value = String.NullImagePath()
        }
    }

    private fun downloadProfileImage(profileId: String?) {
        // Temporary show loading image
        if (profileId == null)
            _profileImageLD.value = String.LoadingImagePath()
        else
            _otherProfileImageLD.value = String.LoadingImagePath()

        viewModelScope.launch {
            val res = downloadProfileImageFromFirebase(profileId)

            if (res.isSuccess) {
                val localPath = if (profileId == null) {
                    LOCAL_PERSISTENT_PROFILE_PICTURE_PATH
                } else {
                    "$profileId.jpg"
                }

                if (profileId == null)
                    _profileImageLD.value = localPath
                else
                    _otherProfileImageLD.value = localPath
            } else {
                if (profileId == null)
                    _profileImageLD.value = String.NullImagePath()
                else
                    _otherProfileImageLD.value = String.NullImagePath()
            }
        }
    }

    private fun createProfileFromAuthUser() {
        // We use global scope to ensure the db writing independently from the view model lifecycle
        GlobalScope.launch {
            val currUser = Firebase.auth.currentUser!!

            val result = firestoreSetProfile(Profile.FromFirebaseUser(currUser))

            if (result.isSuccess) {
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Profile created from Goole account!", Snackbar.LENGTH_LONG)
                }
            } else {
                viewModelScope.launch {
                    _snackbarMessages.addMessage("Error while creating profile from Google account!", Snackbar.LENGTH_LONG)
                }
            }
        }
    }

    fun loadOtherProfile(profileId: String) {
        _otherProfileLD.value = Profile()
        _otherProfileImageLD.value = String.NullImagePath()

        otherProfileListenerRegistration?.remove()

        otherProfileListenerRegistration = db.collection("users")
            .document(profileId)
            .addSnapshotListener(Dispatchers.IO.asExecutor()) { value, e ->
                if (e != null) {
                    _snackbarMessages.postMessage("Error while retrieving user's profile!", Snackbar.LENGTH_LONG)

                    return@addSnapshotListener
                }
                if (value != null && value.exists()) {
                    _otherProfileLD.postValue(value.toObject(Profile::class.java))
                }
            }

        downloadProfileImage(profileId)
    }

    /**
     * Firestore async suspend functions
     */

    private suspend fun firestoreSetProfile(profile: Profile): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                db.collection("users")
                    .document(profile.id)
                    .set(profile)
                    .await()
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun uploadNewProfileImageToFirebase(): Result<Unit> {
        if (hasBeenModifiedProfileImageLD.value == false)
            return Result.success(Unit)

        val result = withContext(Dispatchers.IO) {
            try {
                // Firebase storage file reference
                val storageImgRef = storage.child("${Firebase.auth.currentUser!!.uid}.jpg")

                // Upload temporary edited image to Firebase
                val appDir = getApplication<Application>().filesDir
                val localFile = File(appDir.path, LOCAL_TMP_PROFILE_PICTURE_PATH)
                val localFileUri = Uri.fromFile(localFile)

                storageImgRef.putFile(localFileUri).await()

                // If the upload has succeeded, copy temporary file to persistent file (local)
                localFile.copyTo(
                    File(appDir.path, LOCAL_PERSISTENT_PROFILE_PICTURE_PATH),
                    true
                )
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        return result
    }

    private suspend fun downloadProfileImageFromFirebase(profileId: String?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val localPath = if (profileId == null) {
                    LOCAL_PERSISTENT_PROFILE_PICTURE_PATH
                } else {
                    "$profileId.jpg"
                }

                // Reference to Firebase storage profile picture
                val storagePathRef = if (profileId == null) {
                    storage.child("${Firebase.auth.currentUser?.uid}.jpg")
                } else {
                    storage.child(localPath)
                }

                // Create or overwrite profile image on disk
                val appDir = getApplication<Application>().filesDir
                val localFile = File(appDir.path, localPath)
                localFile.createNewFile()

                // Download
                storagePathRef.getFile(localFile).await()

                // Success
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }


    /**
     * ViewModel callbacks
     */
    override fun onCleared() {
        super.onCleared()

        // Clear all snapshot listeners
        profileListenerRegistration?.remove()
        otherProfileListenerRegistration?.remove()
    }
}