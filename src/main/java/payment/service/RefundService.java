package payment.service;

import java.time.LocalDateTime;
import java.util.List;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.razorpay.RazorpayClient;
import com.razorpay.Refund;

import lombok.RequiredArgsConstructor;
import payment.Utils.CommonUtils;
import payment.dto.GetRefundsDTO;
import payment.dto.GetRefundsResponseDTO;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;
import payment.repository.RefundOrderRepository;

@Service
@RequiredArgsConstructor
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundOrderRepository refundOrderRepository;
    private final RazorpayClient        razorpayClient;
    private final CommonUtils commonUtils;

    // ─────────────────────────────────────────────────────────────────────────
    // Called by RefundPoller — one virtual thread per order, no DB conn held
    // ─────────────────────────────────────────────────────────────────────────
    public void createRazorpayRefundOrder(RefundOrder order) {
        log.info("Processing refund — orderId={} razorpayPaymentId={} amount={}",
                order.getId(), order.getRazorpayPaymentId(), order.getAmount());

        try {
            JSONObject refundRequest = new JSONObject();
            // refundRequest.put("amount", order.getAmount().multiply(new java.math.BigDecimal(100)).intValue());
            refundRequest.put("amount", 100);
            refundRequest.put("receipt", "rfnd_rcpt1_" + order.getId());

            JSONObject notes = new JSONObject();
            notes.put("auction_id",      String.valueOf(order.getAuctionId()));
            notes.put("bidder_id",       order.getBidderId());
            notes.put("refund_order_id", String.valueOf(order.getId()));
            refundRequest.put("notes", notes);

            log.info("Razorpay refund payload — orderId={} payload={}",
                    order.getId(), refundRequest);

            // On retries — check if a refund was already created at Razorpay
            // to avoid duplicate API calls that could create duplicate refunds
            if (order.getRetryCount() > 0) {
                List<Refund> existingRefunds = razorpayClient.payments
                        .fetchAllRefunds(order.getRazorpayPaymentId());

                for (Refund refundItem : existingRefunds) {
                    String paymentId = refundItem.get("payment_id");
                    String receipt   = refundItem.toJson().optString("receipt", null);

                    if (order.getRazorpayPaymentId().equals(paymentId)
                            && ("rfnd_rcpt1_" + order.getId()).equals(receipt)) {

                        String status = refundItem.get("status");

                        if ("processed".equalsIgnoreCase(status)) {
                            order.setRazorpayRefundId(refundItem.get("id"));
                            order.setStatus(RefundStatus.PROCESSED);
                            order.setRefundedAt(LocalDateTime.now());
                            commonUtils.markProcessedAndSaveOutbox(order, refundItem.get("id"));
                        } else if ("pending".equalsIgnoreCase(status)) {
                            order.setRazorpayRefundId(refundItem.get("id"));
                            order.setStatus(RefundStatus.PROCESSING);
                            refundOrderRepository.save(order);
                        }

                        return; // refund already exists — do not call API again
                    }
                }
            }

            // First attempt or no existing refund found — create a new one
            Refund refundResponse    = razorpayClient.payments.refund(
                    order.getRazorpayPaymentId(), refundRequest);
            String refundOrderStatus = refundResponse.get("status");

            if ("pending".equalsIgnoreCase(refundOrderStatus)) {
                order.setRazorpayRefundId(refundResponse.get("id"));
                order.setStatus(RefundStatus.PENDING);
                refundOrderRepository.save(order);

            } else if ("processed".equalsIgnoreCase(refundOrderStatus)) {
                order.setRazorpayRefundId(refundResponse.get("id"));
                order.setStatus(RefundStatus.PROCESSED);
                order.setRefundedAt(LocalDateTime.now());
                commonUtils.markProcessedAndSaveOutbox(order, refundResponse.get("id"));

            } else {
                // SDK throws RazorpayException on error — this branch is unreachable in practice
                JSONObject error = refundResponse.get("error");
                order.setErrorCode(error.optString("code"));
                order.setErrorDescription(error.optString("description"));
                order.setLastRetryAt(LocalDateTime.now());
                order.setRetryCount(order.getRetryCount() + 1);
                order.setStatus(RefundStatus.FAILED);
                refundOrderRepository.save(order);
            }

        } catch (Exception ex) {
            // Razorpay API call failed — parse error code from exception message ("CODE:description")
            String   message         = ex.getMessage();
            String[] parts           = message != null ? message.split(":", 2) : new String[]{ "UNKNOWN", "" };
            String   errorCode       = parts[0];
            String   errorDescription = parts.length > 1 ? parts[1] : "";

            log.error("Razorpay refund failed — orderId={} code={} description={}",
                    order.getId(), errorCode, errorDescription);

            order.setErrorCode(errorCode);
            order.setErrorDescription(errorDescription);
            order.setRetryCount(order.getRetryCount() + 1);
            order.setLastRetryAt(LocalDateTime.now());
            RefundStatus nextStatus = order.getRetryCount() >= 5 ? RefundStatus.FAILED : RefundStatus.PENDING;
            order.setStatus(nextStatus);
            refundOrderRepository.save(order);
            if (nextStatus == RefundStatus.FAILED) {
                log.error("REFUND EXHAUSTED — orderId={} auctionId={} bidderId={} after {} retries. MANUAL INTERVENTION REQUIRED.",
                        order.getId(), order.getAuctionId(), order.getBidderId(), order.getRetryCount());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by RefundController when Razorpay fires refund.processed webhook
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public void handleRefundSuccess(JSONObject payload) {
        JSONObject refundItem = payload
                .getJSONObject("payload")
                .getJSONObject("refund")
                .getJSONObject("entity");

        String razorpayRefundId = refundItem.getString("id");

        // Resolve the exact RefundOrder via the receipt we stamped: "rfnd_rcpt_<orderId>"
        // This is a 1-to-1 mapping — safer than payment_id which is shared across auctions
        // when the same bidder paid for multiple auctions on the same Razorpay payment.
        String receipt = refundItem.optString("receipt", null);
        if (receipt == null || !receipt.startsWith("rfnd_rcpt1_")) {
            log.warn("Refund webhook missing or unrecognised receipt — refundId={} receipt={}",
                    razorpayRefundId, receipt);
            return;
        }

        long orderId;
        try {
            orderId = Long.parseLong(receipt.substring("rfnd_rcpt1_".length()));
        } catch (NumberFormatException e) {
            log.warn("Could not parse orderId from receipt — refundId={} receipt={}",
                    razorpayRefundId, receipt);
            return;
        }

        RefundOrder order = refundOrderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("No RefundOrder found for orderId={} refundId={}", orderId, razorpayRefundId);
            return;
        }

        // Idempotency — webhook may fire more than once
        if (order.getStatus() == RefundStatus.PROCESSED) {
            log.info("RefundOrder already PROCESSED — skipping duplicate webhook. orderId={}", order.getId());
            return;
        }

        order.setRazorpayRefundId(razorpayRefundId);
        order.setRefundedAt(LocalDateTime.now());
        order.setStatus(RefundStatus.PROCESSED);
        commonUtils.markProcessedAndSaveOutbox(order, razorpayRefundId);
        log.info("RefundOrder updated to PROCESSED via webhook — orderId={} refundId={}",
                order.getId(), razorpayRefundId);
    }


    public List<GetRefundsResponseDTO> getRefunds(GetRefundsDTO getPendingRefundsDTO){

        return refundOrderRepository.findAllByStatusAndAuctionId(getPendingRefundsDTO.getStatus(),getPendingRefundsDTO.getAuctionId()).stream()
                .map(item -> {
                    return GetRefundsResponseDTO.builder()
                        .auctionId(item.getAuctionId())
                        .bidderId(item.getBidderId())
                    .build();
                }).toList();

    }
}
