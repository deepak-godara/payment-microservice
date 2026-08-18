package payment.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class RazorpayRefundErrorDTO {
    private String code;
    private String description;
    private String source;
    private String step;
    private String reason;
    private String field;
}
