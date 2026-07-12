# 사용자 안전 기능

## 구현 범위

- 사용자를 차단하거나 차단 해제하고 내 차단 목록을 조회한다.
- 사용자, 피드 게시물, 댓글, 메시지를 신고 대상으로 기록할 수 있다.
- 게시물, 댓글, 메시지를 사용자별로 숨길 수 있다.
- 신고할 때 해당 콘텐츠를 동시에 숨길 수 있다.
- 중복 신고를 막고 신고 상태를 `PENDING`, `REVIEWING`, `RESOLVED`, `DISMISSED`로 보관한다.

## 차단 규칙

차단은 양방향 노출 제한으로 적용한다. 어느 한쪽이 상대를 차단하면 다음 동작을 금지한다.

- 매칭 추천과 주변 사용자 검색 노출
- 새로운 매칭 요청과 기존 요청 수락
- 상대가 작성한 피드 및 댓글 노출과 상호작용
- 상대가 만든 공개 모임 노출과 참여
- 새 1:1 채팅방 생성 및 기존 1:1 채팅방 메시지 전송

차단 시 진행 중인 두 사용자의 매칭은 즉시 `EXPIRED`로 바뀐다. 그룹 채팅은 다른 참여자에게 영향을 주지 않도록 유지한다.

## 데이터 모델

```text
user_blocks(id, blocker_id, blocked_id, created_at)
safety_reports(id, reporter_id, target_type, target_id, reason, details,
               status, created_at, resolved_at)
hidden_contents(id, user_id, target_type, target_id, created_at)
```

각 차단 관계와 숨김 대상에는 고유 제약 조건을 두어 재시도 요청을 멱등하게 처리한다.

## API

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/safety/blocks/{userId}` | 사용자 차단 |
| DELETE | `/api/safety/blocks/{userId}` | 사용자 차단 해제 |
| GET | `/api/safety/blocks` | 내 차단 목록 |
| POST | `/api/safety/reports` | 신고 접수 |
| GET | `/api/safety/reports/mine` | 내 신고 내역 |
| POST | `/api/safety/hidden` | 콘텐츠 숨김 |
| DELETE | `/api/safety/hidden/{targetType}/{targetId}` | 숨김 해제 |

모든 API는 로그인 세션이 필요하다. 신고 대상 유형은 `USER`, `FEED_POST`, `COMMENT`, `MESSAGE`다.

## 이벤트와 전달 보장

- 차단 생성 시 `USER_BLOCKED`
- 신고 접수 시 `CONTENT_REPORTED`

두 이벤트는 업무 데이터와 같은 트랜잭션에서 Outbox에 저장된다. 현재 모놀리스에서는 검색·추천 규칙이 DB를 직접 확인하며, 이후 관리자 검토 알림이나 별도 모더레이션 서비스가 생기면 동일 이벤트를 소비할 수 있다.

## 프론트엔드 흐름

피드 카드의 안전 메뉴에서 다음 작업을 할 수 있다.

1. 게시물만 내 피드에서 숨긴다.
2. 신고 사유와 상세 설명을 작성하고 신고 후 숨김 여부를 선택한다.
3. 작성자를 차단한다. 차단 영향 범위를 확인한 뒤 해당 사용자의 게시물을 즉시 화면에서 제거한다.

## 다음 보완

- 관리자 전용 신고 검토 큐와 감사 로그
- 댓글 및 채팅 메시지의 인라인 안전 메뉴
- 신고 증거 스냅샷과 첨부 파일의 보존 정책
- 반복 신고, 반복 차단 등 위험 신호의 속도 제한과 자동 탐지
