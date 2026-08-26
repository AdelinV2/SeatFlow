package com.seatflow.payment.repository;

import com.seatflow.payment.model.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByReservationId(UUID reservationId);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Payment> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Payment p SET p.userId = :userId, p.updatedAt = :now WHERE p.customerEmail = :email AND p.userId IS NULL")
    int updateUserIdForCustomerEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("now") Instant now);
}
