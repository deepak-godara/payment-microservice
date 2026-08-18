package payment.model.Types;

public enum Status {
    CREATING,   // Row saved before Razorpay API call — blocks duplicate requests via unique constraint
    CREATED,    // Razorpay order created successfully — awaiting payment
    SUCCESS,
    FAILED
}
