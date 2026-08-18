package payment.components;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import payment.model.OutboxEvents;
import payment.model.Types.OutboxEventStatus;
import payment.repository.OutboxEventsRepository;

@Service
@RequiredArgsConstructor
public class OutboxDatabaseService {

    private static final Logger log                     = LoggerFactory.getLogger(OutboxDatabaseService.class);
    private static final int    BATCH_SIZE              = 50;
    private static final int    STUCK_THRESHOLD_MINUTES = 10;

    private final OutboxEventsRepository outboxEventsRepository;

    // TX1 — claim pending rows and mark PROCESSING
    // FOR UPDATE SKIP LOCKED — two pods never claim the same row
    @Transactional
    public List<OutboxEvents> claimPendingEventsBatch() {
        List<OutboxEvents> events = outboxEventsRepository
                .findPendingEventsForUpdateSkipLocked(BATCH_SIZE);

        if (events.isEmpty()) {
            log.debug("No PENDING outbox events found");
            return events;
        }

        log.info("Claiming {} PENDING outbox event(s) → marking PROCESSING", events.size());

        for (OutboxEvents event : events) {
            event.setStatus(OutboxEventStatus.PROCESSING);
            event.setLastRetryAt(LocalDateTime.now());
        }

        return outboxEventsRepository.saveAll(events);
    }

    // TX2 — persist final statuses after Kafka ACK
    @Transactional
    public void finalizeEventsBatch(List<OutboxEvents> events) {
        outboxEventsRepository.saveAll(events);
        log.info("Finalized {} outbox event(s)", events.size());
    }

    // Recovery — reset rows stuck in PROCESSING (pod crashed before TX2)
    @Transactional
    public int resetStuckProcessingEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        return outboxEventsRepository.resetStuckProcessingEvents(cutoff);
    }
}
