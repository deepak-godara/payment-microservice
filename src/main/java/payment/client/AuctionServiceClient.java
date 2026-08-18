package payment.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import payment.client.dto.RegistrationStatusResponseDTO;
import payment.client.dto.WinnerStatusResponseDTO;

@HttpExchange
public interface AuctionServiceClient {

    @GetExchange("/api/v1/auctions/{auctionId}/registrations/status")
    RegistrationStatusResponseDTO getRegistrationStatus(
            @PathVariable Long auctionId,
            @RequestHeader("Authorization") String authorizationHeader);

    @GetExchange("/api/v1/auctions/{auctionId}/winner/status")
    WinnerStatusResponseDTO getWinnerStatus(
            @PathVariable Long auctionId,
            @RequestHeader("Authorization") String authorizationHeader);
}
