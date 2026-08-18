package payment.Utils;

import java.util.List;

import org.springframework.stereotype.Component;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;
import payment.repository.RefundOrderRepository;

@Component
@RequiredArgsConstructor
public class RefundServiceUtils {
    
    private final RefundOrderRepository refundOrderRepository;

    @Transactional
    public List<RefundOrder> fetchAndClaimPendingOrders(int limit) {
        List<RefundOrder> orders = refundOrderRepository.fetchPendingOrders(RefundStatus.PENDING.toString(),limit);
        for (RefundOrder order : orders) {
            order.setStatus(RefundStatus.PROCESSING);
        }
        return refundOrderRepository.saveAll(orders);
    }
}
