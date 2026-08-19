package payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class GetRefundsResponseDTO {
    private Long auctionId;
    private String bidderId;
}
