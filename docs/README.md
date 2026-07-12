# 이웃톡 기술 문서

이 문서는 현재 저장소의 실제 구현을 기준으로 작성한다. 목표 구조나 아이디어는 현재 구현과 혼동되지 않도록 별도로 표시한다.

## 문서 지도

1. [시스템 개요](01-system-overview.md) — 제품 목적, 기술 스택, 실행 구조
2. [기능 명세](02-functional-specification.md) — 사용자 기능, 규칙, 구현 상태
3. [데이터 모델](03-data-model.md) — MySQL 엔티티와 Redis 데이터
4. [데이터 흐름](04-data-flows.md) — 인증, 피드, 매칭, 채팅, 알림 흐름
5. [API 및 실시간 통신](05-api-and-realtime.md) — REST, WebSocket 계약과 알려진 불일치
6. [실행·배포·CI/CD](06-operations-and-cicd.md) — Docker Compose, GHCR, GitHub Actions
7. [아키텍처 발전 계획](07-architecture-roadmap.md) — 모듈형 모놀리스와 Kafka 도입 기준
8. [제품 기능 백로그](08-product-backlog.md) — 다음 기능 후보와 우선순위
9. [사용자 안전 기능](09-safety-and-moderation.md) — 차단·신고·콘텐츠 숨김 정책과 API
10. [제품 확장 기능](10-product-expansion.md) — 영구 알림함, 온보딩, 모임 일정, 프라이버시, 설명형 매칭

## 빠른 시작

프로젝트 루트에서 다음 명령을 실행한다.

```bash
docker compose up --build -d
```

- 프론트엔드: <http://localhost:3000>
- 백엔드 API: <http://localhost:8080>
- 종료: `docker compose down`

## 문서 유지 원칙

- API나 데이터 모델을 변경하는 PR은 관련 문서도 함께 수정한다.
- `현재 구현`, `알려진 제한`, `목표 구조`를 명확히 구분한다.
- 보안 값과 실제 운영 비밀은 문서나 저장소에 기록하지 않는다.
- 문서의 최종 근거는 실행 코드와 자동화된 테스트다.
