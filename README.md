# Talk With Neighbors API

Spring Boot 3 기반의 이웃톡 백엔드야. 인증, 피드, 관심사 매칭, 취미 모임, 채팅과 알림 API를 제공해.

## 로컬 실행

필수 환경은 Java 17, MySQL 8, Redis 7이야. 상위 폴더에서 `docker compose up -d`로 인프라를 띄운 뒤 실행해.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 검증

```bash
./gradlew clean test
```

PR과 `main` 브랜치 푸시에서는 GitHub Actions가 테스트와 `bootJar` 생성을 수행해. `main` 또는 `v*` 태그가 푸시되면 백엔드 이미지를 GitHub Container Registry에 게시해.
