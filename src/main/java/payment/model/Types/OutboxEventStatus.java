package payment.model.Types;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED,
    DEAD
}
