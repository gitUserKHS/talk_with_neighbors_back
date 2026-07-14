package com.talkwithneighbors.auth.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndProviderSubject(OAuthProvider provider, String providerSubject);
}
