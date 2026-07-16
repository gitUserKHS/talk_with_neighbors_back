package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.repository.projection.PostEngagementCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    boolean existsByPost_IdAndUser_Id(String postId, Long userId);

    long countByPost_Id(String postId);

    @Query("""
            select new com.talkwithneighbors.repository.projection.PostEngagementCount(pl.post.id, count(pl.id))
            from PostLike pl
            where pl.post.id in :postIds
            group by pl.post.id
            """)
    List<PostEngagementCount> countByPostIds(@Param("postIds") List<String> postIds);

    @Query("""
            select pl.post.id
            from PostLike pl
            where pl.user.id = :userId and pl.post.id in :postIds
            """)
    List<String> findLikedPostIds(
            @Param("userId") Long userId,
            @Param("postIds") List<String> postIds
    );

    @Transactional
    void deleteByPost_IdAndUser_Id(String postId, Long userId);

    List<PostLike> findByUser_IdOrderByCreatedAtDesc(Long userId);

    @Transactional
    void deleteByPost_Id(String postId);
}
