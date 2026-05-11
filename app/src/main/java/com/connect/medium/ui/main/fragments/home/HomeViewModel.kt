package com.connect.medium.ui.main.fragments.home

import android.app.Application
import android.util.Log
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
import com.connect.medium.utils.Constants
import com.connect.medium.utils.Resource
import com.google.firebase.firestore.DocumentSnapshot
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

    companion object {
        private const val TAG_FEED = "FeedPagination"
    }

    val currentUid = authRepository.getCurrentUser()?.uid
        ?: throw IllegalStateException("HomeViewModel requires a logged in user")

    // ─── Feed ────────────────────────────────────────────
    private val _postsState = MutableLiveData<Resource<List<Post>>>()
    val postsState: LiveData<Resource<List<Post>>> = _postsState

    // ─── Pagination state ────────────────────────────────
    /** Last document of the current live first page — used as cursor for page 2 */
    private var firstPageCursor: DocumentSnapshot? = null
    /** Accumulated posts from page 2 onwards (not real-time) */
    private val _paginatedPosts = mutableListOf<Post>()
    /** Cursor pointing to the last document of the latest loaded page */
    private var lastPageCursor: DocumentSnapshot? = null
    private var isLastPage = false

    private val _isLoadingMore = MutableLiveData<Boolean>(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

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
        // Cancel existing listener and reset all pagination state
        feedJob?.cancel()
        _paginatedPosts.clear()
        firstPageCursor = null
        lastPageCursor = null
        isLastPage = false
        _isLoadingMore.value = false

        feedJob = viewModelScope.launch {
            _postsState.value = Resource.Loading
            Log.d(TAG_FEED, "🔄 loadFeed() — resetting pagination, subscribing to first page")
            delay(800)
            postRepository.observeFirstPagePosts()
                .collect { (livePosts, cursor) ->
                    // Store the cursor from the live page for load-more
                    firstPageCursor = cursor
                    // If we haven’t fetched any older pages yet, lastPageCursor == firstPageCursor
                    if (lastPageCursor == null) lastPageCursor = cursor

                    // Merge live first page with accumulated older pages.
                    // Deduplicate by postId — live page always wins for recency.
                    val mergedIds = livePosts.map { it.postId }.toSet()
                    val dedupedOlder = _paginatedPosts.filter { it.postId !in mergedIds }
                    val merged = livePosts + dedupedOlder

                    // Propagate comment-count delta clearing
                    var deltasChanged = false
                    merged.forEach { post ->
                        val expected = expectedCommentCounts[post.postId]
                        if (expected != null && post.commentCount >= expected) {
                            expectedCommentCounts.remove(post.postId)
                            deltasChanged = true
                        }
                    }
                    if (deltasChanged) _commentCountDeltas.postValue(buildDeltaMap(merged))

                    Log.d(
                        TAG_FEED,
                        "📰 ViewModel dispatching ${merged.size} posts " +
                        "(live=${livePosts.size} + older=${dedupedOlder.size})"
                    )
                    _postsState.postValue(Resource.Success(merged))
                }
        }
    }

    /**
     * Fetches the next page of posts using the cursor from the last loaded page.
     * Safe to call multiple times — guards against concurrent fetches and last-page.
     */
    fun loadMorePosts() {
        if (_isLoadingMore.value == true || isLastPage) {
            Log.d(TAG_FEED, "⏭️ loadMorePosts skipped: isLoadingMore=${_isLoadingMore.value} isLastPage=$isLastPage")
            return
        }
        val cursor = lastPageCursor ?: run {
            Log.d(TAG_FEED, "⏭️ loadMorePosts skipped: cursor not ready yet")
            return
        }

        viewModelScope.launch {
            _isLoadingMore.postValue(true)
            Log.d(TAG_FEED, "⬇️ loadMorePosts() — fetching next page after cursor=${cursor.id}")

            when (val result = postRepository.loadMorePosts(cursor)) {
                is Resource.Success -> {
                    val (newPosts, newCursor) = result.data
                    if (newPosts.size < Constants.PAGE_SIZE) {
                        isLastPage = true
                        Log.d(TAG_FEED, "✅ Reached last page (fetched=${newPosts.size} < PAGE_SIZE=${Constants.PAGE_SIZE})")
                    }
                    _paginatedPosts.addAll(newPosts)
                    lastPageCursor = newCursor ?: cursor  // keep old cursor if no new one

                    // Re-merge with the current live first page
                    val livePosts = (postsState.value as? Resource.Success)?.data
                        ?.take(Constants.PAGE_SIZE.toInt()) ?: emptyList()
                    val mergedIds = livePosts.map { it.postId }.toSet()
                    val dedupedOlder = _paginatedPosts.filter { it.postId !in mergedIds }
                    val merged = livePosts + dedupedOlder

                    Log.d(
                        TAG_FEED,
                        "📊 After load-more: total=${merged.size} " +
                        "(live=${livePosts.size} older=${dedupedOlder.size})"
                    )
                    _postsState.postValue(Resource.Success(merged))
                }
                is Resource.Error -> {
                    Log.e(TAG_FEED, "🔴 loadMorePosts error: ${result.message}")
                    // Don’t update postsState — keep showing existing list
                }
                else -> Unit
            }
            _isLoadingMore.postValue(false)
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

                // Also patch _paginatedPosts in-place so when the Firestore snapshot
                // fires (triggered by the like write itself) and the collect block
                // re-merges livePosts + _paginatedPosts, the updated count survives.
                val paginatedIndex = _paginatedPosts.indexOfFirst { it.postId == post.postId }
                if (paginatedIndex != -1) {
                    val p = _paginatedPosts[paginatedIndex]
                    val newCount = if (isLiked) maxOf(0, p.likeCount - 1) else p.likeCount + 1
                    _paginatedPosts[paginatedIndex] = p.copy(likeCount = newCount)
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

                    // Mirror rollback into _paginatedPosts too
                    if (paginatedIndex != -1) {
                        val p = _paginatedPosts[paginatedIndex]
                        val revertedCount = if (isLiked) p.likeCount + 1 else maxOf(0, p.likeCount - 1)
                        _paginatedPosts[paginatedIndex] = p.copy(likeCount = revertedCount)
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