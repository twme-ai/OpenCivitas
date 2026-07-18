package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.claim.ClaimCapacity;
import dev.opencivitas.claim.ClaimOperation;
import dev.opencivitas.claim.ClaimRegistry;
import dev.opencivitas.claim.ClaimRepository;
import dev.opencivitas.claim.ClaimResult;
import dev.opencivitas.claim.LandClaim;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ClaimCommand implements CommandExecutor, TabCompleter, Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final ClaimRepository claims;
    private final ClaimRegistry registry;
    private final MessageService messages;
    private final String currencySymbol;
    private final long blockCostCents;

    public ClaimCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            ClaimRepository claims,
            ClaimRegistry registry,
            MessageService messages,
            String currencySymbol,
            long blockCostCents
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.claims = claims;
        this.registry = registry;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.blockCostCents = blockCostCents;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "claim" -> claim(sender, args);
            case "claimwand" -> claimWand(sender, args);
            case "giveclaim" -> giveClaim(sender, args);
            case "claimexplosions" -> explosions(sender, args);
            case "claimkickout" -> kickOut(sender, args);
            default -> false;
        };
    }

    private boolean claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 0) {
            complete(sender, database.submit(() -> claims.capacity(player.getUniqueId())),
                    capacity -> openMenu(player, capacity));
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player, args);
            case "info" -> info(player, args);
            case "create" -> create(player, args);
            case "buy", "buyblocks" -> buyBlocks(player, args);
            case "trust", "add" -> trust(player, args, true);
            case "untrust", "remove" -> trust(player, args, false);
            case "delete" -> delete(player, args);
            default -> {
                messages.send(sender, "claims.usage");
                yield true;
            }
        };
    }

    private boolean list(Player player, String[] args) {
        if (args.length != 1) {
            usage(player, "/claim list");
            return true;
        }
        List<LandClaim> owned = registry.ownedBy(player.getUniqueId());
        messages.send(player, "claims.list-header");
        if (owned.isEmpty()) {
            messages.send(player, "claims.list-empty");
            return true;
        }
        for (LandClaim claim : owned) {
            messages.send(player, "claims.list-entry",
                    Placeholder.unparsed("id", Long.toString(claim.id())),
                    Placeholder.unparsed("world", claim.worldName()),
                    Placeholder.unparsed("min_x", Integer.toString(claim.minX())),
                    Placeholder.unparsed("min_z", Integer.toString(claim.minZ())),
                    Placeholder.unparsed("max_x", Integer.toString(claim.maxX())),
                    Placeholder.unparsed("max_z", Integer.toString(claim.maxZ())),
                    Placeholder.unparsed("area", Integer.toString(claim.area())));
        }
        return true;
    }

    private boolean info(Player player, String[] args) {
        if (args.length != 1) {
            usage(player, "/claim info");
            return true;
        }
        Optional<LandClaim> selected = claimAt(player);
        if (selected.isEmpty()) {
            messages.send(player, "claims.not-found-here");
            return true;
        }
        LandClaim claim = selected.get();
        messages.send(player, "claims.info-header", Placeholder.unparsed("id", Long.toString(claim.id())));
        messages.send(player, "claims.info-owner", Placeholder.unparsed("player", claim.ownerName()));
        messages.send(player, "claims.info-bounds",
                Placeholder.unparsed("world", claim.worldName()),
                Placeholder.unparsed("min_x", Integer.toString(claim.minX())),
                Placeholder.unparsed("min_z", Integer.toString(claim.minZ())),
                Placeholder.unparsed("max_x", Integer.toString(claim.maxX())),
                Placeholder.unparsed("max_z", Integer.toString(claim.maxZ())),
                Placeholder.unparsed("area", Integer.toString(claim.area())));
        messages.send(player, "claims.info-trusted",
                Placeholder.unparsed("count", Integer.toString(claim.trusted().size())));
        messages.send(player, claim.explosions()
                ? "claims.info-explosions-on" : "claims.info-explosions-off");
        return true;
    }

    private boolean create(Player player, String[] args) {
        if (args.length > 2) {
            usage(player, "/claim create [radius]");
            return true;
        }
        if (!registry.enabled(player.getWorld().getName())) {
            messages.send(player, "claims.wrong-world");
            return true;
        }
        int radius = 1;
        if (args.length == 2) {
            try {
                radius = Integer.parseInt(args[1]);
                if (radius < 0 || radius > 32) {
                    throw new NumberFormatException("Radius outside supported range");
                }
            } catch (NumberFormatException exception) {
                messages.send(player, "claims.invalid-radius");
                return true;
            }
        }
        int selectedRadius = radius;
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();
        complete(player, database.submit(() -> claims.create(
                player.getUniqueId(), world,
                Math.subtractExact(x, selectedRadius), Math.subtractExact(z, selectedRadius),
                Math.addExact(x, selectedRadius), Math.addExact(z, selectedRadius),
                Instant.now().toEpochMilli())), operation -> finishMutation(player, operation, Mutation.CREATE));
        return true;
    }

    private boolean buyBlocks(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/claim buy <blocks>");
            return true;
        }
        int blocks;
        try {
            blocks = Integer.parseInt(args[1]);
            if (blocks < 1) {
                throw new NumberFormatException("Blocks must be positive");
            }
        } catch (NumberFormatException exception) {
            messages.send(player, "claims.invalid-blocks");
            return true;
        }
        long cost;
        try {
            cost = Math.multiplyExact(blockCostCents, blocks);
        } catch (ArithmeticException exception) {
            messages.send(player, "claims.max-blocks");
            return true;
        }
        complete(player, database.submit(() -> claims.purchaseBlocks(
                player.getUniqueId(), blocks, Instant.now().toEpochMilli())), operation -> {
            if (operation.result() != ClaimResult.SUCCESS) {
                mutationError(player, operation.result());
                return;
            }
            messages.send(player, "claims.blocks-purchased",
                    Placeholder.unparsed("blocks", Integer.toString(blocks)),
                    Placeholder.unparsed("cost", Money.format(cost, currencySymbol)),
                    Placeholder.unparsed("remaining", Integer.toString(operation.remainingBlocks())),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean trust(Player player, String[] args, boolean add) {
        if (args.length != 2) {
            usage(player, add ? "/claim trust <player>" : "/claim untrust <player>");
            return true;
        }
        Optional<LandClaim> selected = ownedClaimAt(player);
        if (selected.isEmpty()) {
            return true;
        }
        long claimId = selected.get().id();
        complete(player, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            ClaimOperation operation = target.isEmpty()
                    ? ClaimOperation.failed(ClaimResult.CITIZEN_NOT_FOUND)
                    : add
                    ? claims.trust(player.getUniqueId(), claimId, target.get().uuid(), Instant.now().toEpochMilli())
                    : claims.untrust(player.getUniqueId(), claimId, target.get().uuid());
            return new TargetMutation(target, operation);
        }), result -> {
            if (result.target().isEmpty()) {
                messages.send(player, "error.player-not-found", Placeholder.unparsed("player", args[1]));
                return;
            }
            if (result.operation().result() != ClaimResult.SUCCESS) {
                mutationError(player, result.operation().result());
                return;
            }
            registry.upsert(result.operation().claim().orElseThrow());
            messages.send(player, add ? "claims.trusted" : "claims.untrusted",
                    Placeholder.unparsed("player", result.target().get().lastName()));
        });
        return true;
    }

    private boolean delete(Player player, String[] args) {
        if (args.length != 1) {
            usage(player, "/claim delete");
            return true;
        }
        Optional<LandClaim> selected = ownedClaimAt(player);
        if (selected.isEmpty()) {
            return true;
        }
        long claimId = selected.get().id();
        complete(player, database.submit(() -> claims.delete(player.getUniqueId(), claimId)), operation -> {
            if (operation.result() != ClaimResult.SUCCESS) {
                mutationError(player, operation.result());
                return;
            }
            registry.remove(claimId);
            messages.send(player, "claims.deleted",
                    Placeholder.unparsed("remaining", Integer.toString(operation.remainingBlocks())));
        });
        return true;
    }

    private boolean claimWand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 0) {
            usage(sender, "/claimwand");
            return true;
        }
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        complete(player, database.submit(() -> claims.issueWand(player.getUniqueId(), day)), issued -> {
            if (!issued) {
                messages.send(player, "claims.wand-already-issued");
                return;
            }
            ItemStack wand = new ItemStack(Material.GOLDEN_SHOVEL);
            ItemMeta meta = wand.getItemMeta();
            meta.displayName(messages.component(player, "claims.wand-name"));
            wand.setItemMeta(meta);
            Collection<ItemStack> overflow = player.getInventory().addItem(wand).values();
            for (ItemStack item : overflow) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            messages.send(player, "claims.wand-issued");
        });
        return true;
    }

    private boolean giveClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/giveclaim <player>");
            return true;
        }
        Optional<LandClaim> selected = ownedClaimAt(player);
        if (selected.isEmpty()) {
            return true;
        }
        long claimId = selected.get().id();
        complete(player, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[0]);
            ClaimOperation operation = target.isEmpty()
                    ? ClaimOperation.failed(ClaimResult.CITIZEN_NOT_FOUND)
                    : claims.transfer(
                            player.getUniqueId(), claimId, target.get().uuid(), Instant.now().toEpochMilli());
            return new TargetMutation(target, operation);
        }), result -> {
            if (result.target().isEmpty()) {
                messages.send(player, "error.player-not-found", Placeholder.unparsed("player", args[0]));
                return;
            }
            if (result.operation().result() != ClaimResult.SUCCESS) {
                mutationError(player, result.operation().result());
                return;
            }
            registry.upsert(result.operation().claim().orElseThrow());
            messages.send(player, "claims.transferred",
                    Placeholder.unparsed("player", result.target().get().lastName()));
        });
        return true;
    }

    private boolean explosions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 0) {
            usage(sender, "/claimexplosions");
            return true;
        }
        Optional<LandClaim> selected = ownedClaimAt(player);
        if (selected.isEmpty()) {
            return true;
        }
        complete(player, database.submit(() -> claims.toggleExplosions(
                player.getUniqueId(), selected.get().id(), Instant.now().toEpochMilli())), operation -> {
            if (operation.result() != ClaimResult.SUCCESS) {
                mutationError(player, operation.result());
                return;
            }
            LandClaim updated = operation.claim().orElseThrow();
            registry.upsert(updated);
            messages.send(player, updated.explosions()
                    ? "claims.explosions-enabled" : "claims.explosions-disabled");
        });
        return true;
    }

    private boolean kickOut(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("kickout")) {
            usage(sender, "/claimkickout kickout <player>");
            return true;
        }
        Optional<LandClaim> selected = ownedClaimAt(player);
        if (selected.isEmpty()) {
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(player, "error.player-not-found", Placeholder.unparsed("player", args[1]));
            return true;
        }
        LandClaim claim = selected.get();
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "claims.self");
            return true;
        }
        if (!claim.contains(
                target.getWorld().getName(), target.getLocation().getBlockX(), target.getLocation().getBlockZ())) {
            messages.send(player, "claims.player-not-inside", Placeholder.unparsed("player", target.getName()));
            return true;
        }
        target.teleportAsync(safeOutside(claim, target)).thenAccept(success ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!success) {
                        messages.send(player, "claims.kickout-failed");
                        return;
                    }
                    messages.send(player, "claims.kicked-out", Placeholder.unparsed("player", target.getName()));
                    messages.send(target, "claims.you-were-kicked-out");
                }));
        return true;
    }

    private static org.bukkit.Location safeOutside(LandClaim claim, Player target) {
        org.bukkit.World world = target.getWorld();
        org.bukkit.Location spawn = world.getSpawnLocation();
        if (!claim.contains(world.getName(), spawn.getBlockX(), spawn.getBlockZ())) {
            return spawn;
        }
        int x = claim.maxX() <= Integer.MAX_VALUE - 2
                ? claim.maxX() + 2 : Math.subtractExact(claim.minX(), 2);
        int z = Math.max(claim.minZ(), Math.min(claim.maxZ(), target.getLocation().getBlockZ()));
        return new org.bukkit.Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    private void openMenu(Player player, ClaimCapacity capacity) {
        ClaimMenu menu = new ClaimMenu(messages.component(player, "claims.menu-title"));
        menu.inventory().setItem(10, menuItem(
                Material.GOLDEN_SHOVEL,
                messages.component(player, "claims.menu-wand"),
                messages.component(player, "claims.menu-wand-lore")));
        menu.inventory().setItem(12, menuItem(
                Material.MAP,
                messages.component(player, "claims.menu-list"),
                messages.component(player, "claims.menu-capacity",
                        Placeholder.unparsed("used", Integer.toString(capacity.usedBlocks())),
                        Placeholder.unparsed("available", Integer.toString(capacity.availableBlocks())),
                        Placeholder.unparsed("maximum", Integer.toString(capacity.maximumBlocks())))));
        menu.inventory().setItem(14, menuItem(
                Material.GOLD_INGOT,
                messages.component(player, "claims.menu-buy"),
                messages.component(player, "claims.menu-buy-lore",
                        Placeholder.unparsed("cost", Money.format(
                                Math.multiplyExact(blockCostCents, 10), currencySymbol)))));
        menu.inventory().setItem(16, menuItem(
                Material.BARRIER,
                messages.component(player, "claims.menu-close"),
                messages.component(player, "claims.menu-close-lore")));
        player.openInventory(menu.inventory());
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ClaimMenu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        switch (event.getRawSlot()) {
            case 10 -> {
                player.closeInventory();
                claimWand(player, new String[0]);
            }
            case 12 -> {
                player.closeInventory();
                list(player, new String[]{"list"});
            }
            case 14 -> {
                player.closeInventory();
                buyBlocks(player, new String[]{"buy", "10"});
            }
            case 16 -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ClaimMenu) {
            event.setCancelled(true);
        }
    }

    private static ItemStack menuItem(Material material, Component name, Component lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void finishMutation(Player player, ClaimOperation operation, Mutation mutation) {
        if (operation.result() != ClaimResult.SUCCESS) {
            mutationError(player, operation.result());
            return;
        }
        LandClaim claim = operation.claim().orElseThrow();
        registry.upsert(claim);
        messages.send(player, mutation == Mutation.CREATE ? "claims.created" : "claims.resized",
                Placeholder.unparsed("area", Integer.toString(claim.area())),
                Placeholder.unparsed("remaining", Integer.toString(operation.remainingBlocks())));
    }

    private Optional<LandClaim> ownedClaimAt(Player player) {
        Optional<LandClaim> selected = claimAt(player);
        if (selected.isEmpty()) {
            messages.send(player, "claims.not-found-here");
            return Optional.empty();
        }
        if (!selected.get().ownerId().equals(player.getUniqueId())) {
            messages.send(player, "claims.no-permission");
            return Optional.empty();
        }
        return selected;
    }

    private Optional<LandClaim> claimAt(Player player) {
        return registry.at(
                player.getWorld().getName(), player.getLocation().getBlockX(), player.getLocation().getBlockZ());
    }

    private void mutationError(Player player, ClaimResult result) {
        String key = switch (result) {
            case CLAIM_NOT_FOUND -> "claims.not-found-here";
            case NO_PERMISSION -> "claims.no-permission";
            case OVERLAP -> "claims.overlap";
            case INSUFFICIENT_BLOCKS -> "claims.insufficient-blocks";
            case MAX_BLOCKS -> "claims.max-blocks";
            case INSUFFICIENT_FUNDS -> "claims.insufficient-funds";
            case CITIZEN_NOT_FOUND -> "error.player-not-found";
            case ALREADY_TRUSTED -> "claims.already-trusted";
            case NOT_TRUSTED -> "claims.not-trusted";
            case SELF -> "claims.self";
            default -> "claims.failed";
        };
        if (result == ClaimResult.CITIZEN_NOT_FOUND) {
            messages.send(player, key, Placeholder.unparsed("player", "Unknown"));
        } else {
            messages.send(player, key);
        }
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous claim operation failed", error);
                messages.send(sender, "error.database");
                return;
            }
            success.accept(result);
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("claim")) {
            if (args.length == 1) {
                return filter(args[0], List.of("list", "info", "create", "buy", "trust", "untrust", "delete"));
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("trust")
                    || args[0].equalsIgnoreCase("untrust"))) {
                return filter(args[1], onlineNames());
            }
        }
        if (command.getName().equalsIgnoreCase("giveclaim") && args.length == 1) {
            return filter(args[0], onlineNames());
        }
        if (command.getName().equalsIgnoreCase("claimkickout") && args.length == 1) {
            return filter(args[0], List.of("kickout"));
        }
        if (command.getName().equalsIgnoreCase("claimkickout") && args.length == 2) {
            return filter(args[1], onlineNames());
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(String input, Collection<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private enum Mutation {
        CREATE,
        RESIZE
    }

    private record TargetMutation(Optional<CitizenProfile> target, ClaimOperation operation) {
    }

    private static final class ClaimMenu implements InventoryHolder {
        private final Inventory inventory;

        private ClaimMenu(Component title) {
            inventory = Bukkit.createInventory(this, 27, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        private Inventory inventory() {
            return inventory;
        }
    }
}
