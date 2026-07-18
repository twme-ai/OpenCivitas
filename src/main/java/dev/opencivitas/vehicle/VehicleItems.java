package dev.opencivitas.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class VehicleItems {
    private static final String RECIPE_PREFIX = "vehicle_";
    private static final long FUEL_UNITS = 4_000;
    private static final int REPAIR_POINTS = 40;

    private final JavaPlugin plugin;
    private final VehicleRegistry registry;
    private final NamespacedKey typeKey;
    private final NamespacedKey partKey;
    private final NamespacedKey fuelKey;
    private final NamespacedKey repairKey;
    private final NamespacedKey recipeKey;
    private final NamespacedKey storedFuelKey;
    private final NamespacedKey storedHealthKey;
    private final NamespacedKey storedTrunkKey;
    private final Map<String, ItemStack> parts = new LinkedHashMap<>();
    private final Map<String, VehicleRecipeView> recipeViews = new LinkedHashMap<>();

    public VehicleItems(JavaPlugin plugin, VehicleRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        typeKey = new NamespacedKey(plugin, "vehicle_type");
        partKey = new NamespacedKey(plugin, "vehicle_part");
        fuelKey = new NamespacedKey(plugin, "vehicle_fuel");
        repairKey = new NamespacedKey(plugin, "vehicle_repair");
        recipeKey = new NamespacedKey(plugin, "vehicle_recipe_view");
        storedFuelKey = new NamespacedKey(plugin, "vehicle_stored_fuel");
        storedHealthKey = new NamespacedKey(plugin, "vehicle_stored_health");
        storedTrunkKey = new NamespacedKey(plugin, "vehicle_stored_trunk");
        parts.put("wheel", part("wheel", Material.IRON_NUGGET, "Wheel", "車輪"));
        parts.put("engine", part("engine", Material.PISTON, "Engine", "引擎"));
        parts.put("battery", part("battery", Material.REDSTONE, "Battery", "電池"));
        parts.put("propeller", part("propeller", Material.FEATHER, "Propeller", "螺旋槳"));
    }

    public void registerRecipes() {
        recipeViews.clear();
        registerPart("wheel", new String[]{" I ", "IDI", " I "}, Map.of(
                'I', new ItemStack(Material.IRON_INGOT), 'D', new ItemStack(Material.BLACK_DYE)));
        registerPart("engine", new String[]{"IRI", "IPI", "III"}, Map.of(
                'I', new ItemStack(Material.IRON_INGOT), 'R', new ItemStack(Material.REDSTONE),
                'P', new ItemStack(Material.PISTON)));
        registerPart("battery", new String[]{"CRC", "RCR", "CRC"}, Map.of(
                'C', new ItemStack(Material.COPPER_INGOT), 'R', new ItemStack(Material.REDSTONE)));
        registerPart("propeller", new String[]{" F ", "FIF", " F "}, Map.of(
                'F', new ItemStack(Material.IRON_NUGGET), 'I', new ItemStack(Material.IRON_INGOT)));

        ItemStack fuel = fuel();
        register("fuel_coal", fuel, new String[]{"CEC", "EBE", "CEC"}, Map.of(
                'C', new ItemStack(Material.COAL), 'E', new ItemStack(Material.EMERALD),
                'B', new ItemStack(Material.BUCKET)));
        register("fuel_kelp", fuel, new String[]{"EKE", "KBK", "EKE"}, Map.of(
                'E', new ItemStack(Material.EMERALD), 'K', new ItemStack(Material.DRIED_KELP_BLOCK),
                'B', new ItemStack(Material.BUCKET)));
        register("repair_kit", repairKit(), new String[]{"ILI", "LSL", "ILI"}, Map.of(
                'I', new ItemStack(Material.IRON_INGOT), 'L', new ItemStack(Material.LEATHER),
                'S', new ItemStack(Material.SLIME_BALL)));

        for (VehicleDefinition definition : registry.all()) {
            Map<Character, ItemStack> ingredients = new LinkedHashMap<>();
            for (Map.Entry<Character, String> entry : definition.recipeIngredients().entrySet()) {
                ingredients.put(entry.getKey(), ingredient(entry.getValue()));
            }
            register("type_" + definition.id().replace('-', '_'), createVehicle(definition, "en_US"),
                    definition.recipeShape().toArray(String[]::new), ingredients);
        }
    }

    public ItemStack createVehicle(VehicleDefinition definition, String locale) {
        return createVehicle(definition, locale, 0, definition.maximumHealth());
    }

    public ItemStack createVehicle(
            VehicleDefinition definition, String locale, long storedFuel, int storedHealth) {
        return createVehicle(definition, locale, storedFuel, storedHealth, new byte[0]);
    }

    public ItemStack createVehicle(
            VehicleDefinition definition, String locale, long storedFuel, int storedHealth, byte[] storedTrunk) {
        ItemStack item = new ItemStack(definition.itemMaterial());
        item.editMeta(meta -> {
            meta.displayName(Component.text(registry.name(definition, locale), NamedTextColor.AQUA));
            meta.lore(List.of(
                    Component.text(definition.category().name().toLowerCase(Locale.ROOT) + " vehicle",
                            NamedTextColor.GRAY),
                    Component.text("License: " + definition.licenseId(), NamedTextColor.DARK_GRAY),
                    Component.text("Fuel: " + storedFuel + " / " + definition.maximumFuel(), NamedTextColor.GRAY),
                    Component.text("Health: " + storedHealth + " / " + definition.maximumHealth(),
                            NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, definition.id());
            meta.getPersistentDataContainer().set(storedFuelKey, PersistentDataType.LONG,
                    Math.max(0, Math.min(definition.maximumFuel(), storedFuel)));
            meta.getPersistentDataContainer().set(storedHealthKey, PersistentDataType.INTEGER,
                    Math.max(1, Math.min(definition.maximumHealth(), storedHealth)));
            if (storedTrunk != null && storedTrunk.length > 0) {
                meta.getPersistentDataContainer().set(
                        storedTrunkKey, PersistentDataType.BYTE_ARRAY, storedTrunk.clone());
            }
        });
        return item;
    }

    public ItemStack display(VehicleDefinition definition) {
        ItemStack display = new ItemStack(definition.displayMaterial());
        display.editMeta(meta -> meta.displayName(Component.text(
                registry.name(definition, "en_US"), NamedTextColor.AQUA)));
        return display;
    }

    public Optional<VehicleDefinition> vehicle(ItemStack item) {
        if (empty(item)) return Optional.empty();
        String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? Optional.empty() : registry.find(id);
    }

    public long storedFuel(ItemStack item, VehicleDefinition definition) {
        if (empty(item)) return 0;
        Long fuel = item.getItemMeta().getPersistentDataContainer().get(storedFuelKey, PersistentDataType.LONG);
        return fuel == null ? 0 : Math.max(0, Math.min(definition.maximumFuel(), fuel));
    }

    public int storedHealth(ItemStack item, VehicleDefinition definition) {
        if (empty(item)) return definition.maximumHealth();
        Integer health = item.getItemMeta().getPersistentDataContainer().get(
                storedHealthKey, PersistentDataType.INTEGER);
        return health == null ? definition.maximumHealth()
                : Math.max(1, Math.min(definition.maximumHealth(), health));
    }

    public byte[] storedTrunk(ItemStack item) {
        if (empty(item)) return new byte[0];
        byte[] storage = item.getItemMeta().getPersistentDataContainer().get(
                storedTrunkKey, PersistentDataType.BYTE_ARRAY);
        return storage == null ? new byte[0] : storage.clone();
    }

    public ItemStack fuel() {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Vehicle Fuel", NamedTextColor.GOLD));
            meta.lore(List.of(Component.text(FUEL_UNITS + " fuel units", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(fuelKey, PersistentDataType.LONG, FUEL_UNITS);
        });
        return item;
    }

    public long fuelAmount(ItemStack item) {
        if (empty(item)) return 0;
        Long amount = item.getItemMeta().getPersistentDataContainer().get(fuelKey, PersistentDataType.LONG);
        return amount == null ? 0 : amount;
    }

    public ItemStack repairKit() {
        ItemStack item = new ItemStack(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Vehicle Repair Kit", NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text(REPAIR_POINTS + " repair points", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(repairKey, PersistentDataType.INTEGER, REPAIR_POINTS);
        });
        return item;
    }

    public int repairAmount(ItemStack item) {
        if (empty(item)) return 0;
        Integer amount = item.getItemMeta().getPersistentDataContainer().get(repairKey, PersistentDataType.INTEGER);
        return amount == null ? 0 : amount;
    }

    public boolean isVehicleRecipe(org.bukkit.inventory.Recipe recipe) {
        return recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(namespace())
                && keyed.getKey().getKey().startsWith(RECIPE_PREFIX);
    }

    public Collection<VehicleRecipeView> recipes() {
        return List.copyOf(recipeViews.values());
    }

    public Optional<VehicleRecipeView> recipe(String id) {
        return Optional.ofNullable(recipeViews.get(id));
    }

    public ItemStack recipeIcon(VehicleRecipeView view) {
        ItemStack icon = view.result().clone();
        icon.editMeta(meta -> meta.getPersistentDataContainer().set(
                recipeKey, PersistentDataType.STRING, view.id()));
        return icon;
    }

    public Optional<String> recipeId(ItemStack item) {
        if (empty(item)) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(
                recipeKey, PersistentDataType.STRING));
    }

    private void registerPart(String id, String[] shape, Map<Character, ItemStack> ingredients) {
        register("part_" + id, parts.get(id).clone(), shape, ingredients);
    }

    private void register(String id, ItemStack result, String[] shape, Map<Character, ItemStack> ingredients) {
        NamespacedKey key = new NamespacedKey(plugin, RECIPE_PREFIX + id);
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape(shape);
        for (Map.Entry<Character, ItemStack> ingredient : ingredients.entrySet()) {
            recipe.setIngredient(ingredient.getKey(), choice(ingredient.getValue()));
        }
        Bukkit.addRecipe(recipe);
        List<ItemStack> grid = new ArrayList<>(9);
        for (String row : shape) for (char symbol : row.toCharArray()) {
            ItemStack item = ingredients.get(symbol);
            grid.add(item == null ? null : item.clone());
        }
        recipeViews.put(id, new VehicleRecipeView(id, result, grid));
    }

    private ItemStack ingredient(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("part:")) {
            ItemStack part = parts.get(normalized.substring("part:".length()));
            if (part == null) throw new IllegalArgumentException("Unknown vehicle part " + value);
            return part.clone();
        }
        Material material = Material.matchMaterial(value);
        if (material == null || material.isAir()) throw new IllegalArgumentException(
                "Invalid vehicle recipe material " + value);
        return new ItemStack(material);
    }

    private ItemStack part(String id, Material material, String english, String chinese) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(english, NamedTextColor.YELLOW));
            meta.lore(List.of(Component.text(chinese, NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, id);
        });
        return item;
    }

    private static RecipeChoice choice(ItemStack item) {
        return item.hasItemMeta() ? new RecipeChoice.ExactChoice(item) : new RecipeChoice.MaterialChoice(item.getType());
    }

    private String namespace() {
        return plugin.getName().toLowerCase(Locale.ROOT);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || !item.hasItemMeta();
    }
}
