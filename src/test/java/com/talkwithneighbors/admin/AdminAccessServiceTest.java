package com.talkwithneighbors.admin;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class AdminAccessServiceTest {

    @Mock UserRepository userRepository;

    AdminProperties properties;
    AdminAccessService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new AdminProperties();
        properties.setEmails(Set.of("Owner@Example.Test", "  second@example.test  "));
        service = new AdminAccessService(properties, userRepository);
    }

    private void user(Long id, String email) {
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.builder().id(id).email(email).username("u" + id).password("x")
                        .latitude(0.0).longitude(0.0).address("a").build()));
    }

    @Test
    void configuredEmailsMatchRegardlessOfCaseOrSurroundingSpace() {
        user(1L, "owner@example.test");
        user(2L, "SECOND@EXAMPLE.TEST");

        assertThat(service.isAdmin(1L)).isTrue();
        assertThat(service.isAdmin(2L)).isTrue();
    }

    @Test
    void userOutsideTheListIsNotAnAdmin() {
        user(3L, "someone@example.test");

        assertThat(service.isAdmin(3L)).isFalse();
    }

    @Test
    void emptyConfigurationGrantsNobodyAccess() {
        AdminAccessService restricted = new AdminAccessService(new AdminProperties(), userRepository);
        user(1L, "owner@example.test");

        assertThat(restricted.isAdmin(1L)).isFalse();
    }

    @Test
    void missingOrAnonymousUserIsNotAnAdmin() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.isAdmin(null)).isFalse();
        assertThat(service.isAdmin(99L)).isFalse();
    }

    @Test
    void nonAdminGetsNotFoundSoTheToolStaysHidden() {
        user(3L, "someone@example.test");

        assertThatThrownBy(() -> service.requireAdmin(3L))
                .isInstanceOf(MatchingException.class)
                .satisfies(thrown ->
                        assertThat(((MatchingException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
