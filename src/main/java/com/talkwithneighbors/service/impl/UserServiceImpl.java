package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.MatchProfileDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService, UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User createUser(User user) {
        if (existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }
        if (existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다.");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    @Override
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 ID: " + id));
    }

    @Override
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일: " + email));
    }

    @Override
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    @Transactional
    public User updateUser(User user) {
        User existingUser = getUserById(user.getId());
        
        if (!existingUser.getUsername().equals(user.getUsername()) && existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다.");
        }
        
        existingUser.setEmail(user.getEmail());
        existingUser.setProfileImage(user.getProfileImage());
        existingUser.setBio(user.getBio());
        existingUser.setAge(user.getAge());
        existingUser.setGender(user.getGender());

        return userRepository.save(existingUser);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }

    @Override
    public List<MatchProfileDto> findNearbyUsers(double latitude, double longitude, double radiusInKm) {
        return userRepository.findNearbyUsers(latitude, longitude, radiusInKm)
                .stream()
                .map(this::convertToMatchProfileDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<User> findUsersByIds(List<Long> userIds) {
        return userRepository.findAllById(userIds);
    }

    private MatchProfileDto convertToMatchProfileDto(User user) {
        return MatchProfileDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));

        Set<GrantedAuthority> authorities = Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                authorities
        );
    }
} 