package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.SafetyTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedPostRepository extends JpaRepository<FeedPost, String> {
    @EntityGraph(attributePaths = "author")
    @Query(
            value = """
                    select post
                    from FeedPost post
                    where not exists (
                        select hidden.id
                        from HiddenContent hidden
                        where hidden.user.id = :viewerId
                          and hidden.targetType = :targetType
                          and hidden.targetId = post.id
                    )
                      and not exists (
                        select block.id
                        from UserBlock block
                        where (block.blocker.id = :viewerId and block.blocked.id = post.author.id)
                           or (block.blocked.id = :viewerId and block.blocker.id = post.author.id)
                    )
                    order by post.createdAt desc, post.id desc
                    """,
            countQuery = """
                    select count(post)
                    from FeedPost post
                    where not exists (
                        select hidden.id
                        from HiddenContent hidden
                        where hidden.user.id = :viewerId
                          and hidden.targetType = :targetType
                          and hidden.targetId = post.id
                    )
                      and not exists (
                        select block.id
                        from UserBlock block
                        where (block.blocker.id = :viewerId and block.blocked.id = post.author.id)
                           or (block.blocked.id = :viewerId and block.blocker.id = post.author.id)
                    )
                    """
    )
    Page<FeedPost> findVisibleFeed(
            @Param("viewerId") Long viewerId,
            @Param("targetType") SafetyTargetType targetType,
            Pageable pageable
    );

    List<FeedPost> findByAuthor_IdOrderByCreatedAtDesc(Long authorId);
}
