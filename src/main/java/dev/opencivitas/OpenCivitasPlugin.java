package dev.opencivitas;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.auction.AuctionRepository;
import dev.opencivitas.command.AuctionCommand;
import dev.opencivitas.command.BusinessCommand;
import dev.opencivitas.command.ClaimCommand;
import dev.opencivitas.command.CivitasCommand;
import dev.opencivitas.command.CourtCommand;
import dev.opencivitas.command.ExamCommand;
import dev.opencivitas.command.ElectionCommand;
import dev.opencivitas.command.JobCommand;
import dev.opencivitas.command.LegislatureCommand;
import dev.opencivitas.command.MobCaptureCommand;
import dev.opencivitas.command.PropertyCommand;
import dev.opencivitas.command.PoliceCommand;
import dev.opencivitas.command.ProtectionCommand;
import dev.opencivitas.command.ShopCommand;
import dev.opencivitas.command.ShopSignCommand;
import dev.opencivitas.command.HealthCommand;
import dev.opencivitas.command.ChatCommand;
import dev.opencivitas.command.NavigationCommand;
import dev.opencivitas.command.FamilyCommand;
import dev.opencivitas.command.VehicleCommand;
import dev.opencivitas.command.StockCommand;
import dev.opencivitas.command.NetworkCommand;
import dev.opencivitas.command.SecurityCommand;
import dev.opencivitas.database.Database;
import dev.opencivitas.court.CourtRepository;
import dev.opencivitas.claim.ClaimListener;
import dev.opencivitas.claim.ClaimRegistry;
import dev.opencivitas.claim.ClaimRepository;
import dev.opencivitas.economy.Money;
import dev.opencivitas.election.ElectionRegistry;
import dev.opencivitas.election.ElectionRepository;
import dev.opencivitas.elevator.ElevatorListener;
import dev.opencivitas.elevator.ElevatorPolicy;
import dev.opencivitas.exam.ExamRegistry;
import dev.opencivitas.exam.ExamRepository;
import dev.opencivitas.exam.UniversityService;
import dev.opencivitas.job.JobRegistry;
import dev.opencivitas.job.JobRepository;
import dev.opencivitas.health.HealthItems;
import dev.opencivitas.health.HealthListener;
import dev.opencivitas.health.HealthRegistry;
import dev.opencivitas.health.HealthRepository;
import dev.opencivitas.chat.ChatPolicy;
import dev.opencivitas.chat.ChatRepository;
import dev.opencivitas.chat.ChatRouter;
import dev.opencivitas.navigation.NavigationPolicy;
import dev.opencivitas.navigation.NavigationRepository;
import dev.opencivitas.navigation.NavigationService;
import dev.opencivitas.navigation.SafeTeleportService;
import dev.opencivitas.family.FamilyListener;
import dev.opencivitas.family.FamilyPolicy;
import dev.opencivitas.family.FamilyRegistry;
import dev.opencivitas.family.FamilyRepository;
import dev.opencivitas.vehicle.VehicleAccessService;
import dev.opencivitas.vehicle.VehicleItems;
import dev.opencivitas.vehicle.VehicleListener;
import dev.opencivitas.vehicle.VehicleManager;
import dev.opencivitas.vehicle.VehicleRegistry;
import dev.opencivitas.vehicle.VehicleRepository;
import dev.opencivitas.vehicle.VehicleStorageService;
import dev.opencivitas.stock.StockPolicy;
import dev.opencivitas.stock.StockRepository;
import dev.opencivitas.network.NetworkPolicy;
import dev.opencivitas.network.NetworkService;
import dev.opencivitas.security.CameraManager;
import dev.opencivitas.security.CameraViewService;
import dev.opencivitas.security.SecurityItems;
import dev.opencivitas.security.SecurityListener;
import dev.opencivitas.security.SecurityMenuService;
import dev.opencivitas.security.SecurityPolicy;
import dev.opencivitas.security.SecurityRegistry;
import dev.opencivitas.security.SecurityRepository;
import dev.opencivitas.legislature.LegislatureRepository;
import dev.opencivitas.legislature.LegislatureService;
import dev.opencivitas.listener.CitizenListener;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.mobcapture.MobCaptureListener;
import dev.opencivitas.mobcapture.MobCapturePolicy;
import dev.opencivitas.mobcapture.MobCaptureRepository;
import dev.opencivitas.police.CustodyService;
import dev.opencivitas.police.PoliceListener;
import dev.opencivitas.police.PolicePolicy;
import dev.opencivitas.police.PoliceRepository;
import dev.opencivitas.shop.ShopListener;
import dev.opencivitas.shop.ShopHologramService;
import dev.opencivitas.shop.ShopRepository;
import dev.opencivitas.property.PropertyListener;
import dev.opencivitas.property.PropertyRegistry;
import dev.opencivitas.property.PropertyRepository;
import dev.opencivitas.protection.PasswordHasher;
import dev.opencivitas.protection.ProtectionListener;
import dev.opencivitas.protection.ProtectionPolicy;
import dev.opencivitas.protection.ProtectionRegistry;
import dev.opencivitas.protection.ProtectionRepository;
import dev.opencivitas.protection.ProtectionSessionService;
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
    private VehicleManager vehicleManager;
    private VehicleStorageService vehicleStorage;
    private NetworkService networkService;
    private CameraViewService cameraViews;
    private CameraManager cameraManager;
    private ShopHologramService shopHolograms;

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

        MobCapturePolicy mobCapturePolicy;
        try {
            mobCapturePolicy = new MobCapturePolicy(this, jobRegistry);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load mob-capture.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MobCaptureRepository mobCaptures = new MobCaptureRepository(database);
        try {
            int recovered = mobCaptures.recoverPending(System.currentTimeMillis());
            if (recovered > 0) getLogger().warning("Refunded " + recovered + " interrupted mob captures");
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not recover pending mob captures", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MobCaptureCommand mobCaptureCommands = new MobCaptureCommand(
                this, database, citizens, mobCaptures, mobCapturePolicy,
                messages, currencySymbol, pageSize);
        PluginCommand mobCapture = Objects.requireNonNull(
                getCommand("mobcapture"), "Missing command mobcapture");
        mobCapture.setExecutor(mobCaptureCommands);
        mobCapture.setTabCompleter(mobCaptureCommands);
        getServer().getPluginManager().registerEvents(new MobCaptureListener(
                this, database, mobCapturePolicy, mobCaptures, messages, currencySymbol), this);

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

        shopHolograms = new ShopHologramService(this, database, shops, currencySymbol);
        try {
            shopHolograms.start(shops.active());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load chest shop holograms", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(shopHolograms, this);
        ShopCommand shopCommands = new ShopCommand(
                this, database, shops, shopHolograms, messages, currencySymbol, pageSize);
        for (String name : List.of("find", "chestshop")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(shopCommands);
            command.setTabCompleter(shopCommands);
        }
        ShopListener shopListener = new ShopListener(
                this, database, shops, shopHolograms, messages, currencySymbol);
        getServer().getPluginManager().registerEvents(shopListener, this);
        ShopSignCommand shopSignCommand = new ShopSignCommand(
                this, database, shops, shopListener, messages);
        for (String name : List.of("sign", "iteminfo")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(shopSignCommand);
            command.setTabCompleter(shopSignCommand);
        }

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

        ProtectionPolicy protectionPolicy;
        try {
            protectionPolicy = new ProtectionPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load block-protection.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ProtectionRepository protectionRepository = new ProtectionRepository(database, protectionPolicy);
        ProtectionRegistry protectionRegistry = new ProtectionRegistry();
        try {
            protectionRegistry.replaceAll(protectionRepository.loadState());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore block protections", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ProtectionSessionService protectionSessions = new ProtectionSessionService();
        PasswordHasher protectionPasswords = new PasswordHasher(protectionPolicy.passwordPepper());
        ProtectionCommand protectionCommands = new ProtectionCommand(
                this, database, citizens, protectionRepository, protectionRegistry,
                protectionSessions, protectionPasswords, messages);
        PluginCommand bolt = Objects.requireNonNull(getCommand("bolt"), "Missing command bolt");
        bolt.setExecutor(protectionCommands);
        bolt.setTabCompleter(protectionCommands);
        getServer().getPluginManager().registerEvents(new ProtectionListener(
                this, database, protectionRepository, protectionRegistry, protectionPolicy,
                protectionSessions, protectionPasswords, messages), this);

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

        ElectionRegistry electionRegistry;
        try {
            electionRegistry = new ElectionRegistry(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load elections.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ElectionRepository electionRepository = new ElectionRepository(
                database,
                electionRegistry.minimumVoterRecentPlaytime(),
                electionRegistry.voterRecentWindow());
        try {
            electionRepository.closeDue(System.currentTimeMillis());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not settle due elections", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ElectionCommand electionCommands = new ElectionCommand(
                this, database, citizens, electionRepository, electionRegistry, messages);
        PluginCommand electionCommand = Objects.requireNonNull(getCommand("election"), "Missing command election");
        electionCommand.setExecutor(electionCommands);
        electionCommand.setTabCompleter(electionCommands);
        getServer().getScheduler().runTaskTimer(this, () -> database.submit(
                () -> electionRepository.closeDue(System.currentTimeMillis()))
                .whenComplete((ignored, error) -> {
                    if (error != null && isEnabled()) {
                        getLogger().log(Level.SEVERE, "Could not settle due elections", error);
                    }
                }), 1_200L, 1_200L);

        int presidentialActionDays = Math.max(
                1, Math.min(60, getConfig().getInt("legislature.presidential-action-days", 14)));
        int constitutionalReferendumHours = Math.max(
                1, Math.min(168, getConfig().getInt("legislature.constitutional-referendum-hours", 48)));
        LegislatureRepository legislatureRepository = new LegislatureRepository(
                database, Duration.ofDays(presidentialActionDays));
        LegislatureService legislatureService = new LegislatureService(
                legislatureRepository, electionRepository, Duration.ofHours(constitutionalReferendumHours));
        try {
            legislatureService.settle(System.currentTimeMillis());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not settle legislative deadlines", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LegislatureCommand legislatureCommands = new LegislatureCommand(
                this, database, legislatureRepository, legislatureService, messages);
        for (String name : List.of("bill", "laws", "law")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(legislatureCommands);
            command.setTabCompleter(legislatureCommands);
        }
        getServer().getScheduler().runTaskTimer(this, () -> database.submit(() -> {
            legislatureService.settle(System.currentTimeMillis());
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null && isEnabled()) {
                getLogger().log(Level.SEVERE, "Could not settle legislative deadlines", error);
            }
        }), 1_200L, 1_200L);

        CourtCommand courtCommands = new CourtCommand(
                this, database, citizens, new CourtRepository(database), messages, currencySymbol);
        for (String name : List.of("case", "warrants")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(courtCommands);
            command.setTabCompleter(courtCommands);
        }

        PolicePolicy policePolicy;
        try {
            policePolicy = new PolicePolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load law-enforcement.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PoliceRepository policeRepository = new PoliceRepository(
                database, policePolicy.selfDefenseWindow(), policePolicy.reportWindow());
        CustodyService custody = new CustodyService(this, database, policeRepository, messages);
        try {
            custody.start(policeRepository.activeDetentions());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore active police detentions", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PoliceCommand policeCommands = new PoliceCommand(
                this, database, citizens, policeRepository, policePolicy,
                custody, messages, currencySymbol);
        for (String name : List.of("police", "911", "wanted", "records")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(policeCommands);
            command.setTabCompleter(policeCommands);
        }
        getServer().getPluginManager().registerEvents(
                new PoliceListener(this, database, policeRepository, custody, messages), this);

        HealthRegistry healthRegistry;
        try {
            healthRegistry = new HealthRegistry(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load health.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        HealthRepository healthRepository = new HealthRepository(database);
        try {
            healthRepository.releaseStaleClaims(
                    System.currentTimeMillis() - healthRegistry.callClaimTimeout().toMillis());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore medical call claims", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        HealthItems healthItems = new HealthItems(this, healthRegistry);
        healthItems.registerRecipes();
        HealthCommand healthCommands = new HealthCommand(
                this, database, citizens, healthRepository, healthRegistry,
                healthItems, messages, currencySymbol);
        for (String name : List.of("health", "doctor-attend", "bulkbill", "doh")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(healthCommands);
            command.setTabCompleter(healthCommands);
        }
        getServer().getPluginManager().registerEvents(healthCommands, this);
        HealthListener healthListener = new HealthListener(
                this, database, healthRepository, healthRegistry, healthItems, messages);
        getServer().getPluginManager().registerEvents(healthListener, this);
        healthListener.start();
        getServer().getScheduler().runTaskTimer(this, () -> database.submit(() ->
                healthRepository.releaseStaleClaims(
                        System.currentTimeMillis() - healthRegistry.callClaimTimeout().toMillis()))
                .exceptionally(error -> {
                    if (isEnabled()) getLogger().log(Level.WARNING, "Could not release stale medical calls", error);
                    return 0;
                }), 1_200L, 1_200L);

        ChatPolicy chatPolicy;
        try {
            chatPolicy = new ChatPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load chat.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ChatRepository chatRepository = new ChatRepository(database);
        NetworkPolicy networkPolicy;
        try {
            networkPolicy = new NetworkPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load network.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        networkService = new NetworkService(this, networkPolicy);
        ChatRouter chatRouter = new ChatRouter(
                this, database, chatRepository, chatPolicy, networkService, messages);
        ChatCommand chatCommands = new ChatCommand(
                this, database, citizens, chatRepository, chatPolicy, chatRouter, messages);
        for (String name : List.of(
                "g", "l", "murmur", "doj", "sen", "jud",
                "msg", "r", "mail", "ad", "ask", "ignore", "unignore", "timestamp")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(chatCommands);
            command.setTabCompleter(chatCommands);
        }
        getServer().getPluginManager().registerEvents(chatRouter, this);
        getServer().getPluginManager().registerEvents(networkService, this);
        networkService.setChatConsumer(chatRouter::receiveNetworkChat);
        NetworkCommand networkCommand = new NetworkCommand(this, networkService, messages);
        PluginCommand network = Objects.requireNonNull(getCommand("network"), "Missing command network");
        network.setExecutor(networkCommand);
        network.setTabCompleter(networkCommand);
        networkService.start();

        NavigationPolicy navigationPolicy;
        try {
            navigationPolicy = new NavigationPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load navigation.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        NavigationRepository navigationRepository = new NavigationRepository(database);
        SafeTeleportService safeTeleports = new SafeTeleportService(this);
        NavigationService navigationService = new NavigationService(
                this, messages, navigationPolicy.gpsUpdateTicks());
        NavigationCommand navigationCommands = new NavigationCommand(
                this, database, navigationRepository, navigationPolicy,
                navigationService, safeTeleports, propertyRegistry, messages);
        for (String name : List.of(
                "sethome", "home", "homes", "delhome", "civicwarp",
                "coords", "sendcoords", "map", "gps", "directions",
                "spawn", "spawn-north", "spawn-south", "spawn-university",
                "spawn-airport", "spawn-oakridge", "spawn-willow", "spawn-aventura")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(navigationCommands);
            command.setTabCompleter(navigationCommands);
        }
        getServer().getPluginManager().registerEvents(navigationService, this);
        navigationService.start();

        ElevatorPolicy elevatorPolicy;
        try {
            elevatorPolicy = new ElevatorPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load elevators.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(
                new ElevatorListener(elevatorPolicy, messages), this);

        FamilyPolicy familyPolicy;
        try {
            familyPolicy = new FamilyPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load families.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        FamilyRepository familyRepository = new FamilyRepository(database);
        FamilyRegistry familyRegistry = new FamilyRegistry();
        try {
            familyRegistry.replaceAll(familyRepository.activeMarriages());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore active family relationships", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        FamilyCommand familyCommands = new FamilyCommand(
                this, database, citizens, familyRepository, familyRegistry,
                familyPolicy, safeTeleports, messages);
        for (String name : List.of(
                "friend", "marriage", "partnerchat", "partnerhome",
                "setpartnerhome", "partnerpvp")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(familyCommands);
            command.setTabCompleter(familyCommands);
        }
        getServer().getPluginManager().registerEvents(
                new FamilyListener(familyRegistry, messages), this);

        VehicleRegistry vehicleRegistry;
        VehicleItems vehicleItems;
        try {
            vehicleRegistry = new VehicleRegistry(this);
            vehicleItems = new VehicleItems(this, vehicleRegistry);
            vehicleItems.registerRecipes();
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load vehicles.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        VehicleRepository vehicleRepository = new VehicleRepository(database);
        VehicleAccessService vehicleAccess = new VehicleAccessService(
                this, database, vehicleRepository);
        vehicleManager = new VehicleManager(
                this, database, vehicleRepository, vehicleRegistry, vehicleItems, messages);
        try {
            vehicleManager.start(vehicleRepository.all());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore vehicles", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        VehicleCommand vehicleCommands = new VehicleCommand(
                this, database, citizens, vehicleRepository, vehicleRegistry,
                vehicleItems, vehicleManager, vehicleAccess, messages);
        for (String name : List.of("vehicle", "recipes")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(vehicleCommands);
            command.setTabCompleter(vehicleCommands);
        }
        getServer().getPluginManager().registerEvents(vehicleCommands, this);
        vehicleStorage = new VehicleStorageService(
                this, database, vehicleRepository, vehicleManager, vehicleRegistry, messages);
        getServer().getPluginManager().registerEvents(vehicleStorage, this);
        getServer().getPluginManager().registerEvents(new VehicleListener(
                this, database, vehicleRepository, vehicleRegistry, vehicleItems,
                vehicleManager, vehicleAccess, vehicleStorage, messages), this);
        getServer().getOnlinePlayers().forEach(player -> vehicleAccess.refresh(player.getUniqueId()));
        getServer().getScheduler().runTaskTimer(this,
                () -> getServer().getOnlinePlayers().forEach(
                        player -> vehicleAccess.refresh(player.getUniqueId())), 600L, 600L);

        StockPolicy stockPolicy;
        try {
            stockPolicy = new StockPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load stocks.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        StockCommand stockCommands = new StockCommand(
                this, database, citizens, new StockRepository(database),
                stockPolicy, messages, currencySymbol);
        PluginCommand stockCommand = Objects.requireNonNull(getCommand("stock"), "Missing command stock");
        stockCommand.setExecutor(stockCommands);
        stockCommand.setTabCompleter(stockCommands);

        SecurityPolicy securityPolicy;
        try {
            securityPolicy = new SecurityPolicy(this);
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not load security.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SecurityRepository securityRepository = new SecurityRepository(database, securityPolicy);
        SecurityRegistry securityRegistry = new SecurityRegistry();
        try {
            securityRegistry.replaceAll(
                    securityRepository.allCameras(), securityRepository.allComputers());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore security cameras and computers", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SecurityItems securityItems = new SecurityItems(this);
        securityItems.registerRecipes(this);
        cameraManager = new CameraManager(this, securityRegistry, securityItems);
        cameraManager.start();
        cameraViews = new CameraViewService(
                this, securityRegistry, cameraManager, securityPolicy, messages);
        cameraViews.start();
        SecurityMenuService securityMenus = new SecurityMenuService(
                this, database, securityRepository, securityRegistry, cameraManager,
                cameraViews, securityItems, messages);
        SecurityCommand securityCommands = new SecurityCommand(
                this, database, citizens, securityRepository, securityRegistry,
                cameraManager, cameraViews, securityMenus, securityItems, messages);
        PluginCommand cctv = Objects.requireNonNull(getCommand("cctv"), "Missing command cctv");
        cctv.setExecutor(securityCommands);
        cctv.setTabCompleter(securityCommands);
        getServer().getPluginManager().registerEvents(cameraViews, this);
        getServer().getPluginManager().registerEvents(securityMenus, this);
        getServer().getPluginManager().registerEvents(new SecurityListener(
                this, database, securityRepository, securityRegistry, securityItems,
                cameraManager, cameraViews, securityMenus, messages), this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            List<java.util.UUID> online = getServer().getOnlinePlayers().stream()
                    .map(org.bukkit.entity.Player::getUniqueId).toList();
            database.submit(() -> {
                for (java.util.UUID playerId : online) citizens.heartbeatActivity(playerId, now);
                return null;
            }).exceptionally(error -> {
                if (isEnabled()) getLogger().log(Level.WARNING, "Could not record player activity", error);
                return null;
            });
        }, 1_200L, 1_200L);

        getServer().getPluginManager().registerEvents(
                new CitizenListener(this, database, citizens, messages, startingBalance, currencySymbol), this);
        messages.send(getServer().getConsoleSender(), "plugin.enabled");
    }

    @Override
    public void onDisable() {
        if (shopHolograms != null) {
            shopHolograms.stop();
        }
        if (cameraViews != null) {
            cameraViews.stop();
        }
        if (cameraManager != null) {
            cameraManager.stop();
        }
        if (networkService != null) {
            networkService.close();
        }
        if (vehicleStorage != null) {
            vehicleStorage.stop();
        }
        if (vehicleManager != null) {
            vehicleManager.stop();
        }
        if (database != null) {
            database.close();
        }
    }
}
