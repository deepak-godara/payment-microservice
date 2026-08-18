package payment.controller;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import payment.dto.CreateOrderRequestDTO;
import payment.dto.CreateOrderResponseDTO;
import payment.service.PaymentService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payment")
public class paymentcontroller {

    private static final Logger log = LoggerFactory.getLogger(paymentcontroller.class);

    private final PaymentService paymentService;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    // ─── POST /payment/order ─────────────────────────────────────────────────

    @PostMapping("/order")
    public ResponseEntity<CreateOrderResponseDTO> createOrder(
            Authentication authentication,
            @RequestHeader("Authorization") String authorizationHeader,
            @Valid @RequestBody CreateOrderRequestDTO createOrderRequestDTO) throws RazorpayException {
        String userId = (String) authentication.getPrincipal();
        return ResponseEntity.status(201)
                .body(paymentService.createOrder(userId, authorizationHeader, createOrderRequestDTO));
    }

    // ─── POST /payment/webhook ───────────────────────────────────────────────

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {
        try {
            // Step 1 — verify signature
            boolean isValid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!isValid) {
                log.warn("Invalid payment webhook signature received");
                return ResponseEntity.status(400).body("Invalid signature");
            }

            // Step 2 — parse event type
            JSONObject body = new JSONObject(payload);
            String event = body.getString("event");
            log.info("Payment webhook received — event={}", event);

            // Step 3 — route based on event
            switch (event) {
                case "payment.captured" -> paymentService.handlePaymentCaptured(body);
                case "payment.failed"   -> paymentService.handlePaymentFailed(body);
                default                 -> log.info("Unhandled payment webhook event={}", event);
            }

        } catch (RazorpayException e) {
            log.error("Payment webhook signature verification error: {}", e.getMessage());
            return ResponseEntity.status(400).body("Signature verification failed");
        } catch (Exception e) {
            // Internal processing error — return 200 so Razorpay does not retry
            log.error("Payment webhook processing error: {}", e.getMessage());
        }

        // Always return 200 — Razorpay retries on anything else
        return ResponseEntity.ok("OK");
    }
}
