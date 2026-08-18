package payment.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderResponseDTO {

    private String        razorpayOrderId;   // "order_XXXXXXXXXX" — used by Razorpay JS SDK
    private Long          amount;            // in paise
    private String        currency;          // "INR"
    private String        keyId;             // rzp_test_XXXXX — used by Razorpay JS SDK to init
    private LocalDateTime expiresAt;         // frontend can show countdown timer if needed
}
