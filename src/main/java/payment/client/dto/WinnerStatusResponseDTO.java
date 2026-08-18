package payment.client.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WinnerStatusResponseDTO {
    private Boolean     isWinner;
    private BigDecimal  auctionedPrice;   // in INR — used as Razorpay order amount
    private Boolean     alreadyPaid;      // true if winning payment already SUCCESS in DB
}
