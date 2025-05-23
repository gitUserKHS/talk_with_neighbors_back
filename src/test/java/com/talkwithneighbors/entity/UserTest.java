package com.talkwithneighbors.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

class UserTest {

    private User.UserBuilder createCompleteUserBuilder() {
        return User.builder()
                .age(30)
                .gender("Male")
                .interests(List.of("coding", "music"))
                .latitude(34.0522)
                .longitude(-118.2437)
                .address("123 Main St, Anytown, USA");
    }

    @Test
    void testIsProfileComplete_whenAllFieldsSet_shouldReturnTrue() {
        User user = createCompleteUserBuilder().build();
        assertTrue(user.isProfileComplete(), "Profile should be complete when all fields are set");
    }

    @Test
    void testIsProfileComplete_whenAgeMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().age(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if age is missing");
    }

    @Test
    void testIsProfileComplete_whenGenderMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().gender(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if gender is null");
    }

    @Test
    void testIsProfileComplete_whenGenderEmpty_shouldReturnFalse() {
        User user = createCompleteUserBuilder().gender("").build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if gender is empty");
    }

    @Test
    void testIsProfileComplete_whenInterestsMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().interests(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if interests are null");
    }

    @Test
    void testIsProfileComplete_whenInterestsEmpty_shouldReturnFalse() {
        User user = createCompleteUserBuilder().interests(Collections.emptyList()).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if interests are empty");
    }
    
    @Test
    void testIsProfileComplete_whenInterestsSetToModifiableEmptyList_shouldReturnFalse() {
        User user = createCompleteUserBuilder().interests(new ArrayList<>()).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if interests are an empty modifiable list");
    }

    @Test
    void testIsProfileComplete_whenLatitudeMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().latitude(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if latitude is missing");
    }

    @Test
    void testIsProfileComplete_whenLongitudeMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().longitude(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if longitude is missing");
    }

    @Test
    void testIsProfileComplete_whenAddressMissing_shouldReturnFalse() {
        User user = createCompleteUserBuilder().address(null).build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if address is null");
    }

    @Test
    void testIsProfileComplete_whenAddressEmpty_shouldReturnFalse() {
        User user = createCompleteUserBuilder().address("").build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if address is empty");
    }

    @Test
    void testIsProfileComplete_whenMultipleFieldsMissing_shouldReturnFalse() {
        User user = User.builder()
                .age(30)
                .gender(null) // Missing gender
                .interests(List.of("reading"))
                .latitude(null) // Missing latitude
                .longitude(-118.2437)
                .address("") // Empty address
                .build();
        assertFalse(user.isProfileComplete(), "Profile should be incomplete if multiple fields are missing or invalid");
    }

    @Test
    void testIsProfileComplete_forNewlyCreatedUser_shouldReturnFalse() {
        User user = new User(); // Uses default constructor
        assertFalse(user.isProfileComplete(), "Profile should be incomplete for a newly created user with default constructor");
    }

    @Test
    void testIsProfileComplete_forUserBuiltWithBuilderDefaults_shouldReturnFalse() {
        User user = User.builder().build(); // Uses builder with no fields set
        // Note: User.interests is initialized to new ArrayList<>() by Lombok's @Builder.Default or if manually set in builder.
        // If it's not initialized, this test might pass if interests is null and other fields are also null.
        // The isProfileComplete() checks for interests != null && !interests.isEmpty().
        // A default builder will result in interests being an empty list if @Builder.Default is used as new ArrayList<>().
        // If User.interests is not initialized by default in the User entity or its builder, it would be null.
        // Let's assume 'interests' is initialized to an empty list by the builder if not specified.
        // All other relevant fields (age, gender, latitude, longitude, address) will be null.
        assertFalse(user.isProfileComplete(), "Profile should be incomplete for a user built with builder defaults");
    }
}
