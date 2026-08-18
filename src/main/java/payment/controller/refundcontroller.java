package payment.controller;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;

import lombok.RequiredArgsConstructor;
import payment.service.RefundService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/refunds")
public class refundcontroller {

    private static final Logger log = LoggerFactory.getLogger(refundcontroller.class);

    private final RefundService refundService;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {
        try {
            // Step 1 — verify signature
            boolean isValid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!isValid) {
                log.warn("Invalid refund webhook signature received");
                return ResponseEntity.status(400).body("Invalid signature");
            }

            // Step 2 — parse event type
            JSONObject body = new JSONObject(payload);
            String event = body.getString("event");
            log.info("Refund webhook received — event={}", event);

            // Step 3 — route based on event
            switch (event) {
                case "refund.processed" -> refundService.handleRefundSuccess(body);
                default                 -> log.info("Unhandled refund webhook event={}", event);
            }

        } catch (RazorpayException e) {
            log.error("Refund webhook signature verification error: {}", e.getMessage());
            return ResponseEntity.status(400).body("Signature verification failed");
        } catch (Exception e) {
            // Internal processing error — return 200 so Razorpay does not retry
            // Failures are handled internally via poller retry mechanism
            log.error("Refund webhook processing error: {}", e.getMessage());
        }

        // Always return 200 — Razorpay retries on anything else
        return ResponseEntity.ok("OK");
    }
}
