package com.connect.medium.ui.main.fragments.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.connect.medium.data.local.AppDatabase
import com.connect.medium.data.model.Comment
import com.connect.medium.data.model.Post
import com.connect.medium.data.model.User
import com.connect.medium.data.remote.FirestoreDataSource
import com.connect.medium.data.repository.AuthRepository
import com.connect.medium.data.repository.PostRepository
import com.connect.medium.data.repository.UserRepository
import com.connect.medium.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val firestoreDataSource = FirestoreDataSource()
    private val postRepository = PostRepository(firestoreDataSource, db.postDao())
    private val authRepository = AuthRepository()
    private val userRepository = UserRepository(firestoreDataSource, db.userDao(), db.followDao())
    private val _currentUser = MutableLiveData<User?>()

    val currentUid = authRepository.getCurrentUser()?.uid
        ?: throw IllegalStateException("HomeViewModel requires a logged in user")

    // ─── Feed ────────────────────────────────────────────
    private val _postsState = MutableLiveData<Resource<List<Post>>>()
    val postsState: LiveData<Resource<List<Post>>> = _postsState

    // ─── Likes ───────────────────────────────────────────
    private val _likeState = MutableLiveData<Resource<Unit>>()
    val likeState: LiveData<Resource<Unit>> = _likeState
    private val _likedPostIds = MutableLiveData<Set<String>>()
    val likedPostIds: LiveData<Set<String>> = _likedPostIds

    // ─── Comments ────────────────────────────────────────
    private val _commentsState = MutableLiveData<Resource<List<Comment>>>()
    val commentsState: LiveData<Resource<List<Comment>>> = _commentsState

    private val _addCommentState = MutableLiveData<Resource<Unit>>()
    val addCommentState: LiveData<Resource<Unit>> = _addCommentState

    // Optimistic comment count: postId → expected count after posting.
    // Cleared per-post when Firestore confirms the new count.
    private val expectedCommentCounts = mutableMapOf<String, Int>()
    private val _commentCountDeltas = MutableLiveData<Map<String, Int>>(emptyMap())
    val commentCountDeltas: LiveData<Map<String, Int>> = _commentCountDeltas

    private var feedJob: Job? = null
    private var commentsJob: Job? = null
    private val likeMutex = Mutex()
    private val localLikedPosts = mutableSetOf<String>()

    init {
        loadFeed()
        loadCurrentUser()
    }

    // ─── Feed ────────────────────────────────────────────

    fun loadFeed() {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _postsState.value = Resource.Loading
            delay(800)
            postRepository.observeFeedPosts()
                .collect { posts ->
                    // Clear deltas for posts where Firestore has confirmed the updated count
                    var deltasChanged = false
                    posts.forEach { post ->
                        val expected = expectedCommentCounts[post.postId]
                        if (expected != null && post.commentCount >= expected) {
                            expectedCommentCounts.remove(post.postId)
                            deltasChanged = true
                        }
                    }
                    if (deltasChanged) {
                        _commentCountDeltas.postValue(buildDeltaMap(posts))
                    }
                    _postsState.postValue(Resource.Success(posts))
                }
        }
    }

    private fun buildDeltaMap(posts: List<Post>): Map<String, Int> {
        return posts.mapNotNull { post ->
            val expected = expectedCommentCounts[post.postId] ?: return@mapNotNull null
            val delta = expected - post.commentCount
            if (delta > 0) post.postId to delta else null
        }.toMap()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            userRepository.observeUser(currentUid)
                .collect { _currentUser.postValue(it) }
        }
    }

    // ─── Likes ───────────────────────────────────────────

    fun toggleLike(post: Post) {
        viewModelScope.launch {
            likeMutex.withLock {
                val isLiked = localLikedPosts.contains(post.postId)

                if (isLiked) localLikedPosts.remove(post.postId)
                else localLikedPosts.add(post.postId)
                _likedPostIds.postValue(localLikedPosts.toSet())

                // Optimistically patch likeCount in postsState so the UI updates
                // immediately. FieldValue.increment is a server transform — Firestore's
                // local pending-write snapshot keeps the original value, meaning DiffUtil
                // would see no change and skip the rebind until the server round-trip
                // completes. Patching here gives instant feedback the same way
                // commentCountDeltas does for comments.
                val currentPosts = (_postsState.value as? Resource.Success)?.data
                if (currentPosts != null) {
                    val updatedPosts = currentPosts.map { p ->
                        if (p.postId == post.postId) {
                            val newCount = if (isLiked) maxOf(0, p.likeCount - 1) else p.likeCount + 1
                            p.copy(likeCount = newCount)
                        } else p
                    }
                    _postsState.postValue(Resource.Success(updatedPosts))
                }

                val currentUser = _currentUser.value
                val result = if (isLiked) {
                    postRepository.unlikePost(post.postId, currentUid)
                } else {
                    if (currentUser != null) {
                        postRepository.likePost(post.postId, currentUid, post.authorUid, currentUser)
                    } else {
                        Resource.Error("User not found")
                    }
                }

                if (result is Resource.Error) {
                    // Roll back both the liked-set and the optimistic count patch
                    if (isLiked) localLikedPosts.add(post.postId)
                    else localLikedPosts.remove(post.postId)
                    _likedPostIds.postValue(localLikedPosts.toSet())

                    val rollbackPosts = (_postsState.value as? Resource.Success)?.data
                    if (rollbackPosts != null) {
                        val revertedPosts = rollbackPosts.map { p ->
                            if (p.postId == post.postId) {
                                val revertedCount = if (isLiked) p.likeCount + 1 else maxOf(0, p.likeCount - 1)
                                p.copy(likeCount = revertedCount)
                            } else p
                        }
                        _postsState.postValue(Resource.Success(revertedPosts))
                    }

                    _likeState.postValue(result)
                }
            }
        }
    }


    fun checkLikedPosts(postIds: List<String>) {
        viewModelScope.launch {
            likeMutex.withLock {
                val unknownPostIds = postIds.filter { it !in localLikedPosts }
                unknownPostIds.forEach { postId ->
                    val isLiked = postRepository.isPostLikedByUser(postId, currentUid)
                    if (isLiked) localLikedPosts.add(postId)
                }
                _likedPostIds.postValue(localLikedPosts.toSet())
            }
        }
    }

    // ─── Comments ────────────────────────────────────────

    fun loadComments(postId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            postRepository.observeComments(postId)
                .collect { comments ->
                    _commentsState.postValue(Resource.Success(comments))
                }
        }
    }

    fun stopObservingComments() {
        commentsJob?.cancel()
        commentsJob = null
    }

    fun addComment(postId: String, postAuthorUid: String, text: String) {
        if (text.isBlank()) return
        _addCommentState.value = Resource.Loading

        viewModelScope.launch {
            val user = _currentUser.value
            if (user == null) {
                _addCommentState.value = Resource.Error("User not found")
                return@launch
            }

            // Optimistic: show +1 immediately before Firestore round-trip
            val currentCount = (postsState.value as? Resource.Success)
                ?.data?.find { it.postId == postId }?.commentCount ?: 0
            expectedCommentCounts[postId] = currentCount + 1
            _commentCountDeltas.postValue(mapOf(postId to 1))

            val comment = Comment(
                commentId = UUID.randomUUID().toString(),
                postId = postId,
                authorUid = currentUid,
                authorUsername = user.username,
                authorProfileImageUrl = user.profileImageUrl,
                text = text,
                createdAt = System.currentTimeMillis()
            )

            val result = postRepository.addComment(comment, postAuthorUid, user)
            _addCommentState.postValue(result)

            // Roll back optimistic delta if the write failed
            if (result is Resource.Error) {
                expectedCommentCounts.remove(postId)
                _commentCountDeltas.postValue(emptyMap())
            }
        }
    }
}