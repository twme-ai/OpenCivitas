package dev.opencivitas.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class StockPolicy {
    private final int defaultFeeBasisPoints;
    private final int maximumFeeBasisPoints;
    private final int maximumOpenOrders;
    private final long maximumOrderQuantity;
    private final long maximumListingShares;

    public StockPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "stocks.yml");
        if (!file.exists()) plugin.saveResource("stocks.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        maximumFeeBasisPoints = bounded(configuration.getInt("maximum-fee-basis-points", 1_000),
                0, 5_000, "maximum-fee-basis-points");
        defaultFeeBasisPoints = bounded(configuration.getInt("default-fee-basis-points", 100),
                0, maximumFeeBasisPoints, "default-fee-basis-points");
        maximumOpenOrders = bounded(configuration.getInt("maximum-open-orders", 20),
                1, 1_000, "maximum-open-orders");
        maximumOrderQuantity = bounded(configuration.getLong("maximum-order-quantity", 1_000_000),
                1, 1_000_000_000L, "maximum-order-quantity");
        maximumListingShares = bounded(configuration.getLong("maximum-listing-shares", 1_000_000_000L),
                1, 1_000_000_000L, "maximum-listing-shares");
    }

    public int defaultFeeBasisPoints() { return defaultFeeBasisPoints; }
    public int maximumFeeBasisPoints() { return maximumFeeBasisPoints; }
    public int maximumOpenOrders() { return maximumOpenOrders; }
    public long maximumOrderQuantity() { return maximumOrderQuantity; }
    public long maximumListingShares() { return maximumListingShares; }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }
}
