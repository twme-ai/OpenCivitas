package dev.opencivitas.shop;

import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.business.BusinessPermission;
import dev.opencivitas.business.BusinessResult;
import dev.opencivitas.business.BusinessRole;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
import dev.opencivitas.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRepositoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private BusinessRepository businesses;
    private ShopRepository shops;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ShopRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(OWNER, "Owner", "en_US", 120_000);
        citizens.register(CUSTOMER, "Customer", "en_US", 120_000);
        citizens.register(WORKER, "Worker", "en_US", 120_000);
        businesses = new BusinessRepository(database);
        shops = new ShopRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void createsPlayerShopAndProtectsActiveSignLocation() throws Exception {
        ShopCreation creation = shops.create(OWNER, playerDraft(10, 20, 30, 2_500L, 1_000L), NOW);

        assertEquals(ShopResult.SUCCESS, creation.result());
        ChestShop shop = creation.shop().orElseThrow();
        assertEquals(ShopOwnerType.PLAYER, shop.ownerType());
        assertEquals(OWNER, shop.ownerId());
        assertEquals("Owner", shop.accountName());
        assertEquals(ShopResult.LOCATION_OCCUPIED,
                shops.create(OWNER, playerDraft(10, 20, 30, 3_000L, null), NOW + 1).result());

        shops.deactivate(shop.id(), NOW + 2);
        assertEquals(ShopResult.SUCCESS,
                shops.create(OWNER, playerDraft(10, 20, 30, 3_000L, null), NOW + 3).result());
    }

    @Test
    void hologramPreferencePersistsAndActiveSnapshotExcludesDeactivatedShops() throws Exception {
        ChestShop shop = shops.create(
                OWNER, playerDraft(10, 20, 30, 2_500L, 1_000L), NOW).shop().orElseThrow();
        assertEquals(List.of(shop), shops.active());
        assertTrue(shops.hologramsVisible(OWNER));

        ShopHologramSetting hidden = shops.toggleHolograms(OWNER, NOW + 1);
        assertEquals(ShopResult.SUCCESS, hidden.result());
        assertFalse(hidden.visible());
        assertFalse(new ShopRepository(database).hologramsVisible(OWNER));
        assertTrue(shops.toggleHolograms(OWNER, NOW + 2).visible());
        assertEquals(ShopResult.CITIZEN_NOT_FOUND, shops.toggleHolograms(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), NOW + 3).result());

        shops.deactivate(shop.id(), NOW + 4);
        assertTrue(shops.active().isEmpty());
    }

    @Test
    void ownerCanEditShopConfigurationWithoutChangingItsAccount() throws Exception {
        ChestShop shop = shops.create(
                OWNER, playerDraft(10, 20, 30, 2_500L, 1_000L), NOW).shop().orElseThrow();
        ShopDraft updated = new ShopDraft(
                "world", 10, 20, 30, 10, 19, 30,
                ShopOwnerType.PLAYER, OWNER, null, "GOLD_INGOT", 2, 3_000L, null);

        assertEquals(ShopResult.SUCCESS, shops.canManage(OWNER, shop.id()));
        assertEquals(ShopResult.NO_PERMISSION, shops.canManage(WORKER, shop.id()));
        assertEquals(ShopResult.NO_PERMISSION, shops.update(WORKER, shop.id(), updated).result());
        assertEquals(ShopResult.SUCCESS, shops.update(OWNER, shop.id(), updated).result());

        ChestShop stored = shops.find(shop.id()).orElseThrow();
        assertEquals("GOLD_INGOT", stored.itemKey());
        assertEquals(2, stored.quantity());
        assertEquals(3_000L, stored.buyPriceCents());
        assertNull(stored.sellPriceCents());
        assertEquals(OWNER, stored.ownerId());

        ShopDraft ownerChange = new ShopDraft(
                "world", 10, 20, 30, 10, 19, 30,
                ShopOwnerType.BUSINESS, null, "acme", "GOLD_INGOT", 2, 3_000L, null);
        assertEquals(ShopResult.OWNER_CHANGE_NOT_ALLOWED,
                shops.update(OWNER, shop.id(), ownerChange).result());
    }

    @Test
    void playerSaleMovesExactFundsAndWritesBothLedgers() throws Exception {
        ChestShop shop = shops.create(
                OWNER, playerDraft(1, 2, 3, 2_500L, 1_000L), NOW).shop().orElseThrow();

        ShopSettlement result = shops.settle(shop.id(), CUSTOMER, ShopDirection.BUY, 2, NOW + 1);

        assertEquals(ShopResult.SUCCESS, result.result());
        assertEquals(8, result.itemAmount());
        assertEquals(5_000, result.totalCents());
        assertEquals(115_000, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(125_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.SHOP_PURCHASE,
                citizens.transactions(CUSTOMER, 10, 0).getFirst().type());
        assertEquals(LedgerEntryType.SHOP_SALE,
                citizens.transactions(OWNER, 10, 0).getFirst().type());
        assertEquals(1, shops.history(CUSTOMER, 10, 0).size());
        assertEquals(1, shops.history(OWNER, 10, 0).size());
    }

    @Test
    void rejectsSelfTradeAndUnderfundedOwnerWithoutPartialWrites() throws Exception {
        ChestShop shop = shops.create(
                OWNER, playerDraft(1, 2, 3, 500L, 200_000L), NOW).shop().orElseThrow();

        assertEquals(ShopResult.SELF_TRADE,
                shops.settle(shop.id(), OWNER, ShopDirection.BUY, 1, NOW + 1).result());
        assertEquals(ShopResult.OWNER_FUNDS,
                shops.settle(shop.id(), CUSTOMER, ShopDirection.SELL, 1, NOW + 2).result());
        assertEquals(120_000, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(120_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertTrue(shops.history(CUSTOMER, 10, 0).isEmpty());
    }

    @Test
    void supervisorCanCreateBusinessShopAndSettleAgainstFirmAccount() throws Exception {
        createBusiness();
        employ(WORKER, BusinessRole.SUPERVISOR);
        businesses.deposit(OWNER, "acme", 50_000);
        ShopDraft draft = businessDraft("acme", 5, 6, 7, 4_000L, 1_500L);

        ShopCreation creation = shops.create(WORKER, draft, NOW + 2);
        assertEquals(ShopResult.SUCCESS, creation.result());
        ChestShop shop = creation.shop().orElseThrow();
        assertEquals("acme", shop.businessSlug());

        assertEquals(ShopResult.SUCCESS,
                shops.settle(shop.id(), CUSTOMER, ShopDirection.BUY, 1, NOW + 3).result());
        assertEquals(54_000, businesses.find("acme").orElseThrow().balanceCents());
        assertEquals("SHOP_SALE", businesses.ledger("acme", 10, 0).getFirst().type());

        assertEquals(ShopResult.SUCCESS,
                shops.settle(shop.id(), CUSTOMER, ShopDirection.SELL, 2, NOW + 4).result());
        assertEquals(51_000, businesses.find("acme").orElseThrow().balanceCents());
        assertEquals(2, shops.businessSales("ACME", 10, 0).size());
        assertEquals(1, shops.search("DIAMOND", 10).size());
    }

    @Test
    void managerWithoutShopDutyCannotCreateBusinessShop() throws Exception {
        createBusiness();
        employ(WORKER, BusinessRole.MANAGER);

        assertEquals(ShopResult.NO_PERMISSION,
                shops.create(WORKER, businessDraft("acme", 5, 6, 7, 4_000L, null), NOW + 2).result());
        assertEquals(ShopResult.BUSINESS_NOT_FOUND,
                shops.create(OWNER, businessDraft("missing", 8, 9, 10, 4_000L, null), NOW + 2).result());
    }

    @Test
    void customChestShopRoleAuthorizesBusinessShop() throws Exception {
        createBusiness();
        assertEquals(BusinessResult.SUCCESS, businesses.createCustomRole(
                OWNER,
                "acme",
                "sales-associate",
                "Sales Associate",
                Set.of(BusinessPermission.CHEST_SHOP, BusinessPermission.DEFAULT)));
        employ(WORKER, businesses.resolveRole("acme", "sales-associate").orElseThrow());

        ChestShop shop = shops.create(
                WORKER, businessDraft("acme", 9, 10, 11, 2_000L, null), NOW + 2).shop().orElseThrow();
        ShopDraft updated = new ShopDraft(
                "world", 9, 10, 11, 9, 9, 11,
                ShopOwnerType.BUSINESS, null, "acme", "GOLD_INGOT", 2, 3_000L, 1_000L);
        assertEquals(ShopResult.SUCCESS, shops.canManage(WORKER, shop.id()));
        assertEquals(ShopResult.SUCCESS, shops.update(WORKER, shop.id(), updated).result());
        assertEquals("GOLD_INGOT", shops.find(shop.id()).orElseThrow().itemKey());
    }

    private ShopDraft playerDraft(int x, int y, int z, Long buy, Long sell) {
        return new ShopDraft(
                "world", x, y, z, x, y - 1, z,
                ShopOwnerType.PLAYER, OWNER, null, "DIAMOND", 4, buy, sell);
    }

    private ShopDraft businessDraft(String slug, int x, int y, int z, Long buy, Long sell) {
        return new ShopDraft(
                "world", x, y, z, x, y - 1, z,
                ShopOwnerType.BUSINESS, null, slug, "DIAMOND", 4, buy, sell);
    }

    private void createBusiness() throws Exception {
        new JobRepository(database).grantQualification(OWNER, "entrepreneur", null);
        assertEquals(BusinessResult.SUCCESS, businesses.create(OWNER, "acme", "Acme"));
    }

    private void employ(UUID player, BusinessRole role) throws Exception {
        assertEquals(BusinessResult.SUCCESS,
                businesses.offer(OWNER, "acme", player, role, 0, NOW, NOW + 10_000));
        assertEquals(BusinessResult.SUCCESS, businesses.acceptOffer(player, "acme", NOW + 1));
    }
}
