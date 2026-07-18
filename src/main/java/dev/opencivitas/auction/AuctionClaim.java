package dev.opencivitas.auction;

import java.time.Instant;

public record AuctionClaim(
        long id,
        long listingId,
        byte[] itemData,
        String itemKey,
        String itemName,
        int itemQuantity,
        Instant createdAt
) {
    public AuctionClaim {
        itemData = itemData.clone();
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }
}
