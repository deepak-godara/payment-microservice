package payment.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import payment.dto.RefundRequestDTO;
import payment.repository.RefundOrderRepository;

@Component
@RequiredArgsConstructor
public class RefundEventListener {

    private static final Logger log = LoggerFactory.getLogger(RefundEventListener.class);
    private final RefundOrderRepository refundOrderRepository;

    @RetryableTopic(
        attempts = "3",
        backOff  = @BackOff(delay = 1000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
        topics = "auction-ended",
        groupId = "payment-service-refund-group",
        concurrency = "6",
        properties = {
            "max.poll.records=50",
            "max.poll.interval.ms=300000"
        }
    )
    public void recordRefundForAuctions(
        RefundRequestDTO refunds,
        Acknowledgment ack,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("Consuming 'auction-ended' event from partition={}, offset={} for auctionId={}",
                partition, offset, refunds.getAuctionId());

        int createdRows = refundOrderRepository.createRefundOrdersForLosers(
                refunds.getAuctionId(),
                refunds.getExcludedBidderIds()
        );

        log.info("Created {} PENDING refund order(s) for auctionId={}", createdRows, refunds.getAuctionId());
        ack.acknowledge();
    }
}