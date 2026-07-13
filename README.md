# Talk With Neighbors API

전체 시스템 구조, 기능 명세, 데이터 모델, 흐름, API, 운영 및 발전 계획은 [기술 문서](docs/README.md)에서 확인할 수 있어.

Spring Boot 3 기반의 이웃톡 백엔드야. 인증, 피드, 관심사 매칭, 취미 모임, 채팅과 알림 API를 제공해.

## 로컬 실행

필수 환경은 Java 17, MySQL 8, Redis 7, FFmpeg/FFprobe야. 상위 폴더에서 `docker compose up -d`로 인프라를 띄운 뒤 실행해. Docker 이미지에는 FFmpeg가 포함되어 있어.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 검증

```bash
./gradlew clean test
```

## 로컬 컨테이너 실행

개발 환경에서는 두 저장소의 상위 폴더에서 `docker compose up --build -d`를 실행하면 프런트, 백엔드, MySQL, Redis가 함께 시작돼.

프로필 사진, 피드 사진·동영상, 채팅 사진·동영상·문서는 `uploads_data` Docker 볼륨에 저장되므로 컨테이너를 다시 만들어도 유지돼. 이미지와 영상은 WebP·MP4로 최적화되고 썸네일도 같은 볼륨에 보관해. DB·Redis·업로드 데이터를 모두 지우려는 경우가 아니라면 `docker compose down -v`는 사용하지 마.

현재 프로젝트는 로컬 전용으로 운영해. PR과 `main` 브랜치 푸시에서는 GitHub Actions가 테스트와 `bootJar` 생성을 수행하지만, 이미지 게시 워크플로는 수동 비활성화 상태야.

CI는 애플리케이션 테스트뿐 아니라 실제 프로덕션 Docker 이미지 빌드도 검증해.
