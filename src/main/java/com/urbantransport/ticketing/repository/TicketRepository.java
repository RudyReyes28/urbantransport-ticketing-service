package com.urbantransport.ticketing.repository;

import com.urbantransport.ticketing.domain.TicketEntity;
import com.urbantransport.ticketing.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<TicketEntity, UUID> {

    @Query("select t.seatNumber from TicketEntity t where t.tripId = :tripId and t.status in :statuses")
    List<Integer> findSeatNumbersByTripIdAndStatusIn(@Param("tripId") UUID tripId,
                                                     @Param("statuses") Collection<TicketStatus> statuses);
}