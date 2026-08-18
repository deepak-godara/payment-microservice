package payment.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import payment.dto.TransactionDoneDTO;
import payment.dto.SaveOutboxEventRequestDTO;
import payment.model.OutboxEvents;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;
import payment.repository.OutboxEventsRepository;
import payment.repository.RefundOrderRepository;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CommonUtils {

    private final ObjectMapper objectMapper;
    private final OutboxEventsRepository outboxEventsRepository;
    private final RefundOrderRepository refundOrderRepository;
    private static final Logger log = LoggerFactory.getLogger(CommonUtils.class);
    public SaveOutboxEventRequestDTO createSaveOutboxEventRequestDTO(Long auctionId,String userId,String topic,String transactionId){
         TransactionDoneDTO dto = TransactionDoneDTO.builder()
                    .auctionId(auctionId)
                    .userId(userId)
                    .transactionId(transactionId)
                    .build();

            SaveOutboxEventRequestDTO outboxEvent = SaveOutboxEventRequestDTO.builder()
                    .topic(topic)
                    .auctionId(auctionId)
                    .userId(userId)
                    .payload(objectMapper.writeValueAsString(dto))
                    .build();
            return outboxEvent;
    }
    public void saveOutboxEvent(SaveOutboxEventRequestDTO outboxEvent) {
        
        // Idempotency — one outbox row per (auctionId + userId + topic)
        if (outboxEventsRepository.existsByAuctionIdAndUserIdAndTopic(
                outboxEvent.getAuctionId(), outboxEvent.getUserId(), outboxEvent.getTopic())) {
            log.info("OutboxEvent already exists — skipping duplicate. auctionId={} bidderId={}",
                    outboxEvent.getAuctionId(), outboxEvent.getUserId());
            return;
        }

        try {

            OutboxEvents event = OutboxEvents.builder()
                    .topic(outboxEvent.getTopic())
                    .auctionId(outboxEvent.getAuctionId())
                    .userId(outboxEvent.getUserId())
                    .payload(outboxEvent.getPayload())
                    .build();

            outboxEventsRepository.save(event);
            log.info("OutboxEvent saved — auctionId={} bidderId={} topic={}",
                    outboxEvent.getAuctionId(),outboxEvent.getUserId(), outboxEvent.getTopic());

        } catch (Exception e) {
            log.error("Failed to serialize/save OutboxEvent — auctionId={} bidderId={}",
                    outboxEvent.getAuctionId(),outboxEvent.getUserId(), e);
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atomic: persist PROCESSED status + outbox event in one transaction.
    // Lives here (separate bean) so Spring AOP proxy applies @Transactional —
    // self-invocation from RefundService would bypass the proxy.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public void markProcessedAndSaveOutbox(RefundOrder order, String razorpayRefundId) {
        refundOrderRepository.save(order);
        saveOutboxEvent(createSaveOutboxEventRequestDTO(
                order.getAuctionId(), order.getBidderId(), "refund-completed", razorpayRefundId));
    }
}
