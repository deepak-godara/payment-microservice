package payment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.transaction.annotation.Transactional;
import payment.model.RefundOrder;
import payment.model.Types.RefundStatus;

public interface RefundOrderRepository extends JpaRepository<RefundOrder,Long>{
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO refund_orders (
            payment_order_id,
            auction_id,
            bidder_id,
            razorpay_payment_id,
            amount,
            status,
            retry_count,
            created_at,
            updated_at
        )
        SELECT 
            po.id,
            po.auction_id,
            po.bidder_id,
            po.razorpay_payment_id,
            po.amount,
            'PENDING',
            0,
            NOW(),
            NOW()
        FROM payment_orders po
        WHERE po.auction_id = :auctionId
          AND po.type = 'REGISTERATION'
          AND po.status = 'SUCCESS'
          AND po.bidder_id NOT IN (:excludedBidderIds)
        ON CONFLICT (payment_order_id) DO NOTHING
        """, nativeQuery = true)
    int createRefundOrdersForLosers(
            @Param("auctionId") Long auctionId,
            @Param("excludedBidderIds") List<String> excludedBidderIds);


    @Transactional       
    @Query(value="SELECT * FROM refund_orders WHERE status = :status  ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<RefundOrder> fetchPendingOrders(
        @Param("status")String status,
        @Param("limit") int limit);

    Optional<RefundOrder> findByRazorpayPaymentId(String razorpayPaymentId);

    @Modifying
    @Transactional
    @Query("UPDATE RefundOrder r SET r.status = payment.model.Types.RefundStatus.PENDING " +
           "WHERE r.status = payment.model.Types.RefundStatus.PROCESSING " +
           "AND r.updatedAt < :cutoff")
    int resetStuckProcessingOrders(@Param("cutoff") LocalDateTime cutoff);

    List<RefundOrder> findAllByStatusAndAuctionId(RefundStatus status,Long auctionId);

}
