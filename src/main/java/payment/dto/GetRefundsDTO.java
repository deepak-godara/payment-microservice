package payment.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import payment.model.Types.RefundStatus;

@Data
@RequiredArgsConstructor
public class GetRefundsDTO {
    private RefundStatus status;
    private Long auctionId;
    
}
