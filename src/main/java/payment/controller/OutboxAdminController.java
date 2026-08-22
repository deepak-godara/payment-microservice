package payment.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import payment.dto.OutboxEventResponseDTO;
import payment.model.Types.OutboxEventStatus;
import payment.service.OutboxAdminService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payment/outbox")
public class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    // ─── GET /payment/outbox?status=FAILED ───────────────────────────────────
    // ?status=FAILED  → events stuck at retryCount >= 5
    // ?status=PENDING → all PENDING events, etc.

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OutboxEventResponseDTO>> getOutboxEventsByStatus(
            @RequestParam OutboxEventStatus status) {
        return ResponseEntity.ok(outboxAdminService.getEventsByStatus(status));
    }

    // ─── PATCH /payment/outbox/{id}/reset ────────────────────────────────────
    // Resets a permanently FAILED event back to PENDING so the poller retries it

    @PatchMapping("/{id}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OutboxEventResponseDTO> resetOutboxEvent(@PathVariable Long id) {
        return ResponseEntity.ok(outboxAdminService.resetToPending(id));
    }
}
