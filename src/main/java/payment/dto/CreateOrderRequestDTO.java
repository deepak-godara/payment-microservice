package payment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequestDTO {

    @NotNull(message = "Auction id is required to create payment order")
    private Long auctionId;

    @NotNull(message = "Type is required to create the order")
    private String type;

    // @NotNull(message = "Amount is required to create the order")
    // private Long amount;   // in paise — temporary until auction service validation is added
}
