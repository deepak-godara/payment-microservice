package payment.scheduler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import payment.model.RefundOrder;
import payment.repository.RefundOrderRepository;
import payment.service.RefundService;

@Component
@RequiredArgsConstructor
public class RefundPoller {

    private static final Logger log              = LoggerFactory.getLogger(RefundPoller.class);
    private static final int    BATCH_SIZE       = 10;
    private static final int    STUCK_THRESHOLD  = 10;  // minutes
    private static final int    MAX_CONCURRENCY  = 5;
    private static final int    TIMEOUT_SECONDS  = 100;

    private final RefundOrderRepository refundOrderRepository;
    private final RefundService         refundService;
    private final Utils                 utils;

    private final ExecutorService refundExecutor   = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore       concurrencyLimit  = new Semaphore(MAX_CONCURRENCY);

    @Scheduled(fixedDelay = 180_000)
    public void initiateRefundForAuctions() {

        // Step 0 — recover orders stuck in PROCESSING (JVM crash recovery)
        int recovered = refundOrderRepository.resetStuckProcessingOrders(
                LocalDateTime.now().minusMinutes(STUCK_THRESHOLD));
        if (recovered > 0) {
            log.warn("Recovered {} stuck PROCESSING order(s) back to PENDING", recovered);
        }

        // Step 1 — claim a batch of PENDING orders
        List<RefundOrder> refundItems = utils.fetchAndClaimPendingOrders(BATCH_SIZE);

        if (refundItems.isEmpty()) {
            return;
        }

        log.info("Processing {} PENDING refund order(s)", refundItems.size());

        // Step 2 — process each order in parallel via virtual threads, capped at MAX_CONCURRENCY
        List<CompletableFuture<Void>> futures = refundItems.stream()
                .map(refundItem -> CompletableFuture.runAsync(() -> {
                    try {
                        concurrencyLimit.acquire();
                        refundService.createRazorpayRefundOrder(refundItem);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrencyLimit.release();
                    }
                }, refundExecutor))
                .toList();

        // Step 3 — wait for all tasks to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("Refund processing timed out or was interrupted", ex);
        }
    }
}
