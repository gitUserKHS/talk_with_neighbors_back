# 제품 확장 기능

## 영구 알림함

WebSocket 전달 여부(`deliveredAt`)와 사용자의 읽음 여부(`readAt`)를 분리했다. 알림은 전달된 뒤에도 30일 동안 보관되며 REST로 복구할 수 있다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/api/notifications` | 최근 알림 페이지 조회 |
| GET | `/api/notifications/unread-count` | 읽지 않은 알림 수 |
| PATCH | `/api/notifications/{id}/read` | 한 건 읽음 |
| PATCH | `/api/notifications/read-all` | 모두 읽음 |
| DELETE | `/api/notifications/{id}` | 한 건 삭제 |

알림 조회·수정·삭제는 항상 현재 세션의 `userId`로 소유권을 확인한다.

## 프로필 온보딩

가입 직후 기본 정보, 관심사, 동네를 차례로 입력한다. 서버는 프로필 완성 여부와 0~100 완성도를 함께 반환하며, 미완성 사용자는 보호된 주요 화면보다 온보딩을 먼저 완료한다.

완성 조건은 다음 다섯 항목이다.

1. 나이
2. 성별 또는 공개하지 않음 선택
3. 관심사 한 개 이상
4. 위치 좌표
5. 주소

## 모임 일정과 대기열

공개 취미 모임의 일정은 채팅방 단위 다중 회차 캘린더로 관리한다.

```text
chat_schedules(room_id, creator_id, starts_at, duration_minutes, time_zone, location, status, version)
chat_schedule_rsvps(schedule_id, user_id, status, responded_at)
chat_rooms(scheduled_at, duration_minutes, meetup_time_basis, reminder_sent_at)  # 다음 회차 projection
meetup_waitlist(room_id, user_id, created_at)
```

- 정원이 찬 모임에 신청하면 FIFO 대기열에 등록된다.
- 기존 참여자가 나가면 가장 오래 기다린 사용자가 자동 승급한다.
- 시작 24시간 전 구간에 들어온 모임은 참여자별 알림함에 리마인더를 저장한다.
- 새 프런트는 모임 프로필에서 일정과 `registration_deadline`을 쓰지 않는다. expand 단계의 백엔드는 이전 프런트 요청을 계속 받아 결정적 calendar 회차로 동기화하고 legacy 열도 롤백용으로 보존한다. 단, 이전 폼이 표시하던 deterministic 미래 회차가 더 이상 현재 projection이 아니거나 취소·시작된 경우 start/duration 변경은 `409`로 거절하고 모임 달력에서 수정하게 한다. 일정 필드가 동일한 프로필 metadata 수정은 허용한다. 일정 생성·수정·취소와 참석 응답의 최종 기준은 모임 달력이다.

현재 캘린더 계약은 다음과 같다.

- `chat_schedules`가 기준 데이터이며 한 방에 여러 미래·지난·취소 회차를 보관한다.
- 회차 변경 뒤 가장 이른 미래 `SCHEDULED` 시작·기간을 `chat_rooms`에 UTC projection으로 복사한다. 다음 회차가 없으면 일정 projection과 리마인더 상태를 비우되, expand 릴리스에서는 `registration_deadline`을 롤백 호환용으로 그대로 둔다. canonical 회차가 있는 방의 참가 판정에는 이 legacy 마감을 적용하지 않는다.
- 컷오버 전 `chat_rooms.scheduled_at`만 있는 방은 달력 첫 조회 때 실제 회차·모임장 RSVP·연결 카드로 멱등 변환한다. null `meetup_time_basis`는 `Asia/Seoul` wall clock으로 해석한다.
- 생성 API는 오프셋이 포함된 ISO-8601과 IANA `timeZone`을 받는다. 예: `2026-07-18T19:00:00+09:00`, `Asia/Seoul`.
- 인증/공개 모임 API 응답은 UTC로 정규화한 `Z` 시각을 반환한다. 예: `2026-07-18T10:00:00Z`.
- 브라우저의 `datetime-local` 값은 사용자의 로컬 시각으로 해석해 UTC ISO 문자열로 전송하고,
  응답의 오프셋을 해석해 다시 사용자의 로컬 시각으로 표시한다.
- 한국 대상 MVP의 기본 표기 시간대는 `Asia/Seoul`이며, API와 DB에는 시간대 없는 문자열을
  주고받지 않는다. 향후 다른 지역을 지원할 때는 IANA `timeZone` 필드를 회차에 함께 보관한다.

종료 시각은 각 회차의 `starts_at + duration_minutes`로 계산한다. 일정 카드 메시지는 WebSocket
갱신용 안정 ID를 제공하지만 일반 채팅 페이지·읽지 않음 수·마지막 메시지 미리보기에는 포함하지
않는다. 목록과 참석자 현황은 모임 달력 API가 한곳에서 제공한다.

## 위치 프라이버시

정확한 위도·경도는 본인의 프로필 응답에서만 사용한다. 다른 사용자에게 제공되는 매칭 프로필은 좌표를 `null`로 만들고 주소는 시·구 수준 두 토큰으로 일반화한다. 사용자 간 거리는 서버에서 계산한 숫자만 반환한다.

## 설명형 매칭과 피드백

추천 결과에 점수와 함께 다음과 같은 설명 목록을 제공한다.

- 공통 관심사 개수
- 거리 구간
- 비슷한 연령대

사용자는 추천에 `POSITIVE` 또는 `NEGATIVE` 피드백을 남길 수 있다. 사용자와 후보 조합은 고유하며 같은 대상에 다시 피드백하면 최신 값으로 갱신된다.

```http
POST /api/matching/recommendations/{candidateId}/feedback
```

## 운영 시 다음 단계

- 스케줄러 다중 인스턴스 실행 시 분산 락 적용
- 알림 목록의 커서 기반 페이지네이션 전환
- 추천 피드백을 실제 랭킹 가중치 학습에 반영
- 모임 시간대와 사용자의 조용한 시간 설정 연동
