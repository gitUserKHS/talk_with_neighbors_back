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

공개 취미 모임에 다음 필드를 추가했다.

```text
scheduled_at, duration_minutes, registration_deadline, reminder_sent_at
meetup_waitlist(room_id, user_id, created_at)
```

- 정원이 찬 모임에 신청하면 FIFO 대기열에 등록된다.
- 기존 참여자가 나가면 가장 오래 기다린 사용자가 자동 승급한다.
- 신청 마감 이후에는 새 참여와 대기 등록을 막는다.
- 시작 24시간 전 구간에 들어온 모임은 참여자별 알림함에 리마인더를 저장한다.

현재 MVP의 캘린더 계약은 다음과 같다.

- DB의 `scheduled_at`, `registration_deadline`은 기존 `LocalDateTime` 컬럼을 유지하되 새 값은 UTC로 저장하고 `meetup_time_basis=UTC`를 기록한다.
- 컷오버 전 생성되어 `meetup_time_basis`가 null인 행은 `Asia/Seoul` wall clock으로 해석한다. DTO·마감 검사·리마인더 조회가 같은 호환 정책을 사용하므로 기존 일정이 9시간 밀리지 않는다.
- 생성 API는 오프셋이 포함된 ISO-8601만 받는다. 예: `2026-07-18T19:00:00+09:00`.
- 인증/공개 모임 API 응답은 UTC로 정규화한 `Z` 시각을 반환한다. 예: `2026-07-18T10:00:00Z`.
- 브라우저의 `datetime-local` 값은 사용자의 로컬 시각으로 해석해 UTC ISO 문자열로 전송하고,
  응답의 오프셋을 해석해 다시 사용자의 로컬 시각으로 표시한다.
- 한국 대상 MVP의 기본 표기 시간대는 `Asia/Seoul`이며, API와 DB에는 시간대 없는 문자열을
  주고받지 않는다. 향후 다른 지역을 지원할 때는 IANA `timeZone` 필드를 회차에 함께 보관한다.

종료 시각은 `scheduled_at + duration_minutes`로 계산한다. `registration_deadline`은 참가 신청
마감일 뿐 종료 시각으로 사용하지 않는다. 내 모임 캘린더 화면은 우선 `/api/mypage/meetups`의
이 세 필드를 월·목록 보기로 투영한다.

반복 모임이나 한 채팅방 안의 여러 약속이 필요해지는 시점에는 `chat_rooms`에 반복 규칙을 계속
붙이지 않고 `meetup_occurrences`(시작·종료·장소·회차별 RSVP) 엔티티를 분리한다. 이렇게 해야
한 회차만 시간이나 지도 장소를 바꾸는 예외와 캘린더 내보내기를 안전하게 처리할 수 있다.

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
