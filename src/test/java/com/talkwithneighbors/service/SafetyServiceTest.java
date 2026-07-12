package com.talkwithneighbors.service;

import com.talkwithneighbors.domain.event.ContentReportedEvent;
import com.talkwithneighbors.domain.event.UserBlockedEvent;
import com.talkwithneighbors.dto.safety.CreateReportRequest;
import com.talkwithneighbors.entity.*;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyServiceTest {
    @Mock UserRepository userRepository;
    @Mock UserBlockRepository userBlockRepository;
    @Mock SafetyReportRepository safetyReportRepository;
    @Mock HiddenContentRepository hiddenContentRepository;
    @Mock FeedPostRepository feedPostRepository;
    @Mock PostCommentRepository postCommentRepository;
    @Mock MessageRepository messageRepository;
    @Mock MatchRepository matchRepository;
    @Mock DomainEventPublisher domainEventPublisher;
    @InjectMocks SafetyService safetyService;

    private User currentUser;
    private User targetUser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        currentUser = user(1L, "current");
        targetUser = user(2L, "target");
    }

    @Test
    void blockingUserExpiresMatchesAndPublishesOutboxEvent() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(userBlockRepository.findByBlocker_IdAndBlocked_Id(1L, 2L)).thenReturn(Optional.empty());
        when(userBlockRepository.save(any(UserBlock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = safetyService.blockUser(1L, 2L);

        assertEquals(2L, result.userId());
        verify(matchRepository).expireMatchesBetween(eq(1L), eq(2L), any(), eq(MatchStatus.EXPIRED), any());
        verify(domainEventPublisher).publish(any(UserBlockedEvent.class));
    }

    @Test
    void cannotBlockSelf() {
        MatchingException exception = assertThrows(MatchingException.class, () -> safetyService.blockUser(1L, 1L));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void reportCanHideContentAndPublishesOutboxEvent() {
        when(feedPostRepository.existsById("post-1")).thenReturn(true);
        when(safetyReportRepository.existsByReporter_IdAndTargetTypeAndTargetId(
                1L, SafetyTargetType.FEED_POST, "post-1")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(currentUser));
        when(safetyReportRepository.save(any(SafetyReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(hiddenContentRepository.findByUser_IdAndTargetTypeAndTargetId(
                1L, SafetyTargetType.FEED_POST, "post-1")).thenReturn(Optional.empty());

        safetyService.report(1L, new CreateReportRequest(
                SafetyTargetType.FEED_POST, "post-1", ReportReason.SPAM, "광고예요", true));

        verify(hiddenContentRepository).save(any(HiddenContent.class));
        verify(domainEventPublisher).publish(any(ContentReportedEvent.class));
    }

    @Test
    void duplicateReportIsRejected() {
        when(feedPostRepository.existsById("post-1")).thenReturn(true);
        when(safetyReportRepository.existsByReporter_IdAndTargetTypeAndTargetId(
                1L, SafetyTargetType.FEED_POST, "post-1")).thenReturn(true);

        MatchingException exception = assertThrows(MatchingException.class, () -> safetyService.report(1L,
                new CreateReportRequest(SafetyTargetType.FEED_POST, "post-1", ReportReason.SPAM, null, false)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
