package com.talkwithneighbors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

/**
 * 이웃과 대화하기 애플리케이션의 메인 클래스
 * Spring Boot 애플리케이션의 시작점입니다.
 */
@SpringBootApplication
@EnableSpringHttpSession
public class TalkWithNeighborsApplication {
    /**
     * 애플리케이션의 진입점
     * Spring Boot 애플리케이션을 시작하고 실행합니다.
     * 
     * @param args 커맨드 라인 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(TalkWithNeighborsApplication.class, args);
    }
} 