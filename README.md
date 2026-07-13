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

로컬 개발은 계속 상위 폴더의 Docker Compose를 기본으로 사용해. PR과 `main` 브랜치에서는 GitHub Actions가 테스트, `bootJar`, 프로덕션 이미지, MySQL·Redis 연동 컨테이너 스모크 테스트를 검증해. `main`과 버전 태그의 이미지는 같은 품질 검증을 통과한 뒤 GHCR에 게시돼.

선택형 AWS 포트폴리오 배포는 [EC2 + S3 + k3s 가이드](docs/deployment/aws-k3s.md)를 따라. Terraform과 SSM 배포 워크플로가 준비되어 있지만 비용이 발생하는 `terraform apply`는 자동 실행하지 않아.
