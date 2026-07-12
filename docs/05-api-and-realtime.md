# API 및 실시간 통신

## 공통 규칙

- 개발 기준 API 주소: `http://localhost:8080/api`
- Docker 프론트 기준: 같은 출처의 `/api`를 Nginx가 백엔드로 전달
- 인증 헤더: `X-Session-Id: {sessionId}`
- 본문 형식: JSON
- 페이지 응답: Spring Data `Page<T>` 형태
- 인증 필요 API는 `@RequireLogin`과 사용자 세션 해석기를 사용한다.

## 인증 API

| 메서드 | 경로 | 인증 | 목적 |
|---|---|---|---|
| POST | `/api/auth/register` | 없음 | 가입, 응답 헤더로 세션 발급 |
| POST | `/api/auth/login` | 없음 | 로그인, 응답 헤더로 세션 발급 |
| POST | `/api/auth/logout` | 선택 | 세션 삭제 |
| GET | `/api/auth/me` | 헤더 필요 | 현재 사용자 조회 |
| GET | `/api/auth/profile` | 필요 | 프로필 조회 |
| PUT | `/api/auth/profile` | 필요 | 프로필 수정 |
| GET | `/api/auth/check-duplicates` | 없음 | `email`, `username` 중복 확인 |

## 피드 API

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | `/api/feed?page=0&size=20` | 피드 조회 |
| POST | `/api/feed` | 게시물 생성 |
| POST | `/api/feed/{postId}/likes` | 좋아요 |
| DELETE | `/api/feed/{postId}/likes` | 좋아요 취소 |
| GET | `/api/feed/{postId}/comments` | 댓글 조회 |
| POST | `/api/feed/{postId}/comments` | 댓글 작성 |

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
| GET | `/api/chat/rooms` | 내 방 또는 조건 검색 |
| GET | `/api/chat/rooms/search/all` | 전체 공개 검색 |
| GET | `/api/chat/rooms/search` | 그룹방 목록 검색 |
| GET | `/api/chat/rooms/my` | 내 채팅방 목록 |
| POST | `/api/chat/rooms/one-to-one/{otherUserId}` | 1:1 방 생성 또는 재사용 |
| GET | `/api/chat/rooms/{roomId}` | 방 상세 |
| PATCH | `/api/chat/rooms/{roomId}` | 생성자 전용 방 수정·종료 |
| POST | `/api/chat/rooms/{roomId}/join` | 입장 |
| POST | `/api/chat/rooms/{roomId}/leave` | 퇴장 |
| DELETE | `/api/chat/rooms/{roomId}` | 방 삭제 |
| GET | `/api/chat/rooms/{roomId}/messages` | 메시지 페이지 |
| POST | `/api/chat/rooms/{roomId}/messages` | 메시지 저장 |
| POST | `/api/chat/rooms/{roomId}/messages/read` | 방 전체 읽음 |
| POST | `/api/chat/rooms/{roomId}/messages/{messageId}/read` | 메시지 하나 읽음 |
| GET | `/api/chat/rooms/{roomId}/unread-count` | 방의 미읽음 수 |
| GET | `/api/chat/unread-counts` | 모든 방의 미읽음 수 |

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

- 알림 컨트롤러의 `/api/notifications/test/**` 경로는 운영 환경에서 제거하거나 관리자 권한으로 제한해야 한다.
- `category`는 현재 독립 필드가 아니며 관심사 태그 또는 채팅방 유형으로 모델링해야 한다.
- REST와 STOMP 메시지 전송 중 프론트의 기준 경로를 하나로 통일해야 중복 저장을 예방할 수 있다.
