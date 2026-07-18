package dev.opencivitas.auction;

import java.util.Optional;

public record AuctionOperation(
        AuctionResult result,
        Optional<AuctionListing> listing,
        long amountCents,
        long balanceCents
) {
    public static AuctionOperation failed(AuctionResult result) {
        return new AuctionOperation(result, Optional.empty(), 0, 0);
    }

    public static AuctionOperation succeeded(AuctionListing listing, long amount, long balance) {
        return new AuctionOperation(AuctionResult.SUCCESS, Optional.ofNullable(listing), amount, balance);
    }
}
