package dev.opencivitas.auction;

import java.time.Instant;
import java.util.UUID;

public record AuctionListing(
        long id,
        UUID sellerId,
        String sellerName,
        byte[] itemData,
        String itemKey,
        String itemName,
        int itemQuantity,
        long startingBidCents,
        Long buyoutCents,
        long currentBidCents,
        UUID currentBidderId,
        String currentBidderName,
        AuctionState state,
        Instant createdAt,
        Instant endsAt
) {
    public AuctionListing {
        itemData = itemData.clone();
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }
}
