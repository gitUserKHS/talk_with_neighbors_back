package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.repository.projection.PostEngagementCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, String> {
    List<PostComment> findByPost_IdOrderByCreatedAtAsc(String postId);

    long countByPost_Id(String postId);

    @Query("""
            select new com.talkwithneighbors.repository.projection.PostEngagementCount(pc.post.id, count(pc.id))
            from PostComment pc
            where pc.post.id in :postIds
            group by pc.post.id
            """)
    List<PostEngagementCount> countByPostIds(@Param("postIds") List<String> postIds);

    List<PostComment> findByAuthor_IdOrderByCreatedAtDesc(Long authorId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByPost_Id(String postId);
}
