package dev.opencivitas.family;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class FamilyPolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Duration friendRequestExpiry;
    private final Duration marriageProposalExpiry;
    private final boolean partnerPvpEnabledByDefault;
    private final List<String> lawyerJobs;
    private final List<String> lawyerQualifications;

    public FamilyPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "families.yml");
        if (!file.exists()) plugin.saveResource("families.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        friendRequestExpiry = Duration.ofHours(bounded(
                configuration.getLong("friend-request-expiry-hours", 72), 1, 8_760,
                "friend-request-expiry-hours"));
        marriageProposalExpiry = Duration.ofHours(bounded(
                configuration.getLong("marriage-proposal-expiry-hours", 168), 1, 8_760,
                "marriage-proposal-expiry-hours"));
        partnerPvpEnabledByDefault = configuration.getBoolean("partner-pvp-enabled-by-default", false);
        lawyerJobs = ids(configuration.getStringList("lawyer-jobs"), "lawyer-jobs");
        lawyerQualifications = ids(
                configuration.getStringList("lawyer-qualifications"), "lawyer-qualifications");
        if (lawyerJobs.isEmpty() && lawyerQualifications.isEmpty()) throw new IllegalArgumentException(
                "families.yml must configure a lawyer job or qualification");
    }

    FamilyPolicy(
            Duration friendRequestExpiry,
            Duration marriageProposalExpiry,
            boolean partnerPvpEnabledByDefault,
            List<String> lawyerJobs,
            List<String> lawyerQualifications
    ) {
        this.friendRequestExpiry = friendRequestExpiry;
        this.marriageProposalExpiry = marriageProposalExpiry;
        this.partnerPvpEnabledByDefault = partnerPvpEnabledByDefault;
        this.lawyerJobs = List.copyOf(lawyerJobs);
        this.lawyerQualifications = List.copyOf(lawyerQualifications);
    }

    public Duration friendRequestExpiry() {
        return friendRequestExpiry;
    }

    public Duration marriageProposalExpiry() {
        return marriageProposalExpiry;
    }

    public boolean partnerPvpEnabledByDefault() {
        return partnerPvpEnabledByDefault;
    }

    public List<String> lawyerJobs() {
        return lawyerJobs;
    }

    public List<String> lawyerQualifications() {
        return lawyerQualifications;
    }

    private static List<String> ids(List<String> values, String path) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).peek(value -> {
            if (!ID.matcher(value).matches()) throw new IllegalArgumentException(
                    "Invalid id in " + path + ": " + value);
        }).distinct().toList();
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }
}
