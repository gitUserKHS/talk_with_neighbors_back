package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.FeedPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedPostRepository extends JpaRepository<FeedPost, String> {
    List<FeedPost> findAllByOrderByCreatedAtDesc();

    List<FeedPost> findByAuthor_IdOrderByCreatedAtDesc(Long authorId);
}
