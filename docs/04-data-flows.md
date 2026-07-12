# 데이터 흐름

## 가입과 로그인

```mermaid
sequenceDiagram
    actor User
    participant FE as React
    participant API as Auth API
    participant DB as MySQL
    participant Redis

    User->>FE: 가입 또는 로그인 제출
    FE->>API: POST /api/auth/register 또는 login
    API->>DB: 사용자 조회/저장, 비밀번호 검증
    API->>Redis: session:{uuid} 저장
    API-->>FE: UserDto + X-Session-Id 헤더
    FE->>FE: 세션 ID와 사용자 상태 보관
    FE->>API: 이후 요청에 X-Session-Id 첨부
    API->>Redis: 세션 검증
```

현재 세션의 신뢰 기준은 Redis다. 세션이 없거나 만료되면 보호 API는 인증 실패로 처리한다.

## 피드 작성과 반응

```mermaid
flowchart LR
    A["인증 사용자"] --> B["POST /api/feed"]
    B --> C["FeedService"]
    C --> D[("feed_posts")]
    C --> E[("feed_post_interest_tags")]
    A --> F["좋아요/댓글 요청"]
    F --> G[("post_likes / post_comments")]
    G --> H["갱신된 DTO 반환"]
```

## 매칭 요청과 수락

```mermaid
sequenceDiagram
    participant A as 요청 사용자
    participant API as Matching API
    participant DB as MySQL
    participant N as Notification
    participant B as 대상 사용자
    participant Chat as Chat Service

    A->>API: POST /matching/users/{id}/request
    API->>DB: PENDING Match 저장
    API->>N: 매칭 요청 알림
    N-->>B: WebSocket 또는 오프라인 저장
    B->>API: POST /matching/{matchId}/accept
    API->>DB: ACCEPTED + respondedAt
    API->>Chat: 1:1 채팅방 생성/조회
    API->>DB: MatchCompletedEvent Outbox 저장
    API-->>B: ChatRoomDto
    DB-->>N: 커밋 후 이벤트 전달
    N-->>A: 매칭 성사 알림
```

매칭 상태와 채팅방 생성은 같은 트랜잭션에서 처리되고, 후속 알림은 Outbox 이벤트로 분리한다.

## 취미 모임

```mermaid
flowchart TD
    A["모임 생성 요청"] --> B["HobbyMeetupService"]
    B --> C["공개 GROUP ChatRoom 생성"]
    C --> D["설명·태그·위치·정원 저장"]
    D --> E["생성자를 참가자로 추가"]
    F["다른 사용자 참가"] --> G{"정원/중복 검사"}
    G -->|통과| H["참가자 + MeetupJoined Outbox 저장"]
    G -->|실패| I["오류 응답"]
```

## 실시간 채팅

```mermaid
sequenceDiagram
    participant FE as React STOMP Client
    participant WS as /ws SockJS
    participant Chat as ChatController
    participant DB as MySQL
    participant Q as 사용자별 큐

    FE->>WS: X-Session-Id 기반 연결
    FE->>Q: /user/queue/chat/room/{roomId} 구독
    FE->>Chat: /app/chat.sendMessage
    Chat->>DB: 메시지 저장, 방 마지막 메시지 갱신
    Chat->>Q: 참가자별 메시지 전달
    Q-->>FE: 새 메시지
    FE->>Chat: 읽음 처리
    Chat->>DB: message_read_by 갱신
```

REST `POST /api/chat/rooms/{roomId}/messages`도 메시지를 저장할 수 있다. 두 전송 경로의 중복 호출을 피하도록 프론트 전송 정책을 하나로 고정해야 한다.

## 오프라인 알림

```mermaid
flowchart TD
    E["업무 이벤트"] --> O{"수신자 온라인?"}
    O -->|예| W["사용자별 STOMP 큐 전송"]
    O -->|아니오| D[("offline_notifications 저장")]
    R["WebSocket 재연결"] --> P["미발송·미만료 알림 조회"]
    P --> W
    W --> S["발송 완료 표시"]
    C["정리 작업"] --> X["발송·만료 데이터 삭제"]
```

## 실패와 재시도 원칙

- DB 트랜잭션이 실패하면 해당 업무 변경은 롤백한다.
- WebSocket 전달 실패는 업무 데이터 저장 실패와 구분한다.
- 오프라인 알림은 중복을 확인하고 재시도 횟수를 기록한다.
- 중요 후속 작업은 `outbox_events`에 같은 트랜잭션으로 기록한다.
- 커밋 직후 전달에 실패하거나 프로세스가 종료되면 5초 주기 릴레이가 재시도한다.
- 전달은 최소 1회 방식이므로 소비자는 `eventId`를 기준으로 중복을 견뎌야 한다.
