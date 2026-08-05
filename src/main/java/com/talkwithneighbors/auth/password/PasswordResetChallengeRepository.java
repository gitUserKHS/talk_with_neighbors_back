package com.talkwithneighbors.auth.password;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetChallengeRepository extends JpaRepository<PasswordResetChallenge, String> {

    Optional<PasswordResetChallenge> findByEmailNormalized(String emailNormalized);

    /** 만료된 요청을 주기적으로 정리한다. */
    void deleteByExpiresAtBefore(Instant cutoff);
}
