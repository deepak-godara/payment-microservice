package payment.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;

import lombok.RequiredArgsConstructor;
import payment.Utils.CommonUtils;
import payment.client.AuctionServiceClient;
import payment.client.dto.RegistrationStatusResponseDTO;
import payment.client.dto.WinnerStatusResponseDTO;
import payment.dto.CreateOrderRequestDTO;
import payment.dto.CreateOrderResponseDTO;
import payment.exception.AuctionValidationException;
import payment.exception.PaymentAlreadyPaidException;
import payment.model.PaymentOrder;
import payment.model.Types.Payment;
import payment.model.Types.Status;
import payment.repository.PaymentRepository;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository      paymentRepository;
    private final AuctionServiceClient   auctionServiceClient;
    private final RazorpayClient         razorpayClient;
    private final CommonUtils commonUtils;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    // ─────────────────────────────────────────────────────────────────────────
    // Create Razorpay order — called by POST /payment/order
    // ─────────────────────────────────────────────────────────────────────────
    public CreateOrderResponseDTO createOrder(
            String userId,
            String authHeader,
            CreateOrderRequestDTO request) throws RazorpayException {

        log.info("createOrder — userId={} auctionId={} type={}", userId, request.getAuctionId(), request.getType());

        // ── Validate + resolve amount based on payment type ───────────────────
        BigDecimal orderAmount;

        if (Payment.valueOf(request.getType()) == Payment.REGISTERATION) {
            RegistrationStatusResponseDTO status = auctionServiceClient
                    .getRegistrationStatus(request.getAuctionId(), authHeader);
            log.info("Registration status — registered={} feePaid={}", status.getRegistered(), status.getFeePaid());

            if (!status.getRegistered()) {
                throw new AuctionValidationException("You have not registered for this auction");
            }
            if (status.getFeePaid()) {
                throw new PaymentAlreadyPaidException("Registration fee already paid for this auction");
            }
            orderAmount = BigDecimal.valueOf(500); // test value — production: fetch from auction

        } else if (Payment.valueOf(request.getType()) == Payment.AUCTION_PAYMENT) {
            WinnerStatusResponseDTO winnerStatus = auctionServiceClient
                    .getWinnerStatus(request.getAuctionId(), authHeader);
            log.info("Winner status — isWinner={} auctionedPrice={} alreadyPaid={}",
                    winnerStatus.getIsWinner(), winnerStatus.getAuctionedPrice(), winnerStatus.getAlreadyPaid());

            if (!winnerStatus.getIsWinner()) {
                throw new AuctionValidationException("You are not the winner of this auction");
            }
            if (winnerStatus.getAlreadyPaid()) {
                throw new PaymentAlreadyPaidException("Winning payment already completed for this auction");
            }
            orderAmount = winnerStatus.getAuctionedPrice();

        } else {
            throw new AuctionValidationException("Unsupported payment type: " + request.getType());
        }

        // Check for an existing payment order in DB
        PaymentOrder paymentOrder = paymentRepository
                .findByAuctionIdAndBidderIdAndType(request.getAuctionId(), userId,request.getType())
                .orElse(null);
        log.debug("Existing payment order in DB — found={}", paymentOrder != null);

        if (paymentOrder != null) {
            log.info("Existing order — id={} status={} razorpayOrderId={}",
                    paymentOrder.getId(), paymentOrder.getStatus(), paymentOrder.getRazorpayOrderId());

            if (paymentOrder.getStatus() == Status.SUCCESS) {
                log.warn("Payment already SUCCESS in DB — userId={} auctionId={}", userId, request.getAuctionId());
                throw new PaymentAlreadyPaidException("Registration fee already paid for this auction");
            }

            // CREATING = another request is mid-flight calling Razorpay right now.
            // If the row is less than 30s old — reject to avoid duplicate Razorpay calls.
            // If older than 30s — JVM crashed before Razorpay responded, delete and let this request retry.
            if (paymentOrder.getStatus() == Status.CREATING) {
                if (paymentOrder.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
                    log.warn("Concurrent createOrder blocked — CREATING row is recent. userId={} auctionId={}", userId, request.getAuctionId());
                    throw new AuctionValidationException("Order creation in progress. Please retry in a moment.");
                }
                log.warn("Stale CREATING row (JVM crash recovery) — deleting. id={} createdAt={}", paymentOrder.getId(), paymentOrder.getCreatedAt());
                paymentRepository.deleteById(paymentOrder.getId());
                paymentOrder = null;
            }

            if (paymentOrder != null && paymentOrder.getStatus() == Status.CREATED) {
                // DB uncertain — verify actual status with Razorpay
                log.info("DB status CREATED — verifying with Razorpay API for orderId={}", paymentOrder.getRazorpayOrderId());
                Order rzpOrder  = razorpayClient.orders.fetch(paymentOrder.getRazorpayOrderId());
                String rzpStatus = rzpOrder.get("status");
                log.info("Razorpay order status={} for orderId={}", rzpStatus, paymentOrder.getRazorpayOrderId());

                if ("paid".equals(rzpStatus)) {
                    // Webhook was missed — recover by fetching the captured payment from Razorpay
                    log.warn("Missed webhook detected — order already paid at Razorpay. Recovering orderId={}",
                            paymentOrder.getRazorpayOrderId());

                    String capturedPaymentId = null;
                    List<com.razorpay.Payment> payments = razorpayClient.orders
                            .fetchPayments(paymentOrder.getRazorpayOrderId());
                    log.info("Payment attempts on order={} count={}", paymentOrder.getRazorpayOrderId(), payments.size());

                    for (com.razorpay.Payment payment : payments) {
                        String payStatus = payment.get("status");
                        log.debug("Payment id={} status={}", (String) payment.get("id"), payStatus);
                        if ("captured".equals(payStatus)) {
                            capturedPaymentId = payment.get("id");
                            log.info("Captured payment found — paymentId={}", capturedPaymentId);
                            break;
                        }
                    }

                    if (capturedPaymentId == null) {
                        log.error("No captured payment found despite Razorpay status=paid. orderId={}",
                                paymentOrder.getRazorpayOrderId());
                    }

                    paymentOrder.setStatus(Status.SUCCESS);
                    paymentOrder.setRazorpayPaymentId(capturedPaymentId);
                    paymentRepository.save(paymentOrder);
                    log.info("DB synced after webhook recovery — id={} status=SUCCESS paymentId={}",
                            paymentOrder.getId(), capturedPaymentId);
                    
                    commonUtils.saveOutboxEvent(commonUtils.createSaveOutboxEventRequestDTO(paymentOrder.getAuctionId(), paymentOrder.getBidderId(), 
                    paymentOrder.getType() == Payment.REGISTERATION? "registration-fee-paid": "winning-fee-paid", paymentOrder.getRazorpayPaymentId()));

                    throw new PaymentAlreadyPaidException(
                            "Payment already completed. Your registration will be confirmed shortly.");
                }

                // Razorpay says unpaid — check expiry
                log.debug("Razorpay status unpaid — checking expiry. expiresAt={}", paymentOrder.getOrderExpiresAt());
                if (paymentOrder.getOrderExpiresAt().isAfter(LocalDateTime.now())) {
                    log.info("Existing order still valid — returning it. orderId={}", paymentOrder.getRazorpayOrderId());
                    return buildResponse(paymentOrder);
                }

                // Expired and unpaid — delete and create a fresh order below
                log.info("Order expired and unpaid — deleting. id={}", paymentOrder.getId());
                paymentRepository.deleteById(paymentOrder.getId());
            }

            if (paymentOrder != null && paymentOrder.getStatus() == Status.FAILED) {
                log.info("Order was FAILED — deleting and recreating. id={}", paymentOrder.getId());
                paymentRepository.deleteById(paymentOrder.getId());
            }
        }

        // ── Step 1: Save CREATING row — DB unique constraint blocks all concurrent duplicates here.
        // Threads 2-9 hit the constraint and fail before touching Razorpay.
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(14);
        PaymentOrder newOrder = PaymentOrder.builder()
                .auctionId(request.getAuctionId())
                .bidderId(userId)
                .amount(orderAmount)
                .currency("INR")
                .type(Payment.valueOf(request.getType()))
                .status(Status.CREATING)
                .orderExpiresAt(expiresAt)
                .build();

        try {
            paymentRepository.saveAndFlush(newOrder); // flush immediately to trigger constraint check
            log.info("CREATING row saved — id={} userId={} auctionId={}", newOrder.getId(), userId, request.getAuctionId());
        } catch (Exception e) {
            log.warn("Duplicate createOrder blocked by DB constraint — userId={} auctionId={}", userId, request.getAuctionId());
            throw new AuctionValidationException("Order creation already in progress. Please retry in a moment.");
        }

        // ── Step 2: Call Razorpay — guaranteed only 1 thread reaches here ────
        try {
            log.info("Creating Razorpay order — userId={} auctionId={}", userId, request.getAuctionId());
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", orderAmount.multiply(BigDecimal.valueOf(100)).intValue()); // convert INR → paise
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "receipt_" + request.getAuctionId() + "_" + userId);
            JSONObject notes = new JSONObject();
            notes.put("auction_id", request.getAuctionId().toString());
            notes.put("bidder_id", userId);
            orderRequest.put("notes", notes);

            Order order = razorpayClient.orders.create(orderRequest);
            log.info("Razorpay order created — razorpayOrderId={}", (String) order.get("id"));

            // ── Step 3: Update with razorpayOrderId, status → CREATED ────────
            newOrder.setRazorpayOrderId(order.get("id"));
            newOrder.setStatus(Status.CREATED);
            paymentRepository.save(newOrder);
            log.info("PaymentOrder → CREATED — id={} razorpayOrderId={}", newOrder.getId(), newOrder.getRazorpayOrderId());

            return buildResponse(newOrder);

        } catch (Exception e) {
            // Razorpay failed — delete CREATING row so next request can retry cleanly
            log.error("Razorpay order creation failed — deleting CREATING row. userId={} auctionId={}", userId, request.getAuctionId(), e);
            paymentRepository.deleteById(newOrder.getId());
            throw new RuntimeException("Failed to create payment order. Please try again.", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handle payment.captured webhook
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public void handlePaymentCaptured(JSONObject body) {
        JSONObject paymentEntity = body
                .getJSONObject("payload")
                .getJSONObject("payment")
                .getJSONObject("entity");

        String razorpayOrderId   = paymentEntity.getString("order_id");
        String razorpayPaymentId = paymentEntity.getString("id");

        log.info("payment.captured — razorpayOrderId={} razorpayPaymentId={}", razorpayOrderId, razorpayPaymentId);

        PaymentOrder paymentOrder = paymentRepository
                .findByRazorpayOrderId(razorpayOrderId)
                .orElse(null);

        if (paymentOrder == null) {
            log.warn("PaymentOrder not found for razorpayOrderId={} — cannot process capture", razorpayOrderId);
            return;
        }

        if (paymentOrder.getStatus() == Status.SUCCESS) {
            log.info("PaymentOrder already SUCCESS — duplicate webhook ignored. razorpayOrderId={}", razorpayOrderId);
            return;
        }

        paymentOrder.setStatus(Status.SUCCESS);
        paymentOrder.setRazorpayPaymentId(razorpayPaymentId);
        paymentRepository.save(paymentOrder);
        log.info("PaymentOrder updated to SUCCESS — id={} paymentId={}", paymentOrder.getId(), razorpayPaymentId);

        commonUtils.saveOutboxEvent(commonUtils.createSaveOutboxEventRequestDTO(paymentOrder.getAuctionId(), paymentOrder.getBidderId(), paymentOrder.getType() == Payment.REGISTERATION
        ? "registration-fee-paid"
        : "winning-fee-paid", paymentOrder.getRazorpayPaymentId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handle payment.failed webhook
    // ─────────────────────────────────────────────────────────────────────────
    public void handlePaymentFailed(JSONObject body) {
        JSONObject paymentEntity = body
                .getJSONObject("payload")
                .getJSONObject("payment")
                .getJSONObject("entity");

        String razorpayOrderId   = paymentEntity.getString("order_id");
        String razorpayPaymentId = paymentEntity.getString("id");
        String errorCode         = paymentEntity.optString("error_code", "UNKNOWN");
        String errorDescription  = paymentEntity.optString("error_description", "No description");

        log.warn("payment.failed — razorpayOrderId={} razorpayPaymentId={} errorCode={} errorDescription={}",
                razorpayOrderId, razorpayPaymentId, errorCode, errorDescription);

        // PaymentOrder stays CREATED so user can retry on the same order
        // TODO: save PaymentAttempt record when PaymentAttempt model is built
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private CreateOrderResponseDTO buildResponse(PaymentOrder order) {
        return CreateOrderResponseDTO.builder()
                .razorpayOrderId(order.getRazorpayOrderId())
                .amount(order.getAmount().longValue())
                .currency(order.getCurrency())
                .keyId(razorpayKeyId)
                .expiresAt(order.getOrderExpiresAt())
                .build();
    }

    
}
