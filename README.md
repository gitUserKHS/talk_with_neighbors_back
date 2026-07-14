# Talk With Neighbors API

[![Backend CI](https://github.com/gitUserKHS/talk_with_neighbors_back/actions/workflows/ci.yml/badge.svg)](https://github.com/gitUserKHS/talk_with_neighbors_back/actions/workflows/ci.yml)

## 익명 공개 API

로그인 없이 포트폴리오의 기본 콘텐츠를 열람할 수 있다.

- `GET /api/public/feed`
- `GET /api/public/meetups`

피드는 작성자가 `publicPreview=true`로 명시적으로 공개한 게시글만 익명 작성자명으로 제공한다. 기존 게시글과 옵션을 생략한 새 게시글은 비공개이며, 댓글 내용과 댓글 작성자 정보는 공개 API에서 제공하지 않는다.

콘텐츠 작성, 좋아요·댓글 열람 및 작성, 모임 참여, 채팅 및 개인정보 조회는 로그인이 필요하다.

전체 시스템 구조, 기능 명세, 데이터 모델, 흐름, API, 운영 및 발전 계획은 [기술 문서](docs/README.md)에서 확인할 수 있어.

Spring Boot 3 기반의 이웃톡 백엔드야. 인증, 피드, 관심사 매칭, 취미 모임, 채팅과 알림 API를 제공해.

## 로컬 실행

필수 환경은 Java 17, MySQL 8, Redis 7, FFmpeg/FFprobe야. Docker 이미지에는 FFmpeg가 포함되어 있어.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 검증

```bash
./gradlew clean test
```

## 로컬 컨테이너 실행

프런트와 백엔드 저장소를 같은 상위 폴더에 clone한 뒤, 이 저장소에서 아래 명령을 실행하면 프런트, 백엔드, MySQL, Redis가 함께 시작돼. Compose 파일이 저장소에 포함되어 있어 새 환경에서도 그대로 재현할 수 있어.

```bash
docker compose -f compose.local.yml up --build -d
```

프로필 사진, 피드 사진·동영상, 채팅 사진·동영상·문서는 `uploads_data` Docker 볼륨에 저장되므로 컨테이너를 다시 만들어도 유지돼. 이미지와 영상은 WebP·MP4로 최적화되고 썸네일도 같은 볼륨에 보관해. DB·Redis·업로드 데이터를 모두 지우려는 경우가 아니라면 `docker compose down -v`는 사용하지 마.

종료는 `docker compose -f compose.local.yml down`으로 해. PR과 `main`, `codex/**`, `agent/**` 브랜치에서는 GitHub Actions가 테스트, `bootJar`, 프로덕션 이미지, MySQL·Redis 연동 컨테이너 스모크 테스트를 검증해. `main`과 버전 태그의 이미지는 같은 품질 검증을 통과한 뒤 GHCR에 게시돼.

AWS 포트폴리오 환경은 서울 리전의 ARM64 EC2 한 대, 비공개 S3, 단일 노드 k3s로 실제 구성되어 있어. 배포는 GitHub OIDC와 SSM을 통해 검증된 GHCR 이미지 digest만 사용해. 리소스는 비용이 발생할 수 있으며, `terraform apply`는 자동화하지 않고 명시적 검토 후 수동 실행해. 운영·중지·삭제 절차는 [EC2 + S3 + k3s 가이드](docs/deployment/aws-k3s.md)를 따라.
