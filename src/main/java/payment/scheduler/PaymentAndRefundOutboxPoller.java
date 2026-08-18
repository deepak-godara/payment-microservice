package payment.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import payment.components.OutboxDatabaseService;
import payment.model.OutboxEvents;
import payment.model.Types.OutboxEventStatus;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentAndRefundOutboxPoller {

    private static final Logger log             = LoggerFactory.getLogger(PaymentAndRefundOutboxPoller.class);
    private static final int    TIMEOUT_SECONDS = 30;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxDatabaseService         outboxDatabaseService;
    private final ObjectMapper                  objectMapper;

    @Scheduled(fixedDelay = 60_000) // every 60 seconds
    public void pollAndPublish() {

        // Step 0 — recover rows stuck in PROCESSING (pod crashed before TX2)
        int recovered = outboxDatabaseService.resetStuckProcessingEvents();
        if (recovered > 0) {
            log.warn("Recovered {} stuck PROCESSING outbox event(s) back to PENDING", recovered);
        }

        // Step 1 — TX1: claim ALL PENDING rows (any topic), mark PROCESSING, release connection
        List<OutboxEvents> outboxEvents = outboxDatabaseService.claimPendingEventsBatch();

        if (outboxEvents.isEmpty()) {
            return;
        }

        log.info("Publishing {} outbox event(s)", outboxEvents.size());

        // Step 2 — set fallback before firing async sends
        // If allOf() times out, TX2 persists PENDING — never stuck in PROCESSING
        for (OutboxEvents event : outboxEvents) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setStatus(OutboxEventStatus.PENDING); // outbox always retries — never FAILED
        }

        // Step 3 — async Kafka publish — NO DB connection held during network I/O
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (OutboxEvents event : outboxEvents) {
            try {
                // Deserialize payload to generic Map — Kafka serializes it back to JSON
                // Each consumer deserializes into its own DTO — no coupling here
                Object dto = objectMapper.readValue(event.getPayload(), Object.class);

                CompletableFuture<Void> future = kafkaTemplate
                        .send(event.getTopic(), String.valueOf(event.getAuctionId()), dto)
                        .thenAccept(result -> {
                            // ACK received — override fallback with DELIVERED
                            event.setStatus(OutboxEventStatus.DELIVERED);
                            log.info("Published — id={} topic={} auctionId={} userId={} partition={} offset={}",
                                    event.getId(), event.getTopic(), event.getAuctionId(), event.getUserId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        })
                        .exceptionally(ex -> {
                            // Fallback (PENDING) already set — just log
                            log.error("Failed to publish — id={} topic={} auctionId={}",
                                    event.getId(), event.getTopic(), event.getAuctionId(), ex);
                            return null;
                        });

                futures.add(future);

            } catch (Exception ex) {
                // Payload deserialization failure — keep as PENDING, retry next cycle
                log.error("Failed to deserialize payload — id={} topic={}",
                        event.getId(), event.getTopic(), ex);
            }
        }

        // Wait for all Kafka ACKs (max 30 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Fallback statuses already set — TX2 persists PENDING for unresolved events
            log.warn("Outbox publish timed out or interrupted — saving fallback statuses: {}", e.getMessage());
        }

        // Step 4 — TX2: persist final statuses (DELIVERED / PENDING)
        outboxDatabaseService.finalizeEventsBatch(outboxEvents);
    }
}
