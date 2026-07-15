package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuthNicknameSetupBackfill implements ApplicationRunner {
    private final UserIdentityRepository identities;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        int updated = 0;
        for (UserIdentity identity : identities.findAll()) {
            if (identity.isNicknameSetupAssessed()) {
                continue;
            }
            User user = identity.getUser();
            if (!Boolean.TRUE.equals(user.getNicknameSetupRequired())
                    && OAuthIdentityService.isGeneratedUsername(
                            identity.getProvider(), identity.getProviderSubject(), user.getUsername())) {
                user.setNicknameSetupRequired(true);
                updated++;
            }
            identity.markNicknameSetupAssessed();
        }
        if (updated > 0) {
            log.info("Marked {} existing OAuth account(s) for nickname setup.", updated);
        }
    }
}
