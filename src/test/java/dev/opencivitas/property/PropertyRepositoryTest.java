package dev.opencivitas.property;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyRepositoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private PropertyRepository properties;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = PropertyRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(OWNER, "Owner", "en_US", 100_000);
        citizens.register(TENANT, "Tenant", "en_US", 100_000);
        citizens.register(FRIEND, "Friend", "en_US", 100_000);
        properties = new PropertyRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void registrationNormalizesBoundsAndRejectsOverlap() throws Exception {
        PropertyOperation created = properties.create(draft("r-101", 10, 20, 30, 0, 5, 15), NOW);

        assertEquals(PropertyResult.SUCCESS, created.result());
        Property property = created.property().orElseThrow();
        assertEquals(0, property.minX());
        assertEquals(20, property.maxY());
        assertTrue(property.contains("city", 4, 10, 20));
        assertFalse(property.contains("city", 4, 21, 20));
        assertEquals(PropertyResult.PLOT_ID_TAKEN,
                properties.create(draft("R-101", 50, 5, 50, 60, 10, 60), NOW).result());
        assertEquals(PropertyResult.OVERLAP,
                properties.create(draft("r-102", 10, 20, 30, 12, 22, 32), NOW).result());
        assertEquals(PropertyResult.SUCCESS,
                properties.create(draft("r-103", 11, 20, 30, 20, 30, 40), NOW).result());
    }

    @Test
    void instantPurchaseDebitsBuyerAndAssignsTitleholder() throws Exception {
        properties.create(draft("r-101", 0, 0, 0, 10, 10, 10), NOW);

        PropertyOperation purchase = properties.buy(OWNER, "R-101", NOW + 1);

        assertEquals(PropertyResult.SUCCESS, purchase.result());
        assertEquals(OWNER, purchase.property().orElseThrow().titleholderId());
        assertEquals(80_000, purchase.balanceCents());
        assertEquals(80_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.PROPERTY_PURCHASE,
                citizens.transactions(OWNER, 10, 0).getFirst().type());
        assertEquals(PropertyResult.OCCUPIED, properties.buy(TENANT, "r-101", NOW + 2).result());
        assertEquals(PropertyResult.OCCUPIED, properties.delete("r-101").result());
    }

    @Test
    void earlyUnrentSplitsEscrowBetweenRefundAndLandlord() throws Exception {
        properties.create(draft("r-101", 0, 0, 0, 10, 10, 10), NOW);
        properties.setTitleholder(null, true, "r-101", OWNER, NOW + 1);

        PropertyOperation rented = properties.rent(TENANT, "r-101", NOW + 2);
        assertEquals(PropertyResult.SUCCESS, rented.result());
        assertEquals(70_000, citizens.find(TENANT).orElseThrow().balanceCents());
        assertEquals(100_000, citizens.find(OWNER).orElseThrow().balanceCents());

        PropertyOperation unrented = properties.unrent(TENANT, "r-101", false, NOW + 502);
        assertEquals(PropertyResult.SUCCESS, unrented.result());
        assertEquals(15_000, unrented.amountCents());
        assertEquals(85_000, citizens.find(TENANT).orElseThrow().balanceCents());
        assertEquals(115_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.PROPERTY_RENT_REFUND,
                citizens.transactions(TENANT, 10, 0).getFirst().type());
        assertEquals(LedgerEntryType.PROPERTY_RENT_INCOME,
                citizens.transactions(OWNER, 10, 0).getFirst().type());
        assertTrue(unrented.property().orElseThrow().availableToRent());
    }

    @Test
    void expiryPaysFullEscrowAndReopensRental() throws Exception {
        properties.create(draft("r-101", 0, 0, 0, 10, 10, 10), NOW);
        properties.setTitleholder(null, true, "r-101", OWNER, NOW + 1);
        properties.rent(TENANT, "r-101", NOW + 2);

        assertEquals(1, properties.expireRentals(NOW + 1_002).size());
        Property expired = properties.find("r-101").orElseThrow();
        assertTrue(expired.availableToRent());
        assertEquals(130_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(70_000, citizens.find(TENANT).orElseThrow().balanceCents());
    }

    @Test
    void titleholderTenantAndTrustedBuilderHaveSeparateRoles() throws Exception {
        properties.create(draft("r-101", 0, 0, 0, 10, 10, 10), NOW);
        Property titled = properties.setTitleholder(null, true, "r-101", OWNER, NOW + 1)
                .property().orElseThrow();
        assertTrue(titled.canBuild(OWNER));

        Property tenanted = properties.setTenant(OWNER, false, "r-101", TENANT, NOW + 2)
                .property().orElseThrow();
        assertTrue(tenanted.canBuild(TENANT));
        assertEquals(PropertyResult.NO_PERMISSION,
                properties.setTenant(FRIEND, false, "r-101", FRIEND, NOW + 3).result());

        Property trusted = properties.trust(TENANT, false, "r-101", FRIEND, true, NOW + 3)
                .property().orElseThrow();
        assertTrue(trusted.canBuild(FRIEND));
        assertEquals(PropertyResult.ALREADY_TRUSTED,
                properties.trust(TENANT, false, "r-101", FRIEND, true, NOW + 4).result());
        assertEquals(PropertyResult.SUCCESS,
                properties.trust(OWNER, false, "r-101", FRIEND, false, NOW + 5).result());
    }

    @Test
    void rentalSearchFiltersAvailabilityAndPrice() throws Exception {
        properties.create(draft("cheap", 0, 0, 0, 10, 10, 10), NOW);
        properties.create(new PropertyDraft(
                "costly", "city", 20, 0, 0, 30, 10, 10,
                null, 50_000L, 1_000), NOW);
        properties.rent(TENANT, "cheap", NOW + 1);

        assertEquals(1, properties.searchRentals(40_000, 60_000, 10).size());
        assertEquals("costly", properties.searchRentals(0, Long.MAX_VALUE, 10).getFirst().plotId());
    }

    private static PropertyDraft draft(
            String id, int firstX, int firstY, int firstZ, int secondX, int secondY, int secondZ) {
        return new PropertyDraft(
                id.toLowerCase(), "city",
                firstX, firstY, firstZ, secondX, secondY, secondZ,
                20_000L, 30_000L, 1_000);
    }
}
