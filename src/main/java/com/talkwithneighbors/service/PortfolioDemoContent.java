package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Curated, non-persistent portfolio content used only when explicitly enabled and
 * the corresponding public dataset is empty. IDs deliberately do not follow the
 * UUID format used by persisted entities, and demo records must never be used as
 * mutation targets.
 */
final class PortfolioDemoContent {
    private static final ZoneId PORTFOLIO_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private PortfolioDemoContent() {
    }

    static Page<PublicFeedPostDto> feed(Pageable pageable) {
        LocalDate today = LocalDate.now(PORTFOLIO_TIME_ZONE);
        List<PublicFeedPostDto> items = List.of(
            feed(
                    "portfolio-demo-feed-01",
                    "퇴근 후 이웃들과 천천히 걸으며 발견한 노을 맛집이에요. 다음 산책도 함께해요!",
                    List.of("산책", "사진"),
                    today.minusDays(1).atTime(19, 20),
                    18,
                    5
            ),
            feed(
                    "portfolio-demo-feed-02",
                    "다 읽은 책을 나누는 작은 책장을 만들었어요. 이번 주말에는 서로의 인생 책을 소개해요.",
                    List.of("독서", "나눔"),
                    today.minusDays(2).atTime(14, 10),
                    24,
                    8
            ),
            feed(
                    "portfolio-demo-feed-03",
                    "골목 안 조용한 카페에서 이웃톡 첫 커피 모임을 열었어요. 반가운 이야기가 가득했답니다.",
                    List.of("카페", "친목"),
                    today.minusDays(3).atTime(11, 40),
                    31,
                    11
            ),
            feed(
                    "portfolio-demo-feed-04",
                    "비 오는 날 우산을 빌려준 이웃에게 감사 인사를 전했어요. 가까운 이웃의 힘을 느낀 하루!",
                    List.of("동네생활", "일상"),
                    today.minusDays(4).atTime(20, 5),
                    15,
                    3
            )
        );
        return page(items, pageable);
    }

    static Page<PublicMeetupDto> meetups(String keyword, String interest, Pageable pageable) {
        LocalDate today = LocalDate.now(PORTFOLIO_TIME_ZONE);
        List<PublicMeetupDto> items = List.of(
            meetup(
                    "portfolio-demo-meetup-01",
                    "한강 노을 산책 모임",
                    "가벼운 대화를 나누며 60분 동안 천천히 걸어요. 산책이 처음이어도 환영해요!",
                    List.of("산책", "사진"),
                    8,
                    5,
                    today.plusDays(3).atTime(18, 30),
                    60,
                    today.plusDays(2).atTime(20, 0)
            ),
            meetup(
                    "portfolio-demo-meetup-02",
                    "토요일 아침 동네 독서회",
                    "한 주에 한 챕터씩 읽고 편안하게 감상을 나눠요. 이번 책은 모임에서 함께 골라요.",
                    List.of("독서", "커피"),
                    10,
                    6,
                    today.plusDays(7).atTime(10, 0),
                    90,
                    today.plusDays(6).atTime(18, 0)
            ),
            meetup(
                    "portfolio-demo-meetup-03",
                    "초보자를 위한 3km 러닝",
                    "기록보다 꾸준함이 목표인 모임이에요. 걷고 뛰기를 반복하며 안전하게 완주해요.",
                    List.of("러닝", "건강"),
                    12,
                    7,
                    today.plusDays(10).atTime(19, 30),
                    50,
                    today.plusDays(9).atTime(21, 0)
            ),
            meetup(
                    "portfolio-demo-meetup-04",
                    "반려견과 함께하는 주말 산책",
                    "서로의 반려견을 배려하며 공원 한 바퀴를 걸어요. 기본 산책 예절을 지켜 주세요.",
                    List.of("반려동물", "산책"),
                    6,
                    4,
                    today.plusDays(14).atTime(9, 30),
                    70,
                    today.plusDays(13).atTime(18, 0)
            )
        );
        List<PublicMeetupDto> filtered = items.stream()
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> matchesInterest(item, interest))
                .toList();
        return page(filtered, pageable);
    }

    private static PublicFeedPostDto feed(
            String id,
            String caption,
            List<String> tags,
            LocalDateTime createdAt,
            long likeCount,
            long commentCount
    ) {
        return new PublicFeedPostDto(
                id,
                "이웃톡 데모",
                null,
                List.of(),
                caption,
                tags,
                createdAt,
                createdAt,
                likeCount,
                commentCount,
                true
        );
    }

    private static PublicMeetupDto meetup(
            String id,
            String title,
            String description,
            List<String> tags,
            int maxParticipants,
            int participantCount,
            LocalDateTime scheduledAt,
            int durationMinutes,
            LocalDateTime registrationDeadline
    ) {
        return new PublicMeetupDto(
                id,
                title,
                description,
                tags,
                maxParticipants,
                participantCount,
                participantCount >= maxParticipants,
                scheduledAt.atZone(PORTFOLIO_TIME_ZONE)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toOffsetDateTime(),
                durationMinutes,
                registrationDeadline.atZone(PORTFOLIO_TIME_ZONE)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toOffsetDateTime(),
                true
        );
    }

    private static boolean matchesKeyword(PublicMeetupDto item, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        return contains(item.title(), keyword)
                || contains(item.description(), keyword)
                || item.interestTags().stream().anyMatch(tag -> contains(tag, keyword));
    }

    private static boolean matchesInterest(PublicMeetupDto item, String interest) {
        return interest.isEmpty()
                || item.interestTags().stream().map(PortfolioDemoContent::normalize).anyMatch(interest::equals);
    }

    private static boolean contains(String value, String query) {
        return value != null && normalize(value).contains(query);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static <T> Page<T> page(List<T> items, Pageable pageable) {
        long offset = pageable.getOffset();
        if (offset >= items.size()) {
            return new PageImpl<>(List.of(), pageable, items.size());
        }
        int from = (int) offset;
        int to = Math.min(from + pageable.getPageSize(), items.size());
        return new PageImpl<>(List.copyOf(items.subList(from, to)), pageable, items.size());
    }
}
