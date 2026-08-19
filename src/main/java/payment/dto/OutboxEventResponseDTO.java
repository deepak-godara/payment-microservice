package payment.dto;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;
import payment.model.Types.OutboxEventStatus;

@Data
@Builder
public class OutboxEventResponseDTO {
    private Long             id;
    private String           topic;
    private Long             auctionId;
    private String           userId;
    private OutboxEventStatus status;
    private int              retryCount;
    private LocalDateTime    createdAt;
    private LocalDateTime    lastRetryAt;
}
