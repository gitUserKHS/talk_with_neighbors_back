# Talk With Neighbors API

전체 시스템 구조, 기능 명세, 데이터 모델, 흐름, API, 운영 및 발전 계획은 [기술 문서](docs/README.md)에서 확인할 수 있어.

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

## 컨테이너 배포

개발 환경에서는 두 저장소의 상위 폴더에서 `docker compose up --build -d`를 실행하면 프런트, 백엔드, MySQL, Redis가 함께 시작돼.

배포 서버에서는 `compose.production.yml`과 환경 변수를 사용해 GHCR 이미지를 실행할 수 있어.

```bash
docker compose -f compose.production.yml up -d
```

`MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `JWT_SECRET`, `PUBLIC_ORIGIN`은 반드시 배포 환경에서 지정해야 해.

PR과 `main` 브랜치 푸시에서는 GitHub Actions가 테스트와 `bootJar` 생성을 수행해. `main` 또는 `v*` 태그가 푸시되면 백엔드 이미지를 GitHub Container Registry에 게시해.

CI는 애플리케이션 테스트뿐 아니라 실제 프로덕션 Docker 이미지 빌드도 검증해.
