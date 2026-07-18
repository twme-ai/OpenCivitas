package dev.opencivitas.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShopSignParserTest {
    private final ShopSignParser parser = new ShopSignParser();

    @Test
    void parsesDocumentedPlayerAndBusinessFormats() {
        ParsedShopSign player = parser.parse("Alice", new String[]{
                "", "4", "B 250 : S 200", "diamond"
        }).sign().orElseThrow();

        assertEquals(ShopOwnerType.PLAYER, player.ownerType());
        assertNull(player.businessSlug());
        assertEquals(4, player.quantity());
        assertEquals(25_000L, player.buyPriceCents());
        assertEquals(20_000L, player.sellPriceCents());

        ParsedShopSign business = parser.parse("Alice", new String[]{
                "b:Acme-Co", "1", "12.50 S : 15 B", "?"
        }).sign().orElseThrow();
        assertEquals(ShopOwnerType.BUSINESS, business.ownerType());
        assertEquals("acme-co", business.businessSlug());
        assertEquals(1_500L, business.buyPriceCents());
        assertEquals(1_250L, business.sellPriceCents());
    }

    @Test
    void recognizesOrdinarySignsWithoutClaimingThem() {
        assertEquals(ShopSignStatus.NOT_A_SHOP, parser.parse("Alice", new String[]{
                "Welcome", "to", "the", "city"
        }).status());
    }

    @Test
    void rejectsForgedOwnersAndAmbiguousPrices() {
        assertEquals(ShopSignStatus.INVALID_OWNER, parser.parse("Alice", new String[]{
                "Bob", "1", "B 5", "stone"
        }).status());
        assertEquals(ShopSignStatus.INVALID_PRICE, parser.parse("Alice", new String[]{
                "", "1", "B 5 : 6 B", "stone"
        }).status());
        assertEquals(ShopSignStatus.INVALID_PRICE, parser.parse("Alice", new String[]{
                "", "1", "B 0", "stone"
        }).status());
        assertEquals(ShopSignStatus.INVALID_QUANTITY, parser.parse("Alice", new String[]{
                "", "0", "B 5", "stone"
        }).status());
    }
}
