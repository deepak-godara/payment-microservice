package payment.model;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import payment.model.Types.OutboxEventStatus;

@Entity
@Table(
    name = "payment_outbox_events",
    indexes = {
        // Fast polling of PENDING rows by the outbox poller
        @Index(name = "idx_outbox_status", columnList = "status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvents {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Kafka topic to publish to — drives routing in the outbox poller
    @Column(nullable = false)
    private String topic;

    // Domain context — for observability/debugging queries, not used in logic
    @Column(nullable = false)
    private Long auctionId;

    @Column(nullable = false)
    private String userId;          // bidderId / winnerId / sellerId depending on topic

    // Full serialized DTO — source of truth for what gets sent to Kafka
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    // Observability only — never gates retry logic (outbox always retries until DELIVERED)
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastRetryAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
