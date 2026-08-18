package payment.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import payment.model.Types.RefundStatus;
import payment.model.Types.Status;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
    name = "refund_orders",
    uniqueConstraints = {
        // Enforces 1 refund per payment order (strict idempotency)
        @UniqueConstraint(name = "uk_refund_payment_order", columnNames = {"payment_order_id"}),
        // Enforces unique Razorpay refund ID
        @UniqueConstraint(name = "uk_refund_razorpay_id", columnNames = {"razorpay_refund_id"})
    },
    indexes = {
        // Fast polling of pending refunds by the poller
        @Index(name = "idx_refund_status_auction", columnList = "status, auction_id")
    }
)
public class RefundOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1. Reference to original PaymentOrder
    @Column(name = "payment_order_id", nullable = false)
    private Long paymentOrderId;

    // 2. Domain Context
    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "bidder_id", nullable = false)
    private String bidderId;

    // 3. Razorpay Payment & Refund IDs
    @Column(name = "razorpay_payment_id", nullable = false)
    private String razorpayPaymentId;       // Original 'pay_xxx' from PaymentOrder

    @Column(name = "razorpay_refund_id")
    private String razorpayRefundId;        // 'rfnd_xxx' returned by Razorpay API/webhook

    // 4. Financials
    @Column(nullable = false)
    private BigDecimal amount;              // In INR (e.g. 5.00)

    // 5. Lifecycle & Reason
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;            // PENDING, PROCESSING, PROCESSED, FAILED

    // 6. Error Tracking & Retries
    @Column(name = "error_code")
    private String errorCode;               // e.g. "BAD_REQUEST_ERROR" from Razorpay

    @Column(name = "error_description")
    private String errorDescription;        // e.g. "Payment already fully refunded"

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    // 7. Timestamps
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;       // Exact timestamp when gateway confirmed

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}