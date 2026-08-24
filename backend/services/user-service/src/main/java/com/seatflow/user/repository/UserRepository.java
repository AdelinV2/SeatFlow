package com.seatflow.user.repository;

import com.seatflow.user.model.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByExternalId(String externalId);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    Page<User> findByEmailContainingIgnoreCase(String email, Pageable pageable);

    Page<User> findByExternalIdContainingIgnoreCase(String externalId, Pageable pageable);

    Page<User> findByPhoneContaining(String phone, Pageable pageable);

    boolean existsByExternalId(String externalId);

    boolean existsByEmail(String email);

    Page<User> findAll(Pageable pageable);
}
