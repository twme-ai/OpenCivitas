package dev.opencivitas.command;

import dev.opencivitas.business.Business;
import dev.opencivitas.business.BusinessLedgerEntry;
import dev.opencivitas.business.BusinessMember;
import dev.opencivitas.business.BusinessOffer;
import dev.opencivitas.business.BusinessOperation;
import dev.opencivitas.business.BusinessPermission;
import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.business.BusinessResult;
import dev.opencivitas.business.BusinessRole;
import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.shop.ShopRepository;
import dev.opencivitas.shop.ShopSale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class BusinessCommand implements CommandExecutor, TabCompleter {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");
    private static final Pattern ROLE_KEY = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final BusinessRepository businesses;
    private final ShopRepository shops;
    private final MessageService messages;
    private final String currencySymbol;
    private final int pageSize;
    private final long offerExpiryMillis;

    public BusinessCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            BusinessRepository businesses,
            ShopRepository shops,
            MessageService messages,
            String currencySymbol,
            int pageSize,
            long offerExpiryMillis
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.businesses = businesses;
        this.shops = shops;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.pageSize = pageSize;
        this.offerExpiryMillis = offerExpiryMillis;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            messages.send(sender, "business.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender, args);
            case "deposit" -> transfer(sender, args, TransferCommand.DEPOSIT);
            case "withdraw" -> transfer(sender, args, TransferCommand.WITHDRAW);
            case "pay" -> pay(sender, args);
            case "offer" -> offer(sender, args);
            case "accept" -> respondToOffer(sender, args, true);
            case "deny" -> respondToOffer(sender, args, false);
            case "offers" -> offers(sender, args);
            case "staff" -> staff(sender, args);
            case "fire" -> fire(sender, args);
            case "resign" -> resign(sender, args);
            case "role" -> setRole(sender, args);
            case "roles", "customroles" -> customRoles(sender, args);
            case "wage" -> setWage(sender, args);
            case "payroll" -> payroll(sender, args);
            case "transferproprietorship" -> transferProprietorship(sender, args);
            case "transactions" -> transactions(sender, args);
            case "sales" -> sales(sender, args);
            case "disband" -> disband(sender, args);
            default -> {
                messages.send(sender, "business.usage");
                yield true;
            }
        };
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business create <name>");
            return true;
        }
        String slug = args[1].toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            messages.send(sender, "business.invalid-name");
            return true;
        }
        String displayName = displayName(slug);
        complete(sender, database.submit(() -> businesses.create(player.getUniqueId(), slug, displayName)), result -> {
            switch (result) {
                case SUCCESS -> messages.send(sender, "business.created",
                        Placeholder.unparsed("business", displayName));
                case MISSING_QUALIFICATION -> messages.send(sender, "business.missing-qualification");
                case NAME_TAKEN -> messages.send(sender, "business.name-taken");
                default -> operationError(sender, result, slug);
            }
        });
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            usage(sender, "/business info <business>");
            return true;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[1], viewer) : Optional.empty();
            return new BusinessView(business, role);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[1]);
                return;
            }
            Business business = view.business().get();
            messages.send(sender, "business.info-header",
                    Placeholder.unparsed("business", business.displayName()),
                    Placeholder.unparsed("slug", business.slug()));
            messages.send(sender, "business.info-proprietor",
                    Placeholder.unparsed("player", business.proprietorName()));
            messages.send(sender, "business.info-status",
                    Placeholder.unparsed("status", business.status().name().toLowerCase(Locale.ROOT)));
            if (view.role().map(BusinessRole::canManageFunds).orElse(false) || auditor) {
                messages.send(sender, "business.info-balance",
                        Placeholder.unparsed("balance", Money.format(business.balanceCents(), currencySymbol)));
            }
        });
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "error.player-only");
                return true;
            }
            complete(sender, database.submit(() -> businesses.list(player.getUniqueId())),
                    result -> showBusinesses(sender, result));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            if (args.length > 3) {
                usage(sender, "/business list all [page]");
                return true;
            }
            Optional<Integer> page = page(sender, args.length == 3 ? args[2] : null);
            if (page.isEmpty()) {
                return true;
            }
            int offset;
            try {
                offset = Math.multiplyExact(page.get() - 1, pageSize);
            } catch (ArithmeticException exception) {
                messages.send(sender, "error.invalid-page");
                return true;
            }
            complete(sender, database.submit(() -> businesses.listAll(pageSize, offset)),
                    result -> showBusinesses(sender, result));
            return true;
        }
        if (args.length == 2) {
            if (!sender.hasPermission("opencivitas.business.list.others")) {
                messages.send(sender, "error.no-permission");
                return true;
            }
            complete(sender, database.submit(() -> {
                Optional<CitizenProfile> profile = citizens.findByName(args[1]);
                return new BusinessListView(
                        profile,
                        profile.isEmpty() ? List.of() : businesses.list(profile.get().uuid())
                );
            }), view -> {
                if (view.profile().isEmpty()) {
                    messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[1]));
                    return;
                }
                showBusinesses(sender, view.businesses());
            });
            return true;
        }
        usage(sender, "/business list [player|all [page]]");
        return true;
    }

    private void showBusinesses(CommandSender sender, List<Business> result) {
        messages.send(sender, "business.list-header");
        if (result.isEmpty()) {
            messages.send(sender, "business.list-empty");
            return;
        }
        for (Business business : result) {
            messages.send(sender, "business.list-entry",
                    Placeholder.unparsed("business", business.displayName()),
                    Placeholder.unparsed("slug", business.slug()));
        }
    }

    private boolean transfer(CommandSender sender, String[] args, TransferCommand command) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/business " + command.commandName + " <business> <amount>");
            return true;
        }
        Optional<Long> amount = amount(sender, args[2]);
        if (amount.isEmpty()) {
            return true;
        }
        CompletableFuture<BusinessOperation> request = database.submit(() -> command == TransferCommand.DEPOSIT
                ? businesses.deposit(player.getUniqueId(), args[1], amount.get())
                : businesses.withdraw(player.getUniqueId(), args[1], amount.get()));
        complete(sender, request, operation -> {
            if (operation.result() != BusinessResult.SUCCESS) {
                operationError(sender, operation.result(), args[1]);
                return;
            }
            String key = command == TransferCommand.DEPOSIT ? "business.deposited" : "business.withdrew";
            messages.send(sender, key,
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("amount", Money.format(amount.get(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.businessBalanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 4) {
            usage(sender, "/business pay <business> <player> <amount>");
            return true;
        }
        Optional<Long> amount = amount(sender, args[3]);
        if (amount.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> recipient = citizens.findByName(args[2]);
            if (recipient.isEmpty()) {
                return new BusinessPayment(Optional.empty(),
                        new BusinessOperation(BusinessResult.CITIZEN_NOT_FOUND, 0));
            }
            BusinessOperation operation = businesses.pay(
                    player.getUniqueId(), args[1], recipient.get().uuid(), amount.get());
            return new BusinessPayment(recipient, operation);
        }), payment -> {
            if (payment.recipient().isEmpty()) {
                messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[2]));
                return;
            }
            if (payment.operation().result() != BusinessResult.SUCCESS) {
                operationError(sender, payment.operation().result(), args[1]);
                return;
            }
            messages.send(sender, "business.paid",
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("player", payment.recipient().get().lastName()),
                    Placeholder.unparsed("amount", Money.format(amount.get(), currencySymbol)),
                    Placeholder.unparsed("balance",
                            Money.format(payment.operation().businessBalanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean offer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length < 3 || args.length > 5) {
            usage(sender, "/business offer <business> <player> [role] [wage]");
            return true;
        }
        String roleInput = args.length >= 4 ? args[3] : BusinessRole.EMPLOYEE.name();
        Optional<Long> wage = args.length == 5
                ? nonNegativeAmount(sender, args[4]) : Optional.of(0L);
        if (wage.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long expiresAt;
        try {
            expiresAt = Math.addExact(now, offerExpiryMillis);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.database");
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[2]);
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> selectedRole = businesses.resolveRole(args[1], roleInput);
            BusinessResult result;
            if (target.isEmpty()) {
                result = BusinessResult.CITIZEN_NOT_FOUND;
            } else if (business.isEmpty()) {
                result = BusinessResult.BUSINESS_NOT_FOUND;
            } else if (selectedRole.isEmpty()) {
                result = BusinessResult.INVALID_ROLE;
            } else {
                result = businesses.offer(
                        player.getUniqueId(),
                        args[1],
                        target.get().uuid(),
                        selectedRole.get(),
                        wage.get(),
                        now,
                        expiresAt
                );
            }
            return new RoleStaffAction(target, selectedRole, result);
        }), action -> {
            if (action.target().isEmpty()) {
                playerNotFound(sender, args[2]);
                return;
            }
            CitizenProfile target = action.target().get();
            BusinessRole selectedRole = action.role().orElse(BusinessRole.EMPLOYEE);
            if (action.result() != BusinessResult.SUCCESS) {
                if (action.result() == BusinessResult.INVALID_ROLE && action.role().isEmpty()) {
                    messages.send(sender, "business.invalid-role",
                            Placeholder.unparsed("role", roleInput));
                    return;
                }
                membershipError(sender, action.result(), args[1], target.lastName(), selectedRole);
                return;
            }
            String formattedWage = Money.format(wage.get(), currencySymbol);
            messages.send(sender, "business.offer-sent",
                    Placeholder.unparsed("player", target.lastName()),
                    Placeholder.unparsed("role", displayRole(selectedRole)),
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("wage", formattedWage));
            Player onlineTarget = Bukkit.getPlayer(target.uuid());
            if (onlineTarget != null) {
                messages.send(onlineTarget, "business.offer-received",
                        Placeholder.unparsed("role", displayRole(selectedRole)),
                        Placeholder.unparsed("business", displayName(args[1])),
                        Placeholder.unparsed("wage", formattedWage));
            }
        });
        return true;
    }

    private boolean respondToOffer(CommandSender sender, String[] args, boolean accept) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business " + (accept ? "accept" : "deny") + " <business>");
            return true;
        }
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> accept
                ? businesses.acceptOffer(player.getUniqueId(), args[1], now)
                : businesses.denyOffer(player.getUniqueId(), args[1], now)), result -> {
            if (result == BusinessResult.SUCCESS) {
                messages.send(sender, accept ? "business.accepted" : "business.denied",
                        Placeholder.unparsed("business", displayName(args[1])));
            } else if (result == BusinessResult.OFFER_NOT_FOUND) {
                messages.send(sender, "business.offer-not-found",
                        Placeholder.unparsed("business", displayName(args[1])));
            } else {
                membershipError(sender, result, args[1], sender.getName(), BusinessRole.EMPLOYEE);
            }
        });
        return true;
    }

    private boolean offers(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/business offers");
            return true;
        }
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> businesses.offers(player.getUniqueId(), now)), activeOffers -> {
            messages.send(sender, "business.offers-header");
            if (activeOffers.isEmpty()) {
                messages.send(sender, "business.offers-empty");
                return;
            }
            for (BusinessOffer activeOffer : activeOffers) {
                messages.send(sender, "business.offers-entry",
                        Placeholder.unparsed("business", activeOffer.businessName()),
                        Placeholder.unparsed("role", displayRole(activeOffer.role())),
                        Placeholder.unparsed("wage", Money.format(activeOffer.wageCents(), currencySymbol)),
                        Placeholder.unparsed("expires", DATE.format(activeOffer.expiresAt())));
            }
        });
        return true;
    }

    private boolean staff(CommandSender sender, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("list")) {
            usage(sender, "/business staff list <business>");
            return true;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[2]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[2], viewer) : Optional.empty();
            List<BusinessMember> members = business.isPresent()
                    ? businesses.members(args[2]) : List.of();
            boolean showWages = auditor || role.map(BusinessRole::canManageFunds).orElse(false);
            return new StaffView(business, members, showWages);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[2]);
                return;
            }
            messages.send(sender, "business.staff-header",
                    Placeholder.unparsed("business", view.business().get().displayName()));
            for (BusinessMember member : view.members()) {
                String key = view.showWages() ? "business.staff-entry-wage" : "business.staff-entry";
                messages.send(sender, key,
                        Placeholder.unparsed("player", member.playerName()),
                        Placeholder.unparsed("role", displayRole(member.role())),
                        Placeholder.unparsed("wage", Money.format(member.wageCents(), currencySymbol)));
            }
        });
        return true;
    }

    private boolean fire(CommandSender sender, String[] args) {
        return memberMutation(sender, args, MemberMutation.FIRE);
    }

    private boolean resign(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business resign <business>");
            return true;
        }
        complete(sender, database.submit(() -> businesses.resign(player.getUniqueId(), args[1])), result -> {
            if (result == BusinessResult.SUCCESS) {
                messages.send(sender, "business.resigned",
                        Placeholder.unparsed("business", displayName(args[1])));
            } else {
                membershipError(sender, result, args[1], sender.getName(), BusinessRole.EMPLOYEE);
            }
        });
        return true;
    }

    private boolean setRole(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 4) {
            usage(sender, "/business role <business> <player> <role>");
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[2]);
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = businesses.resolveRole(args[1], args[3]);
            BusinessResult result;
            if (target.isEmpty()) {
                result = BusinessResult.CITIZEN_NOT_FOUND;
            } else if (business.isEmpty()) {
                result = BusinessResult.BUSINESS_NOT_FOUND;
            } else if (role.isEmpty()) {
                result = BusinessResult.INVALID_ROLE;
            } else {
                result = businesses.setRole(
                        player.getUniqueId(), args[1], target.get().uuid(), role.get());
            }
            return new RoleStaffAction(target, role, result);
        }), action -> {
            if (action.target().isEmpty()) {
                playerNotFound(sender, args[2]);
                return;
            }
            BusinessRole role = action.role().orElse(BusinessRole.EMPLOYEE);
            if (action.result() != BusinessResult.SUCCESS) {
                if (action.result() == BusinessResult.INVALID_ROLE && action.role().isEmpty()) {
                    messages.send(sender, "business.invalid-role",
                            Placeholder.unparsed("role", args[3]));
                    return;
                }
                membershipError(sender, action.result(), args[1], action.target().get().lastName(), role);
                return;
            }
            messages.send(sender, "business.role-changed",
                    Placeholder.unparsed("player", action.target().get().lastName()),
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("role", displayRole(role)));
        });
        return true;
    }

    private boolean customRoles(CommandSender sender, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            complete(sender, database.submit(() -> {
                Optional<Business> business = businesses.find(args[2]);
                List<BusinessRole> roles = business.isPresent()
                        ? businesses.customRoles(args[2]) : List.of();
                return new RoleListView(business, roles);
            }), view -> showCustomRoles(sender, args[2], view));
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("delete")) {
            complete(sender, database.submit(() -> businesses.deleteCustomRole(
                    player.getUniqueId(), args[2], args[3])), result -> {
                if (result == BusinessResult.SUCCESS) {
                    messages.send(sender, "business.role-deleted",
                            Placeholder.unparsed("role", displayName(args[3])),
                            Placeholder.unparsed("business", displayName(args[2])));
                } else {
                    customRoleError(sender, result, args[2], args[3]);
                }
            });
            return true;
        }
        boolean create = args.length >= 5 && args[1].equalsIgnoreCase("create");
        boolean edit = args.length >= 5 && args[1].equalsIgnoreCase("edit");
        if (!create && !edit) {
            usage(sender, "/business roles <list|create|edit|delete> <business> [role] [permissions...]");
            return true;
        }
        String roleKey = BusinessRole.normalizeCustomKey(args[3]);
        if (!ROLE_KEY.matcher(roleKey).matches()) {
            messages.send(sender, "business.invalid-role",
                    Placeholder.unparsed("role", args[3]));
            return true;
        }
        Optional<EnumSet<BusinessPermission>> permissions = parsePermissions(sender, args, 4);
        if (permissions.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> create
                ? businesses.createCustomRole(
                        player.getUniqueId(), args[2], roleKey, displayName(roleKey), permissions.get())
                : businesses.editCustomRole(
                        player.getUniqueId(), args[2], roleKey, permissions.get())), result -> {
            if (result == BusinessResult.SUCCESS) {
                messages.send(sender, create ? "business.role-created" : "business.role-edited",
                        Placeholder.unparsed("role", displayName(roleKey)),
                        Placeholder.unparsed("business", displayName(args[2])),
                        Placeholder.component("permissions", permissionList(sender, permissions.get())));
            } else {
                customRoleError(sender, result, args[2], roleKey);
            }
        });
        return true;
    }

    private void showCustomRoles(CommandSender sender, String slug, RoleListView view) {
        if (view.business().isEmpty()) {
            businessNotFound(sender, slug);
            return;
        }
        messages.send(sender, "business.roles-header",
                Placeholder.unparsed("business", view.business().get().displayName()));
        if (view.roles().isEmpty()) {
            messages.send(sender, "business.roles-empty");
            return;
        }
        for (BusinessRole role : view.roles()) {
            messages.send(sender, "business.roles-entry",
                    Placeholder.unparsed("role", role.displayName()),
                    Placeholder.component("permissions", permissionList(sender, role.permissions())));
        }
    }

    private boolean setWage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 4) {
            usage(sender, "/business wage <business> <player> <amount>");
            return true;
        }
        Optional<Long> wage = nonNegativeAmount(sender, args[3]);
        if (wage.isEmpty()) {
            return true;
        }
        return memberMutation(sender, args, MemberMutation.WAGE, BusinessRole.EMPLOYEE, wage.get());
    }

    private boolean memberMutation(CommandSender sender, String[] args, MemberMutation mutation) {
        return memberMutation(sender, args, mutation, BusinessRole.EMPLOYEE, 0);
    }

    private boolean memberMutation(
            CommandSender sender,
            String[] args,
            MemberMutation mutation,
            BusinessRole role,
            long wage
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        int expected = mutation == MemberMutation.FIRE ? 3 : 4;
        if (args.length != expected) {
            usage(sender, mutation == MemberMutation.FIRE
                    ? "/business fire <business> <player>"
                    : "/business wage <business> <player> <amount>");
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[2]);
            BusinessResult result;
            if (target.isEmpty()) {
                result = BusinessResult.CITIZEN_NOT_FOUND;
            } else {
                result = switch (mutation) {
                    case FIRE -> businesses.fire(player.getUniqueId(), args[1], target.get().uuid());
                    case WAGE -> businesses.setWage(player.getUniqueId(), args[1], target.get().uuid(), wage);
                };
            }
            return new StaffAction(target, result);
        }), action -> {
            if (action.target().isEmpty()) {
                playerNotFound(sender, args[2]);
                return;
            }
            CitizenProfile target = action.target().get();
            if (action.result() != BusinessResult.SUCCESS) {
                membershipError(sender, action.result(), args[1], target.lastName(), role);
                return;
            }
            String key = switch (mutation) {
                case FIRE -> "business.fired";
                case WAGE -> "business.wage-changed";
            };
            messages.send(sender, key,
                    Placeholder.unparsed("player", target.lastName()),
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("role", displayRole(role)),
                    Placeholder.unparsed("wage", Money.format(wage, currencySymbol)));
        });
        return true;
    }

    private boolean payroll(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business payroll <business>");
            return true;
        }
        complete(sender, database.submit(() -> businesses.runPayroll(player.getUniqueId(), args[1])), payroll -> {
            if (payroll.result() != BusinessResult.SUCCESS) {
                operationError(sender, payroll.result(), args[1]);
                return;
            }
            messages.send(sender, "business.payroll-complete",
                    Placeholder.unparsed("recipients", Integer.toString(payroll.recipients())),
                    Placeholder.unparsed("total", Money.format(payroll.totalPaidCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(payroll.businessBalanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean transferProprietorship(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/business transferproprietorship <business> <player>");
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[2]);
            BusinessResult result = target.isEmpty()
                    ? BusinessResult.CITIZEN_NOT_FOUND
                    : businesses.transferProprietorship(
                            player.getUniqueId(), args[1], target.get().uuid());
            return new StaffAction(target, result);
        }), action -> {
            if (action.target().isEmpty()) {
                playerNotFound(sender, args[2]);
                return;
            }
            if (action.result() != BusinessResult.SUCCESS) {
                membershipError(
                        sender, action.result(), args[1], action.target().get().lastName(), BusinessRole.PROPRIETOR);
                return;
            }
            messages.send(sender, "business.ownership-transferred",
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("player", action.target().get().lastName()));
        });
        return true;
    }

    private boolean transactions(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(sender, "/business transactions <business> [page]");
            return true;
        }
        Optional<Integer> page = page(sender, args.length == 3 ? args[2] : null);
        if (page.isEmpty()) {
            return true;
        }
        int offset;
        try {
            offset = Math.multiplyExact(page.get() - 1, pageSize);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[1], viewer) : Optional.empty();
            boolean authorized = auditor
                    || role.map(BusinessRole::canManageFunds).orElse(false);
            List<BusinessLedgerEntry> entries = business.isPresent() && authorized
                    ? businesses.ledger(args[1], pageSize, offset) : List.of();
            return new LedgerView(business, authorized, entries);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[1]);
                return;
            }
            if (!view.authorized()) {
                messages.send(sender, "business.no-permission");
                return;
            }
            showLedger(sender, view.business().get(), page.get(), view.entries());
        });
        return true;
    }

    private void showLedger(
            CommandSender sender,
            Business business,
            int page,
            List<BusinessLedgerEntry> entries
    ) {
        messages.send(sender, "business.ledger-header",
                Placeholder.unparsed("business", business.displayName()),
                Placeholder.unparsed("page", Integer.toString(page)));
        if (entries.isEmpty()) {
            messages.send(sender, "business.ledger-empty");
            return;
        }
        for (BusinessLedgerEntry entry : entries) {
            String key = switch (entry.type()) {
                case "DEPOSIT" -> "business.ledger-deposit";
                case "WITHDRAWAL" -> "business.ledger-withdrawal";
                case "PAYMENT" -> "business.ledger-payment";
                case "DISBAND_REFUND" -> "business.ledger-disband";
                case "WAGE" -> "business.ledger-wage";
                case "SHOP_SALE" -> "business.ledger-shop-sale";
                case "SHOP_PURCHASE" -> "business.ledger-shop-purchase";
                default -> "business.ledger-withdrawal";
            };
            Component description = messages.component(sender, key,
                    Placeholder.unparsed("actor", Optional.ofNullable(entry.actorName()).orElse("System")),
                    Placeholder.unparsed("player",
                            Optional.ofNullable(entry.counterpartyName()).orElse("Unknown")));
            messages.send(sender, "business.ledger-entry",
                    Placeholder.unparsed("date", DATE.format(entry.createdAt())),
                    Placeholder.unparsed("amount", Money.format(entry.amountCents(), currencySymbol)),
                    Placeholder.component("description", description));
        }
    }

    private boolean sales(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(sender, "/business sales <business> [page]");
            return true;
        }
        Optional<Integer> page = page(sender, args.length == 3 ? args[2] : null);
        if (page.isEmpty()) {
            return true;
        }
        int offset;
        try {
            offset = Math.multiplyExact(page.get() - 1, pageSize);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[1], viewer) : Optional.empty();
            boolean authorized = auditor || role
                    .map(value -> value.canManageFunds() || value.canManageShops())
                    .orElse(false);
            List<ShopSale> entries = business.isPresent() && authorized
                    ? shops.businessSales(args[1], pageSize, offset) : List.of();
            return new SalesView(business, authorized, entries);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[1]);
                return;
            }
            if (!view.authorized()) {
                messages.send(sender, "business.no-permission");
                return;
            }
            messages.send(sender, "shops.business-sales.header",
                    Placeholder.unparsed("business", view.business().get().displayName()),
                    Placeholder.unparsed("page", Integer.toString(page.get())));
            if (view.sales().isEmpty()) {
                messages.send(sender, "shops.business-sales.empty");
                return;
            }
            for (ShopSale sale : view.sales()) {
                messages.send(sender, sale.direction() == dev.opencivitas.shop.ShopDirection.BUY
                                ? "shops.business-sales.entry-buy" : "shops.business-sales.entry-sell",
                        Placeholder.unparsed("date", DATE.format(sale.createdAt())),
                        Placeholder.unparsed("player", sale.customerName()),
                        Placeholder.unparsed("amount", Integer.toString(sale.itemAmount())),
                        Placeholder.unparsed("item", sale.itemKey().toLowerCase(Locale.ROOT).replace('_', ' ')),
                        Placeholder.unparsed("price", Money.format(sale.totalCents(), currencySymbol)));
            }
        });
        return true;
    }

    private boolean disband(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business disband <business>");
            return true;
        }
        complete(sender, database.submit(() -> businesses.disband(player.getUniqueId(), args[1])), operation -> {
            if (operation.result() == BusinessResult.SUCCESS) {
                messages.send(sender, "business.disbanded",
                        Placeholder.unparsed("business", displayName(args[1])));
            } else {
                operationError(sender, operation.result(), args[1]);
            }
        });
        return true;
    }

    private void operationError(CommandSender sender, BusinessResult result, String slug) {
        switch (result) {
            case BUSINESS_NOT_FOUND -> businessNotFound(sender, slug);
            case BUSINESS_INACTIVE -> messages.send(sender, "business.inactive");
            case NO_PERMISSION -> messages.send(sender, "business.no-permission");
            case INSUFFICIENT_PERSONAL_FUNDS -> messages.send(sender, "business.insufficient-personal");
            case INSUFFICIENT_BUSINESS_FUNDS -> messages.send(sender, "business.insufficient-business");
            case MISSING_QUALIFICATION -> messages.send(sender, "business.missing-qualification");
            case NAME_TAKEN -> messages.send(sender, "business.name-taken");
            default -> messages.send(sender, "error.database");
        }
    }

    private void membershipError(
            CommandSender sender,
            BusinessResult result,
            String slug,
            String player,
            BusinessRole role
    ) {
        switch (result) {
            case ALREADY_MEMBER -> messages.send(sender, "business.already-member",
                    Placeholder.unparsed("player", player));
            case NOT_MEMBER -> messages.send(sender, "business.not-member",
                    Placeholder.unparsed("player", player));
            case OFFER_EXISTS -> messages.send(sender, "business.offer-exists",
                    Placeholder.unparsed("player", player));
            case OFFER_NOT_FOUND -> messages.send(sender, "business.offer-not-found",
                    Placeholder.unparsed("business", displayName(slug)));
            case INVALID_ROLE -> messages.send(sender, "business.invalid-role",
                    Placeholder.unparsed("role", displayRole(role)));
            case CANNOT_REMOVE_PROPRIETOR -> messages.send(sender, "business.proprietor-protected");
            default -> operationError(sender, result, slug);
        }
    }

    private Optional<Long> amount(CommandSender sender, String input) {
        try {
            return Optional.of(Money.parsePositiveCents(input));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-amount");
            return Optional.empty();
        }
    }

    private Optional<Long> nonNegativeAmount(CommandSender sender, String input) {
        try {
            return Optional.of(Money.parseCents(input));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-non-negative-amount");
            return Optional.empty();
        }
    }

    private Optional<EnumSet<BusinessPermission>> parsePermissions(
            CommandSender sender,
            String[] args,
            int start
    ) {
        EnumSet<BusinessPermission> permissions = EnumSet.noneOf(BusinessPermission.class);
        for (int index = start; index < args.length; index++) {
            for (String token : args[index].split(",")) {
                Optional<BusinessPermission> permission = BusinessPermission.parse(token);
                if (permission.isEmpty()) {
                    messages.send(sender, "business.invalid-role-permission",
                            Placeholder.unparsed("permission", token));
                    return Optional.empty();
                }
                permissions.add(permission.get());
            }
        }
        return permissions.isEmpty() ? Optional.empty() : Optional.of(permissions);
    }

    private Component permissionList(CommandSender sender, Set<BusinessPermission> permissions) {
        Component result = Component.empty();
        boolean first = true;
        for (BusinessPermission permission : BusinessPermission.values()) {
            if (!permissions.contains(permission)) {
                continue;
            }
            if (!first) {
                result = result.append(Component.text(", "));
            }
            result = result.append(messages.component(sender,
                    "business.permission." + permission.name().toLowerCase(Locale.ROOT).replace('_', '-')));
            first = false;
        }
        return result;
    }

    private void customRoleError(CommandSender sender, BusinessResult result, String slug, String role) {
        switch (result) {
            case ROLE_EXISTS -> messages.send(sender, "business.role-exists",
                    Placeholder.unparsed("role", displayName(role)));
            case ROLE_LIMIT_REACHED -> messages.send(sender, "business.role-limit");
            case INVALID_ROLE -> messages.send(sender, "business.invalid-role",
                    Placeholder.unparsed("role", role));
            default -> operationError(sender, result, slug);
        }
    }

    private Optional<Integer> page(CommandSender sender, String input) {
        if (input == null) {
            return Optional.of(1);
        }
        try {
            int page = Integer.parseInt(input);
            if (page < 1) {
                throw new NumberFormatException("Page must be positive");
            }
            return Optional.of(page);
        } catch (NumberFormatException exception) {
            messages.send(sender, "error.invalid-page");
            return Optional.empty();
        }
    }

    private void businessNotFound(CommandSender sender, String slug) {
        messages.send(sender, "business.not-found", Placeholder.unparsed("business", slug));
    }

    private void playerNotFound(CommandSender sender, String player) {
        messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", player));
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous business operation failed", error);
                messages.send(sender, "error.database");
                return;
            }
            success.accept(result);
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "create", "info", "list", "deposit", "withdraw", "pay", "offer", "accept", "deny",
                    "offers", "staff", "fire", "resign", "role", "roles", "wage", "payroll",
                    "transferproprietorship", "transactions", "sales", "disband"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            List<String> options = new ArrayList<>(onlineNames());
            options.add("all");
            return filter(args[1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            return filter(args[2], onlineNames());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("staff")) {
            return filter(args[1], List.of("list"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("roles")
                || args[0].equalsIgnoreCase("customroles"))) {
            return filter(args[1], List.of("list", "create", "edit", "delete"));
        }
        if (args.length >= 5 && (args[0].equalsIgnoreCase("roles")
                || args[0].equalsIgnoreCase("customroles"))
                && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("edit"))) {
            return filter(args[args.length - 1], List.of("administrator", "financial", "chestshop", "default"));
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("offer")
                || args[0].equalsIgnoreCase("fire")
                || args[0].equalsIgnoreCase("role")
                || args[0].equalsIgnoreCase("wage")
                || args[0].equalsIgnoreCase("transferproprietorship"))) {
            return filter(args[2], onlineNames());
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("offer")
                || args[0].equalsIgnoreCase("role"))) {
            return filter(args[3], assignableRoles());
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> assignableRoles() {
        return List.of("co-proprietor", "manager", "supervisor", "employee");
    }

    private static List<String> filter(String input, Collection<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private static String displayName(String slug) {
        String[] words = slug.split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String displayRole(BusinessRole role) {
        return role.displayName();
    }

    private enum TransferCommand {
        DEPOSIT("deposit"),
        WITHDRAW("withdraw");

        private final String commandName;

        TransferCommand(String commandName) {
            this.commandName = commandName;
        }
    }

    private enum MemberMutation {
        FIRE,
        WAGE
    }

    private record BusinessView(Optional<Business> business, Optional<BusinessRole> role) {
    }

    private record BusinessListView(Optional<CitizenProfile> profile, List<Business> businesses) {
    }

    private record BusinessPayment(Optional<CitizenProfile> recipient, BusinessOperation operation) {
    }

    private record StaffAction(Optional<CitizenProfile> target, BusinessResult result) {
    }

    private record RoleStaffAction(
            Optional<CitizenProfile> target,
            Optional<BusinessRole> role,
            BusinessResult result
    ) {
    }

    private record RoleListView(Optional<Business> business, List<BusinessRole> roles) {
    }

    private record StaffView(
            Optional<Business> business,
            List<BusinessMember> members,
            boolean showWages
    ) {
    }

    private record LedgerView(
            Optional<Business> business,
            boolean authorized,
            List<BusinessLedgerEntry> entries
    ) {
    }

    private record SalesView(
            Optional<Business> business,
            boolean authorized,
            List<ShopSale> sales
    ) {
    }
}
