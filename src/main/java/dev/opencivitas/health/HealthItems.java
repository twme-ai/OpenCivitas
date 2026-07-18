package dev.opencivitas.health;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;

public final class HealthItems {
    private static final String RECIPE_PREFIX = "medicine_";

    private final JavaPlugin plugin;
    private final HealthRegistry registry;
    private final NamespacedKey treatmentKey;

    public HealthItems(JavaPlugin plugin, HealthRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        treatmentKey = new NamespacedKey(plugin, "health_treatment");
    }

    public void registerRecipes() {
        for (TreatmentDefinition treatment : registry.treatments()) {
            if (treatment.careSetting() != CareSetting.HOSPITAL) continue;
            NamespacedKey key = recipeKey(treatment);
            Bukkit.removeRecipe(key);
            ShapelessRecipe recipe = new ShapelessRecipe(key, create(treatment, "en_US"));
            for (var ingredient : treatment.ingredients()) recipe.addIngredient(ingredient);
            Bukkit.addRecipe(recipe);
        }
    }

    public ItemStack create(TreatmentDefinition treatment, String locale) {
        ItemStack item = new ItemStack(treatment.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(registry.name(treatment, locale), NamedTextColor.AQUA));
            meta.lore(java.util.List.of(Component.text(
                    treatment.careSetting() == CareSetting.HOSPITAL
                            ? "Hospital treatment" : "Pharmacy medicine",
                    NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(treatmentKey, PersistentDataType.STRING, treatment.id());
        });
        return item;
    }

    public Optional<TreatmentDefinition> treatment(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        String id = item.getItemMeta().getPersistentDataContainer().get(treatmentKey, PersistentDataType.STRING);
        return id == null ? Optional.empty() : registry.treatment(id);
    }

    public boolean isHealthRecipe(org.bukkit.inventory.Recipe recipe) {
        return recipe instanceof Keyed keyed
                && keyed.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
                && keyed.getKey().getKey().startsWith(RECIPE_PREFIX);
    }

    private NamespacedKey recipeKey(TreatmentDefinition treatment) {
        return new NamespacedKey(plugin, RECIPE_PREFIX + treatment.id().replace('-', '_'));
    }
}
