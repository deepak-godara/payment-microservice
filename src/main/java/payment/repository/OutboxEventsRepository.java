package payment.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import payment.model.OutboxEvents;

public interface OutboxEventsRepository extends JpaRepository<OutboxEvents, Long> {

    // Idempotency — one outbox row per (auctionId + userId + topic)
    boolean existsByAuctionIdAndUserIdAndTopic(Long auctionId, String userId, String topic);

    // All PENDING rows regardless of topic — one poller handles everything
    @Query(value = "SELECT * FROM payment_outbox_events WHERE status = 'PENDING' " +
                   "ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEvents> findPendingEventsForUpdateSkipLocked(@Param("limit") int limit);

    // Recover rows stuck in PROCESSING (pod crashed after TX1, before TX2)
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvents o SET o.status = payment.model.Types.OutboxEventStatus.PENDING " +
           "WHERE o.status = payment.model.Types.OutboxEventStatus.PROCESSING " +
           "AND o.lastRetryAt < :cutoff")
    int resetStuckProcessingEvents(@Param("cutoff") LocalDateTime cutoff);
}
