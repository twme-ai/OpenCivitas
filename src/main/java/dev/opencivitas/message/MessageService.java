package dev.opencivitas.message;

import dev.opencivitas.locale.LocaleResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, YamlConfiguration> catalogs = new LinkedHashMap<>();
    private final Map<UUID, String> preferences = new ConcurrentHashMap<>();
    private final List<String> supported;
    private final String defaultLocale;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        List<String> configured = plugin.getConfig().getStringList("locale.supported");
        if (configured.isEmpty()) {
            configured = List.of("en_US");
        }
        supported = configured.stream().map(LocaleResolver::normalize).distinct().toList();

        String requestedDefault = plugin.getConfig().getString("locale.default", "en_US");
        defaultLocale = LocaleResolver.resolve(requestedDefault, supported, supported.getFirst());
        for (String locale : supported) {
            catalogs.put(locale, loadCatalog(locale));
        }
    }

    public void send(CommandSender recipient, String key, TagResolver... resolvers) {
        recipient.sendMessage(component(recipient, key, resolvers));
    }

    public Component component(CommandSender recipient, String key, TagResolver... resolvers) {
        return component(locale(recipient), key, resolvers);
    }

    public Component component(String locale, String key, TagResolver... resolvers) {
        String template = template(locale, key);
        return miniMessage.deserialize(template, resolvers);
    }

    public String locale(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return defaultLocale;
        }
        String preference = preferences.get(player.getUniqueId());
        if (preference != null) {
            return preference;
        }
        return LocaleResolver.resolve(player.locale().toString(), supported, defaultLocale);
    }

    public void setPreference(UUID playerId, String locale) {
        if (locale == null) {
            preferences.remove(playerId);
        } else {
            preferences.put(playerId, locale);
        }
    }

    public void clear(UUID playerId) {
        preferences.remove(playerId);
    }

    public List<String> supported() {
        return supported;
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    private String template(String locale, String key) {
        YamlConfiguration selected = catalogs.getOrDefault(locale, catalogs.get(defaultLocale));
        String value = selected.getString(key);
        if (value == null && selected != catalogs.get(defaultLocale)) {
            value = catalogs.get(defaultLocale).getString(key);
        }
        if (value == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return "<red>Missing message: " + key + "</red>";
        }
        return value;
    }

    private YamlConfiguration loadCatalog(String locale) {
        String resource = "lang/" + locale + ".yml";
        File target = new File(plugin.getDataFolder(), resource);
        if (!target.exists()) {
            plugin.saveResource(resource, false);
        }

        YamlConfiguration catalog = YamlConfiguration.loadConfiguration(target);
        try (var stream = plugin.getResource(resource)) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                catalog.setDefaults(defaults);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not load defaults for " + resource, exception);
        }
        return catalog;
    }
}
