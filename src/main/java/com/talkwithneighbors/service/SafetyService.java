package com.talkwithneighbors.service;

import com.talkwithneighbors.domain.event.ContentReportedEvent;
import com.talkwithneighbors.domain.event.UserBlockedEvent;
import com.talkwithneighbors.dto.safety.*;
import com.talkwithneighbors.entity.*;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SafetyService {
    private static final Set<MatchStatus> ACTIVE_MATCH_STATUSES = Set.of(
            MatchStatus.PENDING, MatchStatus.USER1_ACCEPTED, MatchStatus.USER2_ACCEPTED, MatchStatus.BOTH_ACCEPTED);

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final SafetyReportRepository safetyReportRepository;
    private final HiddenContentRepository hiddenContentRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final MessageRepository messageRepository;
    private final MatchRepository matchRepository;
    private final DomainEventPublisher domainEventPublisher;

    @Transactional
    public BlockedUserDto blockUser(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw error("자기 자신을 차단할 수 없어요.", HttpStatus.BAD_REQUEST);
        }
        User blocker = user(currentUserId);
        User blocked = user(targetUserId);
        return userBlockRepository.findByBlocker_IdAndBlocked_Id(currentUserId, targetUserId)
                .map(BlockedUserDto::from)
                .orElseGet(() -> {
                    UserBlock saved = userBlockRepository.save(new UserBlock(blocker, blocked));
                    matchRepository.expireMatchesBetween(currentUserId, targetUserId, ACTIVE_MATCH_STATUSES,
                            MatchStatus.EXPIRED, LocalDateTime.now());
                    domainEventPublisher.publish(UserBlockedEvent.create(currentUserId, targetUserId));
                    return BlockedUserDto.from(saved);
                });
    }

    @Transactional
    public void unblockUser(Long currentUserId, Long targetUserId) {
        userBlockRepository.findByBlocker_IdAndBlocked_Id(currentUserId, targetUserId)
                .ifPresent(userBlockRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<BlockedUserDto> blockedUsers(Long currentUserId) {
        return userBlockRepository.findByBlocker_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(BlockedUserDto::from).toList();
    }

    @Transactional
    public SafetyReportDto report(Long currentUserId, CreateReportRequest request) {
        String targetId = request.targetId().trim();
        validateTarget(request.targetType(), targetId);
        if (request.targetType() == SafetyTargetType.USER && currentUserId.toString().equals(targetId)) {
            throw error("자기 자신을 신고할 수 없어요.", HttpStatus.BAD_REQUEST);
        }
        if (safetyReportRepository.existsByReporter_IdAndTargetTypeAndTargetId(
                currentUserId, request.targetType(), targetId)) {
            throw error("이미 접수된 신고예요. 검토 결과를 기다려 주세요.", HttpStatus.CONFLICT);
        }

        String details = request.details() == null ? null : request.details().trim();
        SafetyReport saved = safetyReportRepository.save(new SafetyReport(
                user(currentUserId), request.targetType(), targetId, request.reason(), details));
        if (request.hideContent() && request.targetType() != SafetyTargetType.USER) {
            hide(currentUserId, new ContentVisibilityRequest(request.targetType(), targetId));
        }
        domainEventPublisher.publish(ContentReportedEvent.create(saved.getId(), currentUserId,
                request.targetType(), targetId, request.reason()));
        return SafetyReportDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SafetyReportDto> myReports(Long currentUserId) {
        return safetyReportRepository.findByReporter_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(SafetyReportDto::from).toList();
    }

    @Transactional
    public void hide(Long currentUserId, ContentVisibilityRequest request) {
        String targetId = request.targetId().trim();
        if (request.targetType() == SafetyTargetType.USER) {
            throw error("사용자는 숨김 대신 차단 기능을 이용해 주세요.", HttpStatus.BAD_REQUEST);
        }
        validateTarget(request.targetType(), targetId);
        if (hiddenContentRepository.findByUser_IdAndTargetTypeAndTargetId(
                currentUserId, request.targetType(), targetId).isEmpty()) {
            hiddenContentRepository.save(new HiddenContent(user(currentUserId), request.targetType(), targetId));
        }
    }

    @Transactional
    public void unhide(Long currentUserId, SafetyTargetType targetType, String targetId) {
        hiddenContentRepository.findByUser_IdAndTargetTypeAndTargetId(currentUserId, targetType, targetId)
                .ifPresent(hiddenContentRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<HiddenContentDto> hiddenContents(Long currentUserId) {
        return hiddenContentRepository.findByUser_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(this::toHiddenContentDto)
                .toList();
    }

    private HiddenContentDto toHiddenContentDto(HiddenContent hidden) {
        String title = "숨긴 콘텐츠";
        String preview = null;
        String imageUrl = null;
        boolean available = false;
        if (hidden.getTargetType() == SafetyTargetType.FEED_POST) {
            FeedPost post = feedPostRepository.findById(hidden.getTargetId()).orElse(null);
            if (post != null) {
                title = post.getAuthor() != null ? post.getAuthor().getUsername() + "님의 게시글" : "게시글";
                preview = post.getCaption();
                imageUrl = post.getImageUrl();
                available = true;
            }
        } else if (hidden.getTargetType() == SafetyTargetType.COMMENT) {
            PostComment comment = postCommentRepository.findById(hidden.getTargetId()).orElse(null);
            if (comment != null) {
                title = comment.getAuthor() != null ? comment.getAuthor().getUsername() + "님의 댓글" : "댓글";
                preview = comment.getContent();
                available = true;
            }
        } else if (hidden.getTargetType() == SafetyTargetType.MESSAGE) {
            Message message = messageRepository.findById(hidden.getTargetId()).orElse(null);
            if (message != null) {
                title = "숨긴 메시지";
                preview = message.getContent();
                available = true;
            }
        }
        return new HiddenContentDto(hidden.getId(), hidden.getTargetType(), hidden.getTargetId(), title,
                preview, imageUrl, available, hidden.getCreatedAt());
    }

    private void validateTarget(SafetyTargetType type, String id) {
        boolean exists = switch (type) {
            case USER -> parseUserId(id) != null && userRepository.existsById(parseUserId(id));
            case FEED_POST -> feedPostRepository.existsById(id);
            case COMMENT -> postCommentRepository.existsById(id);
            case MESSAGE -> messageRepository.existsById(id);
        };
        if (!exists) throw error("신고하거나 숨길 대상을 찾을 수 없어요.", HttpStatus.NOT_FOUND);
    }

    private Long parseUserId(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return null; }
    }

    private User user(Long id) {
        return userRepository.findById(id).orElseThrow(() -> error("사용자를 찾을 수 없어요.", HttpStatus.NOT_FOUND));
    }

    private MatchingException error(String message, HttpStatus status) {
        return new MatchingException(message, status);
    }
}
