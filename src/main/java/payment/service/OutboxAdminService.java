package payment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import payment.dto.OutboxEventResponseDTO;
import payment.model.OutboxEvents;
import payment.model.Types.OutboxEventStatus;
import payment.repository.OutboxEventsRepository;

@Service
@RequiredArgsConstructor
public class OutboxAdminService {

    private static final int STUCK_RETRY_THRESHOLD = 5;

    private final OutboxEventsRepository outboxEventsRepository;

    // GET /payment/outbox?status=FAILED  → events stuck at retryCount >= 5
    // GET /payment/outbox?status=PENDING → all PENDING events, etc.
    public List<OutboxEventResponseDTO> getEventsByStatus(OutboxEventStatus status) {
        List<OutboxEvents> events = (status == OutboxEventStatus.FAILED)
                ? outboxEventsRepository.findByStatusAndRetryCountGreaterThanEqual(
                        OutboxEventStatus.FAILED, STUCK_RETRY_THRESHOLD)
                : outboxEventsRepository.findByStatus(status);

        return events.stream().map(this::toDTO).toList();
    }

    // PATCH /payment/outbox/{id}/reset
    // Resets a FAILED event back to PENDING so the poller retries it
    @Transactional
    public OutboxEventResponseDTO resetToPending(Long id) {
        OutboxEvents event = outboxEventsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + id));

        if (event.getStatus() != OutboxEventStatus.FAILED) {
            throw new IllegalStateException(
                    "Only FAILED events can be reset. Current status: " + event.getStatus());
        }

        event.setStatus(OutboxEventStatus.PENDING);
        event.setRetryCount(0);
        outboxEventsRepository.save(event);
        return toDTO(event);
    }

    private OutboxEventResponseDTO toDTO(OutboxEvents event) {
        return OutboxEventResponseDTO.builder()
                .id(event.getId())
                .topic(event.getTopic())
                .auctionId(event.getAuctionId())
                .userId(event.getUserId())
                .status(event.getStatus())
                .retryCount(event.getRetryCount())
                .createdAt(event.getCreatedAt())
                .lastRetryAt(event.getLastRetryAt())
                .build();
    }
}
