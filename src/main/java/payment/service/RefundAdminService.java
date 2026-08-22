package payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import payment.dto.RefundOrderResponseDTO;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;
import payment.repository.RefundOrderRepository;

@Service
@RequiredArgsConstructor
public class RefundAdminService {

    private final RefundOrderRepository refundOrderRepository;

    // PATCH /payment/refunds/{orderId}/reset
    // Resets a permanently FAILED refund back to PENDING so the poller retries it
    @Transactional
    public RefundOrderResponseDTO resetToPending(Long orderId) {
        RefundOrder order = refundOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Refund order not found: " + orderId));

        if (order.getStatus() != RefundStatus.FAILED) {
            throw new IllegalStateException(
                    "Only FAILED refund orders can be reset. Current status: " + order.getStatus());
        }

        order.setStatus(RefundStatus.PENDING);
        order.setRetryCount(0);
        order.setErrorCode(null);
        order.setErrorDescription(null);
        refundOrderRepository.save(order);
        return toDTO(order);
    }

    private RefundOrderResponseDTO toDTO(RefundOrder order) {
        return RefundOrderResponseDTO.builder()
                .id(order.getId())
                .auctionId(order.getAuctionId())
                .bidderId(order.getBidderId())
                .status(order.getStatus())
                .amount(order.getAmount())
                .razorpayRefundId(order.getRazorpayRefundId())
                .errorCode(order.getErrorCode())
                .errorDescription(order.getErrorDescription())
                .retryCount(order.getRetryCount())
                .refundedAt(order.getRefundedAt())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
