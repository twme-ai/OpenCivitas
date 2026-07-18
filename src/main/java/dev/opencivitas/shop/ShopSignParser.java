package dev.opencivitas.shop;

import dev.opencivitas.economy.Money;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopSignParser {
    private static final Pattern BUSINESS_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{2,31}");
    private static final Pattern PRICE = Pattern.compile(
            "(?i)^\\s*([BS])?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*([BS])?\\s*$");
    private static final int MAX_QUANTITY = 36 * 64;

    public ShopSignParse parse(String playerName, String[] lines) {
        if (lines.length < 4 || !looksLikeShop(lines[2], lines[0])) {
            return ShopSignParse.failed(ShopSignStatus.NOT_A_SHOP);
        }

        String owner = lines[0].trim();
        ShopOwnerType ownerType;
        String businessSlug = null;
        if (owner.isEmpty() || owner.equalsIgnoreCase(playerName)) {
            ownerType = ShopOwnerType.PLAYER;
        } else if (owner.regionMatches(true, 0, "b:", 0, 2)) {
            businessSlug = owner.substring(2).trim().toLowerCase(Locale.ROOT);
            if (!BUSINESS_SLUG.matcher(businessSlug).matches()) {
                return ShopSignParse.failed(ShopSignStatus.INVALID_OWNER);
            }
            ownerType = ShopOwnerType.BUSINESS;
        } else {
            return ShopSignParse.failed(ShopSignStatus.INVALID_OWNER);
        }

        int quantity;
        try {
            quantity = Integer.parseInt(lines[1].trim());
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                return ShopSignParse.failed(ShopSignStatus.INVALID_QUANTITY);
            }
        } catch (NumberFormatException exception) {
            return ShopSignParse.failed(ShopSignStatus.INVALID_QUANTITY);
        }

        Long buyPrice = null;
        Long sellPrice = null;
        String[] prices = lines[2].split(":", -1);
        if (prices.length > 2) {
            return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
        }
        try {
            for (String price : prices) {
                Matcher matcher = PRICE.matcher(price);
                if (!matcher.matches() || (matcher.group(1) == null) == (matcher.group(3) == null)) {
                    return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
                }
                char side = (matcher.group(1) == null ? matcher.group(3) : matcher.group(1))
                        .toUpperCase(Locale.ROOT).charAt(0);
                long cents = Money.parsePositiveCents(matcher.group(2));
                if (side == 'B') {
                    if (buyPrice != null) {
                        return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
                    }
                    buyPrice = cents;
                } else {
                    if (sellPrice != null) {
                        return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
                    }
                    sellPrice = cents;
                }
            }
        } catch (IllegalArgumentException exception) {
            return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
        }
        if (buyPrice == null && sellPrice == null) {
            return ShopSignParse.failed(ShopSignStatus.INVALID_PRICE);
        }

        String item = lines[3].trim();
        if (item.isEmpty()) {
            return ShopSignParse.failed(ShopSignStatus.INVALID_ITEM);
        }
        return ShopSignParse.parsed(new ParsedShopSign(
                ownerType, businessSlug, quantity, buyPrice, sellPrice, item));
    }

    private static boolean looksLikeShop(String price, String owner) {
        String normalized = price.toUpperCase(Locale.ROOT);
        return normalized.contains("B") || normalized.contains("S")
                || owner.regionMatches(true, 0, "b:", 0, 2);
    }
}
