package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.MatchProfileDto;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public User createUser(User user) {
        if (existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }
        if (existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다.");
        }
        return userRepository.save(user);
    }

    @Override
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
    }

    @Override
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
    }

    @Override
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
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
        
        // 사용자명이 변경된 경우 중복 체크
        if (!existingUser.getUsername().equals(user.getUsername()) && existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("이미 존재하는 사용자명입니다.");
        }
        
        return userRepository.save(user);
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
                .interests(user.getInterests())
                .build();
    }
} 