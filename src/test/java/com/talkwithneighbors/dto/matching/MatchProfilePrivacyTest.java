package com.talkwithneighbors.dto.matching;

import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchProfilePrivacyTest {
    @Test void hidesExactCoordinatesAndGeneralizesAddress() {
        User user = new User();
        user.setId(1L);
        user.setUsername("neighbor");
        user.setLatitude(37.123456);
        user.setLongitude(127.654321);
        user.setAddress("서울특별시 마포구 합정동 123-4");

        MatchProfileDto dto = MatchProfileDto.fromUser(user, 1.2, null);

        assertNull(dto.getLocation().getLatitude());
        assertNull(dto.getLocation().getLongitude());
        assertEquals("서울특별시 마포구", dto.getLocation().getAddress());
        assertEquals(1.2, dto.getDistance());
    }
}
