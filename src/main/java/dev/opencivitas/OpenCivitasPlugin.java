package dev.opencivitas;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.command.CivitasCommand;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.listener.CitizenListener;
import dev.opencivitas.message.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public final class OpenCivitasPlugin extends JavaPlugin {
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        long startingBalance;
        try {
            startingBalance = Money.parseCents(getConfig().getString("economy.starting-balance", "1200.00"));
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "economy.starting-balance is invalid", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String currencySymbol = getConfig().getString("economy.currency-symbol", "$");
        int pageSize = Math.max(1, Math.min(50, getConfig().getInt("economy.transaction-page-size", 10)));
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
        for (String name : List.of("balance", "pay", "transactions", "about", "locale")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }

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
