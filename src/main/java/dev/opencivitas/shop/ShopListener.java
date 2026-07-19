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
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final ShopHologramService holograms;
    private final MessageService messages;
    private final String currencySymbol;
    private final ShopSignParser parser = new ShopSignParser();
    private final NamespacedKey shopIdKey;
    private final NamespacedKey pendingOwnerKey;
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, ShopEditSession> editSessions = new ConcurrentHashMap<>();

    public ShopListener(
            JavaPlugin plugin,
            Database database,
            ShopRepository shops,
            ShopHologramService holograms,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.shops = shops;
        this.holograms = holograms;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        shopIdKey = new NamespacedKey(plugin, "shop-id");
        pendingOwnerKey = new NamespacedKey(plugin, "pending-shop-owner");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
        String[] lines = event.lines().stream().map(plainText::serialize).toArray(String[]::new);
        ShopSignParse parsed = parser.parse(event.getPlayer().getName(), lines);
        Sign currentSign = (Sign) event.getBlock().getState();
        Long existingShopId = currentSign.getPersistentDataContainer().get(shopIdKey, PersistentDataType.LONG);
        ShopEditSession edit = existingShopId == null ? null
                : editSessions.remove(event.getPlayer().getUniqueId());
        if (existingShopId != null && (edit == null || !edit.matches(event.getBlock(), existingShopId))) {
            event.setCancelled(true);
            rewax(event.getBlock());
            return;
        }
        if (parsed.status() == ShopSignStatus.NOT_A_SHOP) {
            if (existingShopId != null) {
                event.setCancelled(true);
                messages.send(event.getPlayer(), "shops.creation.invalid-price");
                finishEdit(event.getBlock(), existingShopId);
            }
            return;
        }
        if (parsed.status() != ShopSignStatus.SUCCESS) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), signError(parsed.status()));
            if (existingShopId != null) finishEdit(event.getBlock(), existingShopId);
            return;
        }

        Optional<Block> selectedContainer = findContainer(event.getBlock());
        if (selectedContainer.isEmpty()) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "shops.creation.no-container");
            if (existingShopId != null) finishEdit(event.getBlock(), existingShopId);
            return;
        }
        Block containerBlock = selectedContainer.get();
        Container container = (Container) containerBlock.getState();
        ParsedShopSign sign = parsed.sign().orElseThrow();
        Optional<Material> material = resolveMaterial(
                sign.itemInput(), container.getInventory(), existingShopId == null
                        ? null : event.getPlayer().getInventory().getItemInMainHand());
        if (material.isEmpty()) {
            if (existingShopId == null && sign.itemInput().equals("?")) {
                event.line(0, Component.text(sign.ownerType() == ShopOwnerType.BUSINESS
                        ? "b:" + sign.businessSlug() : event.getPlayer().getName()));
                preparePendingSign(event.getBlock(), event.getPlayer());
                messages.send(event.getPlayer(), "shops.creation.pending-item");
                return;
            }
            event.setCancelled(true);
            messages.send(event.getPlayer(), "shops.creation.invalid-item");
            if (existingShopId != null) finishEdit(event.getBlock(), existingShopId);
            return;
        }

        event.setCancelled(true);
        Block signBlock = event.getBlock();
        UUID actor = event.getPlayer().getUniqueId();
        ShopDraft draft = new ShopDraft(
                signBlock.getWorld().getName(),
                signBlock.getX(), signBlock.getY(), signBlock.getZ(),
                containerBlock.getX(), containerBlock.getY(), containerBlock.getZ(),
                sign.ownerType(), sign.ownerType() == ShopOwnerType.PLAYER ? actor : null,
                sign.businessSlug(), material.get().name(), sign.quantity(),
                sign.buyPriceCents(), sign.sellPriceCents());
        submitConfiguration(event.getPlayer(), draft, existingShopId);
    }

    public Long shopId(Sign sign) {
        return sign.getPersistentDataContainer().get(shopIdKey, PersistentDataType.LONG);
    }

    public boolean openEditor(Player player, Sign sign, long shopId) {
        if (!lockedShops.add(shopId)) return false;
        ShopEditSession session = new ShopEditSession(
                shopId,
                sign.getWorld().getName(),
                sign.getX(), sign.getY(), sign.getZ());
        editSessions.put(player.getUniqueId(), session);
        try {
            sign.setWaxed(false);
            sign.update(true, false);
            player.openSign(sign, Side.FRONT);
        } catch (RuntimeException exception) {
            editSessions.remove(player.getUniqueId(), session);
            lockedShops.remove(shopId);
            sign.setWaxed(true);
            sign.update(true, false);
            throw exception;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (editSessions.remove(player.getUniqueId(), session)) {
                lockedShops.remove(shopId);
                restoreSign(shopId);
            }
        }, 1_200L);
        return true;
    }

    private void submitConfiguration(Player actor, ShopDraft draft, Long existingShopId) {
        database.submit(() -> existingShopId == null
                        ? shops.create(actor.getUniqueId(), draft, Instant.now().toEpochMilli())
                        : shops.update(actor.getUniqueId(), existingShopId, draft))
                .whenComplete((creation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Could not configure chest shop", error);
                            messages.send(actor, "error.database");
                            if (existingShopId != null) restoreSign(existingShopId);
                            return;
                        }
                        if (creation.result() != ShopResult.SUCCESS) {
                            messages.send(actor, creationError(creation.result()));
                            if (existingShopId != null) restoreSign(existingShopId);
                            return;
                        }
                        activateSign(actor, creation.shop().orElseThrow(), existingShopId != null);
                    } finally {
                        if (existingShopId != null) lockedShops.remove(existingShopId);
                    }
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
            String pendingOwner = sign.getPersistentDataContainer().get(
                    pendingOwnerKey, PersistentDataType.STRING);
            if (pendingOwner != null) {
                event.setCancelled(true);
                completePendingSign(event.getPlayer(), event.getClickedBlock(), sign, pendingOwner);
            }
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
                holograms.remove(shopId);
                database.submit(() -> {
                    shops.deactivate(shopId, Instant.now().toEpochMilli());
                    return null;
                });
            }
            return;
        }
        if (block.getState() instanceof Container) {
            for (BlockFace face : ADJACENT) {
                if (block.getRelative(face).getState() instanceof Sign sign) {
                    Long shopId = sign.getPersistentDataContainer().get(shopIdKey, PersistentDataType.LONG);
                    if (shopId != null) holograms.remove(shopId);
                }
            }
            database.submit(() -> {
                shops.deactivateContainer(
                        block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        Instant.now().toEpochMilli());
                return null;
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lockedPlayers.remove(playerId);
        ShopEditSession edit = editSessions.remove(playerId);
        if (edit != null) {
            lockedShops.remove(edit.shopId());
            restoreSign(edit.shopId());
        }
    }

    private void activateSign(Player actor, ChestShop shop, boolean updated) {
        Block block = actor.getServer().getWorld(shop.worldName()) == null ? null
                : actor.getServer().getWorld(shop.worldName()).getBlockAt(shop.signX(), shop.signY(), shop.signZ());
        if (block == null || !(block.getState() instanceof Sign sign)
                || !(block.getWorld().getBlockAt(
                        shop.containerX(), shop.containerY(), shop.containerZ()).getState() instanceof Container)) {
            holograms.remove(shop.id());
            database.submit(() -> {
                shops.deactivate(shop.id(), Instant.now().toEpochMilli());
                return null;
            });
            messages.send(actor, "shops.creation.block-changed");
            return;
        }
        writeSign((Sign) block.getState(), shop);
        holograms.upsert(shop);
        messages.send(actor, updated ? "shops.creation.updated" : "shops.creation.created",
                Placeholder.unparsed("item", displayItem(shop.itemKey())));
    }

    private void writeSign(Sign sign, ChestShop shop) {
        sign.getPersistentDataContainer().set(shopIdKey, PersistentDataType.LONG, shop.id());
        sign.getPersistentDataContainer().remove(pendingOwnerKey);
        sign.getSide(Side.FRONT).line(0, Component.text(
                shop.ownerType() == ShopOwnerType.BUSINESS ? "b:" + shop.businessSlug() : shop.accountName()));
        sign.getSide(Side.FRONT).line(1, Component.text(Integer.toString(shop.quantity())));
        sign.getSide(Side.FRONT).line(2, Component.text(priceLine(shop)));
        sign.getSide(Side.FRONT).line(3, Component.text(shop.itemKey().toLowerCase(java.util.Locale.ROOT)));
        sign.setWaxed(true);
        sign.update(true, false);
    }

    private void preparePendingSign(Block block, Player owner) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(block.getState() instanceof Sign pending)
                    || pending.getPersistentDataContainer().has(shopIdKey, PersistentDataType.LONG)) {
                return;
            }
            pending.getPersistentDataContainer().set(
                    pendingOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            pending.setWaxed(true);
            pending.update(true, false);
        });
    }

    private void completePendingSign(Player player, Block signBlock, Sign sign, String pendingOwner) {
        if (!player.getUniqueId().toString().equals(pendingOwner)) {
            messages.send(player, "shops.creation.pending-owner");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !held.getType().isItem()) {
            messages.send(player, "shops.creation.pending-item-required");
            return;
        }
        if (!lockedPlayers.add(player.getUniqueId())) {
            messages.send(player, "shops.transaction.busy");
            return;
        }
        try {
            PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
            String[] lines = sign.getSide(Side.FRONT).lines().stream()
                    .map(plain::serialize).toArray(String[]::new);
            ShopSignParse parsed = parser.parse(player.getName(), lines);
            Optional<Block> container = findContainer(signBlock);
            if (parsed.status() != ShopSignStatus.SUCCESS || container.isEmpty()) {
                lockedPlayers.remove(player.getUniqueId());
                messages.send(player, parsed.status() == ShopSignStatus.SUCCESS
                        ? "shops.creation.no-container" : signError(parsed.status()));
                return;
            }
            ParsedShopSign definition = parsed.sign().orElseThrow();
            Block containerBlock = container.get();
            ShopDraft draft = new ShopDraft(
                    signBlock.getWorld().getName(),
                    signBlock.getX(), signBlock.getY(), signBlock.getZ(),
                    containerBlock.getX(), containerBlock.getY(), containerBlock.getZ(),
                    definition.ownerType(),
                    definition.ownerType() == ShopOwnerType.PLAYER ? player.getUniqueId() : null,
                    definition.businessSlug(), held.getType().name(), definition.quantity(),
                    definition.buyPriceCents(), definition.sellPriceCents());
            database.submit(() -> shops.create(
                            player.getUniqueId(), draft, Instant.now().toEpochMilli()))
                    .whenComplete((creation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        lockedPlayers.remove(player.getUniqueId());
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Could not complete pending chest shop", error);
                            messages.send(player, "error.database");
                        } else if (creation.result() != ShopResult.SUCCESS) {
                            messages.send(player, creationError(creation.result()));
                        } else {
                            activateSign(player, creation.shop().orElseThrow(), false);
                        }
                    }));
        } catch (RuntimeException exception) {
            lockedPlayers.remove(player.getUniqueId());
            throw exception;
        }
    }

    private void finishEdit(Block block, long shopId) {
        lockedShops.remove(shopId);
        rewax(block);
    }

    private void rewax(Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getState() instanceof Sign sign) {
                sign.setWaxed(true);
                sign.update(true, false);
            }
        });
    }

    private void restoreSign(long shopId) {
        database.submit(() -> shops.find(shopId)).whenComplete((selected, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not restore chest shop sign", error);
                        return;
                    }
                    selected.filter(ChestShop::active).ifPresent(shop -> {
                        org.bukkit.World world = Bukkit.getWorld(shop.worldName());
                        if (world != null && world.getBlockAt(
                                shop.signX(), shop.signY(), shop.signZ()).getState() instanceof Sign sign) {
                            writeSign(sign, shop);
                        }
                    });
                }));
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

    private static Optional<Material> resolveMaterial(String input, Inventory inventory, ItemStack fallback) {
        if (input.equals("?")) {
            for (ItemStack item : inventory.getStorageContents()) {
                if (item != null && !item.getType().isAir()) {
                    return Optional.of(item.getType());
                }
            }
            if (fallback != null && !fallback.getType().isAir() && fallback.getType().isItem()) {
                return Optional.of(fallback.getType());
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
            case OWNER_CHANGE_NOT_ALLOWED -> "shops.creation.owner-change";
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

    private record ShopEditSession(long shopId, String world, int x, int y, int z) {
        private boolean matches(Block block, long selectedShopId) {
            return shopId == selectedShopId
                    && world.equals(block.getWorld().getName())
                    && x == block.getX() && y == block.getY() && z == block.getZ();
        }
    }
}
