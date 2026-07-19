package dev.opencivitas.shop;

import dev.opencivitas.business.BusinessRole;
import dev.opencivitas.business.BusinessPermission;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ShopRepository {
    private static final String SHOP_SELECT = """
            SELECT s.*, owner.last_name AS owner_name,
                   b.display_name AS business_name, b.slug AS business_slug
            FROM chest_shops s
            LEFT JOIN players owner ON owner.uuid = s.owner_uuid
            LEFT JOIN businesses b ON b.id = s.business_id
            """;

    private static final String SALE_SELECT = """
            SELECT sale.*, customer.last_name AS customer_name,
                   shop.owner_type, shop.owner_uuid, owner.last_name AS owner_name,
                   business.display_name AS business_name, business.slug AS business_slug,
                   shop.world_name, shop.sign_x, shop.sign_y, shop.sign_z
            FROM shop_sales sale
            JOIN players customer ON customer.uuid = sale.customer_uuid
            JOIN chest_shops shop ON shop.id = sale.shop_id
            LEFT JOIN players owner ON owner.uuid = shop.owner_uuid
            LEFT JOIN businesses business ON business.id = shop.business_id
            """;

    private final Database database;

    public ShopRepository(Database database) {
        this.database = database;
    }

    public ShopCreation create(UUID actor, ShopDraft draft, long now) throws SQLException {
        validateDraft(draft);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!accountExists(connection, actor)) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.CITIZEN_NOT_FOUND);
                }
                if (activeAt(connection, draft.worldName(), draft.signX(), draft.signY(), draft.signZ())) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.LOCATION_OCCUPIED);
                }

                Long businessId = null;
                UUID ownerId = null;
                if (draft.ownerType() == ShopOwnerType.PLAYER) {
                    if (!actor.equals(draft.ownerId())) {
                        connection.rollback();
                        return ShopCreation.failed(ShopResult.NO_PERMISSION);
                    }
                    ownerId = actor;
                } else {
                    Optional<BusinessAccount> business = business(connection, draft.businessSlug());
                    if (business.isEmpty()) {
                        connection.rollback();
                        return ShopCreation.failed(ShopResult.BUSINESS_NOT_FOUND);
                    }
                    if (!business.get().active()) {
                        connection.rollback();
                        return ShopCreation.failed(ShopResult.BUSINESS_INACTIVE);
                    }
                    Optional<BusinessRole> role = businessRole(connection, business.get().id(), actor);
                    if (role.isEmpty() || !role.get().canManageShops()) {
                        connection.rollback();
                        return ShopCreation.failed(ShopResult.NO_PERMISSION);
                    }
                    businessId = business.get().id();
                }

                long id = insertShop(connection, draft, ownerId, businessId, now);
                ChestShop shop = find(connection, id).orElseThrow(
                        () -> new SQLException("Created chest shop could not be loaded"));
                connection.commit();
                return ShopCreation.created(shop);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<ChestShop> find(long id) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return find(connection, id);
        }
    }

    public ShopResult canManage(UUID actor, long shopId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<ChestShop> selected = find(connection, shopId);
            if (selected.isEmpty()) return ShopResult.SHOP_NOT_FOUND;
            if (!selected.get().active()) return ShopResult.SHOP_INACTIVE;
            return authorization(connection, actor, selected.get());
        }
    }

    public ShopCreation update(UUID actor, long shopId, ShopDraft draft) throws SQLException {
        validateDraft(draft);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ChestShop> selected = find(connection, shopId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.SHOP_NOT_FOUND);
                }
                ChestShop shop = selected.get();
                if (!shop.active()) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.SHOP_INACTIVE);
                }
                ShopResult authorization = authorization(connection, actor, shop);
                if (authorization != ShopResult.SUCCESS) {
                    connection.rollback();
                    return ShopCreation.failed(authorization);
                }
                if (!sameLocation(shop, draft)) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.SHOP_NOT_FOUND);
                }
                if (!sameOwner(connection, shop, draft)) {
                    connection.rollback();
                    return ShopCreation.failed(ShopResult.OWNER_CHANGE_NOT_ALLOWED);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE chest_shops
                        SET item_key = ?, quantity = ?, buy_price_cents = ?, sell_price_cents = ?
                        WHERE id = ? AND active = 1
                        """)) {
                    statement.setString(1, draft.itemKey());
                    statement.setInt(2, draft.quantity());
                    setNullableLong(statement, 3, draft.buyPriceCents());
                    setNullableLong(statement, 4, draft.sellPriceCents());
                    statement.setLong(5, shopId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Chest shop disappeared during update");
                    }
                }
                ChestShop updated = find(connection, shopId).orElseThrow(
                        () -> new SQLException("Updated chest shop could not be loaded"));
                connection.commit();
                return ShopCreation.created(updated);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<ChestShop> search(String itemKey, int limit) throws SQLException {
        String sql = SHOP_SELECT + """
                WHERE s.active = 1 AND s.item_key = ? COLLATE NOCASE
                ORDER BY s.created_at DESC, s.id DESC
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemKey);
            statement.setInt(2, limit);
            return readShops(statement);
        }
    }

    public ShopSettlement settle(
            long shopId,
            UUID customer,
            ShopDirection direction,
            int units,
            long now
    ) throws SQLException {
        if (units < 1 || units > 64) {
            throw new IllegalArgumentException("Shop transaction units must be between 1 and 64");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ChestShop> selected = find(connection, shopId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.SHOP_NOT_FOUND);
                }
                ChestShop shop = selected.get();
                if (!shop.active()) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.SHOP_INACTIVE);
                }
                Long unitPrice = direction == ShopDirection.BUY
                        ? shop.buyPriceCents() : shop.sellPriceCents();
                if (unitPrice == null) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.PRICE_UNAVAILABLE);
                }
                if (!accountExists(connection, customer)) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.CITIZEN_NOT_FOUND);
                }
                if (shop.ownerType() == ShopOwnerType.PLAYER && customer.equals(shop.ownerId())) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.SELF_TRADE);
                }
                if (shop.ownerType() == ShopOwnerType.BUSINESS
                        && !activeBusiness(connection, shop.businessId())) {
                    connection.rollback();
                    return ShopSettlement.failed(ShopResult.BUSINESS_INACTIVE);
                }

                int itemAmount;
                long total;
                try {
                    itemAmount = Math.multiplyExact(shop.quantity(), units);
                    total = Math.multiplyExact(unitPrice, units);
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("Shop transaction is too large", exception);
                }

                if (direction == ShopDirection.BUY) {
                    if (!debitPlayer(connection, customer, total)) {
                        connection.rollback();
                        return ShopSettlement.failed(ShopResult.CUSTOMER_FUNDS);
                    }
                    creditOwner(connection, shop, total);
                } else {
                    if (!debitOwner(connection, shop, total)) {
                        connection.rollback();
                        return ShopSettlement.failed(ShopResult.OWNER_FUNDS);
                    }
                    creditPlayer(connection, customer, total);
                }

                insertLedgers(connection, shop, customer, direction, total, now);
                long saleId = insertSale(connection, shop, customer, direction, itemAmount, total, now);
                long customerBalance = playerBalance(connection, customer);
                long ownerBalance = shop.ownerType() == ShopOwnerType.PLAYER
                        ? playerBalance(connection, shop.ownerId())
                        : businessBalance(connection, shop.businessId());
                connection.commit();
                return new ShopSettlement(
                        ShopResult.SUCCESS, saleId, itemAmount, total, customerBalance, ownerBalance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<ShopSale> history(UUID player, int limit, int offset) throws SQLException {
        String sql = SALE_SELECT + """
                WHERE sale.customer_uuid = ? OR shop.owner_uuid = ?
                ORDER BY sale.created_at DESC, sale.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            return readSales(statement);
        }
    }

    public List<ShopSale> businessSales(String slug, int limit, int offset) throws SQLException {
        String sql = SALE_SELECT + """
                WHERE business.slug = ? COLLATE NOCASE
                ORDER BY sale.created_at DESC, sale.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readSales(statement);
        }
    }

    public void deactivate(long shopId, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE chest_shops SET active = 0, deactivated_at = ? WHERE id = ? AND active = 1")) {
            statement.setLong(1, now);
            statement.setLong(2, shopId);
            statement.executeUpdate();
        }
    }

    public void deactivateContainer(String world, int x, int y, int z, long now) throws SQLException {
        String sql = """
                UPDATE chest_shops SET active = 0, deactivated_at = ?
                WHERE world_name = ? AND container_x = ? AND container_y = ? AND container_z = ? AND active = 1
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, y);
            statement.setInt(5, z);
            statement.executeUpdate();
        }
    }

    private static Optional<ChestShop> find(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SHOP_SELECT + "WHERE s.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readShop(results)) : Optional.empty();
            }
        }
    }

    private static List<ChestShop> readShops(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<ChestShop> shops = new ArrayList<>();
            while (results.next()) {
                shops.add(readShop(results));
            }
            return List.copyOf(shops);
        }
    }

    private static ChestShop readShop(ResultSet results) throws SQLException {
        ShopOwnerType ownerType = ShopOwnerType.valueOf(results.getString("owner_type"));
        String ownerUuid = results.getString("owner_uuid");
        long businessId = results.getLong("business_id");
        Long nullableBusinessId = results.wasNull() ? null : businessId;
        long buyPrice = results.getLong("buy_price_cents");
        Long nullableBuyPrice = results.wasNull() ? null : buyPrice;
        long sellPrice = results.getLong("sell_price_cents");
        Long nullableSellPrice = results.wasNull() ? null : sellPrice;
        String accountName = ownerType == ShopOwnerType.PLAYER
                ? results.getString("owner_name") : results.getString("business_name");
        return new ChestShop(
                results.getLong("id"),
                results.getString("world_name"),
                results.getInt("sign_x"), results.getInt("sign_y"), results.getInt("sign_z"),
                results.getInt("container_x"), results.getInt("container_y"), results.getInt("container_z"),
                ownerType,
                ownerUuid == null ? null : UUID.fromString(ownerUuid),
                nullableBusinessId,
                accountName,
                results.getString("business_slug"),
                results.getString("item_key"),
                results.getInt("quantity"),
                nullableBuyPrice,
                nullableSellPrice,
                results.getInt("active") == 1,
                Instant.ofEpochMilli(results.getLong("created_at"))
        );
    }

    private static List<ShopSale> readSales(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<ShopSale> sales = new ArrayList<>();
            while (results.next()) {
                ShopOwnerType ownerType = ShopOwnerType.valueOf(results.getString("owner_type"));
                String ownerUuid = results.getString("owner_uuid");
                sales.add(new ShopSale(
                        results.getLong("id"),
                        results.getLong("shop_id"),
                        UUID.fromString(results.getString("customer_uuid")),
                        results.getString("customer_name"),
                        ShopDirection.valueOf(results.getString("direction")),
                        results.getString("item_key"),
                        results.getInt("item_amount"),
                        results.getLong("total_cents"),
                        ownerType,
                        ownerUuid == null ? null : UUID.fromString(ownerUuid),
                        ownerType == ShopOwnerType.PLAYER
                                ? results.getString("owner_name") : results.getString("business_name"),
                        results.getString("business_slug"),
                        results.getString("world_name"),
                        results.getInt("sign_x"), results.getInt("sign_y"), results.getInt("sign_z"),
                        Instant.ofEpochMilli(results.getLong("created_at"))
                ));
            }
            return List.copyOf(sales);
        }
    }

    private static long insertShop(
            Connection connection,
            ShopDraft draft,
            UUID ownerId,
            Long businessId,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO chest_shops(
                    world_name, sign_x, sign_y, sign_z, container_x, container_y, container_z,
                    owner_type, owner_uuid, business_id, item_key, quantity,
                    buy_price_cents, sell_price_cents, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, draft.worldName());
            statement.setInt(2, draft.signX());
            statement.setInt(3, draft.signY());
            statement.setInt(4, draft.signZ());
            statement.setInt(5, draft.containerX());
            statement.setInt(6, draft.containerY());
            statement.setInt(7, draft.containerZ());
            statement.setString(8, draft.ownerType().name());
            if (ownerId == null) {
                statement.setNull(9, java.sql.Types.VARCHAR);
            } else {
                statement.setString(9, ownerId.toString());
            }
            if (businessId == null) {
                statement.setNull(10, java.sql.Types.BIGINT);
            } else {
                statement.setLong(10, businessId);
            }
            statement.setString(11, draft.itemKey());
            statement.setInt(12, draft.quantity());
            setNullableLong(statement, 13, draft.buyPriceCents());
            setNullableLong(statement, 14, draft.sellPriceCents());
            statement.setLong(15, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Chest shop insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static long insertSale(
            Connection connection,
            ChestShop shop,
            UUID customer,
            ShopDirection direction,
            int itemAmount,
            long total,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO shop_sales(
                    shop_id, customer_uuid, direction, item_key, item_amount, total_cents, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, shop.id());
            statement.setString(2, customer.toString());
            statement.setString(3, direction.name());
            statement.setString(4, shop.itemKey());
            statement.setInt(5, itemAmount);
            statement.setLong(6, total);
            statement.setLong(7, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Shop sale insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertLedgers(
            Connection connection,
            ChestShop shop,
            UUID customer,
            ShopDirection direction,
            long total,
            long now
    ) throws SQLException {
        long customerDelta = direction == ShopDirection.BUY ? -total : total;
        LedgerEntryType customerType = direction == ShopDirection.BUY
                ? LedgerEntryType.SHOP_PURCHASE : LedgerEntryType.SHOP_SALE;
        insertPlayerLedger(
                connection, customer, shop.ownerType() == ShopOwnerType.PLAYER ? shop.ownerId() : null,
                customerDelta, customerType, now);
        if (shop.ownerType() == ShopOwnerType.PLAYER) {
            insertPlayerLedger(
                    connection, shop.ownerId(), customer, -customerDelta,
                    direction == ShopDirection.BUY
                            ? LedgerEntryType.SHOP_SALE : LedgerEntryType.SHOP_PURCHASE,
                    now);
        } else {
            insertBusinessLedger(
                    connection, shop.businessId(), customer, customer, -customerDelta,
                    direction == ShopDirection.BUY ? "SHOP_SALE" : "SHOP_PURCHASE", now);
        }
    }

    private static void insertPlayerLedger(
            Connection connection,
            UUID player,
            UUID counterparty,
            long amount,
            LedgerEntryType type,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            if (counterparty == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, counterparty.toString());
            }
            statement.setLong(3, amount);
            statement.setString(4, type.name());
            statement.setLong(5, now);
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

    private static Optional<BusinessAccount> business(Connection connection, String slug) throws SQLException {
        String sql = "SELECT id, status FROM businesses WHERE slug = ? COLLATE NOCASE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(new BusinessAccount(
                                results.getLong("id"), "ACTIVE".equals(results.getString("status"))))
                        : Optional.empty();
            }
        }
    }

    private static Optional<BusinessRole> businessRole(
            Connection connection, long businessId, UUID player) throws SQLException {
        String sql = """
                SELECT m.role, r.role_key, r.display_name, r.administrator, r.financial,
                       r.chest_shop, r.default_access
                FROM business_members m
                LEFT JOIN business_custom_roles r
                    ON r.business_id = m.business_id
                    AND m.role = 'CUSTOM:' || r.role_key
                WHERE m.business_id = ? AND m.player_uuid = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                Optional<BusinessRole> builtIn = BusinessRole.builtIn(results.getString("role"));
                if (builtIn.isPresent()) {
                    return builtIn;
                }
                if (results.getString("role_key") == null) {
                    throw new SQLException("Missing custom business role: " + results.getString("role"));
                }
                EnumSet<BusinessPermission> permissions = EnumSet.noneOf(BusinessPermission.class);
                if (results.getBoolean("administrator")) {
                    permissions.add(BusinessPermission.ADMINISTRATOR);
                }
                if (results.getBoolean("financial")) {
                    permissions.add(BusinessPermission.FINANCIAL);
                }
                if (results.getBoolean("chest_shop")) {
                    permissions.add(BusinessPermission.CHEST_SHOP);
                }
                if (results.getBoolean("default_access")) {
                    permissions.add(BusinessPermission.DEFAULT);
                }
                return Optional.of(BusinessRole.custom(
                        results.getString("role_key"), results.getString("display_name"), permissions));
            }
        }
    }

    private static boolean activeAt(Connection connection, String world, int x, int y, int z)
            throws SQLException {
        String sql = """
                SELECT 1 FROM chest_shops
                WHERE world_name = ? AND sign_x = ? AND sign_y = ? AND sign_z = ? AND active = 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean accountExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static ShopResult authorization(Connection connection, UUID actor, ChestShop shop)
            throws SQLException {
        if (!accountExists(connection, actor)) return ShopResult.CITIZEN_NOT_FOUND;
        if (shop.ownerType() == ShopOwnerType.PLAYER) {
            return actor.equals(shop.ownerId()) ? ShopResult.SUCCESS : ShopResult.NO_PERMISSION;
        }
        if (!activeBusiness(connection, shop.businessId())) return ShopResult.BUSINESS_INACTIVE;
        return businessRole(connection, shop.businessId(), actor)
                .filter(BusinessRole::canManageShops)
                .isPresent() ? ShopResult.SUCCESS : ShopResult.NO_PERMISSION;
    }

    private static boolean sameLocation(ChestShop shop, ShopDraft draft) {
        return shop.worldName().equals(draft.worldName())
                && shop.signX() == draft.signX()
                && shop.signY() == draft.signY()
                && shop.signZ() == draft.signZ()
                && shop.containerX() == draft.containerX()
                && shop.containerY() == draft.containerY()
                && shop.containerZ() == draft.containerZ();
    }

    private static boolean sameOwner(Connection connection, ChestShop shop, ShopDraft draft) throws SQLException {
        if (shop.ownerType() != draft.ownerType()) return false;
        if (shop.ownerType() == ShopOwnerType.PLAYER) return shop.ownerId().equals(draft.ownerId());
        Optional<BusinessAccount> business = business(connection, draft.businessSlug());
        return business.isPresent() && business.get().id() == shop.businessId();
    }

    private static boolean activeBusiness(Connection connection, long businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM businesses WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean debitPlayer(Connection connection, UUID player, long amount) throws SQLException {
        String sql = """
                UPDATE accounts SET balance_cents = balance_cents - ?
                WHERE player_uuid = ? AND balance_cents >= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    private static void creditPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Player account not found while crediting shop transaction");
            }
        }
    }

    private static boolean debitOwner(Connection connection, ChestShop shop, long amount) throws SQLException {
        if (shop.ownerType() == ShopOwnerType.PLAYER) {
            return debitPlayer(connection, shop.ownerId(), amount);
        }
        String sql = """
                UPDATE businesses SET balance_cents = balance_cents - ?
                WHERE id = ? AND status = 'ACTIVE' AND balance_cents >= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amount);
            statement.setLong(2, shop.businessId());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    private static void creditOwner(Connection connection, ChestShop shop, long amount) throws SQLException {
        if (shop.ownerType() == ShopOwnerType.PLAYER) {
            creditPlayer(connection, shop.ownerId(), amount);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, amount);
            statement.setLong(2, shop.businessId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Business account not found while crediting shop transaction");
            }
        }
    }

    private static long playerBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Player account not found");
                }
                return results.getLong(1);
            }
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

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void validateDraft(ShopDraft draft) {
        if (draft.worldName() == null || draft.worldName().isBlank()
                || draft.ownerType() == null || draft.itemKey() == null || draft.itemKey().isBlank()
                || draft.quantity() < 1
                || (draft.buyPriceCents() == null && draft.sellPriceCents() == null)
                || (draft.buyPriceCents() != null && draft.buyPriceCents() <= 0)
                || (draft.sellPriceCents() != null && draft.sellPriceCents() <= 0)) {
            throw new IllegalArgumentException("Invalid chest shop draft");
        }
        if (draft.ownerType() == ShopOwnerType.PLAYER && draft.ownerId() == null) {
            throw new IllegalArgumentException("Player shop requires an owner");
        }
        if (draft.ownerType() == ShopOwnerType.BUSINESS
                && (draft.businessSlug() == null || draft.businessSlug().isBlank())) {
            throw new IllegalArgumentException("Business shop requires a business slug");
        }
    }

    private record BusinessAccount(long id, boolean active) {
    }
}
