package dev.opencivitas.health;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class HealthListener implements Listener {
    private static final long ENVIRONMENT_PERIOD_TICKS = 600L;

    private final JavaPlugin plugin;
    private final Database database;
    private final HealthRepository health;
    private final HealthRegistry registry;
    private final HealthItems items;
    private final MessageService messages;

    public HealthListener(
            JavaPlugin plugin,
            Database database,
            HealthRepository health,
            HealthRegistry registry,
            HealthItems items,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.health = health;
        this.registry = registry;
        this.items = items;
        this.messages = messages;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::environmentTick,
                ENVIRONMENT_PERIOD_TICKS, ENVIRONMENT_PERIOD_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player patient)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getFinalDamage() >= 6) {
            expose(patient, "fall", "fall");
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        ItemStack weapon = weapon(byEntity.getDamager());
        expose(patient, "blunt-attack", "attack");
        if (weapon != null && isCuttingWeapon(weapon.getType())) expose(patient, "cut", "cut");
        if (weapon != null && isMetalWeapon(weapon.getType())) expose(patient, "metal-wound", "metal wound");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (items.treatment(event.getItem()).isPresent()) return;
        Material type = event.getItem().getType();
        if (type == Material.POTION) expose(event.getPlayer(), "unsafe-water", "water");
        if (isRawMeat(type)) expose(event.getPlayer(), "raw-meat", "raw meat");
        if (type == Material.BEEF) expose(event.getPlayer(), "raw-beef", "raw beef");
        if (type == Material.HONEY_BOTTLE) expose(event.getPlayer(), "sugar", "sugary food");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!items.isHealthRecipe(event.getRecipe()) || !(event.getWhoClicked() instanceof Player player)) return;
        try {
            if (!health.isDoctor(player.getUniqueId())) {
                event.setCancelled(true);
                messages.send(player, "health.craft-doctor-only");
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.SEVERE, "Could not authorize medicine crafting", exception);
            messages.send(player, "error.database");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            BlockPosition position = new BlockPosition(event.getClickedBlock().getWorld().getName(),
                    event.getClickedBlock().getX(), event.getClickedBlock().getY(), event.getClickedBlock().getZ());
            try {
                if (health.isMonitor(position)) {
                    event.setCancelled(true);
                    fileCall(event.getPlayer());
                    return;
                }
                if (health.isPharmacy(position) && items.treatment(event.getItem()).isEmpty()) {
                    event.setCancelled(true);
                    messages.send(event.getPlayer(), "health.pharmacy-hint");
                    return;
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not read medical call monitor", exception);
                messages.send(event.getPlayer(), "error.database");
                return;
            }
        }
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        TreatmentDefinition treatment = items.treatment(event.getItem()).orElse(null);
        if (treatment == null) return;
        event.setCancelled(true);
        applyMedicine(event.getPlayer(), event.getPlayer(), treatment);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTreatOther(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player patient)) return;
        TreatmentDefinition treatment = items.treatment(event.getPlayer().getInventory().getItemInMainHand())
                .orElse(null);
        if (treatment == null) return;
        event.setCancelled(true);
        applyMedicine(event.getPlayer(), patient, treatment);
    }

    private void applyMedicine(Player practitioner, Player patient, TreatmentDefinition treatment) {
        ItemStack reserved = reserveOne(practitioner);
        if (reserved == null) return;
        List<String> matching = registry.conditions().stream()
                .filter(condition -> condition.treatmentId().equals(treatment.id()))
                .map(HealthConditionDefinition::id).toList();
        database.submit(() -> health.treat(patient.getUniqueId(), practitioner.getUniqueId(),
                treatment, matching, System.currentTimeMillis())).whenComplete((operation, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        restore(practitioner, reserved);
                        plugin.getLogger().log(Level.SEVERE, "Could not administer health treatment", error);
                        messages.send(practitioner, "error.database");
                        return;
                    }
                    if (operation.result() != HealthResult.SUCCESS) {
                        restore(practitioner, reserved);
                        messages.send(practitioner, switch (operation.result()) {
                            case NOT_DOCTOR -> "health.not-doctor";
                            case NO_TREATABLE_CONDITION -> "health.no-treatable-condition";
                            case CITIZEN_NOT_FOUND, ACCOUNT_NOT_FOUND -> "error.player-not-found";
                            case INSUFFICIENT_FUNDS -> "error.insufficient-funds";
                            case PHARMACY_NOT_FOUND -> "health.pharmacy-not-found";
                            case NOT_PHARMACY_TREATMENT -> "health.not-pharmacy-treatment";
                            default -> "health.operation-failed";
                        });
                        return;
                    }
                    MedicalTreatment administered = operation.value().orElseThrow();
                    messages.send(practitioner, "health.treatment-administered",
                            Placeholder.unparsed("player", patient.getName()),
                            Placeholder.unparsed("treatment", registry.name(treatment, messages.locale(practitioner))));
                    if (!patient.getUniqueId().equals(practitioner.getUniqueId())) messages.send(
                            patient, "health.treatment-received",
                            Placeholder.unparsed("doctor", practitioner.getName()),
                            Placeholder.unparsed("treatment", registry.name(treatment, messages.locale(patient))));
                    clearEffectsFor(patient, administered.conditionId());
                }));
    }

    private void expose(Player patient, String exposure, String source) {
        for (HealthConditionDefinition condition : registry.exposedBy(exposure)) {
            double chance = condition.exposureChances().getOrDefault(exposure, 0.0);
            if (!roll(chance)) continue;
            expose(patient.getUniqueId(), condition, source);
        }
    }

    private void expose(UUID patientId, HealthConditionDefinition condition, String source) {
        database.submit(() -> health.expose(patientId, condition, source, System.currentTimeMillis()))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not record health exposure", error);
                        return;
                    }
                    if (operation.result() != HealthResult.SUCCESS) return;
                    Player patient = Bukkit.getPlayer(patientId);
                    if (patient != null) messages.send(patient, "health.new-symptoms");
                }));
    }

    private void fileCall(Player patient) {
        Location location = patient.getLocation();
        database.submit(() -> {
            HealthOperation<MedicalCall> operation = health.call(patient.getUniqueId(),
                    location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                    System.currentTimeMillis());
            return new CallAndDoctors(operation, operation.result() == HealthResult.SUCCESS
                    ? health.doctorIds() : List.of());
        }).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not create medical call", error);
                messages.send(patient, "error.database");
                return;
            }
            MedicalCall call = result.operation().value().orElse(null);
            if (call == null) return;
            messages.send(patient, "health.call-filed", Placeholder.unparsed("id", Long.toString(call.id())));
            for (UUID doctorId : result.doctors()) {
                Player doctor = Bukkit.getPlayer(doctorId);
                if (doctor != null) messages.send(doctor, "health.call-alert",
                        Placeholder.unparsed("player", patient.getName()),
                        Placeholder.unparsed("id", Long.toString(call.id())));
            }
        }));
    }

    private void environmentTick() {
        List<Environment> environments = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) environments.add(environment(player));
        for (Environment environment : environments) database.submit(() -> tick(environment, environments))
                .whenComplete((state, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not update patient health", error);
                        return;
                    }
                    Player player = Bukkit.getPlayer(state.playerId());
                    if (player != null) applyEffects(player, state.conditions());
                }));
    }

    private PatientState tick(Environment environment, List<Environment> all) throws SQLException {
        long now = System.currentTimeMillis();
        int current = health.temperature(environment.playerId(), registry.normalTemperature(), now)
                .value().orElse(registry.normalTemperature());
        int next = nextTemperature(current, environment);
        health.updateTemperature(environment.playerId(), next, registry.normalTemperature(), now);

        exposeDuringTick(environment, "ambient", "environment", now);
        if (environment.wet()) exposeDuringTick(environment, "wet", "prolonged water exposure", now);
        if (environment.jungle()) exposeDuringTick(environment, "jungle", "jungle exposure", now);
        if (environment.chickenNearby()) exposeDuringTick(environment, "chicken", "sick chicken", now);
        if (environment.cowNearby()) exposeDuringTick(environment, "cow", "sick cattle", now);
        if (current < 33_000 || environment.cold()) exposeDuringTick(environment, "cold", "cold exposure", now);
        if (next <= registry.coldThreshold()) exposeDuringTick(environment, "cold-extreme", "low temperature", now);
        if (next >= registry.hotThreshold()) exposeDuringTick(environment, "heat", "high temperature", now);

        List<PatientCondition> active = health.activeConditions(environment.playerId());
        for (PatientCondition sourceCondition : active) {
            HealthConditionDefinition definition = registry.condition(sourceCondition.conditionId()).orElse(null);
            if (definition == null || !definition.contagious()) continue;
            for (Environment target : all) {
                if (target.playerId().equals(environment.playerId())
                        || !target.world().equals(environment.world())) continue;
                double dx = target.x() - environment.x();
                double dy = target.y() - environment.y();
                double dz = target.z() - environment.z();
                if (dx * dx + dy * dy + dz * dz <= definition.transmissionRadius()
                        * definition.transmissionRadius() && roll(definition.transmissionChance())) {
                    health.expose(target.playerId(), definition,
                            "contact with " + environment.playerName(), now);
                }
            }
        }
        return new PatientState(environment.playerId(), health.activeConditions(environment.playerId()));
    }

    private void exposeDuringTick(Environment environment, String exposure, String source, long now)
            throws SQLException {
        for (HealthConditionDefinition condition : registry.exposedBy(exposure)) if (roll(
                condition.exposureChances().getOrDefault(exposure, 0.0))) {
            health.expose(environment.playerId(), condition, source, now);
        }
    }

    private int nextTemperature(int current, Environment environment) {
        int next;
        if (environment.hot()) next = current + registry.temperatureStep();
        else if (environment.cold() || environment.wet()) next = current - registry.temperatureStep();
        else if (current < registry.normalTemperature()) next = Math.min(
                registry.normalTemperature(), current + registry.temperatureStep());
        else next = Math.max(registry.normalTemperature(), current - registry.temperatureStep());
        return Math.max(registry.minimumTemperature(), Math.min(registry.maximumTemperature(), next));
    }

    private Environment environment(Player player) {
        String biome = player.getLocation().getBlock().getBiome().getKey().getKey();
        boolean nether = player.getWorld().getEnvironment() == World.Environment.NETHER;
        boolean hot = nether || containsAny(biome, "desert", "badlands", "savanna");
        boolean cold = player.getFreezeTicks() > 0 || containsAny(
                biome, "snow", "frozen", "ice_spikes", "cold_ocean", "grove");
        boolean wet = player.isInWater();
        boolean jungle = biome.contains("jungle");
        boolean chicken = false;
        boolean cow = false;
        for (Entity nearby : player.getNearbyEntities(5, 5, 5)) {
            if (nearby instanceof Chicken) chicken = true;
            if (nearby instanceof Cow) cow = true;
        }
        Location location = player.getLocation();
        return new Environment(player.getUniqueId(), player.getName(), player.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), hot, cold, wet, jungle, chicken, cow);
    }

    private void applyEffects(Player player, List<PatientCondition> active) {
        Set<String> symptoms = new LinkedHashSet<>();
        for (PatientCondition condition : active) registry.condition(condition.conditionId())
                .ifPresent(definition -> symptoms.addAll(definition.symptoms()));
        for (String symptom : symptoms) {
            PotionEffectType type = effect(symptom);
            if (type != null) player.addPotionEffect(new PotionEffect(type, 700, 0, true, false, true));
        }
    }

    private void clearEffectsFor(Player player, String conditionId) {
        registry.condition(conditionId).ifPresent(condition -> {
            for (String symptom : condition.symptoms()) {
                PotionEffectType type = effect(symptom);
                if (type != null) player.removePotionEffect(type);
            }
        });
    }

    private static PotionEffectType effect(String symptom) {
        return switch (symptom) {
            case "leg-pain", "slowed-movement", "webbed-feet" -> PotionEffectType.SLOWNESS;
            case "confusion", "vomiting", "stomach-ache", "diarrhoea" -> PotionEffectType.NAUSEA;
            case "blindness" -> PotionEffectType.BLINDNESS;
            case "fatigue", "headache", "severe-headache", "low-temperature", "shivering" -> PotionEffectType.WEAKNESS;
            case "dehydration", "high-temperature" -> PotionEffectType.HUNGER;
            case "clogged-hearing" -> PotionEffectType.DARKNESS;
            default -> null;
        };
    }

    private static ItemStack reserveOne(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || hand.getAmount() <= 0) return null;
        ItemStack reserved = hand.clone();
        reserved.setAmount(1);
        if (hand.getAmount() == 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
        return reserved;
    }

    private static void restore(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private static ItemStack weapon(Entity damager) {
        if (damager instanceof Player player) return player.getInventory().getItemInMainHand();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player.getInventory().getItemInMainHand();
        }
        return null;
    }

    private static boolean isCuttingWeapon(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || material == Material.TRIDENT;
    }

    private static boolean isMetalWeapon(Material material) {
        String name = material.name();
        return (name.startsWith("IRON_") || name.startsWith("GOLDEN_") || name.startsWith("NETHERITE_"))
                && isCuttingWeapon(material);
    }

    private static boolean isRawMeat(Material material) {
        return switch (material) {
            case BEEF, PORKCHOP, CHICKEN, MUTTON, RABBIT, COD, SALMON, TROPICAL_FISH -> true;
            default -> false;
        };
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private static boolean roll(double chance) {
        return chance >= 1 || chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    private record CallAndDoctors(HealthOperation<MedicalCall> operation, List<UUID> doctors) {
    }

    private record Environment(
            UUID playerId,
            String playerName,
            String world,
            double x,
            double y,
            double z,
            boolean hot,
            boolean cold,
            boolean wet,
            boolean jungle,
            boolean chickenNearby,
            boolean cowNearby
    ) {
    }

    private record PatientState(UUID playerId, List<PatientCondition> conditions) {
    }
}
