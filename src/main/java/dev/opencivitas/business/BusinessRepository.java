package dev.opencivitas.business;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class BusinessRepository {
    private final Database database;

    public BusinessRepository(Database database) {
        this.database = database;
    }

    public BusinessResult create(UUID proprietor, String slug, String displayName) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, proprietor)) {
                    connection.rollback();
                    return BusinessResult.CITIZEN_NOT_FOUND;
                }
                if (!hasQualification(connection, proprietor, "entrepreneur")) {
                    connection.rollback();
                    return BusinessResult.MISSING_QUALIFICATION;
                }
                if (businessId(connection, slug).isPresent()) {
                    connection.rollback();
                    return BusinessResult.NAME_TAKEN;
                }
                long businessId = insertBusiness(connection, proprietor, slug, displayName, now);
                insertMember(connection, businessId, proprietor, BusinessRole.PROPRIETOR, now);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<Business> find(String slug) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE b.slug = ? COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readBusiness(results)) : Optional.empty();
            }
        }
    }

    public List<Business> list(UUID member) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN business_members m ON m.business_id = b.id
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE m.player_uuid = ?
                ORDER BY b.display_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, member.toString());
            return readBusinesses(statement);
        }
    }

    public List<Business> listAll(int limit, int offset) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE b.status = 'ACTIVE'
                ORDER BY b.display_name COLLATE NOCASE
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            return readBusinesses(statement);
        }
    }

    public Optional<BusinessRole> role(String slug, UUID player) throws SQLException {
        String sql = """
                SELECT m.role
                FROM business_members m
                JOIN businesses b ON b.id = m.business_id
                WHERE b.slug = ? COLLATE NOCASE AND m.player_uuid = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(BusinessRole.valueOf(results.getString(1)))
                        : Optional.empty();
            }
        }
    }

    public List<BusinessMember> members(String slug) throws SQLException {
        String sql = """
                SELECT m.player_uuid, p.last_name, m.role, m.wage_cents, m.joined_at
                FROM business_members m
                JOIN businesses b ON b.id = m.business_id
                JOIN players p ON p.uuid = m.player_uuid
                WHERE b.slug = ? COLLATE NOCASE
                ORDER BY CASE m.role
                    WHEN 'PROPRIETOR' THEN 0
                    WHEN 'CO_PROPRIETOR' THEN 1
                    WHEN 'MANAGER' THEN 2
                    WHEN 'SUPERVISOR' THEN 3
                    ELSE 4 END,
                    p.last_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                List<BusinessMember> members = new ArrayList<>();
                while (results.next()) {
                    members.add(new BusinessMember(
                            UUID.fromString(results.getString("player_uuid")),
                            results.getString("last_name"),
                            BusinessRole.valueOf(results.getString("role")),
                            results.getLong("wage_cents"),
                            Instant.ofEpochMilli(results.getLong("joined_at"))
                    ));
                }
                return List.copyOf(members);
            }
        }
    }

    public List<BusinessOffer> offers(UUID player, long now) throws SQLException {
        String sql = """
                SELECT o.business_id, b.slug, b.display_name, o.offered_by,
                       offered_by.last_name AS offered_by_name, o.offered_role,
                       o.offered_wage_cents, o.offered_at, o.expires_at
                FROM business_offers o
                JOIN businesses b ON b.id = o.business_id
                JOIN players offered_by ON offered_by.uuid = o.offered_by
                WHERE o.player_uuid = ? AND o.expires_at > ? AND b.status = 'ACTIVE'
                ORDER BY o.offered_at, b.display_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            deleteExpiredOffers(connection, now);
            statement.setString(1, player.toString());
            statement.setLong(2, now);
            try (ResultSet results = statement.executeQuery()) {
                List<BusinessOffer> offers = new ArrayList<>();
                while (results.next()) {
                    offers.add(new BusinessOffer(
                            results.getLong("business_id"),
                            results.getString("slug"),
                            results.getString("display_name"),
                            UUID.fromString(results.getString("offered_by")),
                            results.getString("offered_by_name"),
                            BusinessRole.valueOf(results.getString("offered_role")),
                            results.getLong("offered_wage_cents"),
                            Instant.ofEpochMilli(results.getLong("offered_at")),
                            Instant.ofEpochMilli(results.getLong("expires_at"))
                    ));
                }
                return List.copyOf(offers);
            }
        }
    }

    public BusinessResult offer(
            UUID actor,
            String slug,
            UUID target,
            BusinessRole offeredRole,
            long wageCents,
            long now,
            long expiresAt
    ) throws SQLException {
        validateWage(wageCents);
        if (offeredRole == BusinessRole.PROPRIETOR || expiresAt <= now) {
            return BusinessResult.INVALID_ROLE;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                Optional<BusinessRole> actorRole = memberRole(connection, id.get(), actor);
                if (actorRole.isEmpty() || !actorRole.get().canAssign(offeredRole)) {
                    connection.rollback();
                    return BusinessResult.NO_PERMISSION;
                }
                if (!citizenExists(connection, target)) {
                    connection.rollback();
                    return BusinessResult.CITIZEN_NOT_FOUND;
                }
                if (memberRole(connection, id.get(), target).isPresent()) {
                    connection.rollback();
                    return BusinessResult.ALREADY_MEMBER;
                }
                deleteExpiredOffers(connection, now);
                if (offerExists(connection, id.get(), target)) {
                    connection.rollback();
                    return BusinessResult.OFFER_EXISTS;
                }
                insertOffer(connection, id.get(), target, actor, offeredRole, wageCents, now, expiresAt);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public BusinessResult acceptOffer(UUID player, String slug, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                if (memberRole(connection, id.get(), player).isPresent()) {
                    deleteOffer(connection, id.get(), player);
                    connection.commit();
                    return BusinessResult.ALREADY_MEMBER;
                }
                Optional<OfferRow> offer = offerRow(connection, id.get(), player);
                if (offer.isEmpty() || offer.get().expiresAt() <= now) {
                    deleteOffer(connection, id.get(), player);
                    connection.commit();
                    return BusinessResult.OFFER_NOT_FOUND;
                }
                insertMember(
                        connection, id.get(), player, offer.get().role(), offer.get().wageCents(), now);
                deleteOffer(connection, id.get(), player);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public BusinessResult denyOffer(UUID player, String slug, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<Long> id = businessId(connection, slug);
            if (id.isEmpty()) {
                return BusinessResult.BUSINESS_NOT_FOUND;
            }
            Optional<OfferRow> offer = offerRow(connection, id.get(), player);
            if (offer.isEmpty()) {
                return BusinessResult.OFFER_NOT_FOUND;
            }
            deleteOffer(connection, id.get(), player);
            return offer.get().expiresAt() > now
                    ? BusinessResult.SUCCESS : BusinessResult.OFFER_NOT_FOUND;
        }
    }

    public BusinessResult fire(UUID actor, String slug, UUID target) throws SQLException {
        return removeMember(actor, slug, target, false);
    }

    public BusinessResult resign(UUID player, String slug) throws SQLException {
        return removeMember(player, slug, player, true);
    }

    public BusinessResult setRole(UUID actor, String slug, UUID target, BusinessRole newRole) throws SQLException {
        if (newRole == BusinessRole.PROPRIETOR) {
            return BusinessResult.INVALID_ROLE;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                Optional<BusinessRole> actorRole = memberRole(connection, id.get(), actor);
                Optional<BusinessRole> targetRole = memberRole(connection, id.get(), target);
                if (targetRole.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.NOT_MEMBER;
                }
                if (targetRole.get() == BusinessRole.PROPRIETOR) {
                    connection.rollback();
                    return BusinessResult.CANNOT_REMOVE_PROPRIETOR;
                }
                if (actorRole.isEmpty()
                        || !actorRole.get().canManage(targetRole.get())
                        || !actorRole.get().canAssign(newRole)) {
                    connection.rollback();
                    return BusinessResult.NO_PERMISSION;
                }
                updateMemberRole(connection, id.get(), target, newRole);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public BusinessResult setWage(UUID actor, String slug, UUID target, long wageCents) throws SQLException {
        validateWage(wageCents);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                Optional<BusinessRole> actorRole = memberRole(connection, id.get(), actor);
                Optional<BusinessRole> targetRole = memberRole(connection, id.get(), target);
                if (targetRole.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.NOT_MEMBER;
                }
                if (targetRole.get() == BusinessRole.PROPRIETOR) {
                    connection.rollback();
                    return BusinessResult.CANNOT_REMOVE_PROPRIETOR;
                }
                if (actorRole.isEmpty() || !actorRole.get().canManage(targetRole.get())) {
                    connection.rollback();
                    return BusinessResult.NO_PERMISSION;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE business_members SET wage_cents = ? WHERE business_id = ? AND player_uuid = ?")) {
                    statement.setLong(1, wageCents);
                    statement.setLong(2, id.get());
                    statement.setString(3, target.toString());
                    statement.executeUpdate();
                }
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public BusinessResult transferProprietorship(UUID actor, String slug, UUID target) throws SQLException {
        if (actor.equals(target)) {
            return BusinessResult.ALREADY_MEMBER;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                if (!business.proprietor().equals(actor)) {
                    connection.rollback();
                    return BusinessResult.NO_PERMISSION;
                }
                Optional<BusinessRole> targetRole = memberRole(connection, id.get(), target);
                if (targetRole.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.NOT_MEMBER;
                }
                updateMemberRole(connection, id.get(), actor, BusinessRole.CO_PROPRIETOR);
                updateMemberRole(connection, id.get(), target, BusinessRole.PROPRIETOR);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE businesses SET proprietor_uuid = ? WHERE id = ?")) {
                    statement.setString(1, target.toString());
                    statement.setLong(2, id.get());
                    statement.executeUpdate();
                }
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PayrollOperation runPayroll(UUID actor, String slug) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return payrollError(BusinessResult.BUSINESS_NOT_FOUND);
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return payrollError(BusinessResult.BUSINESS_INACTIVE);
                }
                Optional<BusinessRole> actorRole = memberRole(connection, id.get(), actor);
                if (actorRole.isEmpty() || !actorRole.get().canManageFunds()) {
                    connection.rollback();
                    return payrollError(BusinessResult.NO_PERMISSION);
                }
                List<WageRow> wages = wages(connection, id.get());
                long total = 0;
                try {
                    for (WageRow wage : wages) {
                        total = Math.addExact(total, wage.amountCents());
                    }
                } catch (ArithmeticException exception) {
                    connection.rollback();
                    throw new SQLException("Payroll total is out of range", exception);
                }
                if (total == 0) {
                    connection.rollback();
                    return new PayrollOperation(BusinessResult.SUCCESS, 0, 0, business.balance());
                }
                if (updateBusinessBalance(connection, id.get(), -total, true) == 0) {
                    connection.rollback();
                    return new PayrollOperation(
                            BusinessResult.INSUFFICIENT_BUSINESS_FUNDS, 0, 0, business.balance());
                }
                for (WageRow wage : wages) {
                    depositPlayer(connection, wage.playerId(), wage.amountCents());
                    insertPlayerLedger(
                            connection, wage.playerId(), wage.amountCents(), LedgerEntryType.BUSINESS_PAYMENT, now);
                    insertBusinessLedger(
                            connection, id.get(), actor, wage.playerId(), -wage.amountCents(), "WAGE", now);
                }
                long balance = businessBalance(connection, id.get());
                connection.commit();
                return new PayrollOperation(BusinessResult.SUCCESS, wages.size(), total, balance);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public BusinessOperation deposit(UUID actor, String slug, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, null, slug, amount, TransferKind.DEPOSIT);
    }

    public BusinessOperation withdraw(UUID actor, String slug, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, actor, slug, amount, TransferKind.WITHDRAWAL);
    }

    public BusinessOperation pay(UUID actor, String slug, UUID recipient, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, recipient, slug, amount, TransferKind.PAYMENT);
    }

    public BusinessOperation disband(UUID actor, String slug) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_NOT_FOUND, 0);
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_INACTIVE, business.balance());
                }
                if (!business.proprietor().equals(actor)) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.NO_PERMISSION, business.balance());
                }
                if (business.balance() > 0) {
                    depositPlayer(connection, actor, business.balance());
                    insertPlayerLedger(connection, actor, business.balance(), LedgerEntryType.BUSINESS_TRANSFER, now);
                    insertBusinessLedger(
                            connection, id.get(), actor, actor, -business.balance(), "DISBAND_REFUND", now);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE businesses SET balance_cents = 0, status = 'DISBANDED', disbanded_at = ? WHERE id = ?")) {
                    statement.setLong(1, now);
                    statement.setLong(2, id.get());
                    statement.executeUpdate();
                }
                connection.commit();
                return new BusinessOperation(BusinessResult.SUCCESS, 0);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<BusinessLedgerEntry> ledger(String slug, int limit, int offset) throws SQLException {
        String sql = """
                SELECT l.id, l.amount_cents, l.entry_type, l.created_at,
                       actor.last_name AS actor_name, counterparty.last_name AS counterparty_name
                FROM business_ledger_entries l
                JOIN businesses b ON b.id = l.business_id
                LEFT JOIN players actor ON actor.uuid = l.actor_uuid
                LEFT JOIN players counterparty ON counterparty.uuid = l.counterparty_uuid
                WHERE b.slug = ? COLLATE NOCASE
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<BusinessLedgerEntry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new BusinessLedgerEntry(
                            results.getLong("id"),
                            results.getLong("amount_cents"),
                            results.getString("entry_type"),
                            results.getString("actor_name"),
                            results.getString("counterparty_name"),
                            Instant.ofEpochMilli(results.getLong("created_at"))
                    ));
                }
                return List.copyOf(entries);
            }
        }
    }

    private BusinessOperation transfer(
            UUID actor,
            UUID recipient,
            String slug,
            long amount,
            TransferKind kind
    ) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_NOT_FOUND, 0);
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_INACTIVE, business.balance());
                }
                if (kind != TransferKind.DEPOSIT && !canManageFunds(connection, id.get(), actor)) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.NO_PERMISSION, business.balance());
                }
                if (kind == TransferKind.DEPOSIT) {
                    if (withdrawPlayer(connection, actor, amount) == 0) {
                        connection.rollback();
                        return new BusinessOperation(
                                citizenExists(connection, actor)
                                        ? BusinessResult.INSUFFICIENT_PERSONAL_FUNDS
                                        : BusinessResult.CITIZEN_NOT_FOUND,
                                business.balance()
                        );
                    }
                    updateBusinessBalance(connection, id.get(), amount, false);
                    insertPlayerLedger(connection, actor, -amount, LedgerEntryType.BUSINESS_TRANSFER, now);
                    insertBusinessLedger(connection, id.get(), actor, actor, amount, "DEPOSIT", now);
                } else {
                    if (recipient == null || !citizenExists(connection, recipient)) {
                        connection.rollback();
                        return new BusinessOperation(BusinessResult.CITIZEN_NOT_FOUND, business.balance());
                    }
                    if (updateBusinessBalance(connection, id.get(), -amount, true) == 0) {
                        connection.rollback();
                        return new BusinessOperation(BusinessResult.INSUFFICIENT_BUSINESS_FUNDS, business.balance());
                    }
                    depositPlayer(connection, recipient, amount);
                    LedgerEntryType type = kind == TransferKind.PAYMENT
                            ? LedgerEntryType.BUSINESS_PAYMENT : LedgerEntryType.BUSINESS_TRANSFER;
                    insertPlayerLedger(connection, recipient, amount, type, now);
                    insertBusinessLedger(
                            connection,
                            id.get(),
                            actor,
                            recipient,
                            -amount,
                            kind == TransferKind.PAYMENT ? "PAYMENT" : "WITHDRAWAL",
                            now
                    );
                }
                long balance = businessBalance(connection, id.get());
                connection.commit();
                return new BusinessOperation(BusinessResult.SUCCESS, balance);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private BusinessResult removeMember(UUID actor, String slug, UUID target, boolean resignation)
            throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_NOT_FOUND;
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return BusinessResult.BUSINESS_INACTIVE;
                }
                Optional<BusinessRole> targetRole = memberRole(connection, id.get(), target);
                if (targetRole.isEmpty()) {
                    connection.rollback();
                    return BusinessResult.NOT_MEMBER;
                }
                if (targetRole.get() == BusinessRole.PROPRIETOR) {
                    connection.rollback();
                    return BusinessResult.CANNOT_REMOVE_PROPRIETOR;
                }
                if (resignation) {
                    if (!actor.equals(target)) {
                        connection.rollback();
                        return BusinessResult.NO_PERMISSION;
                    }
                } else {
                    Optional<BusinessRole> actorRole = memberRole(connection, id.get(), actor);
                    if (actorRole.isEmpty() || !actorRole.get().canManage(targetRole.get())) {
                        connection.rollback();
                        return BusinessResult.NO_PERMISSION;
                    }
                }
                deleteMember(connection, id.get(), target);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Business readBusiness(ResultSet results) throws SQLException {
        return new Business(
                results.getLong("id"),
                results.getString("slug"),
                results.getString("display_name"),
                UUID.fromString(results.getString("proprietor_uuid")),
                results.getString("proprietor_name"),
                results.getLong("balance_cents"),
                BusinessStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at"))
        );
    }

    private static List<Business> readBusinesses(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<Business> businesses = new ArrayList<>();
            while (results.next()) {
                businesses.add(readBusiness(results));
            }
            return List.copyOf(businesses);
        }
    }

    private static long insertBusiness(
            Connection connection,
            UUID proprietor,
            String slug,
            String displayName,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO businesses(slug, display_name, proprietor_uuid, created_at)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, slug);
            statement.setString(2, displayName);
            statement.setString(3, proprietor.toString());
            statement.setLong(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Business insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertMember(
            Connection connection,
            long businessId,
            UUID player,
            BusinessRole role,
            long now
    ) throws SQLException {
        insertMember(connection, businessId, player, role, 0, now);
    }

    private static void insertMember(
            Connection connection,
            long businessId,
            UUID player,
            BusinessRole role,
            long wageCents,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_members(business_id, player_uuid, role, wage_cents, joined_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            statement.setString(3, role.name());
            statement.setLong(4, wageCents);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static Optional<BusinessRole> memberRole(Connection connection, long businessId, UUID player)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM business_members WHERE business_id = ? AND player_uuid = ?")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(BusinessRole.valueOf(results.getString(1)))
                        : Optional.empty();
            }
        }
    }

    private static void updateMemberRole(
            Connection connection,
            long businessId,
            UUID player,
            BusinessRole role
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE business_members SET role = ? WHERE business_id = ? AND player_uuid = ?")) {
            statement.setString(1, role.name());
            statement.setLong(2, businessId);
            statement.setString(3, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Business member not found");
            }
        }
    }

    private static int deleteMember(Connection connection, long businessId, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business_members WHERE business_id = ? AND player_uuid = ?")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            return statement.executeUpdate();
        }
    }

    private static void insertOffer(
            Connection connection,
            long businessId,
            UUID player,
            UUID offeredBy,
            BusinessRole role,
            long wageCents,
            long now,
            long expiresAt
    ) throws SQLException {
        String sql = """
                INSERT INTO business_offers(
                    business_id, player_uuid, offered_by, offered_role,
                    offered_wage_cents, offered_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            statement.setString(3, offeredBy.toString());
            statement.setString(4, role.name());
            statement.setLong(5, wageCents);
            statement.setLong(6, now);
            statement.setLong(7, expiresAt);
            statement.executeUpdate();
        }
    }

    private static Optional<OfferRow> offerRow(Connection connection, long businessId, UUID player)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT offered_role, offered_wage_cents, expires_at "
                        + "FROM business_offers WHERE business_id = ? AND player_uuid = ?")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(new OfferRow(
                                BusinessRole.valueOf(results.getString(1)),
                                results.getLong(2),
                                results.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    private static boolean offerExists(Connection connection, long businessId, UUID player) throws SQLException {
        return offerRow(connection, businessId, player).isPresent();
    }

    private static int deleteOffer(Connection connection, long businessId, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business_offers WHERE business_id = ? AND player_uuid = ?")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            return statement.executeUpdate();
        }
    }

    private static void deleteExpiredOffers(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM business_offers WHERE expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private static List<WageRow> wages(Connection connection, long businessId) throws SQLException {
        String sql = """
                SELECT player_uuid, wage_cents
                FROM business_members
                WHERE business_id = ? AND wage_cents > 0 AND role != 'PROPRIETOR'
                ORDER BY player_uuid
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                List<WageRow> wages = new ArrayList<>();
                while (results.next()) {
                    wages.add(new WageRow(
                            UUID.fromString(results.getString(1)),
                            results.getLong(2)
                    ));
                }
                return List.copyOf(wages);
            }
        }
    }

    private static PayrollOperation payrollError(BusinessResult result) {
        return new PayrollOperation(result, 0, 0, 0);
    }

    private static Optional<Long> businessId(Connection connection, String slug) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM businesses WHERE slug = ? COLLATE NOCASE")) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getLong(1)) : Optional.empty();
            }
        }
    }

    private static BusinessRow businessRow(Connection connection, long businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT proprietor_uuid, balance_cents, status FROM businesses WHERE id = ?")) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Business disappeared during transaction");
                }
                return new BusinessRow(
                        UUID.fromString(results.getString(1)),
                        results.getLong(2),
                        BusinessStatus.valueOf(results.getString(3))
                );
            }
        }
    }

    private static boolean citizenExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean hasQualification(Connection connection, UUID player, String qualification)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id = ?")) {
            statement.setString(1, player.toString());
            statement.setString(2, qualification);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean canManageFunds(Connection connection, long businessId, UUID player) throws SQLException {
        return memberRole(connection, businessId, player)
                .map(BusinessRole::canManageFunds)
                .orElse(false);
    }

    private static int withdrawPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents - ? "
                        + "WHERE player_uuid = ? AND balance_cents >= ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate();
        }
    }

    private static void depositPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Player account not found");
            }
        }
    }

    private static int updateBusinessBalance(
            Connection connection,
            long businessId,
            long delta,
            boolean checkFunds
    ) throws SQLException {
        String sql = checkFunds
                ? "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents >= ?"
                : "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, delta);
            statement.setLong(2, businessId);
            if (checkFunds) {
                statement.setLong(3, -delta);
            }
            return statement.executeUpdate();
        }
    }

    private static long businessBalance(Connection connection, long businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM businesses WHERE id = ?")) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Business account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertPlayerLedger(
            Connection connection,
            UUID player,
            long amount,
            LedgerEntryType type,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, NULL, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setString(3, type.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static void insertBusinessLedger(
            Connection connection,
            long businessId,
            UUID actor,
            UUID counterparty,
            long amount,
            String type,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO business_ledger_entries(
                    business_id, actor_uuid, counterparty_uuid, amount_cents, entry_type, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, businessId);
            statement.setString(2, actor.toString());
            statement.setString(3, counterparty.toString());
            statement.setLong(4, amount);
            statement.setString(5, type);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Business transfer amount must be positive");
        }
    }

    private static void validateWage(long wageCents) {
        if (wageCents < 0) {
            throw new IllegalArgumentException("Business wage cannot be negative");
        }
    }

    private enum TransferKind {
        DEPOSIT,
        WITHDRAWAL,
        PAYMENT
    }

    private record BusinessRow(UUID proprietor, long balance, BusinessStatus status) {
    }

    private record OfferRow(BusinessRole role, long wageCents, long expiresAt) {
    }

    private record WageRow(UUID playerId, long amountCents) {
    }
}
