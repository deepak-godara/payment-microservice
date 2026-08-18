package payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;

import payment.client.AuctionServiceClient;

@Configuration
public class AppConfig {

    @Value("${auction.service.url}")
    private String auctionServiceUrl;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    @Value("${razorpay.key-secret}")
    private String razorpayKeySecret;

    @Bean
    public AuctionServiceClient auctionServiceClient() {
        RestClient restClient = RestClient.builder()
                .baseUrl(auctionServiceUrl)
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        return factory.createClient(AuctionServiceClient.class);
    }

    @Bean
    public RazorpayClient setRazorpayClient()  throws RazorpayException{

        return new RazorpayClient(razorpayKeyId, razorpayKeySecret);
    }
}
