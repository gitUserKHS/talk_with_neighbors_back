package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates transparent first-party content as normal persisted entities.
 *
 * <p>Stable primary keys make this safe to run on every deployment. Existing
 * likes, member comments, and meetup participants are never replaced. Meetup
 * timestamps are assigned only when an occurrence is first created, so a
 * restart can never move an appointment that a member already joined.</p>
 */
@Service
public class OfficialContentSeedService {
    static final String OFFICIAL_EMAIL = "official@system.invalid";
    static final String OFFICIAL_USERNAME = "이웃톡 운영팀";

    static final String WALK_POST_ID = "11111111-1111-4111-8111-111111111101";
    static final String MAP_POST_ID = "11111111-1111-4111-8111-111111111102";
    static final String SAFETY_POST_ID = "11111111-1111-4111-8111-111111111103";

    static final String WALK_IMAGE_KEY = "feed/official-evening-walk.svg";
    static final String MAP_IMAGE_KEY = "feed/official-map-meetup.svg";
    static final String SAFETY_IMAGE_KEY = "feed/official-safe-neighbors.svg";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    public OfficialContentSeedService(
            UserRepository userRepository,
            FeedPostRepository feedPostRepository,
            PostCommentRepository postCommentRepository,
            ChatRoomRepository chatRoomRepository,
            PasswordEncoder passwordEncoder
    ) {
        this(userRepository, feedPostRepository, postCommentRepository,
                chatRoomRepository, passwordEncoder, Clock.systemUTC());
    }

    OfficialContentSeedService(
            UserRepository userRepository,
            FeedPostRepository feedPostRepository,
            PostCommentRepository postCommentRepository,
            ChatRoomRepository chatRoomRepository,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.feedPostRepository = feedPostRepository;
        this.postCommentRepository = postCommentRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public void sync() {
        User owner = upsertOfficialOwner();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        FeedPost walkPost = upsertPost(new PostSpec(
                WALK_POST_ID,
                publicUrl(WALK_IMAGE_KEY),
                "퇴근 후 30분, 우리 동네를 천천히 걸어 봤어요. 익숙한 골목도 이웃과 함께 보면 새로운 풍경이 되더라고요.",
                List.of("산책", "동네생활", "사진"),
                nowUtc.minusDays(1)
        ), owner);
        FeedPost mapPost = upsertPost(new PostSpec(
                MAP_POST_ID,
                publicUrl(MAP_IMAGE_KEY),
                "모임 장소를 지도에서 골라 두면 처음 만나는 이웃도 헤매지 않아요. 장소명과 주소, 시간을 한 번에 확인해 보세요.",
                List.of("모임", "지도", "약속"),
                nowUtc.minusDays(3)
        ), owner);
        FeedPost safetyPost = upsertPost(new PostSpec(
                SAFETY_POST_ID,
                publicUrl(SAFETY_IMAGE_KEY),
                "처음 만나는 이웃과는 밝고 사람이 많은 장소에서 만나고, 일정과 귀가 시간을 미리 공유하면 더 편안해요.",
                List.of("안전", "이웃", "생활팁"),
                nowUtc.minusDays(5)
        ), owner);

        upsertOfficialComment("22222222-2222-4222-8222-222222222201", walkPost, owner,
                "걷기 편한 신발과 물 한 병이면 충분해요. 무리하지 않고 서로의 속도에 맞춰 걸어요.",
                nowUtc.minusDays(1).plusMinutes(20));
        upsertOfficialComment("22222222-2222-4222-8222-222222222202", mapPost, owner,
                "공식 모임은 공개 화면에서도 장소를 확인할 수 있고, 일반 회원 모임의 상세 위치는 보호돼요.",
                nowUtc.minusDays(3).plusMinutes(30));
        upsertOfficialComment("22222222-2222-4222-8222-222222222203", safetyPost, owner,
                "불편한 상황은 차단과 신고 기능으로 바로 알려 주세요. 안전한 이웃 관계를 가장 먼저 생각할게요.",
                nowUtc.minusDays(5).plusMinutes(25));

        seedMeetupOccurrences(owner);
    }

    private User upsertOfficialOwner() {
        User owner = userRepository.findByEmail(OFFICIAL_EMAIL).orElse(null);
        if (owner == null) {
            userRepository.findByUsername(OFFICIAL_USERNAME).ifPresent(existing -> {
                throw new IllegalStateException("The reserved official username is already owned by another account.");
            });
            owner = new User();
            owner.setEmail(OFFICIAL_EMAIL);
            owner.setUsername(OFFICIAL_USERNAME);
            owner.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            owner.setAccountType(UserAccountType.SYSTEM);
        } else if (owner.getAccountType() != UserAccountType.SYSTEM) {
            throw new IllegalStateException("The reserved official email is already owned by a member account.");
        }

        owner.setAccountType(UserAccountType.SYSTEM);
        owner.setPasswordLoginEnabled(false);
        owner.setUsername(OFFICIAL_USERNAME);
        owner.setProfileDiscoverable(false);
        owner.setShowNeighborhood(false);
        owner.setMatchNotificationsEnabled(false);
        owner.setChatNotificationsEnabled(false);
        owner.setMeetupNotificationsEnabled(false);
        owner.setIsOnline(false);
        owner.setLatitude(37.5665);
        owner.setLongitude(126.9780);
        owner.setAddress("서울특별시");
        owner.setBio("이웃톡이 직접 운영하는 공식 콘텐츠 계정입니다.");
        owner.setInterests(new ArrayList<>());
        owner.setAge(null);
        owner.setGender(null);
        return userRepository.saveAndFlush(owner);
    }

    private FeedPost upsertPost(PostSpec spec, User owner) {
        FeedPost post = feedPostRepository.findById(spec.id()).orElse(null);
        if (post == null) {
            post = new FeedPost();
            post.setId(spec.id());
            post.setCreatedAt(spec.createdAt());
            post.setUpdatedAt(spec.createdAt());
        } else {
            requireSystemOwner(post.getAuthor(), "feed post", spec.id());
        }

        post.setAuthor(owner);
        post.setImageUrl(spec.imageUrl());
        post.setMedia(new ArrayList<>(List.of(new FeedPostMedia(spec.imageUrl(), FeedMediaType.IMAGE))));
        post.setCaption(spec.caption());
        post.setInterestTags(new ArrayList<>(spec.tags()));
        post.setPublicPreview(true);
        if (post.getCreatedAt() == null) {
            post.setCreatedAt(spec.createdAt());
        }
        if (post.getUpdatedAt() == null) {
            post.setUpdatedAt(spec.createdAt());
        }
        return feedPostRepository.save(post);
    }

    private void upsertOfficialComment(
            String id,
            FeedPost post,
            User owner,
            String content,
            LocalDateTime createdAt
    ) {
        PostComment comment = postCommentRepository.findById(id).orElse(null);
        if (comment == null) {
            comment = new PostComment();
            comment.setId(id);
            comment.setCreatedAt(createdAt);
        } else {
            requireSystemOwner(comment.getAuthor(), "comment", id);
            if (comment.getPost() == null || !post.getId().equals(comment.getPost().getId())) {
                throw new IllegalStateException("Official comment ID collision: " + id);
            }
        }
        comment.setPost(post);
        comment.setAuthor(owner);
        comment.setContent(content);
        postCommentRepository.save(comment);
    }

    private void seedMeetupOccurrences(User owner) {
        ZonedDateTime nowSeoul = ZonedDateTime.now(clock.withZone(SEOUL));
        closePastOfficialOccurrences(owner, nowSeoul);
        ZonedDateTime walkStart = nextOccurrence(DayOfWeek.SATURDAY, LocalTime.of(18, 30), nowSeoul);
        ZonedDateTime bookStart = nextOccurrence(DayOfWeek.SUNDAY, LocalTime.of(14, 0), nowSeoul);

        upsertMeetup(new MeetupSpec(
                occurrenceId("seoul-forest-walk", walkStart.toLocalDate()),
                "서울숲 저녁 산책",
                "이웃톡 운영팀과 함께 서울숲을 천천히 걸어요. 처음 참여하는 이웃도 편하게 대화할 수 있는 공개 모임이에요.",
                List.of("산책", "사진", "친목"),
                "서울숲 방문자센터 앞",
                "서울특별시 성동구 뚝섬로 273",
                37.5443878,
                127.0374424,
                null,
                8,
                walkStart,
                90
        ), owner);
        upsertMeetup(new MeetupSpec(
                occurrenceId("seoul-library-reading", bookStart.toLocalDate()),
                "서울도서관 이웃 독서 모임",
                "각자 읽고 있는 책 한 권을 가져와 인상 깊은 문장을 나눠요. 책의 장르와 독서 속도는 달라도 괜찮아요.",
                List.of("독서", "대화", "커피"),
                "서울도서관 정문 앞",
                "서울특별시 중구 세종대로 110",
                37.566317,
                126.977829,
                null,
                10,
                bookStart,
                120
        ), owner);
    }

    private void closePastOfficialOccurrences(User owner, ZonedDateTime nowSeoul) {
        for (ChatRoom room : chatRoomRepository.findByCreator_IdAndTypeOrderByScheduledAtDesc(
                owner.getId(), ChatRoomType.GROUP)) {
            if (!room.isPublicRoom()
                    || room.getStatus() != ChatRoomStatus.ACTIVE
                    || room.getScheduledAt() == null) {
                continue;
            }
            var scheduledAt = MeetupTimePolicy.toUtcOffset(room.getScheduledAt(), room.getMeetupTimeBasis());
            if (scheduledAt != null && !scheduledAt.toInstant().isAfter(nowSeoul.toInstant())) {
                room.setStatus(ChatRoomStatus.CLOSED);
                chatRoomRepository.save(room);
            }
        }
    }

    private ZonedDateTime nextOccurrence(DayOfWeek dayOfWeek, LocalTime time, ZonedDateTime now) {
        LocalDate date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dayOfWeek));
        ZonedDateTime candidate = ZonedDateTime.of(date, time, SEOUL);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }

    private String occurrenceId(String series, LocalDate date) {
        String key = "official-meetup:" + series + ":" + date;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void upsertMeetup(MeetupSpec spec, User owner) {
        ChatRoom room = chatRoomRepository.findById(spec.id()).orElse(null);
        boolean created = room == null;
        if (created) {
            room = new ChatRoom();
            room.setId(spec.id());
            room.setStatus(ChatRoomStatus.ACTIVE);
        } else {
            requireSystemOwner(room.getCreator(), "meetup", spec.id());
        }

        room.setCreator(owner);
        room.setName(spec.title());
        room.setDescription(spec.description());
        room.setInterestTags(new ArrayList<>(spec.tags()));
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setLocation(spec.location());
        room.setLocationAddress(spec.address());
        room.setLatitude(spec.latitude());
        room.setLongitude(spec.longitude());
        room.setKakaoPlaceId(spec.kakaoPlaceId());
        if (room.getStatus() == null) {
            room.setStatus(ChatRoomStatus.ACTIVE);
        }
        if (created || room.getMaxParticipants() == null) {
            room.setMaxParticipants(spec.maxParticipants());
        }
        if (created || room.getScheduledAt() == null) {
            LocalDateTime scheduledAtUtc = spec.start().withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            room.setScheduledAt(scheduledAtUtc);
            room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
            room.setRegistrationDeadline(scheduledAtUtc.minusDays(1));
            room.setDurationMinutes(spec.durationMinutes());
        }
        room.getParticipants().add(owner);
        chatRoomRepository.save(room);
    }

    private void requireSystemOwner(User owner, String entityName, String id) {
        if (owner == null || owner.getAccountType() != UserAccountType.SYSTEM) {
            throw new IllegalStateException("Official " + entityName + " ID collision: " + id);
        }
    }

    private static String publicUrl(String relativeKey) {
        return "/uploads/" + relativeKey;
    }

    private record PostSpec(
            String id,
            String imageUrl,
            String caption,
            List<String> tags,
            LocalDateTime createdAt
    ) {
    }

    private record MeetupSpec(
            String id,
            String title,
            String description,
            List<String> tags,
            String location,
            String address,
            Double latitude,
            Double longitude,
            String kakaoPlaceId,
            int maxParticipants,
            ZonedDateTime start,
            int durationMinutes
    ) {
    }
}
