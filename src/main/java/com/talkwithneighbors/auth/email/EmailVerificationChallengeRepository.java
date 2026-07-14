package com.talkwithneighbors.auth.email;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;

public interface EmailVerificationChallengeRepository
        extends JpaRepository<EmailVerificationChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailVerificationChallenge c where c.emailNormalized = :email")
    Optional<EmailVerificationChallenge> findLockedByEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailVerificationChallenge c where c.challengeId = :challengeId")
    Optional<EmailVerificationChallenge> findLockedByChallengeId(@Param("challengeId") String challengeId);

    @Modifying
    @Query("delete from EmailVerificationChallenge c where c.codeExpiresAt < :cutoff "
            + "and (c.proofExpiresAt is null or c.proofExpiresAt < :cutoff)")
    int deleteStale(@Param("cutoff") Instant cutoff);
}
