package com.connect.medium.data.remote

import android.util.Log
import com.connect.medium.data.model.*
import com.connect.medium.utils.Constants
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirestoreDataSource {

    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG_FEED = "FeedPagination"
    }

    // ─── User ───────────────────────────────────────────

    suspend fun createUser(user: User) {
        firestore.collection(Constants.COLLECTION_USERS)
            .document(user.uid)
            .set(user)
            .await()
    }

    suspend fun getUser(uid: String): User? {

        return try {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .get()
                .await()
                .toObject(User::class.java)
        } catch(e: Exception) {
            null
        }
    }

    suspend fun updateUser(uid: String, fields: Map<String, Any>) {
        try {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .update(fields)
                .await()
        } catch (e: Exception) {
            throw e // let repository handle it
        }
    }
    suspend fun updateAuthorProfileImageOnPosts(uid: String, newImageUrl: String) {
        try {
            val snapshot = firestore.collection(Constants.COLLECTION_POSTS)
                .whereEqualTo("authorUid", uid)
                .get()
                .await()

            val batch = firestore.batch()
            snapshot.documents.forEach { doc ->
                batch.update(doc.reference, "authorProfileImageUrl", newImageUrl)
            }

            if (snapshot.documents.isNotEmpty()) {
                batch.commit().await()
            }
        } catch (e: Exception) {
            // optional: log or ignore
        }
    }

    suspend fun searchUsers(query: String): List<User> {
        return try {
            val snapshot = firestore.collection(Constants.COLLECTION_USERS)
                .orderBy("username")
                .startAt(query)
                .endAt(query + "\uf8ff")
                .limit(20)
                .get()
                .await()

            snapshot.toObjects(User::class.java)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun observeUser(uid: String): Flow<User?> = callbackFlow {
        val listener = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(User::class.java))
            }
        awaitClose { listener.remove() }
    }

    // ─── Posts ──────────────────────────────────────────

    suspend fun createPost(post: Post) {
        val batch = firestore.batch()
        val postRef = firestore.collection(Constants.COLLECTION_POSTS)
            .document(post.postId)
        batch.set(postRef, post)

        val userRef = firestore.collection(Constants.COLLECTION_USERS)
            .document(post.authorUid)
        batch.update(userRef, "postCount", FieldValue.increment(1))
        batch.commit().await()
    }

    suspend fun deletePost(postId: String) {
        firestore.collection(Constants.COLLECTION_POSTS)
            .document(postId)
            .delete()
            .await()
    }

    // ─── Real-time first page (live updates) ──────────────
    // Emits Pair<posts, lastDocumentSnapshot?> so the ViewModel
    // can use the last document as a cursor for loadMorePosts().
    fun observeFirstPagePosts(): Flow<Pair<List<Post>, DocumentSnapshot?>> = callbackFlow {
        val listener = firestore.collection(Constants.COLLECTION_POSTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(Constants.PAGE_SIZE)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG_FEED, "🔴 First-page snapshot error: ${error.message}")
                    trySend(Pair(emptyList(), null))
                    return@addSnapshotListener
                }

                val docs = snapshot?.documents ?: emptyList()
                val fromCache = snapshot?.metadata?.isFromCache == true
                val hasPendingWrites = snapshot?.metadata?.hasPendingWrites() == true
                Log.d(
                    TAG_FEED,
                    "📥 First-page snapshot | count=${docs.size} " +
                    "fromCache=$fromCache hasPendingWrites=$hasPendingWrites"
                )

                snapshot?.documentChanges?.forEach { change ->
                    val postId = change.document.id
                    val caption = change.document.getString("caption")?.take(40) ?: ""
                    Log.d(
                        TAG_FEED,
                        "  ├─ [${change.type.name}] postId=$postId " +
                        "newIndex=${change.newIndex} caption=\"$caption\""
                    )
                }

                val posts = snapshot?.toObjects(Post::class.java) ?: emptyList()
                val lastDoc = docs.lastOrNull()
                trySend(Pair(posts, lastDoc))
            }
        awaitClose {
            Log.d(TAG_FEED, "🔌 observeFirstPagePosts listener removed")
            listener.remove()
        }
    }

    // ─── One-shot load-more (cursor pagination) ────────────
    // Fetches the next PAGE_SIZE posts starting after `afterDoc`.
    // Returns Pair<posts, lastDocumentSnapshot?> — null lastDoc means no more pages.
    suspend fun loadMorePosts(afterDoc: DocumentSnapshot): Pair<List<Post>, DocumentSnapshot?> {
        val snapshot = firestore.collection(Constants.COLLECTION_POSTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .startAfter(afterDoc)
            .limit(Constants.PAGE_SIZE)
            .get()
            .await()

        val posts = snapshot.toObjects(Post::class.java)
        val lastDoc = snapshot.documents.lastOrNull()
        Log.d(
            TAG_FEED,
            "📄 loadMorePosts | fetched=${posts.size} " +
            "isLastPage=${posts.size < Constants.PAGE_SIZE} " +
            "newCursor=${lastDoc?.id}"
        )
        posts.forEachIndexed { i, post ->
            Log.d(
                TAG_FEED,
                "  [$i] postId=${post.postId} author=@${post.authorUsername} " +
                "caption=\"${post.caption.take(40)}\""
            )
        }
        return Pair(posts, lastDoc)
    }

    fun observeUserPosts(uid: String): Flow<List<Post>> = callbackFlow {
        val listener = firestore.collection(Constants.COLLECTION_POSTS)
            .whereEqualTo("authorUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val posts = snapshot?.toObjects(Post::class.java) ?: emptyList()
                trySend(posts)
            }
        awaitClose { listener.remove() }
    }

    // ─── Likes ──────────────────────────────────────────

    suspend fun likePost(postId: String, uid: String) {
        val batch = firestore.batch()

        // add uid to likes subcollection
        val likeRef = firestore.collection(Constants.COLLECTION_POSTS)
            .document(postId)
            .collection(Constants.COLLECTION_LIKES)
            .document(uid)
        batch.set(likeRef, mapOf("uid" to uid, "createdAt" to System.currentTimeMillis()))

        // increment likeCount on post
        val postRef = firestore.collection(Constants.COLLECTION_POSTS).document(postId)
        batch.update(postRef, "likeCount", FieldValue.increment(1))

        batch.commit().await()
    }

    suspend fun unlikePost(postId: String, uid: String) {
        val batch = firestore.batch()

        val likeRef = firestore.collection(Constants.COLLECTION_POSTS)
            .document(postId)
            .collection(Constants.COLLECTION_LIKES)
            .document(uid)
        batch.delete(likeRef)

        val postRef = firestore.collection(Constants.COLLECTION_POSTS).document(postId)
        batch.update(postRef, "likeCount", FieldValue.increment(-1))

        batch.commit().await()
    }

    suspend fun isPostLikedByUser(postId: String, uid: String): Boolean {
        return try {
            firestore.collection(Constants.COLLECTION_POSTS)
                .document(postId)
                .collection(Constants.COLLECTION_LIKES)
                .document(uid)
                .get()
                .await()
                .exists()
        } catch (e: Exception) {
            false
        }
    }

    // ─── Comments ───────────────────────────────────────

    suspend fun addComment(comment: Comment) {
        val batch = firestore.batch()

        val commentRef = firestore.collection(Constants.COLLECTION_POSTS)
            .document(comment.postId)
            .collection(Constants.COLLECTION_COMMENTS)
            .document(comment.commentId)
        batch.set(commentRef, comment)

        val postRef = firestore.collection(Constants.COLLECTION_POSTS).document(comment.postId)
        batch.update(postRef, "commentCount", FieldValue.increment(1))

        batch.commit().await()
    }

    suspend fun updateAuthorProfileImageOnComments(uid: String, newImageUrl: String) {
        try {
            val postsSnapshot = firestore.collection(Constants.COLLECTION_POSTS)
                .get()
                .await()

            postsSnapshot.documents.forEach { postDoc ->

                val commentsSnapshot = firestore.collection(Constants.COLLECTION_POSTS)
                    .document(postDoc.id)
                    .collection(Constants.COLLECTION_COMMENTS)
                    .whereEqualTo("authorUid", uid)
                    .get()
                    .await()

                if (commentsSnapshot.documents.isNotEmpty()) {
                    val batch = firestore.batch()
                    commentsSnapshot.documents.forEach { doc ->
                        batch.update(doc.reference, "authorProfileImageUrl", newImageUrl)
                    }
                    batch.commit().await()
                }
            }
        } catch (e: Exception) {
            // ignore or log
        }
    }

    fun observeComments(postId: String): Flow<List<Comment>> = callbackFlow {
        val listener = firestore.collection(Constants.COLLECTION_POSTS)
            .document(postId)
            .collection(Constants.COLLECTION_COMMENTS)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList()) // safer
                    return@addSnapshotListener
                }
                val comments = snapshot?.toObjects(Comment::class.java) ?: emptyList()
                trySend(comments)
            }
        awaitClose { listener.remove() }
    }

    // ─── Follow ─────────────────────────────────────────

    suspend fun followUser(currentUid: String, targetUid: String) {
        val batch = firestore.batch()

        // add to following
        val followingRef = firestore.collection(Constants.COLLECTION_USERS)
            .document(currentUid)
            .collection(Constants.COLLECTION_FOLLOWING)
            .document(targetUid)
        batch.set(followingRef, mapOf("uid" to targetUid))

        // add to followers
        val followerRef = firestore.collection(Constants.COLLECTION_USERS)
            .document(targetUid)
            .collection(Constants.COLLECTION_FOLLOWERS)
            .document(currentUid)
        batch.set(followerRef, mapOf("uid" to currentUid))

        // update counts
        batch.update(
            firestore.collection(Constants.COLLECTION_USERS).document(currentUid),
            "followingCount", FieldValue.increment(1)
        )
        batch.update(
            firestore.collection(Constants.COLLECTION_USERS).document(targetUid),
            "followerCount", FieldValue.increment(1)
        )

        batch.commit().await()
    }

    suspend fun unfollowUser(currentUid: String, targetUid: String) {
        val batch = firestore.batch()

        batch.delete(
            firestore.collection(Constants.COLLECTION_USERS)
                .document(currentUid)
                .collection(Constants.COLLECTION_FOLLOWING)
                .document(targetUid)
        )
        batch.delete(
            firestore.collection(Constants.COLLECTION_USERS)
                .document(targetUid)
                .collection(Constants.COLLECTION_FOLLOWERS)
                .document(currentUid)
        )
        batch.update(
            firestore.collection(Constants.COLLECTION_USERS).document(currentUid),
            "followingCount", FieldValue.increment(-1)
        )
        batch.update(
            firestore.collection(Constants.COLLECTION_USERS).document(targetUid),
            "followerCount", FieldValue.increment(-1)
        )

        batch.commit().await()
    }

    suspend fun markAllNotificationsAsRead(uid: String) {
        try {
            val snapshot = firestore.collection(Constants.COLLECTION_NOTIFICATIONS)
                .whereEqualTo("toUid", uid)
                .whereEqualTo("read", false)
                .get()
                .await()

            val batch = firestore.batch()
            snapshot.documents.forEach { doc ->
                batch.update(doc.reference, "read", true)
            }

            if (snapshot.documents.isNotEmpty()) {
                batch.commit().await()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun isFollowingUser(currentUid: String, targetUid: String): Boolean {
        return try {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(currentUid)
                .collection(Constants.COLLECTION_FOLLOWING)
                .document(targetUid)
                .get()
                .await()
                .exists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendNotification(notification: Notification) {
        firestore.collection(Constants.COLLECTION_NOTIFICATIONS)
            .document(notification.notificationId)
            .set(notification)
            .await()
    }

    fun observeNotifications(uid: String): Flow<List<Notification>> = callbackFlow {
        val listener = firestore.collection(Constants.COLLECTION_NOTIFICATIONS)
            .whereEqualTo("toUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val notifications = snapshot?.toObjects(Notification::class.java) ?: emptyList()
                trySend(notifications)
            }
        awaitClose { listener.remove() }
    }
    suspend fun getFollowingList(currentUid: String): List<String> {
        return try {
            val snapshot = firestore.collection(Constants.COLLECTION_USERS)
                .document(currentUid)
                .collection(Constants.COLLECTION_FOLLOWING)
                .get()
                .await()

            snapshot.documents.map { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }
}