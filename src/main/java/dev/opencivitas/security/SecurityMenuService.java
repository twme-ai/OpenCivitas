package dev.opencivitas.security;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class SecurityMenuService implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final SecurityRepository security;
    private final SecurityRegistry registry;
    private final CameraManager cameraManager;
    private final CameraViewService views;
    private final SecurityItems items;
    private final MessageService messages;
    private final NamespacedKey cameraIdKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey targetKey;

    public SecurityMenuService(
            JavaPlugin plugin,
            Database database,
            SecurityRepository security,
            SecurityRegistry registry,
            CameraManager cameraManager,
            CameraViewService views,
            SecurityItems items,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.security = security;
        this.registry = registry;
        this.cameraManager = cameraManager;
        this.views = views;
        this.items = items;
        this.messages = messages;
        cameraIdKey = new NamespacedKey(plugin, "security_menu_camera");
        actionKey = new NamespacedKey(plugin, "security_menu_action");
        targetKey = new NamespacedKey(plugin, "security_menu_target");
    }

    public void openCamera(Player player, SecurityCamera camera) {
        MenuHolder holder = new MenuHolder(MenuType.CAMERA, camera.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.component(
                player, "security.menu-camera-title", Placeholder.unparsed("camera", camera.name())));
        holder.inventory = inventory;
        inventory.setItem(13, cameraItem(player, camera));
        if (camera.ownerId().equals(player.getUniqueId())) {
            inventory.setItem(10, action(Material.ARROW, "rotate-left",
                    messages.component(player, "security.rotate-left")));
            inventory.setItem(11, action(Material.ARROW, "rotate-up",
                    messages.component(player, "security.rotate-up")));
            inventory.setItem(15, action(Material.ARROW, "rotate-down",
                    messages.component(player, "security.rotate-down")));
            inventory.setItem(16, action(Material.ARROW, "rotate-right",
                    messages.component(player, "security.rotate-right")));
            inventory.setItem(22, action(Material.BARRIER, "remove-camera",
                    messages.component(player, "security.camera-remove-button")));
        }
        player.openInventory(inventory);
    }

    public void openComputer(Player player, long computerId) {
        database.submit(() -> new ComputerOpen(
                security.canAccess(computerId, player.getUniqueId()), security.dashboard(computerId).orElse(null)))
                .whenComplete((result, error) -> main(player, error, () -> {
                    if (result.dashboard() == null) {
                        messages.send(player, "security.computer-not-found");
                    } else if (!result.allowed()) {
                        messages.send(player, "security.computer-denied");
                    } else {
                        openDashboard(player, result.dashboard());
                    }
                }));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder holder)
                || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        Long cameraId = item.getItemMeta().getPersistentDataContainer()
                .get(cameraIdKey, PersistentDataType.LONG);
        switch (holder.type) {
            case CAMERA -> cameraClick(player, holder.id, action);
            case COMPUTER -> computerClick(player, holder, action, cameraId, event.getClick());
            case GROUPS -> groupClick(player, holder.id, action);
            case CAMERAS -> membershipClick(player, holder, action);
            case ACCESS -> accessClick(player, holder.id, item, action);
        }
    }

    private void openDashboard(Player player, ComputerDashboard dashboard) {
        SecurityComputer computer = dashboard.computer();
        MenuHolder holder = new MenuHolder(MenuType.COMPUTER, computer.id());
        holder.groupName = dashboard.group().map(CameraGroup::name).orElse(null);
        holder.cameraIds = dashboard.cameras().stream().map(SecurityCamera::id).toList();
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.component(
                player, "security.menu-computer-title", Placeholder.unparsed("computer", computer.name())));
        holder.inventory = inventory;

        inventory.setItem(0, action(computer.publicAccess() ? Material.CHEST : Material.ENDER_CHEST,
                "access", messages.component(player, "security.computer-access")));
        inventory.setItem(2, action(Material.PLAYER_HEAD, "cameras",
                messages.component(player, "security.computer-add-cameras")));
        inventory.setItem(3, action(Material.CHEST, "groups",
                messages.component(player, "security.computer-group")));
        ItemStack status = action(Material.COMPASS, "status", messages.component(player, "security.computer-status"));
        status.editMeta(meta -> meta.lore(List.of(
                messages.component(player, computer.publicAccess()
                        ? "security.access-public" : "security.access-private"),
                messages.component(player, "security.group-current", Placeholder.unparsed(
                        "group", dashboard.group().map(CameraGroup::name).orElse("-"))))));
        inventory.setItem(8, status);
        int slot = 9;
        for (SecurityCamera camera : dashboard.cameras()) {
            if (slot >= inventory.getSize()) break;
            inventory.setItem(slot++, cameraItem(player, camera));
        }
        player.openInventory(inventory);
    }

    private void openGroups(Player player, long computerId) {
        database.submit(() -> security.groups(player.getUniqueId()))
                .whenComplete((groups, error) -> main(player, error, () -> {
                    MenuHolder holder = new MenuHolder(MenuType.GROUPS, computerId);
                    Inventory inventory = Bukkit.createInventory(holder, 54,
                            messages.component(player, "security.menu-groups-title"));
                    holder.inventory = inventory;
                    int slot = 0;
                    for (CameraGroup group : groups) {
                        if (slot >= inventory.getSize()) break;
                        inventory.setItem(slot++, action(Material.CHEST, "group:" + group.name(),
                                Component.text(group.name())));
                    }
                    player.openInventory(inventory);
                }));
    }

    private void openCameras(Player player, long computerId) {
        database.submit(() -> new CameraSelection(
                security.dashboard(computerId).orElse(null), security.cameras(player.getUniqueId())))
                .whenComplete((selection, error) -> main(player, error, () -> {
                    if (selection.dashboard() == null) {
                        messages.send(player, "security.computer-not-found");
                        return;
                    }
                    MenuHolder holder = new MenuHolder(MenuType.CAMERAS, computerId);
                    holder.groupName = selection.dashboard().group().map(CameraGroup::name).orElse(null);
                    holder.cameraIds = selection.dashboard().cameras().stream().map(SecurityCamera::id).toList();
                    Inventory inventory = Bukkit.createInventory(holder, 54,
                            messages.component(player, "security.menu-cameras-title"));
                    holder.inventory = inventory;
                    Set<Long> memberIds = new HashSet<>(holder.cameraIds);
                    int slot = 0;
                    for (SecurityCamera camera : selection.owned()) {
                        if (slot >= inventory.getSize()) break;
                        ItemStack item = cameraItem(player, camera);
                        item.editMeta(meta -> {
                            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING,
                                    (memberIds.contains(camera.id()) ? "remove:" : "add:") + camera.name());
                            meta.lore(List.of(messages.component(player, memberIds.contains(camera.id())
                                    ? "security.camera-member" : "security.camera-not-member")));
                        });
                        inventory.setItem(slot++, item);
                    }
                    player.openInventory(inventory);
                }));
    }

    private void openAccess(Player player, long computerId) {
        database.submit(() -> security.dashboard(computerId).orElse(null))
                .whenComplete((dashboard, error) -> main(player, error, () -> {
                    if (dashboard == null) {
                        messages.send(player, "security.computer-not-found");
                        return;
                    }
                    MenuHolder holder = new MenuHolder(MenuType.ACCESS, computerId);
                    Inventory inventory = Bukkit.createInventory(holder, 54,
                            messages.component(player, "security.menu-access-title"));
                    holder.inventory = inventory;
                    inventory.setItem(0, action(dashboard.computer().publicAccess()
                                    ? Material.CHEST : Material.ENDER_CHEST, "toggle-public",
                            messages.component(player, dashboard.computer().publicAccess()
                                    ? "security.access-public" : "security.access-private")));
                    Set<UUID> granted = new HashSet<>();
                    int slot = 9;
                    for (ComputerAccess access : dashboard.access()) {
                        granted.add(access.playerId());
                        if (slot >= inventory.getSize()) break;
                        inventory.setItem(slot++, playerItem(access.playerId(), access.playerName(), "revoke"));
                    }
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (slot >= inventory.getSize()) break;
                        if (online.getUniqueId().equals(dashboard.computer().ownerId())
                                || granted.contains(online.getUniqueId())) continue;
                        inventory.setItem(slot++, playerItem(online.getUniqueId(), online.getName(), "grant"));
                    }
                    player.openInventory(inventory);
                }));
    }

    private void cameraClick(Player player, long cameraId, String action) {
        if (action == null) return;
        if (action.equals("remove-camera")) {
            database.submit(() -> security.deleteCamera(player.getUniqueId(), cameraId))
                    .whenComplete((operation, error) -> main(player, error, () -> {
                        if (operation.result() != SecurityResult.SUCCESS) {
                            sendResult(player, operation.result());
                            return;
                        }
                        SecurityCamera camera = operation.value().orElseThrow();
                        views.stopViewers(camera.id());
                        cameraManager.remove(camera.id());
                        player.closeInventory();
                        giveOrDrop(player, items.camera(messages.locale(player)));
                        messages.send(player, "security.camera-removed",
                                Placeholder.unparsed("camera", camera.name()));
                    }));
            return;
        }
        float yaw = 0;
        float pitch = 0;
        switch (action) {
            case "rotate-left" -> yaw = -15;
            case "rotate-right" -> yaw = 15;
            case "rotate-up" -> pitch = -15;
            case "rotate-down" -> pitch = 15;
            default -> { return; }
        }
        float yawDelta = yaw;
        float pitchDelta = pitch;
        database.submit(() -> security.rotateCamera(
                        player.getUniqueId(), cameraId, yawDelta, pitchDelta))
                .whenComplete((operation, error) -> main(player, error, () -> {
                    if (operation.result() != SecurityResult.SUCCESS) {
                        sendResult(player, operation.result());
                        return;
                    }
                    SecurityCamera camera = operation.value().orElseThrow();
                    cameraManager.update(camera);
                    openCamera(player, camera);
                }));
    }

    private void computerClick(
            Player player, MenuHolder holder, String action, Long cameraId, ClickType click) {
        SecurityComputer computer = registry.computer(holder.id).orElse(null);
        if (computer == null) return;
        if (cameraId != null) {
            SecurityCamera camera = registry.camera(cameraId).orElse(null);
            if (camera == null) return;
            if (click == ClickType.DROP && computer.ownerId().equals(player.getUniqueId())
                    && holder.groupName != null) {
                membership(player, holder.id, holder.groupName, camera.name(), false);
                return;
            }
            viewFromComputer(player, computer.id(), camera.id());
            return;
        }
        if (!computer.ownerId().equals(player.getUniqueId())) {
            if (!"status".equals(action)) messages.send(player, "security.not-owner");
            return;
        }
        if ("access".equals(action)) openAccess(player, computer.id());
        else if ("groups".equals(action)) openGroups(player, computer.id());
        else if ("cameras".equals(action)) openCameras(player, computer.id());
    }

    private void viewFromComputer(Player player, long computerId, long cameraId) {
        database.submit(() -> new ComputerOpen(
                        security.canAccess(computerId, player.getUniqueId()),
                        security.dashboard(computerId).orElse(null)))
                .whenComplete((result, error) -> main(player, error, () -> {
                    if (!result.allowed() || result.dashboard() == null) {
                        messages.send(player, "security.computer-denied");
                        return;
                    }
                    SecurityCamera selected = result.dashboard().cameras().stream()
                            .filter(camera -> camera.id() == cameraId).findFirst().orElse(null);
                    if (selected == null) {
                        messages.send(player, "security.camera-not-found");
                        return;
                    }
                    player.closeInventory();
                    if (!views.view(player, result.dashboard().cameras(), selected.id())) {
                        messages.send(player, "security.view-unavailable");
                    }
                }));
    }

    private void groupClick(Player player, long computerId, String action) {
        if (action == null || !action.startsWith("group:")) return;
        String group = action.substring("group:".length());
        database.submit(() -> security.assignGroup(player.getUniqueId(), computerId, group))
                .whenComplete((operation, error) -> main(player, error, () -> {
                    if (operation.result() == SecurityResult.SUCCESS) {
                        registry.upsert(operation.value().orElseThrow());
                        openComputer(player, computerId);
                    } else sendResult(player, operation.result());
                }));
    }

    private void membershipClick(Player player, MenuHolder holder, String action) {
        if (holder.groupName == null) {
            messages.send(player, "security.group-required");
            return;
        }
        if (action == null || !(action.startsWith("add:") || action.startsWith("remove:"))) return;
        boolean add = action.startsWith("add:");
        membership(player, holder.id, holder.groupName, action.substring(action.indexOf(':') + 1), add);
    }

    private void membership(
            Player player, long computerId, String group, String camera, boolean add) {
        database.submit(() -> add
                        ? security.addCamera(player.getUniqueId(), group, camera, System.currentTimeMillis())
                        : security.removeCamera(player.getUniqueId(), group, camera))
                .whenComplete((result, error) -> main(player, error, () -> {
                    sendResult(player, result);
                    if (result == SecurityResult.SUCCESS) openComputer(player, computerId);
                }));
    }

    private void accessClick(Player player, long computerId, ItemStack item, String action) {
        if ("toggle-public".equals(action)) {
            database.submit(() -> security.togglePublic(player.getUniqueId(), computerId))
                    .whenComplete((operation, error) -> main(player, error, () -> {
                        if (operation.result() == SecurityResult.SUCCESS) {
                            registry.upsert(operation.value().orElseThrow());
                            openAccess(player, computerId);
                        } else sendResult(player, operation.result());
                    }));
            return;
        }
        if (!("grant".equals(action) || "revoke".equals(action))) return;
        String rawTarget = item.getItemMeta().getPersistentDataContainer()
                .get(targetKey, PersistentDataType.STRING);
        if (rawTarget == null) return;
        UUID target;
        try {
            target = UUID.fromString(rawTarget);
        } catch (IllegalArgumentException exception) {
            return;
        }
        database.submit(() -> "grant".equals(action)
                        ? security.grantAccess(player.getUniqueId(), computerId, target, System.currentTimeMillis())
                        : security.revokeAccess(player.getUniqueId(), computerId, target))
                .whenComplete((result, error) -> main(player, error, () -> {
                    sendResult(player, result);
                    if (result == SecurityResult.SUCCESS) openAccess(player, computerId);
                }));
    }

    private ItemStack cameraItem(Player player, SecurityCamera camera) {
        ItemStack item = items.camera(messages.locale(player));
        item.editMeta(meta -> {
            meta.displayName(Component.text(camera.name()));
            meta.getPersistentDataContainer().set(cameraIdKey, PersistentDataType.LONG, camera.id());
            meta.lore(List.of(messages.component(player, "security.camera-coordinates",
                    Placeholder.unparsed("world", camera.world()),
                    Placeholder.unparsed("x", Integer.toString((int) Math.floor(camera.x()))),
                    Placeholder.unparsed("y", Integer.toString((int) Math.floor(camera.y()))),
                    Placeholder.unparsed("z", Integer.toString((int) Math.floor(camera.z()))))));
        });
        return item;
    }

    private ItemStack playerItem(UUID playerId, String playerName, String action) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> {
            meta.displayName(Component.text(playerName));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, playerId.toString());
        });
        return item;
    }

    private ItemStack action(Material material, String action, Component name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        });
        return item;
    }

    private void sendResult(Player player, SecurityResult result) {
        messages.send(player, "security.result." + result.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        var overflow = player.getInventory().addItem(item);
        for (ItemStack stack : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    private void main(Player player, Throwable error, Runnable success) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not update security-camera controls", error);
                messages.send(player, "error.database");
                return;
            }
            success.run();
        });
    }

    private enum MenuType {
        CAMERA,
        COMPUTER,
        GROUPS,
        CAMERAS,
        ACCESS
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final long id;
        private Inventory inventory;
        private String groupName;
        private List<Long> cameraIds = List.of();

        private MenuHolder(MenuType type, long id) {
            this.type = type;
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record ComputerOpen(boolean allowed, ComputerDashboard dashboard) {
    }

    private record CameraSelection(ComputerDashboard dashboard, List<SecurityCamera> owned) {
    }
}
