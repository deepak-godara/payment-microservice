package payment.client.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationStatusResponseDTO {
    private Long          registrationId;
    private Long          auctionId;
    private String        bidderId;
    private Boolean       registered;
    private Boolean       feePaid;
    private String        feePaymentId;
    private LocalDateTime registeredAt;
}
