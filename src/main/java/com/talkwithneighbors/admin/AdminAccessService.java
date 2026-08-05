package com.talkwithneighbors.admin;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(User::getEmail)
                .filter(adminProperties::isAdminEmail)
                .isPresent();
    }

    /**
     * 운영자가 아니면 404로 막는다.
     * 403은 "여기에 무언가 있다"는 사실을 알려주므로, 운영 도구의 존재 자체를 숨긴다.
     */
    @Transactional(readOnly = true)
    public void requireAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw new MatchingException("요청한 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }
    }
}
