package dev.opencivitas.shop;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ShopListener implements Listener {
    private static final List<BlockFace> ADJACENT = List.of(
            BlockFace.DOWN, BlockFace.NORTH, BlockFace.EAST,
            BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP);

    private final JavaPlugin plugin;
    private final Database database;
    private final ShopRepository shops;
    private final MessageService messages;
    private final String currencySymbol;
    private final ShopSignParser parser = new ShopSignParser();
    private final NamespacedKey shopIdKey;
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();

    public ShopListener(
            JavaPlugin plugin,
            Database database,
            ShopRepository shops,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.shops = shops;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        shopIdKey = new NamespacedKey(plugin, "shop-id");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
        String[] lines = event.lines().stream().map(plainText::serialize).toArray(String[]::new);
        ShopSignParse parsed = parser.parse(event.getPlayer().getName(), lines);
        if (parsed.status() == ShopSignStatus.NOT_A_SHOP) {
            return;
        }
        event.setCancelled(true);
        if (parsed.status() != ShopSignStatus.SUCCESS) {
            messages.send(event.getPlayer(), signError(parsed.status()));
            return;
        }

        Optional<Block> selectedContainer = findContainer(event.getBlock());
        if (selectedContainer.isEmpty()) {
            messages.send(event.getPlayer(), "shops.creation.no-container");
            return;
        }
        Block containerBlock = selectedContainer.get();
        Container container = (Container) containerBlock.getState();
        ParsedShopSign sign = parsed.sign().orElseThrow();
        Optional<Material> material = resolveMaterial(sign.itemInput(), container.getInventory());
        if (material.isEmpty()) {
            messages.send(event.getPlayer(), "shops.creation.invalid-item");
            return;
        }

        Block signBlock = event.getBlock();
        UUID actor = event.getPlayer().getUniqueId();
        ShopDraft draft = new ShopDraft(
                signBlock.getWorld().getName(),
                signBlock.getX(), signBlock.getY(), signBlock.getZ(),
                containerBlock.getX(), containerBlock.getY(), containerBlock.getZ(),
                sign.ownerType(), sign.ownerType() == ShopOwnerType.PLAYER ? actor : null,
                sign.businessSlug(), material.get().name(), sign.quantity(),
                sign.buyPriceCents(), sign.sellPriceCents());
        database.submit(() -> shops.create(actor, draft, Instant.now().toEpochMilli()))
                .whenComplete((creation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not create chest shop", error);
                        messages.send(event.getPlayer(), "error.database");
                        return;
                    }
                    if (creation.result() != ShopResult.SUCCESS) {
                        messages.send(event.getPlayer(), creationError(creation.result()));
                        return;
                    }
                    activateSign(event.getPlayer(), creation.shop().orElseThrow());
                }));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !EnumSet.of(
                        org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                        org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK).contains(event.getAction())) {
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }
        Long shopId = sign.getPersistentDataContainer().get(shopIdKey, PersistentDataType.LONG);
        if (shopId == null) {
            return;
        }
        event.setCancelled(true);
        ShopDirection direction = event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                ? ShopDirection.BUY : ShopDirection.SELL;
        Player player = event.getPlayer();
        if (!lockedPlayers.add(player.getUniqueId())) {
            messages.send(player, "shops.transaction.busy");
            return;
        }
        database.submit(() -> shops.find(shopId)).whenComplete((shop, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        lockedPlayers.remove(player.getUniqueId());
                        plugin.getLogger().log(Level.SEVERE, "Could not load chest shop", error);
                        messages.send(player, "error.database");
                        return;
                    }
                    if (shop.isEmpty() || !shop.get().active()) {
                        lockedPlayers.remove(player.getUniqueId());
                        messages.send(player, "shops.transaction.inactive");
                        return;
                    }
                    beginTransaction(player, shop.get(), direction, player.isSneaking());
                }));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Sign sign) {
            Long shopId = sign.getPersistentDataContainer().get(shopIdKey, PersistentDataType.LONG);
            if (shopId != null) {
                database.submit(() -> {
                    shops.deactivate(shopId, Instant.now().toEpochMilli());
                    return null;
                });
            }
            return;
        }
        if (block.getState() instanceof Container) {
            database.submit(() -> {
                shops.deactivateContainer(
                        block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        Instant.now().toEpochMilli());
                return null;
            });
        }
    }

    private void activateSign(Player actor, ChestShop shop) {
        Block block = actor.getServer().getWorld(shop.worldName()) == null ? null
                : actor.getServer().getWorld(shop.worldName()).getBlockAt(shop.signX(), shop.signY(), shop.signZ());
        if (block == null || !(block.getState() instanceof Sign sign)
                || !(block.getWorld().getBlockAt(
                        shop.containerX(), shop.containerY(), shop.containerZ()).getState() instanceof Container)) {
            database.submit(() -> {
                shops.deactivate(shop.id(), Instant.now().toEpochMilli());
                return null;
            });
            messages.send(actor, "shops.creation.block-changed");
            return;
        }
        sign.getPersistentDataContainer().set(shopIdKey, PersistentDataType.LONG, shop.id());
        sign.getSide(Side.FRONT).line(0, Component.text(
                shop.ownerType() == ShopOwnerType.BUSINESS ? "b:" + shop.businessSlug() : actor.getName()));
        sign.getSide(Side.FRONT).line(1, Component.text(Integer.toString(shop.quantity())));
        sign.getSide(Side.FRONT).line(2, Component.text(priceLine(shop)));
        sign.getSide(Side.FRONT).line(3, Component.text(shop.itemKey().toLowerCase(java.util.Locale.ROOT)));
        sign.setWaxed(true);
        sign.update(true, false);
        messages.send(actor, "shops.creation.created",
                Placeholder.unparsed("item", displayItem(shop.itemKey())));
    }

    private void beginTransaction(Player player, ChestShop shop, ShopDirection direction, boolean stack) {
        if (!lockedShops.add(shop.id())) {
            lockedPlayers.remove(player.getUniqueId());
            messages.send(player, "shops.transaction.busy");
            return;
        }
        try {
            Long price = direction == ShopDirection.BUY ? shop.buyPriceCents() : shop.sellPriceCents();
            if (price == null) {
                failBeforeSettlement(player, shop.id(), "shops.transaction.unavailable");
                return;
            }
            if (!player.getWorld().getName().equals(shop.worldName())) {
                failBeforeSettlement(player, shop.id(), "shops.transaction.inactive");
                return;
            }
            Material material = Material.matchMaterial(shop.itemKey());
            org.bukkit.World world = player.getServer().getWorld(shop.worldName());
            if (world == null) {
                failBeforeSettlement(player, shop.id(), "shops.transaction.inactive");
                return;
            }
            Block containerBlock = world.getBlockAt(
                    shop.containerX(), shop.containerY(), shop.containerZ());
            if (material == null || !(containerBlock.getState() instanceof Container container)) {
                failBeforeSettlement(player, shop.id(), "shops.transaction.inactive");
                return;
            }

            int units = stack ? Math.max(1, material.getMaxStackSize() / shop.quantity()) : 1;
            int amount = Math.multiplyExact(shop.quantity(), units);
            Inventory source = direction == ShopDirection.BUY
                    ? container.getInventory() : player.getInventory();
            Inventory target = direction == ShopDirection.BUY
                    ? player.getInventory() : container.getInventory();
            Location sourceLocation = direction == ShopDirection.BUY
                    ? containerBlock.getLocation() : player.getLocation();
            Location targetLocation = direction == ShopDirection.BUY
                    ? player.getLocation() : containerBlock.getLocation();
            Optional<InventoryReservation> selected = InventoryReservation.take(source, material, amount);
            if (selected.isEmpty()) {
                failBeforeSettlement(player, shop.id(), direction == ShopDirection.BUY
                        ? "shops.transaction.shop-stock" : "shops.transaction.player-stock");
                return;
            }
            InventoryReservation reservation = selected.get();
            if (!reservation.fits(target)) {
                dropOverflow(reservation.deliver(source), sourceLocation);
                failBeforeSettlement(player, shop.id(), direction == ShopDirection.BUY
                        ? "shops.transaction.player-space" : "shops.transaction.shop-space");
                return;
            }

            database.submit(() -> shops.settle(
                            shop.id(), player.getUniqueId(), direction, units, Instant.now().toEpochMilli()))
                    .whenComplete((settlement, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (error != null) {
                                dropOverflow(reservation.deliver(source), sourceLocation);
                                plugin.getLogger().log(Level.SEVERE, "Could not settle chest shop transaction", error);
                                messages.send(player, "error.database");
                                return;
                            }
                            if (settlement.result() != ShopResult.SUCCESS) {
                                dropOverflow(reservation.deliver(source), sourceLocation);
                                messages.send(player, transactionError(settlement.result()));
                                return;
                            }
                            dropOverflow(reservation.deliver(target), targetLocation);
                            finishTransaction(player, shop, direction, settlement);
                        } finally {
                            lockedShops.remove(shop.id());
                            lockedPlayers.remove(player.getUniqueId());
                        }
                    }));
        } catch (RuntimeException exception) {
            lockedShops.remove(shop.id());
            lockedPlayers.remove(player.getUniqueId());
            throw exception;
        }
    }

    private void finishTransaction(
            Player player, ChestShop shop, ShopDirection direction, ShopSettlement settlement) {
        messages.send(player, direction == ShopDirection.BUY
                        ? "shops.transaction.bought" : "shops.transaction.sold",
                Placeholder.unparsed("amount", Integer.toString(settlement.itemAmount())),
                Placeholder.unparsed("item", displayItem(shop.itemKey())),
                Placeholder.unparsed("price", Money.format(settlement.totalCents(), currencySymbol)),
                Placeholder.unparsed("account", shop.accountName()));
        if (shop.ownerType() == ShopOwnerType.PLAYER) {
            Player owner = Bukkit.getPlayer(shop.ownerId());
            if (owner != null) {
                messages.send(owner, direction == ShopDirection.BUY
                                ? "shops.transaction.owner-notice-buy" : "shops.transaction.owner-notice-sell",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("amount", Integer.toString(settlement.itemAmount())),
                        Placeholder.unparsed("item", displayItem(shop.itemKey())),
                        Placeholder.unparsed("price", Money.format(settlement.totalCents(), currencySymbol)));
            }
        }
    }

    private void failBeforeSettlement(Player player, long shopId, String message) {
        lockedShops.remove(shopId);
        lockedPlayers.remove(player.getUniqueId());
        messages.send(player, message);
    }

    private void dropOverflow(List<ItemStack> items, Location location) {
        if (items.isEmpty()) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            plugin.getLogger().severe("Could not deliver chest shop overflow because its inventory has no location");
            return;
        }
        Location drop = location.clone().add(0.5, 0.5, 0.5);
        for (ItemStack item : items) {
            location.getWorld().dropItemNaturally(drop, item);
        }
    }

    private static Optional<Block> findContainer(Block sign) {
        for (BlockFace face : ADJACENT) {
            Block relative = sign.getRelative(face);
            if (relative.getState() instanceof Container) {
                return Optional.of(relative);
            }
        }
        return Optional.empty();
    }

    private static Optional<Material> resolveMaterial(String input, Inventory inventory) {
        if (input.equals("?")) {
            for (ItemStack item : inventory.getStorageContents()) {
                if (item != null && !item.getType().isAir()) {
                    return Optional.of(item.getType());
                }
            }
            return Optional.empty();
        }
        Material material = Material.matchMaterial(input.replace(' ', '_'));
        return material == null || !material.isItem() || material.isAir()
                ? Optional.empty() : Optional.of(material);
    }

    private static String priceLine(ChestShop shop) {
        String buy = shop.buyPriceCents() == null ? null : "B " + decimal(shop.buyPriceCents());
        String sell = shop.sellPriceCents() == null ? null : "S " + decimal(shop.sellPriceCents());
        return buy == null ? sell : sell == null ? buy : buy + " : " + sell;
    }

    private static String decimal(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private static String displayItem(String itemKey) {
        return itemKey.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String signError(ShopSignStatus status) {
        return switch (status) {
            case INVALID_OWNER -> "shops.creation.invalid-owner";
            case INVALID_QUANTITY -> "shops.creation.invalid-quantity";
            case INVALID_PRICE -> "shops.creation.invalid-price";
            case INVALID_ITEM -> "shops.creation.invalid-item";
            default -> "shops.creation.invalid-price";
        };
    }

    private static String creationError(ShopResult result) {
        return switch (result) {
            case BUSINESS_NOT_FOUND -> "shops.creation.business-not-found";
            case BUSINESS_INACTIVE -> "shops.creation.business-inactive";
            case NO_PERMISSION -> "shops.creation.no-permission";
            case LOCATION_OCCUPIED -> "shops.creation.location-occupied";
            default -> "shops.creation.failed";
        };
    }

    private static String transactionError(ShopResult result) {
        return switch (result) {
            case CUSTOMER_FUNDS -> "shops.transaction.customer-funds";
            case OWNER_FUNDS -> "shops.transaction.owner-funds";
            case SELF_TRADE -> "shops.transaction.self-trade";
            case PRICE_UNAVAILABLE -> "shops.transaction.unavailable";
            case BUSINESS_INACTIVE, SHOP_INACTIVE, SHOP_NOT_FOUND -> "shops.transaction.inactive";
            default -> "shops.transaction.failed";
        };
    }
}
