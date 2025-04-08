package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionJpaRepository extends JpaRepository<Session, String> {
} 