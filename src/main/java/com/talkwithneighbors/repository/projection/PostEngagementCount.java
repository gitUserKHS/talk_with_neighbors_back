package com.talkwithneighbors.repository.projection;

/**
 * Batched engagement aggregate for one feed post.
 */
public record PostEngagementCount(String postId, long total) {
}
