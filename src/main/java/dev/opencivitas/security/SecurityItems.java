package dev.opencivitas.security;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SecurityItems {
    private final NamespacedKey cameraKey;
    private final NamespacedKey computerKey;

    public SecurityItems(JavaPlugin plugin) {
        cameraKey = new NamespacedKey(plugin, "security_camera_item");
        computerKey = new NamespacedKey(plugin, "security_computer_item");
    }

    public void registerRecipes(JavaPlugin plugin) {
        register(plugin, "security_camera", camera("en_US"), new String[]{"RPP", "PDG", "LCP"},
                new Ingredient('R', Material.REDSTONE_BLOCK),
                new Ingredient('P', Material.HEAVY_WEIGHTED_PRESSURE_PLATE),
                new Ingredient('D', Material.DISPENSER),
                new Ingredient('G', Material.GLASS_PANE),
                new Ingredient('L', Material.DAYLIGHT_DETECTOR),
                new Ingredient('C', Material.COMPARATOR));
        register(plugin, "security_computer", computer("en_US"), new String[]{"ITI", "PGP", "RCR"},
                new Ingredient('I', Material.REDSTONE),
                new Ingredient('T', Material.REDSTONE_TORCH),
                new Ingredient('P', Material.HEAVY_WEIGHTED_PRESSURE_PLATE),
                new Ingredient('G', Material.GLASS_PANE),
                new Ingredient('R', Material.REPEATER),
                new Ingredient('C', Material.COMPARATOR));
    }

    public ItemStack camera(String locale) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> {
            meta.displayName(Component.text(locale.startsWith("zh") ? "安全攝影機" : "Security Camera")
                    .color(NamedTextColor.DARK_GRAY));
            meta.getPersistentDataContainer().set(cameraKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public ItemStack computer(String locale) {
        ItemStack item = new ItemStack(Material.NETHER_BRICK_STAIRS);
        item.editMeta(meta -> {
            meta.displayName(Component.text(locale.startsWith("zh") ? "監控電腦" : "Security Computer")
                    .color(NamedTextColor.DARK_RED));
            meta.getPersistentDataContainer().set(computerKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean isCamera(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(cameraKey, PersistentDataType.BYTE);
    }

    public boolean isComputer(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(computerKey, PersistentDataType.BYTE);
    }

    private static void register(
            JavaPlugin plugin, String key, ItemStack result, String[] shape, Ingredient... ingredients) {
        NamespacedKey recipeKey = new NamespacedKey(plugin, key);
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape(shape);
        for (Ingredient ingredient : ingredients) recipe.setIngredient(ingredient.key(), ingredient.material());
        Bukkit.addRecipe(recipe);
    }

    private record Ingredient(char key, Material material) {
    }
}
