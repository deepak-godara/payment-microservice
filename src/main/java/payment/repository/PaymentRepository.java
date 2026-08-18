package payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import payment.model.PaymentOrder;

public interface PaymentRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByAuctionIdAndBidderId(Long auctionId, String bidderId);

    Optional<PaymentOrder> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PaymentOrder> findByAuctionIdAndBidderIdAndType(Long auctionId, String bidderId,String type);
}
