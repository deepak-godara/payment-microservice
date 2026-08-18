package payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class SaveOutboxEventRequestDTO {
    private String topic;
    private Long auctionId;
    private String userId;
    private String payload;
    
}
