package dev.opencivitas.protection;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public final class ProtectionPolicy {
    private static final Set<Material> AUTO_PROTECT = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.LECTERN, Material.HOPPER);

    private final boolean enabled;
    private final int maximumProtections;
    private final int maximumGroups;
    private final int maximumGroupMembers;
    private final long autoCloseTicks;
    private final String passwordPepper;

    public ProtectionPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "block-protection.yml");
        if (!file.exists()) plugin.saveResource("block-protection.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        enabled = configuration.getBoolean("enabled", true);
        maximumProtections = bounded(configuration.getInt("limits.protections-per-player", 5_000),
                1, 100_000, "limits.protections-per-player");
        maximumGroups = bounded(configuration.getInt("limits.groups-per-player", 32),
                1, 500, "limits.groups-per-player");
        maximumGroupMembers = bounded(configuration.getInt("limits.members-per-group", 100),
                1, 5_000, "limits.members-per-group");
        autoCloseTicks = bounded(configuration.getLong("auto-close-ticks", 60),
                1, 1_200, "auto-close-ticks");
        String configuredPepper = configuration.getString("password-pepper", "").trim();
        if (configuredPepper.isEmpty()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            configuredPepper = Base64.getEncoder().encodeToString(generated);
            configuration.set("password-pepper", configuredPepper);
            try {
                configuration.save(file);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not persist the generated password pepper", exception);
            }
        }
        try {
            if (Base64.getDecoder().decode(configuredPepper).length != 32) {
                throw new IllegalArgumentException("password-pepper must decode to 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("password-pepper must be valid base64 for 32 bytes", exception);
        }
        passwordPepper = configuredPepper;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean protectable(Material material) {
        if (!enabled) return false;
        String name = material.name();
        return AUTO_PROTECT.contains(material)
                || material == Material.LEVER
                || name.endsWith("_BUTTON")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_SIGN")
                || name.endsWith("_HANGING_SIGN");
    }

    public boolean autoProtect(Material material) {
        return enabled && AUTO_PROTECT.contains(material);
    }

    public int maximumProtections() {
        return maximumProtections;
    }

    public int maximumGroups() {
        return maximumGroups;
    }

    public int maximumGroupMembers() {
        return maximumGroupMembers;
    }

    public long autoCloseTicks() {
        return autoCloseTicks;
    }

    public String passwordPepper() {
        return passwordPepper;
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }
}
