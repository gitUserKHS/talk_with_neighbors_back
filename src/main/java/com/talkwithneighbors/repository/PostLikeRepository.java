package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    boolean existsByPost_IdAndUser_Id(String postId, Long userId);

    long countByPost_Id(String postId);

    @Transactional
    void deleteByPost_IdAndUser_Id(String postId, Long userId);
}
