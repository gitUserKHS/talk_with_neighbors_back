package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.MatchProfileDto;
import com.talkwithneighbors.entity.User;

import java.util.List;

public interface UserService {
    User createUser(User user);
    User getUserById(Long id);
    User getUserByEmail(String email);
    User getUserByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    User updateUser(User user);
    void deleteUser(Long id);
    List<MatchProfileDto> findNearbyUsers(double latitude, double longitude, double radiusInKm);
    List<User> findUsersByIds(List<Long> userIds);
} 