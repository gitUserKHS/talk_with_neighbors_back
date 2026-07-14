# API 및 실시간 통신

## 공통 규칙

- 개발 기준 API 주소: `http://localhost:8080/api`
- Docker 프론트 기준: 같은 출처의 `/api`를 Nginx가 백엔드로 전달
- 인증 헤더: `X-Session-Id: {sessionId}`
- 본문 형식: 기본 JSON, 프로필·게시글·채팅 파일 첨부는 `multipart/form-data`
- 페이지 응답: Spring Data `Page<T>` 형태
- 인증 필요 API는 `@RequireLogin`과 사용자 세션 해석기를 사용한다.

## 로그인 없는 공개 API

| 메서드 | 경로 | 공개 범위 |
|---|---|---|
| GET | `/api/public/feed?page=0&size=20` | 작성자가 `publicPreview=true`로 동의한 게시글만 제공; 작성자는 `이웃`으로 익명화 |
| GET | `/api/public/meetups?keyword=&interest=&page=0&size=20` | 활성 상태인 공개 그룹 모임만 제공; 위치·생성자·참가자 식별자·최근 메시지는 제외 |

공개 API의 페이지 크기는 최대 50이야. 댓글 본문과 댓글 작성자, 좋아요 여부, 궁합·공통 관심사 같은 개인화 정보는 제공하지 않아. 작성·수정·삭제, 좋아요·댓글 열람, 모임 참여와 채팅은 기존 인증 API에서만 가능해.

## 인증 API

| 메서드 | 경로 | 인증 | 목적 |
|---|---|---|---|
| POST | `/api/auth/register` | 없음 | 가입, 응답 헤더로 세션 발급 |
| POST | `/api/auth/login` | 없음 | 로그인, 응답 헤더로 세션 발급 |
| POST | `/api/auth/logout` | 선택 | 세션 삭제 |
| GET | `/api/auth/me` | 헤더 필요 | 현재 사용자 조회 |
| GET | `/api/auth/profile` | 필요 | 프로필 조회 |
| PUT | `/api/auth/profile` | 필요 | 프로필 수정 |
| POST | `/api/auth/profile/image` | 필요 | `file` 이미지 파트를 WebP로 최적화해 프로필 사진 교체 |
| DELETE | `/api/auth/profile/image` | 필요 | 프로필 사진과 로컬 파일 삭제 |
| GET | `/api/auth/check-duplicates` | 없음 | `email`, `username` 중복 확인 |

## 피드 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/api/feed?page=0&size=20` | 피드 조회 |
| POST | `/api/feed` | 게시물 생성; JSON은 기존 URL 호환, multipart는 `post` JSON 파트와 `files` 최대 10개; `publicPreview` 기본값은 `false` |
| POST | `/api/feed/{postId}/likes` | 좋아요 |
| DELETE | `/api/feed/{postId}/likes` | 좋아요 취소 |
| GET | `/api/feed/{postId}/comments` | 댓글 조회 |
| POST | `/api/feed/{postId}/comments` | 댓글 작성 |

multipart 업로드 지원 형식은 JPG, PNG, GIF, WebP, MP4, WebM, MOV다. 사진은 파일당 10MB, 동영상은 파일당 100MB, 요청 전체는 200MB로 제한한다. 정적 이미지는 WebP, 영상은 MP4(H.264/AAC)로 변환한다. 응답의 `media[]`에는 `url`, `thumbnailUrl`, `type`, `contentType`, `sizeBytes`, `width`, `height`, `durationSeconds`, `sortOrder`가 포함되며 `/uploads/**`는 Nginx를 거쳐 제공된다.

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
| POST | `/api/meetups/{roomId}/join` | 참가 |
| POST | `/api/meetups/{roomId}/leave` | 탈퇴, 성공 시 204 |

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

채팅 첨부는 이미지·영상 외에 PDF, ZIP, Office 문서, TXT, CSV, JSON, Markdown을 지원한다. 이미지 10MB, 영상 100MB, 문서 25MB, 한 메시지 전체 120MB가 상한이다. 메시지 DTO의 `attachments[]`는 URL·썸네일·원래 파일명·MIME·크기·해상도·재생시간·순서를 포함하며 REST 저장 응답과 STOMP 실시간 이벤트의 계약이 같다. `/uploads/chat/**` 조회도 세션과 해당 채팅방 참가자 권한을 확인하며, 응답은 공유 캐시에 저장하지 않는다.

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

- 연결: `/ws?sessionId={sessionId}`
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
