package dev.opencivitas.command;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.shop.ShopListener;
import dev.opencivitas.shop.ShopRepository;
import dev.opencivitas.shop.ShopResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class ShopSignCommand implements CommandExecutor, TabCompleter {
    private static final int TARGET_DISTANCE = 6;

    private final JavaPlugin plugin;
    private final Database database;
    private final ShopRepository shops;
    private final ShopListener listener;
    private final MessageService messages;

    public ShopSignCommand(
            JavaPlugin plugin,
            Database database,
            ShopRepository shops,
            ShopListener listener,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.shops = shops;
        this.listener = listener;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("iteminfo")) {
            return itemInfo(player, args);
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("ui")) {
            messages.send(sender, "error.usage", Placeholder.unparsed("usage", "/sign ui"));
            return true;
        }
        Block target = player.getTargetBlockExact(TARGET_DISTANCE);
        if (target == null || !(target.getState() instanceof Sign sign)) {
            messages.send(player, "shops.editor.look-at-shop");
            return true;
        }
        Long shopId = listener.shopId(sign);
        if (shopId == null) {
            messages.send(player, "shops.editor.not-shop");
            return true;
        }
        database.submit(() -> shops.canManage(player.getUniqueId(), shopId))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not authorize chest shop editor", error);
                        messages.send(player, "error.database");
                        return;
                    }
                    if (result != ShopResult.SUCCESS) {
                        messages.send(player, result == ShopResult.NO_PERMISSION
                                ? "shops.editor.no-permission" : "shops.editor.inactive");
                        return;
                    }
                    if (!(target.getState() instanceof Sign current)
                            || !shopId.equals(listener.shopId(current))) {
                        messages.send(player, "shops.editor.inactive");
                        return;
                    }
                    if (!listener.openEditor(player, current, shopId)) {
                        messages.send(player, "shops.transaction.busy");
                    }
                }));
        return true;
    }

    private boolean itemInfo(Player player, String[] args) {
        if (args.length != 0) {
            messages.send(player, "error.usage", Placeholder.unparsed("usage", "/iteminfo"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Material material = held.getType();
        if (material.isAir() || !material.isItem()) {
            messages.send(player, "shops.item-info.empty");
            return true;
        }
        messages.send(player, "shops.item-info.value",
                Placeholder.unparsed("item", material.name().toLowerCase(Locale.ROOT)));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("sign") && args.length == 1
                && "ui".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("ui");
        }
        return List.of();
    }
}
