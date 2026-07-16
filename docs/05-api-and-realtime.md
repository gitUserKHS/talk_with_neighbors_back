# API 및 실시간 통신

## 공통 규칙

- 개발 기준 API 주소: `http://localhost:8080/api`
- Docker 프론트 기준: 같은 출처의 `/api`를 Nginx가 백엔드로 전달
- 인증: `TWN_SESSION` HttpOnly 쿠키(브라우저가 동일 출처 요청에 자동 첨부)
- 본문 형식: 기본 JSON, 프로필·게시글·채팅 파일 첨부는 `multipart/form-data`
- 페이지 응답: Spring Data `Page<T>` 형태
- 공개 경로는 `SecurityConfig` allowlist에만 명시하고 나머지 API는 Spring Security가 기본 거부한다. `SessionAuthenticationFilter`와 사용자 세션 해석기가 같은 HttpOnly 쿠키 세션을 사용한다.

## 로그인 없는 공개 API

| 메서드 | 경로 | 공개 범위 |
|---|---|---|
| GET | `/api/public/feed?page=0&size=20&mode=RECOMMENDED` | 작성자가 `publicPreview=true`로 동의한 게시글만 제공; `RECOMMENDED`, `NEARBY`, `LATEST` 지원, 선택적 `region`은 시·도 또는 시·도+시·군·구의 정확한 주소 접두부만 요청 범위에서 사용, 작성자는 `이웃`으로 익명화 |
| GET | `/api/public/meetups?keyword=&interest=&page=0&size=20` | 활성 공개 그룹 모임 제공; 일반 회원 모임 위치는 제외하고 SYSTEM 공식 모임 위치만 공개 |

두 공개 DTO에는 `official: boolean`이 포함된다. `APP_OFFICIAL_CONTENT_ENABLED=true`이면 SYSTEM
운영팀이 소유한 정상 영속 엔티티를 고정 UUID로 동기화한다. 메모리 데모 fallback과 `demo` 필드는
없다. 일반 회원 작성자는 계속 익명화하며, 공식 모임에 한해서 장소명·주소·좌표를 공개한다.

공개 API의 페이지 크기는 최대 50이야. 댓글 본문과 댓글 작성자, 좋아요 여부, 궁합·공통 관심사 같은 개인화 정보는 제공하지 않아. 작성·수정·삭제, 좋아요·댓글 열람, 모임 참여와 채팅은 기존 인증 API에서만 가능해.

## 인증 API

| 메서드 | 경로 | 인증 | 목적 |
|---|---|---|---|
| POST | `/api/auth/register` | 없음 | 가입, `TWN_SESSION` HttpOnly 쿠키 발급 |
| POST | `/api/auth/email-verifications` | 없음 | `{email}`로 6자리 코드 요청, 무작위 `challengeId` 반환 |
| POST | `/api/auth/email-verifications/{challengeId}/confirm` | 없음 | `{code}` 확인 후 가입 경로 전용 Secure HttpOnly proof 쿠키 발급 |
| POST | `/api/auth/email-verifications/{challengeId}/resend` | 없음 | 쿨다운 뒤 같은 challenge에 코드 재발송 |
| POST | `/api/auth/login` | 없음 | 로그인, `TWN_SESSION` HttpOnly 쿠키 발급 |
| POST | `/api/auth/logout` | 선택 | 세션 삭제 |
| GET | `/api/auth/me` | 쿠키 필요 | 현재 사용자 조회 |
| GET | `/api/auth/profile` | 필요 | 프로필 조회 |
| PUT | `/api/auth/profile` | 필요 | 프로필 수정 |
| POST | `/api/auth/profile/image` | 필요 | `file` 이미지 파트를 WebP로 최적화해 프로필 사진 교체 |
| DELETE | `/api/auth/profile/image` | 필요 | 프로필 사진과 로컬 파일 삭제 |
| GET | `/api/auth/check-duplicates` | 없음 | `username` 중복 확인 (이메일은 인증 challenge로 확인) |
| GET | `/api/public/auth/providers` | 없음 | 이메일 인증·Google·Kakao 활성 상태와 표준 시작 경로 조회 |
| GET | `/api/oauth2/authorization/{google\|kakao}` | 없음 | Spring Security OAuth2/OIDC Authorization Code 흐름 시작 |

이메일 challenge는 코드와 proof 원문을 저장하지 않고 HMAC 해시, 만료, 재전송 가능 시각, 실패 횟수, 1회 소비 시각만 저장한다. OAuth callback은 `/api/login/oauth2/code/{registrationId}`이며 공급자 token을 보관하지 않고 성공 시 로컬 `TWN_SESSION`만 남긴다. 공급자 이메일이 기존 로컬 계정과 같아도 자동 연결하지 않는다.

## 피드 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/api/feed?page=0&size=20&mode=RECOMMENDED` | 피드 조회; `RECOMMENDED`, `NEARBY`, `LATEST` 지원, 생략 시 `RECOMMENDED`; 숨김·양방향 차단을 DB에서 먼저 제외하며 정확한 거리는 응답하지 않음 |
| POST | `/api/feed` | 게시물 생성; JSON은 외부 이미지 URL 호환, multipart는 `post` JSON 파트와 `files` 최대 10개; `publicPreview` 기본값은 `false` |
| PATCH | `/api/feed/{postId}` | 작성자 전용 본문·태그·공개 미리보기 수정; 기존 미디어 유지 |
| DELETE | `/api/feed/{postId}` | 작성자 전용 게시물·댓글·좋아요와 서버 소유 미디어 삭제 |
| POST | `/api/feed/{postId}/likes` | 좋아요 |
| DELETE | `/api/feed/{postId}/likes` | 좋아요 취소 |
| GET | `/api/feed/{postId}/comments` | 댓글 조회 |
| POST | `/api/feed/{postId}/comments` | 댓글 작성 |
| PATCH | `/api/feed/comments/{commentId}` | 작성자 전용 댓글 수정 |
| DELETE | `/api/feed/comments/{commentId}` | 작성자 전용 댓글 삭제 |

multipart 업로드 지원 형식은 JPG, PNG, GIF, WebP, MP4, WebM, MOV다. 사진은 파일당 10MB, 동영상은 파일당 30MB·요청당 1개, 서비스 검증 기준 요청 전체는 120MB로 제한한다. multipart 파서는 파일당 30MB·요청당 125MB에서 먼저 차단한다. 동영상은 60초, 긴 변 1920px, 총 2073600픽셀 이하만 허용한다. 정적 이미지는 WebP, 영상은 MP4(H.264/AAC)로 변환한다. 응답의 `media[]`에는 `url`, `thumbnailUrl`, `type`, `contentType`, `sizeBytes`, `width`, `height`, `durationSeconds`, `sortOrder`가 포함되며 `/uploads/**`는 Nginx를 거쳐 제공된다.

JSON 호환 경로에는 서비스 내부 `/uploads/**` URL을 넣을 수 없다. 서버가 소유한 미디어는 multipart 업로드로만 만들고, 삭제 시에도 해당 게시물에 연결된 `feed/` prefix 객체만 정리해 다른 프로필·채팅·게시물 미디어를 건드리지 않는다.

## 매칭 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| POST | `/api/matching/preferences` | 선호 조건 저장 |
| POST | `/api/matching/start` | 매칭 시작·추천 반환 |
| GET | `/api/matching/recommendations` | 추천 목록 |
| GET | `/api/matching/requests/incoming` | 받은 요청 |
| POST | `/api/matching/users/{targetUserId}/request` | 매칭 요청 |
| POST | `/api/matching/stop` | 매칭 중지 |
| POST | `/api/matching/{matchId}/accept` | 요청 수락, 채팅방 반환 |
| POST | `/api/matching/{matchId}/reject` | 요청 거절 |
| GET | `/api/matching/nearby?latitude=&longitude=&radius=` | 주변 사용자 검색 |
| POST | `/api/matching/process-pending` | 대기 매칭 처리 |

## 모임 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/api/meetups?keyword=&interest=&page=0&size=20` | 모임 검색 |
| POST | `/api/meetups` | 공개 그룹 모임 생성 |
| GET | `/api/meetups/{roomId}` | 모임 상세, 참여자 목록과 서버 판정 `canManage` 포함 |
| PATCH | `/api/meetups/{roomId}` | 모임장 전용 프로필 정보·대표 장소·정원 수정 |
| DELETE | `/api/meetups/{roomId}` | 모임장 전용 모임·채팅·약속·참여 기록 삭제 |
| POST | `/api/meetups/{roomId}/join` | 참가 |
| POST | `/api/meetups/{roomId}/leave` | 탈퇴, 성공 시 204 |

모임 생성의 장소 필드는 기존 표시명 `location`과 선택적인 `locationAddress`, `latitude`,
`longitude`, `kakaoPlaceId`다. 위도와 경도는 반드시 함께 보내며, 인증 모임 DTO에서만 이 장소
정보를 돌려준다. `/api/public/meetups`는 정확한 장소 정보를 계속 제외한다.
참가·대기열 변경은 모임 API만 담당한다. 일반 채팅방 입장·수정 API로 공개 모임의 차단 관계,
정원과 대기열 규칙을 우회할 수 없다. 회차 일정 변경은 모임 달력 API만 담당한다.

## 채팅 REST API

| 메서드 | 경로 | 목적 |
|---|---|---|
| POST | `/api/chat/rooms` | 방 생성 |
| GET | `/api/chat/rooms` | 내가 참가한 방 조회 또는 조건 검색 |
| GET | `/api/chat/rooms/search/all` | 내가 참가한 방에서 유형·키워드 검색 |
| GET | `/api/chat/rooms/search` | 내가 참가한 그룹방 검색 |
| GET | `/api/chat/rooms/my` | 내 채팅방 목록 |
| POST | `/api/chat/rooms/one-to-one/{otherUserId}` | 1:1 방 생성 또는 재사용 |
| GET | `/api/chat/rooms/{roomId}` | 방 상세 |
| PATCH | `/api/chat/rooms/{roomId}` | 생성자 전용 방 수정·종료 |
| POST | `/api/chat/rooms/{roomId}/join` | 입장 |
| POST | `/api/chat/rooms/{roomId}/leave` | 퇴장 |
| DELETE | `/api/chat/rooms/{roomId}` | 방 삭제 |
| GET | `/api/chat/rooms/{roomId}/messages` | 메시지 페이지 |
| POST | `/api/chat/rooms/{roomId}/messages` | JSON 텍스트 또는 `message` JSON 파트 + `files` 최대 5개 multipart 저장 |
| POST | `/api/chat/rooms/{roomId}/messages/read` | 방 전체 읽음 |
| POST | `/api/chat/rooms/{roomId}/messages/{messageId}/read` | 메시지 하나 읽음 |
| GET | `/api/chat/rooms/{roomId}/unread-count` | 방의 미읽음 수 |
| GET | `/api/chat/unread-counts` | 모든 방의 미읽음 수 |

채팅 첨부는 이미지·영상 외에 PDF, ZIP, Office 문서, TXT, CSV, JSON, Markdown을 지원한다. 이미지 10MB, 영상 30MB·60초·요청당 1개, 문서 25MB, 한 메시지 전체 120MB가 상한이며 영상에는 피드와 같은 1080p 픽셀 예산을 적용한다. 메시지 DTO의 `attachments[]`는 URL·썸네일·원래 파일명·MIME·크기·해상도·재생시간·순서를 포함하며 REST 저장 응답과 STOMP 실시간 이벤트의 계약이 같다. `/uploads/chat/**` 조회도 세션과 해당 채팅방 참가자 권한을 확인하며, 응답은 공유 캐시에 저장하지 않는다.

## 안전 API

차단·신고·콘텐츠 숨김 API와 적용 규칙은 [사용자 안전 기능](09-safety-and-moderation.md)을 따른다. 차단 관계는 매칭, 피드, 공개 모임, 1:1 채팅에서 양방향으로 제외된다.

| 메서드 | 경로 | 목적 |
|---|---|---|
| POST / DELETE | `/api/safety/blocks/{userId}` | 차단·차단 해제 |
| GET | `/api/safety/blocks` | 내 차단 목록 |
| POST | `/api/safety/reports` | 신고 접수 |
| GET | `/api/safety/reports/mine` | 내 신고 내역 |
| POST / DELETE | `/api/safety/hidden...` | 콘텐츠 숨김·복구 |

## 알림함 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/api/notifications` | 30일 알림 목록 |
| GET | `/api/notifications/unread-count` | 미읽음 수 |
| PATCH | `/api/notifications/{id}/read` | 한 건 읽음 |
| PATCH | `/api/notifications/read-all` | 모두 읽음 |
| DELETE | `/api/notifications/{id}` | 알림 삭제 |

모임 일정·대기열과 추천 피드백 계약은 [제품 확장 기능](10-product-expansion.md)에 정리한다.

## WebSocket/STOMP

- 연결: `/ws` (`TWN_SESSION` HttpOnly 쿠키로 handshake 인증)
- 앱 전송 prefix: `/app`
- 사용자 목적지 prefix: `/user`

클라이언트 전송 목적지:

- `/app/chat.sendMessage`
- `/app/chat.markAsRead`
- `/app/chat.markAllAsRead`
- `/app/chat.enterRoom`
- `/app/chat.leaveRoom`
- `/app/chat.deleteRoom`
- `/app/client/ready`

클라이언트 구독 목적지:

- `/user/queue/chat/room/{roomId}`
- `/user/queue/chat-notifications`
- `/user/queue/chat-updates`
- `/user/queue/match-notifications`
- `/user/queue/system-notifications`

## 남은 계약·보안 과제

- `category`는 현재 독립 필드가 아니며 관심사 태그 또는 채팅방 유형으로 모델링해야 한다.
- 메시지 쓰기는 REST, 실시간 수신·읽음 상태·목록 갱신은 STOMP로 역할을 고정했다.
- 테스트용 알림 HTTP 엔드포인트는 제거됐으며, 재접속 전달은 인증된 `/app/client/ready`만 사용한다.
- 클라이언트 프레임마다 쓰기 없는 세션 검증을 수행하고, 로그아웃·사용자 세션 제거·만료 정리 때 해당 자격의 WebSocket 전송로를 서버측 레지스트리에서 즉시 닫는다.
