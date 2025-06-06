package com.talkwithneighbors.entity;

/**
 * 이웃 매칭의 상태를 나타내는 열거형(Enum)
 * 매칭의 생명주기를 관리하는데 사용됩니다.
 */
public enum MatchStatus {
    /**
     * 매칭 대기 중 상태
     * 상대방의 응답을 기다리는 상태입니다.
     */
    PENDING,    // 매칭 대기 중

    /**
     * 첫 번째 사용자만 매칭을 수락한 상태
     */
    USER1_ACCEPTED,

    /**
     * 두 번째 사용자만 매칭을 수락한 상태
     */
    USER2_ACCEPTED,

    /**
     * 양쪽 모두 매칭을 수락한 상태
     */
    BOTH_ACCEPTED,   // 기존 ACCEPTED에서 변경

    /**
     * 매칭 거절됨 상태
     * 한쪽 또는 양쪽이 매칭을 거절한 상태입니다.
     */
    REJECTED,   // 매칭 거절됨

    /**
     * 매칭 만료됨 상태
     * 24시간이 경과하여 자동으로 만료된 상태입니다.
     */
    EXPIRED     // 매칭 만료됨
} 