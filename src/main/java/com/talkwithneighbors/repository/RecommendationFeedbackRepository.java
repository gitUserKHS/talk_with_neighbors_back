package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {
    Optional<RecommendationFeedback> findByUser_IdAndCandidate_Id(Long userId, Long candidateId);
}
