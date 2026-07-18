package dev.opencivitas;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.auction.AuctionRepository;
import dev.opencivitas.command.AuctionCommand;
import dev.opencivitas.command.BusinessCommand;
import dev.opencivitas.command.ClaimCommand;
import dev.opencivitas.command.CivitasCommand;
import dev.opencivitas.command.ExamCommand;
import dev.opencivitas.command.JobCommand;
import dev.opencivitas.command.PropertyCommand;
import dev.opencivitas.command.ShopCommand;
import dev.opencivitas.database.Database;
import dev.opencivitas.claim.ClaimListener;
import dev.opencivitas.claim.ClaimRegistry;
import dev.opencivitas.claim.ClaimRepository;
import dev.opencivitas.economy.Money;
import dev.opencivitas.exam.ExamRegistry;
import dev.opencivitas.exam.ExamRepository;
import dev.opencivitas.exam.UniversityService;
import dev.opencivitas.job.JobRegistry;
import dev.opencivitas.job.JobRepository;
import dev.opencivitas.listener.CitizenListener;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.shop.ShopListener;
import dev.opencivitas.shop.ShopRepository;
import dev.opencivitas.property.PropertyListener;
import dev.opencivitas.property.PropertyRegistry;
import dev.opencivitas.property.PropertyRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public final class OpenCivitasPlugin extends JavaPlugin {
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        long startingBalance;
        long claimBlockCost;
        long auctionMinimumIncrement;
        try {
            startingBalance = Money.parseCents(getConfig().getString("economy.starting-balance", "1200.00"));
            claimBlockCost = Money.parsePositiveCents(getConfig().getString("claims.block-cost", "20.00"));
            auctionMinimumIncrement = Money.parsePositiveCents(
                    getConfig().getString("auction.minimum-increment", "1.00"));
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "An economy amount in config.yml is invalid", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String currencySymbol = getConfig().getString("economy.currency-symbol", "$");
        int pageSize = Math.max(1, Math.min(50, getConfig().getInt("economy.transaction-page-size", 10)));
        long offerExpiryMinutes = Math.max(
                1, Math.min(525_600, getConfig().getLong("business.offer-expiry-minutes", 1_440)));
        long offerExpiryMillis = Duration.ofMinutes(offerExpiryMinutes).toMillis();
        int maximumClaimBlocks = Math.max(1, getConfig().getInt("claims.maximum-blocks", 4_096));
        int freeClaimBlocks = Math.max(0, Math.min(
                maximumClaimBlocks, getConfig().getInt("claims.free-blocks", 10)));
        int defaultRentalDays = Math.max(1, Math.min(
                3_650, getConfig().getInt("property.default-rental-days", 30)));
        int auctionListingLimit = Math.max(1, Math.min(100, getConfig().getInt("auction.listing-limit", 10)));
        int auctionDefaultHours = Math.max(1, Math.min(720, getConfig().getInt("auction.default-hours", 24)));
        int auctionMaximumHours = Math.max(
                auctionDefaultHours, Math.min(720, getConfig().getInt("auction.maximum-hours", 168)));
        List<String> claimWorlds = getConfig().getStringList("claims.enabled-worlds");
        if (claimWorlds.isEmpty()) {
            claimWorlds = List.of("wilderness");
        }
        Path dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
        Path databaseFile = dataDirectory
                .resolve(getConfig().getString("database.file", "opencivitas.db"))
                .normalize();
        if (!databaseFile.startsWith(dataDirectory)) {
            getLogger().severe("database.file must remain inside the OpenCivitas data directory");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        database = new Database(databaseFile);
        try {
            database.initialize(this);
        } catch (SQLException | IOException exception) {
            getLogger().log(Level.SEVERE, "Could not initialize the OpenCivitas database", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MessageService messages = new MessageService(this);
        CitizenRepository citizens = new CitizenRepository(database);
        CivitasCommand commands = new CivitasCommand(this, database, citizens, messages, currencySymbol, pageSize);
        for (String name : List.of("balance", "pay", "transactions", "baltop", "about", "locale")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }

        JobRegistry jobRegistry;
        try {
            jobRegistry = new JobRegistry(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load jobs.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        JobCommand jobCommands = new JobCommand(
                this, database, citizens, new JobRepository(database), jobRegistry, messages);
        for (String name : List.of(
                "jobs", "job", "qualifications", "qualification", "licenses", "license",
                "setprefix", "quitjob", "quitprofession")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(jobCommands);
            command.setTabCompleter(jobCommands);
        }

        ExamRegistry examRegistry;
        try {
            examRegistry = new ExamRegistry(this, messages.defaultLocale());
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load exams.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ExamCommand examCommands = new ExamCommand(
                this,
                database,
                new ExamRepository(database),
                examRegistry,
                new UniversityService(this),
                messages
        );
        for (String name : List.of("exams", "university")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(examCommands);
            command.setTabCompleter(examCommands);
        }
        getServer().getPluginManager().registerEvents(examCommands, this);

        PluginCommand businessCommand = Objects.requireNonNull(getCommand("business"), "Missing command business");
        ShopRepository shops = new ShopRepository(database);
        BusinessCommand businessCommands = new BusinessCommand(
                this,
                database,
                citizens,
                new BusinessRepository(database),
                shops,
                messages,
                currencySymbol,
                pageSize,
                offerExpiryMillis
        );
        businessCommand.setExecutor(businessCommands);
        businessCommand.setTabCompleter(businessCommands);

        ShopCommand shopCommands = new ShopCommand(
                this, database, shops, messages, currencySymbol, pageSize);
        for (String name : List.of("find", "chestshop")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(shopCommands);
            command.setTabCompleter(shopCommands);
        }
        getServer().getPluginManager().registerEvents(
                new ShopListener(this, database, shops, messages, currencySymbol), this);

        ClaimRepository claimRepository = new ClaimRepository(
                database, freeClaimBlocks, maximumClaimBlocks, claimBlockCost);
        ClaimRegistry claimRegistry = new ClaimRegistry(claimWorlds);
        try {
            claimRegistry.replaceAll(claimRepository.loadAll());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load wilderness claims", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ClaimCommand claimCommands = new ClaimCommand(
                this, database, citizens, claimRepository, claimRegistry,
                messages, currencySymbol, claimBlockCost);
        for (String name : List.of(
                "claim", "claimwand", "giveclaim", "claimexplosions", "claimkickout")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(claimCommands);
            command.setTabCompleter(claimCommands);
        }
        getServer().getPluginManager().registerEvents(
                new ClaimListener(this, database, claimRepository, claimRegistry, messages), this);
        getServer().getPluginManager().registerEvents(claimCommands, this);

        PropertyRepository propertyRepository = new PropertyRepository(database);
        PropertyRegistry propertyRegistry = new PropertyRegistry();
        try {
            propertyRegistry.replaceAll(propertyRepository.expireRentals(System.currentTimeMillis()));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load real-estate properties", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PropertyCommand propertyCommands = new PropertyCommand(
                this, database, citizens, propertyRepository, propertyRegistry,
                messages, currencySymbol, defaultRentalDays);
        PluginCommand propertyCommand = Objects.requireNonNull(
                getCommand("realestate"), "Missing command realestate");
        propertyCommand.setExecutor(propertyCommands);
        propertyCommand.setTabCompleter(propertyCommands);
        getServer().getPluginManager().registerEvents(
                new PropertyListener(propertyRegistry, messages), this);
        getServer().getScheduler().runTaskTimer(this, () -> database.submit(
                () -> propertyRepository.expireRentals(System.currentTimeMillis()))
                .whenComplete((loaded, error) -> {
                    if (!isEnabled()) {
                        return;
                    }
                    getServer().getScheduler().runTask(this, () -> {
                        if (error != null) {
                            getLogger().log(Level.SEVERE, "Could not settle expired property rentals", error);
                        } else {
                            propertyRegistry.replaceAll(loaded);
                        }
                    });
                }), 1_200L, 1_200L);

        AuctionRepository auctionRepository = new AuctionRepository(
                database, auctionMinimumIncrement, auctionListingLimit);
        try {
            auctionRepository.settleExpired(System.currentTimeMillis());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not settle expired auctions", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        AuctionCommand auctionCommands = new AuctionCommand(
                this, database, auctionRepository, messages, currencySymbol,
                auctionDefaultHours, auctionMaximumHours);
        PluginCommand auctionCommand = Objects.requireNonNull(getCommand("auction"), "Missing command auction");
        auctionCommand.setExecutor(auctionCommands);
        auctionCommand.setTabCompleter(auctionCommands);
        getServer().getPluginManager().registerEvents(auctionCommands, this);
        getServer().getScheduler().runTaskTimer(this, () -> database.submit(
                () -> auctionRepository.settleExpired(System.currentTimeMillis()))
                .whenComplete((ignored, error) -> {
                    if (error != null && isEnabled()) {
                        getLogger().log(Level.SEVERE, "Could not settle expired auctions", error);
                    }
                }), 1_200L, 1_200L);

        getServer().getPluginManager().registerEvents(
                new CitizenListener(this, database, citizens, messages, startingBalance, currencySymbol), this);
        messages.send(getServer().getConsoleSender(), "plugin.enabled");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }
}
