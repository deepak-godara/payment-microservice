package payment.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;
import payment.repository.RefundOrderRepository;

@Component
@RequiredArgsConstructor
public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    private final RefundOrderRepository refundOrderRepository;

    @Transactional
    public List<RefundOrder> fetchAndClaimPendingOrders(int limit) {
        List<RefundOrder> orders = refundOrderRepository.fetchPendingOrders(
                RefundStatus.PENDING.toString(), limit);

        if (orders.isEmpty()) {
            log.debug("No PENDING refund orders found");
            return orders;
        }

        log.info("Claiming {} PENDING refund order(s) → marking PROCESSING", orders.size());

        for (RefundOrder order : orders) {
            order.setStatus(RefundStatus.PROCESSING);
        }

        return refundOrderRepository.saveAll(orders);
    }
}
