package payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;
import payment.model.Types.RefundStatus;

@Data
@Builder
public class RefundOrderResponseDTO {
    private Long          id;
    private Long          auctionId;
    private String        bidderId;
    private RefundStatus  status;
    private BigDecimal    amount;
    private String        razorpayRefundId;
    private String        errorCode;
    private String        errorDescription;
    private int           retryCount;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
}
