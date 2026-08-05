package com.talkwithneighbors.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface WebPushSubscriptionRepository extends JpaRepository<WebPushSubscription, Long> {

    List<WebPushSubscription> findByUserId(Long userId);

    Optional<WebPushSubscription> findByEndpoint(String endpoint);

    @Transactional
    void deleteByEndpoint(String endpoint);

    @Transactional
    void deleteByUserIdAndEndpoint(Long userId, String endpoint);
}
