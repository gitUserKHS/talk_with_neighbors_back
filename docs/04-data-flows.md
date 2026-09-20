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
    API-->>FE: UserDto + TWN_SESSION HttpOnly 쿠키
    FE->>FE: 사용자 상태만 보관; HttpOnly 쿠키 값은 브라우저가 관리
    FE->>API: 이후 동일 출처 요청에 쿠키 자동 첨부
    API->>Redis: 세션 검증
```

현재 세션의 신뢰 기준은 Redis다. 세션이 없거나 만료되면 보호 API는 인증 실패로 처리한다.

## 피드 작성과 반응

```mermaid
flowchart LR
    A["인증 사용자"] --> B["multipart POST /api/feed"]
    B --> V["개수·크기·파일 시그니처 검증"]
    V --> P["FFmpeg: WebP 또는 H.264/AAC MP4"]
    P --> T["WebP 썸네일 + FFprobe 메타데이터"]
    T --> S[("uploads_data 볼륨")]
    V --> C["FeedService"]
    C --> D[("feed_posts")]
    C --> M[("feed_post_media + 표시 순서")]
    C --> E[("feed_post_interest_tags")]
    X["게시물 삭제 커밋"] --> Y["FeedPostDeletedEvent"]
    Y --> Z["로컬 미디어와 썸네일 삭제"]
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

    FE->>WS: TWN_SESSION 쿠키 기반 연결
    FE->>Q: /user/queue/chat/room/{roomId} 구독
    FE->>Chat: /app/chat.sendMessage
    Chat->>DB: 메시지 저장, 방 마지막 메시지 갱신
    Chat->>Q: 참가자별 메시지 전달
    Q-->>FE: 새 메시지
    FE->>Chat: 읽음 처리
    Chat->>DB: message_read_by 갱신
```

REST `POST /api/chat/rooms/{roomId}/messages`도 메시지를 저장할 수 있다. 두 전송 경로의 중복 호출을 피하도록 프론트 전송 정책을 하나로 고정해야 한다.

파일이 있는 메시지는 프론트가 `message` JSON 파트와 `files` 배열을 multipart로 보낸다. 서버는 DB 트랜잭션을 열기 전에 파일을 검증·변환하고, 메시지와 `message_attachments`를 같은 트랜잭션에 저장한다. 커밋된 DTO만 `ChatMessageCommittedEvent`로 참가자에게 전달한다. 저장이 실패하면 새 파일을 즉시 지우고, 방 삭제 시에는 첨부 행과 메시지를 지운 뒤 커밋 후 실제 원본·썸네일을 정리한다.

프로필 사진도 같은 파이프라인을 사용한다. 새 WebP 생성에 성공한 뒤 사용자 레코드를 갱신하며, 트랜잭션 커밋 이후 이전 로컬 사진을 삭제한다.

## 오프라인 알림

```mermaid
flowchart TD
    E["업무 이벤트"] --> D[("offline_notifications 저장")]
    D --> O{"열린 STOMP 세션?"}
    O -->|예| W["사용자별 STOMP 큐 전송"]
    O -->|아니오| U["웹푸시 발송"]
    R["WebSocket 재연결"] --> P["미발송·미만료 알림 조회"]
    P --> W
    W --> S["발송 완료 표시"]
    C["정리 작업"] --> X["발송·만료 데이터 삭제"]
```

알림함 행은 이벤트당 한 번만 저장한다. 수신자에게 열린 STOMP 세션이 있으면 앱 안 토스트로 전달하고 같은 행을 발송 완료로 표시하며, 세션이 없을 때만 웹푸시를 보낸다. 브라우저를 열어 둔 사용자가 토스트와 OS 알림을 겹쳐 받지 않기 위해서다. 푸시 페이로드의 `tag`는 채팅방(`chat:{roomId}`) 또는 대상 URL(`twn:{url}`)별로 나뉘어 서로 다른 방·글의 알림이 하나로 합쳐지지 않는다.

채팅의 입장·퇴장·시스템 메시지는 읽지 않은 수만 갱신하고 토스트·알림함·푸시를 만들지 않는다.

재접속 시 프론트는 매칭·채팅·시스템·채팅 갱신 큐를 먼저 구독한 뒤 `/app/client/ready`를 발행한다. 백엔드는 이 준비 신호를 받은 뒤에만 대기 알림을 전달하므로 구독 전 메시지 유실을 피한다.

웹푸시 발송과 대기 알림 전달처럼 실행자를 지정하지 않은 `@Async` 작업은 `async-` 풀(코어 2, 최대 4, 대기열 200)에서 실행되며, 풀이 가득 차면 호출자 스레드에서 대신 실행하지 않고 거부한 뒤 경고를 남긴다. `@Scheduled` 작업은 전용 `sched-` 스케줄러를 쓰고 WebSocket 하트비트 풀(`ws-heartbeat-`)과는 분리되어 있다.

## 실패와 재시도 원칙

- DB 트랜잭션이 실패하면 해당 업무 변경은 롤백한다.
- WebSocket 전달 실패는 업무 데이터 저장 실패와 구분한다.
- 오프라인 알림은 중복을 확인하고 재시도 횟수를 기록한다. 좋아요 알림은 취소 후 다시 눌러도 같은 글·같은 이웃의 알림이 만료되기 전이면 전달 여부와 무관하게 다시 만들지 않으며, 이미 전달된 행이 반환되면 토스트도 다시 보내지 않는다.
- 중요 후속 작업은 `outbox_events`에 같은 트랜잭션으로 기록한다.
- 커밋 직후 전달에 실패하거나 프로세스가 종료되면 5초 주기 릴레이가 재시도한다.
- 전달은 최소 1회 방식이므로 소비자는 `eventId`를 기준으로 중복을 견뎌야 한다.
