package com.talkwithneighbors.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 운영자 계정을 설정으로 지정한다.
 *
 * 사용자 테이블에 역할 컬럼을 두는 대신 설정을 쓰는 이유는 두 가지다.
 * 세션이 Redis에 직렬화되어 있고 serialVersionUID를 기존 값에 고정해 두었기 때문에
 * 세션 구조를 넓히면 이미 로그인해 있는 사용자가 튕길 수 있고, 운영자 지정과 회수를
 * 배포 없이 환경 변수만으로 끝낼 수 있기 때문이다.
 */
@ConfigurationProperties("app.admin")
public class AdminProperties {

    /**
     * 운영자로 취급할 이메일 목록. 비어 있으면 아무도 운영자가 아니다.
     */
    private Set<String> emails = Set.of();

    public Set<String> getEmails() {
        return emails;
    }

    public void setEmails(Set<String> emails) {
        Set<String> normalized = new LinkedHashSet<>();
        if (emails != null) {
            for (String email : emails) {
                if (email == null) {
                    continue;
                }
                String trimmed = email.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed);
                }
            }
        }
        this.emails = Set.copyOf(normalized);
    }

    public boolean isAdminEmail(String email) {
        return email != null && emails.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
