package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, String> {
    List<PostComment> findByPost_IdOrderByCreatedAtAsc(String postId);

    long countByPost_Id(String postId);

    List<PostComment> findByAuthor_IdOrderByCreatedAtDesc(Long authorId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByPost_Id(String postId);
}
