package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.health.BlockPosition;
import dev.opencivitas.health.HealthConditionDefinition;
import dev.opencivitas.health.HealthItems;
import dev.opencivitas.health.HealthOperation;
import dev.opencivitas.health.HealthRegistry;
import dev.opencivitas.health.HealthRepository;
import dev.opencivitas.health.HealthResult;
import dev.opencivitas.health.MedicalCall;
import dev.opencivitas.health.TreatmentDefinition;
import dev.opencivitas.message.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class HealthCommand implements CommandExecutor, TabCompleter, Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final HealthRepository health;
    private final HealthRegistry registry;
    private final HealthItems items;
    private final MessageService messages;
    private final String currencySymbol;
    private final Set<UUID> departmentChat = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public HealthCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            HealthRepository health,
            HealthRegistry registry,
            HealthItems items,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.health = health;
        this.registry = registry;
        this.items = items;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "health" -> health(sender, args);
            case "doctor-attend" -> attend(sender, args);
            case "bulkbill" -> bulkBill(sender, args);
            case "doh" -> departmentChat(sender, args);
            default -> false;
        };
    }

    private boolean health(CommandSender sender, String[] args) {
        if (args.length == 0) return usage(sender,
                "/health <symptoms|temperature|list|diagnose|call|calls|buy|monitor|pharmacy|medicine|expose>");
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "symptoms" -> symptoms(sender, tail);
            case "temperature" -> temperature(sender, tail);
            case "list" -> list(sender, tail);
            case "diagnose" -> diagnose(sender, tail);
            case "call" -> call(sender, tail);
            case "calls" -> calls(sender, tail);
            case "buy" -> buy(sender, tail);
            case "monitor" -> monitor(sender, tail);
            case "pharmacy" -> pharmacy(sender, tail);
            case "medicine" -> medicine(sender, tail);
            case "expose" -> expose(sender, tail);
            default -> usage(sender,
                    "/health <symptoms|temperature|list|diagnose|call|calls|buy|monitor|pharmacy|medicine|expose>");
        };
    }

    private boolean symptoms(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/health symptoms");
        complete(sender, database.submit(() -> health.activeConditions(player.getUniqueId())), active -> {
            LinkedHashSet<String> symptoms = new LinkedHashSet<>();
            for (var patientCondition : active) registry.condition(patientCondition.conditionId())
                    .ifPresent(condition -> symptoms.addAll(condition.symptoms()));
            messages.send(sender, "health.symptoms-header");
            if (symptoms.isEmpty()) messages.send(sender, "health.symptoms-none");
            else symptoms.forEach(symptom -> messages.send(sender, "health.symptom-entry",
                    Placeholder.component("symptom", symptom(sender, symptom))));
        });
        return true;
    }

    private boolean temperature(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/health temperature");
        complete(sender, database.submit(() -> health.temperature(
                player.getUniqueId(), registry.normalTemperature(), System.currentTimeMillis())), operation -> {
            if (operation.result() != HealthResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            messages.send(sender, "health.temperature",
                    Placeholder.unparsed("temperature", formatTemperature(operation.value().orElseThrow())));
        });
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (args.length != 1) return usage(sender, "/health list <diseases|symptoms|treatments>");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "diseases", "conditions" -> {
                messages.send(sender, "health.diseases-header");
                for (HealthConditionDefinition condition : registry.conditions()) messages.send(
                        sender, "health.catalog-entry", Placeholder.unparsed("id", condition.id()),
                        Placeholder.unparsed("name", registry.name(condition, messages.locale(sender))));
                yield true;
            }
            case "symptoms" -> {
                messages.send(sender, "health.symptoms-list-header");
                registry.conditions().stream().flatMap(condition -> condition.symptoms().stream()).distinct().sorted()
                        .forEach(symptom -> messages.send(sender, "health.catalog-entry",
                                Placeholder.unparsed("id", symptom),
                                Placeholder.component("name", symptom(sender, symptom))));
                yield true;
            }
            case "treatments" -> {
                messages.send(sender, "health.treatments-header");
                for (TreatmentDefinition treatment : registry.treatments()) messages.send(
                        sender, "health.catalog-entry", Placeholder.unparsed("id", treatment.id()),
                        Placeholder.unparsed("name", registry.name(treatment, messages.locale(sender))));
                yield true;
            }
            default -> usage(sender, "/health list <diseases|symptoms|treatments>");
        };
    }

    private boolean diagnose(CommandSender sender, String[] args) {
        if (!(sender instanceof Player doctor)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/health diagnose <player>");
        complete(sender, database.submit(() -> {
            if (!health.isDoctor(doctor.getUniqueId())) return new Diagnosis(HealthResult.NOT_DOCTOR, null, List.of());
            CitizenProfile patient = citizens.findByName(args[0]).orElse(null);
            if (patient == null) return new Diagnosis(HealthResult.CITIZEN_NOT_FOUND, null, List.of());
            return new Diagnosis(HealthResult.SUCCESS, patient, health.activeConditions(patient.uuid()));
        }), diagnosis -> {
            if (diagnosis.result() != HealthResult.SUCCESS) {
                error(sender, diagnosis.result());
                return;
            }
            messages.send(sender, "health.diagnosis-header",
                    Placeholder.unparsed("player", diagnosis.patient().lastName()));
            if (diagnosis.conditions().isEmpty()) messages.send(sender, "health.diagnosis-none");
            for (var active : diagnosis.conditions()) registry.condition(active.conditionId()).ifPresent(condition ->
                    messages.send(sender, "health.diagnosis-entry",
                            Placeholder.unparsed("condition", registry.name(condition, messages.locale(sender))),
                            Placeholder.unparsed("source", active.source())));
        });
        return true;
    }

    private boolean call(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/health call");
        Location location = player.getLocation();
        complete(sender, database.submit(() -> {
            HealthOperation<MedicalCall> operation = health.call(player.getUniqueId(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(), System.currentTimeMillis());
            return new CallResult(operation, operation.result() == HealthResult.SUCCESS
                    ? health.doctorIds() : List.of());
        }), result -> {
            if (result.operation().result() != HealthResult.SUCCESS) {
                error(sender, result.operation().result());
                return;
            }
            MedicalCall medicalCall = result.operation().value().orElseThrow();
            messages.send(sender, "health.call-filed", Placeholder.unparsed("id", Long.toString(medicalCall.id())));
            notifyDoctors(result.doctors(), "health.call-alert",
                    Placeholder.unparsed("player", medicalCall.patientName()),
                    Placeholder.unparsed("id", Long.toString(medicalCall.id())));
        });
        return true;
    }

    private boolean calls(CommandSender sender, String[] args) {
        if (!(sender instanceof Player doctor)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/health calls");
        complete(sender, database.submit(() -> health.isDoctor(doctor.getUniqueId())
                ? new CallsResult(HealthResult.SUCCESS, health.openCalls())
                : new CallsResult(HealthResult.NOT_DOCTOR, List.of())), result -> {
            if (result.result() != HealthResult.SUCCESS) {
                error(sender, result.result());
                return;
            }
            messages.send(sender, "health.calls-header");
            if (result.calls().isEmpty()) messages.send(sender, "health.calls-none");
            for (MedicalCall call : result.calls()) messages.send(sender, "health.call-entry",
                    Placeholder.unparsed("id", Long.toString(call.id())),
                    Placeholder.unparsed("player", call.patientName()),
                    Placeholder.unparsed("world", call.world()),
                    Placeholder.unparsed("x", Integer.toString((int) Math.floor(call.x()))),
                    Placeholder.unparsed("y", Integer.toString((int) Math.floor(call.y()))),
                    Placeholder.unparsed("z", Integer.toString((int) Math.floor(call.z()))),
                    Placeholder.unparsed("status", call.status().name()));
        });
        return true;
    }

    private boolean monitor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("opencivitas.health.configure")) return denied(sender);
        if (args.length != 1 || !(args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            return usage(sender, "/health monitor <add|remove>");
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            messages.send(sender, "health.monitor-no-block");
            return true;
        }
        BlockPosition position = position(block);
        boolean add = args[0].equalsIgnoreCase("add");
        complete(sender, database.submit(() -> add
                ? health.setMonitor(position, player.getUniqueId(), System.currentTimeMillis())
                : health.removeMonitor(position)), ignored -> messages.send(sender,
                add ? "health.monitor-added" : "health.monitor-removed"));
        return true;
    }

    private boolean pharmacy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("opencivitas.health.configure")) return denied(sender);
        if (args.length != 1 || !(args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            return usage(sender, "/health pharmacy <add|remove>");
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            messages.send(sender, "health.monitor-no-block");
            return true;
        }
        BlockPosition position = position(block);
        boolean add = args[0].equalsIgnoreCase("add");
        complete(sender, database.submit(() -> add
                ? health.setPharmacy(position, player.getUniqueId(), System.currentTimeMillis())
                : health.removePharmacy(position)), ignored -> messages.send(sender,
                add ? "health.pharmacy-added" : "health.pharmacy-removed"));
        return true;
    }

    private boolean buy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/health buy <treatment>");
        TreatmentDefinition treatment = registry.treatment(args[0]).orElse(null);
        if (treatment == null) {
            messages.send(sender, "health.unknown-treatment");
            return true;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            messages.send(sender, "health.pharmacy-not-found");
            return true;
        }
        BlockPosition counter = position(block);
        complete(sender, database.submit(() -> health.purchaseMedicine(
                player.getUniqueId(), treatment, counter, System.currentTimeMillis())), operation -> {
            if (operation.result() != HealthResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            player.getInventory().addItem(items.create(treatment, messages.locale(sender))).values()
                    .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            messages.send(sender, "health.pharmacy-purchased",
                    Placeholder.unparsed("treatment", registry.name(treatment, messages.locale(sender))),
                    Placeholder.unparsed("amount", Money.format(treatment.pharmacyCopayCents(), currencySymbol)));
        });
        return true;
    }

    private boolean medicine(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("opencivitas.health.configure")) return denied(sender);
        if (args.length != 1) return usage(sender, "/health medicine <treatment>");
        TreatmentDefinition treatment = registry.treatment(args[0]).orElse(null);
        if (treatment == null) {
            messages.send(sender, "health.unknown-treatment");
            return true;
        }
        player.getInventory().addItem(items.create(treatment, messages.locale(sender))).values()
                .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        messages.send(sender, "health.medicine-given",
                Placeholder.unparsed("treatment", registry.name(treatment, messages.locale(sender))));
        return true;
    }

    private boolean expose(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.health.configure")) return denied(sender);
        if (args.length != 2) return usage(sender, "/health expose <player> <condition>");
        HealthConditionDefinition condition = registry.condition(args[1]).orElse(null);
        if (condition == null) {
            messages.send(sender, "health.unknown-condition");
            return true;
        }
        complete(sender, database.submit(() -> {
            CitizenProfile patient = citizens.findByName(args[0]).orElse(null);
            return patient == null
                    ? HealthOperation.<dev.opencivitas.health.PatientCondition>result(HealthResult.CITIZEN_NOT_FOUND)
                    : health.expose(patient.uuid(), condition, "administrative", System.currentTimeMillis());
        }), operation -> {
            if (operation.result() != HealthResult.SUCCESS) error(sender, operation.result());
            else messages.send(sender, "health.exposed",
                    Placeholder.unparsed("condition", registry.name(condition, messages.locale(sender))));
        });
        return true;
    }

    private boolean attend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player doctor)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/doctor-attend <player>");
        complete(sender, database.submit(() -> {
            CitizenProfile patient = citizens.findByName(args[0]).orElse(null);
            if (patient == null) return new AttendResult(
                    HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND), List.of());
            HealthOperation<MedicalCall> operation = health.claimCall(
                    patient.uuid(), doctor.getUniqueId(), System.currentTimeMillis());
            return new AttendResult(operation, operation.result() == HealthResult.SUCCESS
                    ? health.doctorIds() : List.of());
        }), result -> {
            if (result.operation().result() != HealthResult.SUCCESS) {
                error(sender, result.operation().result());
                return;
            }
            MedicalCall call = result.operation().value().orElseThrow();
            World world = Bukkit.getWorld(call.world());
            if (world == null) {
                releaseClaim(call, doctor);
                messages.send(sender, "health.call-world-missing");
                return;
            }
            doctor.teleportAsync(new Location(world, call.x(), call.y(), call.z())).thenAccept(teleported ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!teleported) {
                            releaseClaim(call, doctor);
                            messages.send(sender, "health.attend-failed");
                            return;
                        }
                        database.submit(() -> health.markAttended(
                                call.id(), doctor.getUniqueId(), System.currentTimeMillis())).exceptionally(error -> {
                            plugin.getLogger().log(Level.SEVERE, "Could not mark medical call attended", error);
                            return false;
                        });
                        messages.send(sender, "health.attended",
                                Placeholder.unparsed("player", call.patientName()));
                        notifyDoctors(result.doctors(), "health.attend-alert",
                                Placeholder.unparsed("doctor", doctor.getName()),
                                Placeholder.unparsed("player", call.patientName()));
                    }));
        });
        return true;
    }

    private void releaseClaim(MedicalCall call, Player doctor) {
        database.submit(() -> health.releaseCall(call.id(), doctor.getUniqueId())).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not release medical call claim", error);
            return false;
        });
    }

    private boolean bulkBill(CommandSender sender, String[] args) {
        if (!(sender instanceof Player doctor)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/bulkbill <patient>");
        complete(sender, database.submit(() -> {
            CitizenProfile patient = citizens.findByName(args[0]).orElse(null);
            return patient == null
                    ? HealthOperation.<dev.opencivitas.health.MedicareClaim>result(HealthResult.CITIZEN_NOT_FOUND)
                    : health.bulkBill(doctor.getUniqueId(), patient.uuid(), System.currentTimeMillis());
        }), operation -> {
            if (operation.result() != HealthResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            var claim = operation.value().orElseThrow();
            messages.send(sender, "health.medicare-paid",
                    Placeholder.unparsed("player", claim.patientName()),
                    Placeholder.unparsed("amount", Money.format(claim.benefitCents(), currencySymbol)));
        });
        return true;
    }

    private boolean departmentChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        complete(sender, database.submit(() -> health.isDoctor(player.getUniqueId())), doctor -> {
            if (!doctor) {
                error(sender, HealthResult.NOT_DOCTOR);
                return;
            }
            if (args.length == 0) {
                boolean enabled = departmentChat.add(player.getUniqueId());
                if (!enabled) departmentChat.remove(player.getUniqueId());
                messages.send(sender, enabled ? "health.doh-enabled" : "health.doh-disabled");
            } else {
                broadcastDepartment(player, Component.text(String.join(" ", args)));
            }
        });
        return true;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!departmentChat.contains(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        Component content = event.message();
        Bukkit.getScheduler().runTask(plugin, () -> broadcastDepartment(event.getPlayer(), content));
    }

    private void broadcastDepartment(Player sender, Component content) {
        database.submit(health::doctorIds).whenComplete((doctors, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not deliver Department of Health chat", error);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> notifyDoctors(doctors, "health.doh-message",
                    Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
        });
    }

    private void notifyDoctors(List<UUID> doctors, String key,
                               net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        for (UUID id : doctors) {
            Player doctor = Bukkit.getPlayer(id);
            if (doctor != null) messages.send(doctor, key, resolvers);
        }
    }

    private Component symptom(CommandSender sender, String symptom) {
        return messages.component(sender, "health.symptom." + symptom);
    }

    private void error(CommandSender sender, HealthResult result) {
        messages.send(sender, switch (result) {
            case CITIZEN_NOT_FOUND -> "error.player-not-found";
            case CONDITION_NOT_FOUND -> "health.unknown-condition";
            case ALREADY_AFFECTED -> "health.already-affected";
            case NO_TREATABLE_CONDITION -> "health.no-treatable-condition";
            case NOT_DOCTOR -> "health.not-doctor";
            case CALL_NOT_FOUND -> "health.call-not-found";
            case CALL_ALREADY_CLAIMED -> "health.call-already-claimed";
            case NO_MEDICARE_CLAIM -> "health.no-medicare-claim";
            case ACCOUNT_NOT_FOUND -> "error.player-not-found";
            case INSUFFICIENT_FUNDS -> "error.insufficient-funds";
            case PHARMACY_NOT_FOUND -> "health.pharmacy-not-found";
            case NOT_PHARMACY_TREATMENT -> "health.not-pharmacy-treatment";
            case SUCCESS -> "health.operation-complete";
        });
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future,
                              java.util.function.Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "Health operation failed", cause);
                messages.send(sender, "error.database");
            } else {
                success.accept(result);
            }
        }));
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    private boolean denied(CommandSender sender) {
        messages.send(sender, "error.no-permission");
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
        return true;
    }

    private static String formatTemperature(int milliCelsius) {
        return "%.1f".formatted(Locale.ROOT, milliCelsius / 1_000.0);
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("doctor-attend") || name.equals("bulkbill")) {
            return args.length == 1 ? onlineNames(args[0]) : List.of();
        }
        if (!name.equals("health")) return List.of();
        if (args.length == 1) return filter(List.of(
                "symptoms", "temperature", "list", "diagnose", "call", "calls",
                "buy", "monitor", "pharmacy", "medicine", "expose"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) return filter(
                List.of("diseases", "symptoms", "treatments"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("monitor")) return filter(
                List.of("add", "remove"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("pharmacy")) return filter(
                List.of("add", "remove"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) return filter(
                registry.treatments().stream().filter(treatment ->
                                treatment.careSetting() == dev.opencivitas.health.CareSetting.PHARMACY)
                        .map(TreatmentDefinition::id).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("medicine")) return filter(
                registry.treatments().stream().map(TreatmentDefinition::id).toList(), args[1]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("diagnose")
                || args[0].equalsIgnoreCase("expose"))) return onlineNames(args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("expose")) return filter(
                registry.conditions().stream().map(HealthConditionDefinition::id).toList(), args[2]);
        return List.of();
    }

    private static List<String> onlineNames(String prefix) {
        return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
    }

    private static List<String> filter(Collection<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted().toList();
    }

    private record Diagnosis(HealthResult result, CitizenProfile patient,
                             List<dev.opencivitas.health.PatientCondition> conditions) {
    }

    private record CallResult(HealthOperation<MedicalCall> operation, List<UUID> doctors) {
    }

    private record CallsResult(HealthResult result, List<MedicalCall> calls) {
    }

    private record AttendResult(HealthOperation<MedicalCall> operation, List<UUID> doctors) {
    }
}
