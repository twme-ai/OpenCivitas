package dev.opencivitas.protection;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ProtectionListener implements Listener {
    public static final String REDACTED_SECRET = "__opencivitas_secret__";
    private static final Set<InventoryAction> DEPOSIT_ACTIONS = Set.of(
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME);
    private static final Set<InventoryAction> WITHDRAW_ACTIONS = Set.of(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
            InventoryAction.COLLECT_TO_CURSOR, InventoryAction.CLONE_STACK);

    private final JavaPlugin plugin;
    private final Database database;
    private final ProtectionRepository repository;
    private final ProtectionRegistry registry;
    private final ProtectionPolicy policy;
    private final ProtectionSessionService sessions;
    private final PasswordHasher passwords;
    private final MessageService messages;

    public ProtectionListener(
            JavaPlugin plugin,
            Database database,
            ProtectionRepository repository,
            ProtectionRegistry registry,
            ProtectionPolicy policy,
            ProtectionSessionService sessions,
            PasswordHasher passwords,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.repository = repository;
        this.registry = registry;
        this.policy = policy;
        this.sessions = sessions;
        this.passwords = passwords;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = canonical(event.getBlockPlaced());
        Player player = event.getPlayer();
        if (!policy.autoProtect(block.getType())
                || sessions.hasMode(player.getUniqueId(), ProtectionMode.NOLOCK)
                || registry.at(key(block)).isPresent()) {
            return;
        }
        create(player, block, ProtectionType.PRIVATE, true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String[] tokens = event.getMessage().split("\\s+");
        int secretIndex = secretIndex(tokens);
        if (secretIndex < 0 || secretIndex >= tokens.length
                || tokens[secretIndex].isEmpty() || tokens[secretIndex].length() > 128) {
            return;
        }
        sessions.stageCommandSecret(event.getPlayer().getUniqueId(), tokens[secretIndex]);
        tokens[secretIndex] = REDACTED_SECRET;
        event.setMessage(String.join(" ", tokens));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSecretChat(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Optional<ProtectionAction> action = sessions.consumeSecretAction(playerId);
        boolean passwordPrompt = action.isEmpty() && sessions.consumePasswordPrompt(playerId);
        if (action.isEmpty() && !passwordPrompt) return;
        event.setCancelled(true);

        String secret = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (secret.isEmpty() || secret.length() > 128) {
                messages.send(event.getPlayer(), "protection.invalid-source");
                return;
            }
            if (action.isPresent()) {
                ProtectionAction template = action.orElseThrow();
                sessions.setAction(playerId, ProtectionAction.modify(
                        template.adding(), template.access(), ProtectionSourceType.PASSWORD, secret));
                messages.send(event.getPlayer(), "protection.click-action",
                        Placeholder.unparsed("action", template.adding()
                                ? "add password access" : "remove password access"));
            } else {
                authorizePassword(event.getPlayer(), secret);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        Optional<ProtectionAction> action = sessions.consumeAction(player.getUniqueId());
        if (action.isPresent()) {
            event.setCancelled(true);
            perform(player, clicked, action.orElseThrow());
            return;
        }

        Optional<BlockProtection> found = registry.at(key(canonical(clicked)));
        if (found.isEmpty()) return;
        BlockProtection protection = found.orElseThrow();
        if (!canOpen(protection, player)) {
            event.setCancelled(true);
            denied(player, protection);
            return;
        }
        if (!sessions.hasMode(player.getUniqueId(), ProtectionMode.NOSPAM)) {
            messages.send(player, "protection.notice",
                    Placeholder.unparsed("type", display(protection.type())),
                    Placeholder.unparsed("owner", protection.ownerName()));
        }
        if (protection.autoClose() && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && clicked.getBlockData() instanceof Openable) {
            scheduleClose(clicked, protection.key());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakGuard(BlockBreakEvent event) {
        Block block = canonical(event.getBlock());
        Optional<BlockProtection> found = registry.at(key(block));
        if (found.isEmpty()) return;
        BlockProtection protection = found.orElseThrow();
        if (!canAdmin(protection, event.getPlayer())) {
            event.setCancelled(true);
            denied(event.getPlayer(), protection);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakComplete(BlockBreakEvent event) {
        Block block = canonical(event.getBlock());
        Optional<BlockProtection> found = registry.at(key(block));
        if (found.isEmpty()) return;
        BlockProtection protection = found.orElseThrow();
        registry.remove(protection.key());
        database.submit(() -> {
                    ProtectionOperation<BlockProtection> deleted =
                            repository.delete(protection.key(), protection.ownerId());
                    if (deleted.result() == ProtectionResult.NOT_OWNER) {
                        return repository.delete(protection.key());
                    }
                    return deleted;
                })
                .whenComplete((operation, error) -> {
                    if (error != null) {
                        registry.upsert(protection);
                        plugin.getLogger().log(
                                Level.SEVERE, "Could not remove a broken block protection", error);
                    }
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        for (BlockProtection protection : protections(event.getInventory())) {
            if (!canOpen(protection, player)) {
                event.setCancelled(true);
                denied(player, protection);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        List<BlockProtection> protections = protections(event.getView().getTopInventory());
        if (protections.isEmpty() || protections.stream().allMatch(protection -> fullAccess(protection, player))) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        boolean deposit = false;
        boolean withdraw = false;
        InventoryAction action = event.getAction();
        if (DEPOSIT_ACTIONS.contains(action)) deposit = clicked == event.getView().getTopInventory();
        else if (WITHDRAW_ACTIONS.contains(action)) {
            withdraw = action == InventoryAction.COLLECT_TO_CURSOR || clicked != player.getInventory();
        }
        else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            deposit = clicked == player.getInventory();
            withdraw = clicked == event.getView().getTopInventory();
        } else if (action != InventoryAction.NOTHING
                && action != InventoryAction.DROP_ALL_CURSOR
                && action != InventoryAction.DROP_ONE_CURSOR) {
            deposit = true;
            withdraw = true;
        }
        if ((deposit && protections.stream().anyMatch(protection -> !canDeposit(protection, player)))
                || (withdraw && protections.stream().anyMatch(protection -> !canWithdraw(protection, player)))) {
            event.setCancelled(true);
            messages.send(player, "protection.inventory-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().noneMatch(slot -> slot < topSize)) return;
        List<BlockProtection> protections = protections(event.getView().getTopInventory());
        if (protections.stream().anyMatch(protection -> !canDeposit(protection, player))) {
            event.setCancelled(true);
            messages.send(player, "protection.inventory-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        List<BlockProtection> source = protections(event.getSource());
        List<BlockProtection> destination = protections(event.getDestination());
        if (source.isEmpty() && destination.isEmpty()) return;
        if (!source.isEmpty() && !destination.isEmpty()
                && source.stream().allMatch(first -> destination.stream()
                        .allMatch(second -> first.ownerId().equals(second.ownerId())))) {
            return;
        }
        if (source.stream().anyMatch(protection -> !protection.type().publicWithdraw())
                || destination.stream().anyMatch(protection -> !protection.type().publicDeposit())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBlock(block)
                || protectedBlock(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBlock(block)
                || protectedBlock(block.getRelative(event.getDirection()))
                || protectedBlock(block.getRelative(event.getDirection().getOppositeFace())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Optional<BlockProtection> found = registry.at(key(canonical(event.getBlock())));
        if (found.isPresent() && !canAdmin(found.orElseThrow(), event.getPlayer())) {
            event.setCancelled(true);
            denied(event.getPlayer(), found.orElseThrow());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.clear(event.getPlayer().getUniqueId());
    }

    private void perform(Player player, Block clicked, ProtectionAction action) {
        Block block = canonical(clicked);
        ProtectionKey key = key(block);
        Optional<BlockProtection> found = registry.at(key);
        switch (action.kind()) {
            case LOCK -> {
                if (!policy.protectable(block.getType())) {
                    messages.send(player, "protection.not-protectable");
                } else if (found.isPresent()) {
                    messages.send(player, "protection.already-protected");
                } else {
                    create(player, block, action.protectionType(), false);
                }
            }
            case INFO -> {
                if (found.isEmpty()) {
                    messages.send(player, "protection.not-found");
                    return;
                }
                BlockProtection protection = found.orElseThrow();
                messages.send(player, "protection.info",
                        Placeholder.unparsed("type", display(protection.type())),
                        Placeholder.unparsed("owner", protection.ownerName()),
                        Placeholder.unparsed("access", Integer.toString(protection.access().size())),
                        Placeholder.unparsed("auto_close", Boolean.toString(protection.autoClose())));
            }
            case UNLOCK -> {
                if (!owned(player, found)) return;
                BlockProtection protection = found.orElseThrow();
                complete(player, database.submit(() -> repository.delete(key, protection.ownerId())), operation -> {
                    if (operation.result() == ProtectionResult.SUCCESS) registry.remove(key);
                    result(player, operation.result(), "protection.unlocked");
                });
            }
            case TRANSFER -> {
                if (!owned(player, found)) return;
                BlockProtection protection = found.orElseThrow();
                complete(player, database.submit(() -> repository.transfer(
                        key, protection.ownerId(), action.targetOwnerId())), operation -> {
                    operation.value().ifPresent(registry::upsert);
                    result(player, operation.result(), "protection.transferred");
                });
            }
            case SET_AUTO_CLOSE -> {
                if (!owned(player, found)) return;
                if (!(clicked.getBlockData() instanceof Openable)) {
                    messages.send(player, "protection.auto-close-unsupported");
                    return;
                }
                BlockProtection protection = found.orElseThrow();
                complete(player, database.submit(() -> repository.setAutoClose(
                        key, protection.ownerId(), action.enabled())), operation -> {
                    operation.value().ifPresent(registry::upsert);
                    result(player, operation.result(), action.enabled()
                            ? "protection.auto-close-enabled" : "protection.auto-close-disabled");
                });
            }
            case MODIFY_ACCESS -> {
                if (!owned(player, found)) return;
                BlockProtection protection = found.orElseThrow();
                complete(player, database.submit(() -> modifyAccess(protection, action)), operation -> {
                    operation.value().ifPresent(registry::upsert);
                    result(player, operation.result(), action.adding()
                            ? "protection.access-added" : "protection.access-removed");
                });
            }
        }
    }

    private ProtectionOperation<BlockProtection> modifyAccess(
            BlockProtection protection,
            ProtectionAction action
    ) throws Exception {
        ProtectionSource source;
        if (action.sourceType() == ProtectionSourceType.PASSWORD) {
            if (action.adding()) {
                boolean exists = protection.access().keySet().stream()
                        .filter(candidate -> candidate.type() == ProtectionSourceType.PASSWORD)
                        .anyMatch(candidate -> passwords.matches(
                                action.sourceIdentifier(), candidate.identifier()));
                if (exists) return ProtectionOperation.failed(ProtectionResult.SOURCE_EXISTS);
                source = new ProtectionSource(ProtectionSourceType.PASSWORD,
                        passwords.hash(action.sourceIdentifier()));
            } else {
                source = protection.access().keySet().stream()
                        .filter(candidate -> candidate.type() == ProtectionSourceType.PASSWORD)
                        .filter(candidate -> passwords.matches(
                                action.sourceIdentifier(), candidate.identifier()))
                        .findFirst().orElse(null);
                if (source == null) return ProtectionOperation.failed(ProtectionResult.SOURCE_NOT_FOUND);
            }
        } else {
            source = new ProtectionSource(action.sourceType(), action.sourceIdentifier());
        }
        return repository.modifyAccess(
                protection.key(), protection.ownerId(), source, action.access(),
                action.adding(), Instant.now().toEpochMilli());
    }

    private void authorizePassword(Player player, String secret) {
        complete(player, database.submit(() -> {
            Set<String> matched = new LinkedHashSet<>();
            for (String hash : registry.passwordHashes(passwords.fingerprint(secret))) {
                if (passwords.matches(secret, hash)) matched.add(hash);
            }
            return Set.copyOf(matched);
        }), matched -> {
            sessions.addPasswordAuthorizations(player.getUniqueId(), matched);
            messages.send(player, matched.isEmpty()
                    ? "protection.password-invalid" : "protection.password-accepted");
        });
    }

    private void create(Player player, Block block, ProtectionType type, boolean automatic) {
        ProtectionKey key = key(block);
        complete(player, database.submit(() -> repository.create(
                player.getUniqueId(), key, type, Instant.now().toEpochMilli())), operation -> {
            operation.value().ifPresent(registry::upsert);
            if (operation.result() != ProtectionResult.SUCCESS) {
                result(player, operation.result(), "protection.locked");
            } else if (!automatic || !sessions.hasMode(player.getUniqueId(), ProtectionMode.NOSPAM)) {
                messages.send(player, "protection.locked",
                        Placeholder.unparsed("type", display(type)));
            }
        });
    }

    private boolean owned(Player player, Optional<BlockProtection> found) {
        if (found.isEmpty()) {
            messages.send(player, "protection.not-found");
            return false;
        }
        if (!canAdmin(found.orElseThrow(), player)) {
            messages.send(player, "protection.not-owner");
            return false;
        }
        return true;
    }

    private boolean canOpen(BlockProtection protection, Player player) {
        return protection.type().publicOpen() || fullAccess(protection, player);
    }

    private boolean canDeposit(BlockProtection protection, Player player) {
        return protection.type().publicDeposit() || fullAccess(protection, player);
    }

    private boolean canWithdraw(BlockProtection protection, Player player) {
        return protection.type().publicWithdraw() || fullAccess(protection, player);
    }

    private boolean fullAccess(BlockProtection protection, Player player) {
        return registry.effectiveAccess(
                protection, player, sessions.passwordHashes(player.getUniqueId())) != null;
    }

    private boolean canAdmin(BlockProtection protection, Player player) {
        return registry.effectiveAccess(
                protection, player, sessions.passwordHashes(player.getUniqueId())) == ProtectionAccess.ADMIN;
    }

    private void denied(Player player, BlockProtection protection) {
        messages.send(player, "protection.denied",
                Placeholder.unparsed("owner", protection.ownerName()));
    }

    private boolean protectedBlock(Block block) {
        return registry.at(key(canonical(block))).isPresent();
    }

    private List<BlockProtection> protections(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        List<Block> blocks = new ArrayList<>();
        if (holder instanceof BlockInventoryHolder blockHolder) {
            blocks.add(blockHolder.getBlock());
        } else if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide(false) instanceof BlockInventoryHolder left) blocks.add(left.getBlock());
            if (doubleChest.getRightSide(false) instanceof BlockInventoryHolder right) blocks.add(right.getBlock());
        }
        LinkedHashSet<BlockProtection> found = new LinkedHashSet<>();
        for (Block block : blocks) registry.at(key(canonical(block))).ifPresent(found::add);
        return List.copyOf(found);
    }

    private void scheduleClose(Block clicked, ProtectionKey key) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Optional<BlockProtection> current = registry.at(key);
            if (current.isEmpty() || !current.orElseThrow().autoClose()) return;
            close(canonical(clicked));
        }, policy.autoCloseTicks());
    }

    private static void close(Block block) {
        closeOne(block);
        if (block.getBlockData() instanceof Bisected bisected) {
            Block other = block.getRelative(
                    bisected.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP);
            closeOne(other);
        }
    }

    private static void closeOne(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Openable openable && openable.isOpen()) {
            openable.setOpen(false);
            block.setBlockData(openable, false);
        }
    }

    private void result(Player player, ProtectionResult result, String successKey) {
        if (result == ProtectionResult.SUCCESS) messages.send(player, successKey);
        else messages.send(player, "protection.result." + result.name().toLowerCase(java.util.Locale.ROOT));
    }

    private <T> void complete(Player player, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous block protection operation failed", error);
                if (player.isOnline()) messages.send(player, "error.database");
            } else {
                success.accept(value);
            }
        }));
    }

    private static Block canonical(Block block) {
        if (block.getBlockData() instanceof Bisected bisected
                && bisected.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    private static ProtectionKey key(Block block) {
        Location location = block.getLocation();
        return new ProtectionKey(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String display(ProtectionType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static int secretIndex(String[] tokens) {
        if (tokens.length >= 3 && boltCommand(tokens[0])
                && tokens[1].equalsIgnoreCase("password")) {
            return 2;
        }
        if (tokens.length >= 6 && boltCommand(tokens[0])
                && tokens[1].equalsIgnoreCase("modify")
                && tokens[4].equalsIgnoreCase("password")) {
            return 5;
        }
        if (tokens.length >= 5 && boltCommand(tokens[0])
                && tokens[1].equalsIgnoreCase("trust")
                && tokens[3].equalsIgnoreCase("password")) {
            return 4;
        }
        return -1;
    }

    private static boolean boltCommand(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("/bolt") || normalized.endsWith(":bolt");
    }
}
