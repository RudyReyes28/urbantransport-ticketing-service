package com.urbantransport.ticketing.repository;

import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface TripSnapshotRepository
        extends JpaRepository<TripSnapshotEntity, UUID>, JpaSpecificationExecutor<TripSnapshotEntity> {
}