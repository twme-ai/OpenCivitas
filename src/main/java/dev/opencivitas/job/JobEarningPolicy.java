package dev.opencivitas.job;

import dev.opencivitas.economy.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

public final class JobEarningPolicy {
    private static final long MAXIMUM_ACTION_CENTS = 100_000_000L;
    private static final long MINIMUM_INTERVAL_SECONDS = 1;
    private static final long MAXIMUM_INTERVAL_SECONDS = 86_400;

    private final long payoutIntervalMillis;
    private final Map<JobActionType, Map<String, List<JobEarningRule>>> exactRules;
    private final Map<JobActionType, List<JobEarningRule>> wildcardRules;
    private final int ruleCount;

    public JobEarningPolicy(JavaPlugin plugin, JobRegistry jobs) {
        File file = new File(plugin.getDataFolder(), "job-earnings.yml");
        if (!file.exists()) plugin.saveResource("job-earnings.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        long intervalSeconds = configuration.getLong("payout-interval-seconds", 900);
        if (intervalSeconds < MINIMUM_INTERVAL_SECONDS || intervalSeconds > MAXIMUM_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("payout-interval-seconds must be from 1 to 86400");
        }
        payoutIntervalMillis = Math.multiplyExact(intervalSeconds, 1_000);

        ConfigurationSection configuredJobs = configuration.getConfigurationSection("jobs");
        if (configuredJobs == null || configuredJobs.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("job-earnings.yml must define at least one job");
        }
        List<JobEarningRule> loaded = new ArrayList<>();
        for (String rawJob : configuredJobs.getKeys(false)) {
            String job = rawJob.toLowerCase(Locale.ROOT);
            if (jobs.find(job).isEmpty()) {
                throw new IllegalArgumentException("Unknown earning job: " + rawJob);
            }
            ConfigurationSection actions = configuredJobs.getConfigurationSection(rawJob);
            if (actions == null) throw new IllegalArgumentException("Missing earning actions for " + rawJob);
            for (String rawAction : actions.getKeys(false)) {
                JobActionType action;
                try {
                    action = JobActionType.valueOf(rawAction.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown job earning action: " + rawAction, exception);
                }
                ConfigurationSection targets = actions.getConfigurationSection(rawAction);
                if (targets == null || targets.getKeys(false).isEmpty()) {
                    throw new IllegalArgumentException("Missing targets for " + rawJob + " " + rawAction);
                }
                for (String rawTarget : targets.getKeys(false)) {
                    String target = normalizeTarget(action, rawTarget);
                    ConfigurationSection values = targets.getConfigurationSection(rawTarget);
                    if (values == null) {
                        throw new IllegalArgumentException("Invalid earning rule for " + rawTarget);
                    }
                    long minimum = amount(values, "minimum");
                    long maximum = amount(values, "maximum");
                    if (maximum < minimum || maximum > MAXIMUM_ACTION_CENTS) {
                        throw new IllegalArgumentException("Invalid earning range for " + rawTarget);
                    }
                    loaded.add(new JobEarningRule(
                            job, action, target, minimum, maximum, values.getDouble("chance", 1)));
                }
            }
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No job earning rules were loaded");
        ruleCount = loaded.size();
        exactRules = indexExact(loaded);
        wildcardRules = indexWildcards(loaded);
    }

    JobEarningPolicy(long payoutIntervalMillis, List<JobEarningRule> rules) {
        if (payoutIntervalMillis < 1 || rules.isEmpty()) {
            throw new IllegalArgumentException("A positive payout interval and rules are required");
        }
        this.payoutIntervalMillis = payoutIntervalMillis;
        ruleCount = rules.size();
        exactRules = indexExact(rules);
        wildcardRules = indexWildcards(rules);
    }

    public long payoutIntervalMillis() {
        return payoutIntervalMillis;
    }

    public int ruleCount() {
        return ruleCount;
    }

    public boolean hasRules(JobActionType action, String targetKey) {
        String target = targetKey.toUpperCase(Locale.ROOT);
        return !exactRules.getOrDefault(action, Map.of()).getOrDefault(target, List.of()).isEmpty()
                || !wildcardRules.getOrDefault(action, List.of()).isEmpty();
    }

    public List<JobEarningCandidate> roll(
            JobActionType action, String targetKey, RandomGenerator random) {
        String target = targetKey.toUpperCase(Locale.ROOT);
        Map<String, JobEarningRule> selected = new LinkedHashMap<>();
        for (JobEarningRule rule : wildcardRules.getOrDefault(action, List.of())) {
            selected.put(rule.jobId(), rule);
        }
        for (JobEarningRule rule : exactRules.getOrDefault(action, Map.of())
                .getOrDefault(target, List.of())) {
            selected.put(rule.jobId(), rule);
        }
        List<JobEarningCandidate> candidates = new ArrayList<>();
        for (JobEarningRule rule : selected.values()) {
            rule.roll(random).ifPresent(amount -> candidates.add(new JobEarningCandidate(
                    rule.jobId(), action, target, amount)));
        }
        return List.copyOf(candidates);
    }

    public long nextPayoutAt(long now) {
        return Math.multiplyExact(Math.floorDiv(now, payoutIntervalMillis) + 1, payoutIntervalMillis);
    }

    private static long amount(ConfigurationSection values, String key) {
        String configured = values.getString(key);
        if (configured == null) throw new IllegalArgumentException("Missing earning " + key);
        return Money.parsePositiveCents(configured);
    }

    private static String normalizeTarget(JobActionType action, String rawTarget) {
        if (rawTarget.equals("*")) return "*";
        String target = rawTarget.toUpperCase(Locale.ROOT);
        if (action == JobActionType.BREAK) {
            Material material = Material.matchMaterial(target);
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("Unknown earning block: " + rawTarget);
            }
            return material.name();
        }
        EntityType entity;
        try {
            entity = EntityType.valueOf(target);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown earning entity: " + rawTarget, exception);
        }
        if (!entity.isAlive() || entity == EntityType.PLAYER) {
            throw new IllegalArgumentException("Earning target must be a non-player living entity: " + rawTarget);
        }
        return entity.name();
    }

    private static Map<JobActionType, Map<String, List<JobEarningRule>>> indexExact(
            List<JobEarningRule> rules) {
        Map<JobActionType, Map<String, List<JobEarningRule>>> indexed = new EnumMap<>(JobActionType.class);
        for (JobEarningRule rule : rules) {
            if (rule.targetKey().equals("*")) continue;
            indexed.computeIfAbsent(rule.actionType(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(rule.targetKey(), ignored -> new ArrayList<>()).add(rule);
        }
        Map<JobActionType, Map<String, List<JobEarningRule>>> immutable = new EnumMap<>(JobActionType.class);
        indexed.forEach((action, targets) -> {
            Map<String, List<JobEarningRule>> copied = new LinkedHashMap<>();
            targets.forEach((target, targetRules) -> copied.put(target, List.copyOf(targetRules)));
            immutable.put(action, Map.copyOf(copied));
        });
        return Map.copyOf(immutable);
    }

    private static Map<JobActionType, List<JobEarningRule>> indexWildcards(List<JobEarningRule> rules) {
        Map<JobActionType, List<JobEarningRule>> indexed = new EnumMap<>(JobActionType.class);
        for (JobEarningRule rule : rules) {
            if (rule.targetKey().equals("*")) {
                indexed.computeIfAbsent(rule.actionType(), ignored -> new ArrayList<>()).add(rule);
            }
        }
        Map<JobActionType, List<JobEarningRule>> immutable = new EnumMap<>(JobActionType.class);
        indexed.forEach((action, actionRules) -> immutable.put(action, List.copyOf(actionRules)));
        return Map.copyOf(immutable);
    }
}
