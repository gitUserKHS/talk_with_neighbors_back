# Session security contract

- REST and STOMP authentication use only the `TWN_SESSION` HttpOnly cookie. Session
  credentials must not appear in response headers, browser storage, URLs, or logs.
- The cookie is `SameSite=Lax`. The production deployment derives `Secure=true`
  whenever `PUBLIC_ORIGIN` uses HTTPS.
- CORS allows credentials only for configured origins. SameSite and CORS reduce the
  current cross-site request surface, but they are not a replacement for a complete
  CSRF defense.
- Before any cross-site client requires `SameSite=None`, add and test a synchronizer
  CSRF token (or an equivalent framework-supported strategy) for every unsafe HTTP
  method. Until then, clients must remain same-origin and deployments must keep a
  strict `CORS_ALLOWED_ORIGINS` value.
- STOMP `/topic/**` subscriptions are denied. Room events are delivered through
  authenticated `/user/queue/**` destinations.
- The server revalidates the handshake cookie session on every client
  frame without extending its TTL or writing presence. A server-side transport
  registry closes every socket for the credential during logout, explicit user
  session removal, scheduled expiry cleanup, or an expired-frame check.
- A missing or expired credential is answered with HTTP 401 and the error code
  `SESSION_EXPIRED`; it is never a 500.

## 세션 조회와 터치 창

- `sessions` 테이블이 기준 저장소다. Redis의 `session:{id}` 항목은 이 서비스가
  `sessions` 행을 확인한 뒤 기록한 사본이며, `dbBacked: true` 표식과 마지막 접근
  시각(epoch 초)을 함께 담는다. 표식이 없는 항목(과거 형식이나 외부 기록)은 절대
  신뢰하지 않고 데이터베이스로 넘어간다.
- 인증된 HTTP 요청은 Redis 항목의 마지막 접근 시각이 5분 이내이면 `sessions` 행을 읽거나
  쓰지 않으며, 이 경로에서는 JPA 트랜잭션도 열지 않는다(커넥션 풀을 점유하지 않는다).
  이 경로가 MySQL에 남기는 유일한 쓰기는 아래 접속 상태 규칙에 따른 `users` 행 갱신뿐이며,
  프로세스당 사용자별 최대 60초에 한 번이다. 5분이 지난 뒤의 첫 요청만 `sessions` 행을
  사용자와 함께 한 번 읽고, 저장된 접근 시각이 5분보다 오래됐을 때만 `last_accessed_at`과
  `expires_at`(24시간)을 갱신한 뒤 Redis 항목을 다시 쓴다. 즉 활성 세션 하나당 5분에
  한 번의 SELECT와 최대 한 번의 UPDATE만 발생한다.
- Redis 항목은 마지막으로 `sessions` 행을 확인한 시각(`verifiedAt`)도 담는다. STOMP 프레임
  검증은 표식이 있고 확인 시각이 5분 이내인 항목이면 그대로 답하고, 그렇지 않으면 `sessions`
  행을 한 번 읽어 행이 없거나 만료됐으면 항목을 지우고 거부하며, 살아 있으면 확인 시각만
  갱신해 다시 쓴다. 어느 경우에도 `expires_at`을 늘리거나 접속 상태를 쓰지 않고, JPA
  트랜잭션을 열지 않는다. Redis 항목의 TTL은 행의 `expires_at`까지의 남은 시간이다.
- 폐기는 즉시 반영된다. 로그아웃, 사용자 세션 일괄 제거, 만료 정리는 모두 Redis 항목을
  지우고 해당 자격 증명의 소켓을 닫는다. Redis 장애는 호출자의 트랜잭션을 실패시키지
  않지만 WARN으로 기록되며, 삭제에 실패해 남은 항목은 위의 재확인 규칙에 따라 늦어도
  5분 안에 `sessions` 행과 대조되어 거부된다.
- 닉네임 변경 같은 사용자 정보 갱신은 사용자의 활성 `sessions` 행에 대한 Redis 항목만
  다시 쓴다. 행의 접근/만료 시각은 바뀌지 않고, 아직 정리되지 않은 만료 행은 건너뛴다.

## 접속 상태(presence) 규칙

- `online:{userId}` Redis 키(300초)는 인증된 요청마다 갱신된다. `users.is_online`과
  `last_online_at`은 키가 없다가 생겼을 때(오프라인에서 온라인으로 전환) 또는 이
  프로세스가 마지막으로 기록한 지 60초가 지났을 때만 다시 쓴다. 60초는 오프라인
  판정 기준(5분)보다 충분히 짧으므로 활성 사용자가 잘못 오프라인 처리되지 않는다.
- `isUserOnline`은 이 프로세스에 열린 STOMP 세션이 있으면 온라인, 아니면 Redis 키가
  있으면 온라인(키 TTL 갱신), 그 밖에는 오프라인으로 답한다. `users` 행의
  `is_online`/`last_online_at`은 Redis가 응답하지 않을 때만 사용하는 대체 경로다.
- 온라인 전환 이벤트(보류 알림 전송)는 Redis 키가 없다가 생긴 경우에만 발생한다.

The MVC interceptor and AOP authentication paths were removed. Follow-up
refactoring should consolidate the remaining argument resolver and
`BaseController` helpers around Spring Security's principal.
