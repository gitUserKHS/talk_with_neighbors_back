package com.talkwithneighbors.repository.publiccontent;

import com.talkwithneighbors.entity.FeedPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PublicFeedPostRepository extends Repository<FeedPost, String> {

    @EntityGraph(attributePaths = "author")
    @Query(
            value = """
                    SELECT post
                    FROM FeedPost post
                    WHERE post.publicPreview = true
                    ORDER BY post.createdAt DESC, post.id DESC
                    """,
            countQuery = "SELECT COUNT(post) FROM FeedPost post WHERE post.publicPreview = true"
    )
    Page<FeedPost> findPublicFeed(Pageable pageable);
}
