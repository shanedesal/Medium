package com.connect.medium.utils

import com.connect.medium.data.local.entity.*
import com.connect.medium.data.model.*
import com.google.gson.Gson

// User
fun User.toEntity(): UserEntity = UserEntity(
    uid, username, displayName, bio,
    profileImageUrl, followerCount, followingCount, postCount, createdAt
)

fun UserEntity.toModel(): User = User(
    uid, username, displayName, bio,
    profileImageUrl, followerCount, followingCount, postCount, createdAt
)

// Post
fun Post.toEntity(): PostEntity = PostEntity(
    postId = postId,
    authorUid = authorUid,
    authorUsername = authorUsername,
    authorProfileImageUrl = authorProfileImageUrl,
    mediaUrls = Gson().toJson(mediaUrls),
    mediaTypes = Gson().toJson(mediaTypes),
    caption = caption,
    likeCount = likeCount,
    commentCount = commentCount,
    createdAt = createdAt
)

fun PostEntity.toModel(): Post = Post(
    postId = postId,
    authorUid = authorUid,
    authorUsername = authorUsername,
    authorProfileImageUrl = authorProfileImageUrl,
    mediaUrls = Gson().fromJson(mediaUrls, Array<String>::class.java).toList(),
    mediaTypes = Gson().fromJson(mediaTypes, Array<String>::class.java).toList(),
    caption = caption,
    likeCount = likeCount,
    commentCount = commentCount,
    createdAt = createdAt
)

// Returns the URL unchanged. fl_faststart is an ffmpeg encoder flag, not a Cloudinary
// transformation parameter, and q_auto on video triggers on-the-fly re-encoding which
// has a 40 MB free-plan limit and burns monthly credits. ExoPlayer already handles
// progressive/chunked delivery natively via HTTP range requests (206 Partial Content)
// combined with the CacheDataSource + SimpleCache layer in PostMediaAdapter.
fun String.toCloudinaryStreamingUrl(): String = this


// Notification
fun Notification.toEntity(): NotificationEntity = NotificationEntity(
    notificationId, toUid, fromUid, fromUsername,
    fromProfileImageUrl, type.name, postId, read, createdAt
)

fun NotificationEntity.toModel(): Notification = Notification(
    notificationId, toUid, fromUid, fromUsername,
    fromProfileImageUrl, NotificationType.valueOf(type), postId, read, createdAt
)