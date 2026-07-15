package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthNicknameSetupBackfillTest {
    @Test
    void marksOnlyExactLegacyGeneratedNicknames() throws Exception {
        UserIdentityRepository identities = mock(UserIdentityRepository.class);
        User generated = user(7L, "kakao_legacysub");
        User chosen = user(8L, "다윤이웃");
        UserIdentity generatedIdentity = UserIdentity.create(
                OAuthProvider.KAKAO, "legacy-sub", generated, generated.getEmail(), Instant.now());
        UserIdentity chosenIdentity = UserIdentity.create(
                OAuthProvider.KAKAO, "chosen-sub", chosen, chosen.getEmail(), Instant.now());
        ReflectionTestUtils.setField(generatedIdentity, "nicknameSetupAssessed", false);
        ReflectionTestUtils.setField(chosenIdentity, "nicknameSetupAssessed", false);
        when(identities.findAll()).thenReturn(List.of(generatedIdentity, chosenIdentity));

        new OAuthNicknameSetupBackfill(identities)
                .run(new DefaultApplicationArguments(new String[0]));

        assertTrue(generated.getNicknameSetupRequired());
        assertFalse(chosen.getNicknameSetupRequired());
        assertTrue(generatedIdentity.isNicknameSetupAssessed());
        assertTrue(chosenIdentity.isNicknameSetupAssessed());
    }

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .email("user" + id + "@example.com")
                .username(username)
                .nicknameSetupRequired(false)
                .build();
    }
}
