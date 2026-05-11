package com.connect.medium.data.repository

import android.util.Log
import com.connect.medium.data.local.dao.PostDao
import com.connect.medium.data.model.Comment
import com.connect.medium.data.model.Notification
import com.connect.medium.data.model.NotificationType
import com.connect.medium.data.model.Post
import com.connect.medium.data.model.User
import com.connect.medium.data.remote.FirestoreDataSource
import com.connect.medium.utils.Resource
import com.connect.medium.utils.toEntity
import com.connect.medium.utils.toModel
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID

class PostRepository(
    private val firestoreDataSource: FirestoreDataSource,
    private val postDao: PostDao
) {

    companion object {
        private const val TAG_FEED = "FeedPagination"
    }

    // Real-time listener for the first PAGE_SIZE posts.
    // Emits Pair<posts, lastDocumentSnapshot?> — the cursor for loadMorePosts().
    fun observeFirstPagePosts(): Flow<Pair<List<Post>, DocumentSnapshot?>> {
        return firestoreDataSource.observeFirstPagePosts()
            .onEach { (posts, _) ->
                Log.d(TAG_FEED, "🗃️ Repository caching ${posts.size} first-page posts to Room")
                posts.forEachIndexed { index, post ->
                    Log.v(
                        TAG_FEED,
                        "  [$index] postId=${post.postId} " +
                        "likes=${post.likeCount} comments=${post.commentCount} " +
                        "author=@${post.authorUsername} " +
                        "caption=\"${post.caption.take(40)}\""
                    )
                }
                postDao.insertPosts(posts.map { it.toEntity() })
            }
    }

    // One-shot cursor fetch for subsequent pages.
    suspend fun loadMorePosts(afterDoc: DocumentSnapshot): Resource<Pair<List<Post>, DocumentSnapshot?>> {
        return try {
            val result = firestoreDataSource.loadMorePosts(afterDoc)
            // Cache the new page into Room as well
            postDao.insertPosts(result.first.map { it.toEntity() })
            Resource.Success(result)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load more posts")
        }
    }

    fun getCachedPosts(): Flow<List<Post>> {
        return postDao.getAllPosts().map { list -> list.map { it.toModel() } }
    }

    fun observeUserPosts(uid: String): Flow<List<Post>> {
        return firestoreDataSource.observeUserPosts(uid)
    }

    suspend fun createPost(post: Post): Resource<Unit> {
        return try {
            firestoreDataSource.createPost(post)
            postDao.insertPost(post.toEntity())
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to create post")
        }
    }

    suspend fun deletePost(postId: String): Resource<Unit> {
        return try {
            firestoreDataSource.deletePost(postId)
            postDao.deletePost(postId)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to delete post")
        }
    }

    suspend fun likePost(postId: String, uid: String, postAuthorUid: String, fromUser: User): Resource<Unit> {
        return try {
            firestoreDataSource.likePost(postId, uid)

            // send notification only if liking someone else's post
            if (uid != postAuthorUid) {
                val notification = Notification(
                    notificationId = UUID.randomUUID().toString(),
                    toUid = postAuthorUid,
                    fromUid = uid,
                    fromUsername = fromUser.username,
                    fromProfileImageUrl = fromUser.profileImageUrl,
                    type = NotificationType.LIKE,
                    postId = postId,
                    createdAt = System.currentTimeMillis()
                )
                firestoreDataSource.sendNotification(notification)
            }

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to like post")
        }
    }

    suspend fun unlikePost(postId: String, uid: String): Resource<Unit> {
        return try {
            firestoreDataSource.unlikePost(postId, uid)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to unlike post")
        }
    }

    suspend fun isPostLikedByUser(postId: String, uid: String): Boolean {
        return firestoreDataSource.isPostLikedByUser(postId, uid)
    }

    suspend fun addComment(comment: Comment, postAuthorUid: String, fromUser: User): Resource<Unit> {
        return try {
            firestoreDataSource.addComment(comment)

            // send notification only if commenting on someone else's post
            if (comment.authorUid != postAuthorUid) {
                val notification = Notification(
                    notificationId = UUID.randomUUID().toString(),
                    toUid = postAuthorUid,
                    fromUid = comment.authorUid,
                    fromUsername = fromUser.username,
                    fromProfileImageUrl = fromUser.profileImageUrl,
                    type = NotificationType.COMMENT,
                    postId = comment.postId,
                    createdAt = System.currentTimeMillis()
                )
                firestoreDataSource.sendNotification(notification)
            }

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to add comment")
        }
    }

    fun observeComments(postId: String): Flow<List<Comment>> {
        return firestoreDataSource.observeComments(postId)
    }
}