package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByAggregateIdAndEventType(UUID aggregateId, String eventType);
}
