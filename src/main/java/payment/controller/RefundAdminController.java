package payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import payment.dto.RefundOrderResponseDTO;
import payment.service.RefundAdminService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payment/refunds")
public class RefundAdminController {

    private final RefundAdminService refundAdminService;

    // ─── PATCH /payment/refunds/{orderId}/reset ───────────────────────────────
    // Resets a permanently FAILED refund order back to PENDING so the poller
    // retries it. Only FAILED orders can be reset.

    @PatchMapping("/{orderId}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RefundOrderResponseDTO> resetRefundOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(refundAdminService.resetToPending(orderId));
    }
}
