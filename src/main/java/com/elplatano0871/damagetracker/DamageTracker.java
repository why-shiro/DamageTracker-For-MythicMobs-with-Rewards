package com.elplatano0871.damagetracker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.TabCompleter;
import java.util.*;
import java.util.stream.Collectors;

public class DamageTracker extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Map<UUID, Map<UUID, Double>> bossDamageMaps;
    private Map<String, BossConfig> bossConfigs;
    private BossConfig defaultBossConfig;
    private Map<UUID, Double> bossMaxHealth;
    private String damageFormat;
    private String percentageFormat;

    private static class BossConfig {
        String victoryMessage;
        int topPlayersToShow;
        List<String> topPlayersFormat;
        String damageDisplay;

        BossConfig(String victoryMessage, int topPlayersToShow, List<String> topPlayersFormat, String damageDisplay) {
            this.victoryMessage = victoryMessage;
            this.topPlayersToShow = topPlayersToShow;
            this.topPlayersFormat = topPlayersFormat;
            this.damageDisplay = damageDisplay;
        }
    }

    @Override
    public void onEnable() {
        bossDamageMaps = new HashMap<>();
        bossConfigs = new HashMap<>();
        bossMaxHealth = new HashMap<>();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        new DamageTrackerPlaceholder(this).register();
        getCommand("damagetracker").setExecutor(this);
        getCommand("damagetracker").setTabCompleter(this);
        
        displayAsciiArt();
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();

        ConfigurationSection bossesSection = getConfig().getConfigurationSection("bosses");
        if (bossesSection != null) {
            for (String bossName : bossesSection.getKeys(false)) {
                ConfigurationSection bossSection = bossesSection.getConfigurationSection(bossName);
                if (bossSection != null && !bossSection.getKeys(false).isEmpty()) {
                    String victoryMessage = bossSection.getString("victory_message");
                    int topPlayersToShow = bossSection.getInt("top_players_to_show", 3);
                    List<String> topPlayersFormat = bossSection.getStringList("top_players_format");
                    String damageDisplay = bossSection.getString("damage_display", "percentage");
                    bossConfigs.put(bossName.toUpperCase(), new BossConfig(victoryMessage, topPlayersToShow, topPlayersFormat, damageDisplay));
                } else {
                    bossConfigs.put(bossName.toUpperCase(), null);
                }
            }
        }

        ConfigurationSection defaultSection = getConfig().getConfigurationSection("default_boss_config");
        if (defaultSection != null) {
            String defaultVictoryMessage = defaultSection.getString("victory_message");
            int defaultTopPlayersToShow = defaultSection.getInt("top_players_to_show", 3);
            List<String> defaultTopPlayersFormat = defaultSection.getStringList("top_players_format");
            String defaultDamageDisplay = defaultSection.getString("damage_display", "percentage");
            defaultBossConfig = new BossConfig(defaultVictoryMessage, defaultTopPlayersToShow, defaultTopPlayersFormat, defaultDamageDisplay);
        }

        damageFormat = getConfig().getString("damage_format", "%.2f");
        percentageFormat = getConfig().getString("percentage_format", "%.1f%%");
        getLogger().info("Configuration loaded. Number of configured bosses: " + bossConfigs.size());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("damagetracker")) {
            if (args.length > 0) {
                switch (args[0].toLowerCase()) {
                    case "reload":
                        return handleReloadCommand(sender);
                    case "damage":
                        return handleDamageCommand(sender);
                    case "top":
                        return handleTopCommand(sender);
                }
            }
            sender.sendMessage(ChatColor.YELLOW + "Available commands: /damagetracker reload, /damagetracker damage, /damagetracker top");
            return true;
        }
        return false;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("damagetracker")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                completions.add("reload");
                completions.add("damage");
                completions.add("top");
                return completions.stream()
                        .filter(c -> c.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return new ArrayList<>();
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (sender.hasPermission("damagetracker.reload")) {
            try {
                loadConfig();
                sender.sendMessage(ChatColor.GREEN + "DamageTracker configuration reloaded!");
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Error reloading configuration: " + e.getMessage());
                getLogger().severe("Error reloading configuration: " + e.getMessage());
            }
        } else {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
        }
        return true;
    }

    private boolean handleDamageCommand(CommandSender sender) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            double totalDamage = 0;
            for (Map<UUID, Double> damageMap : bossDamageMaps.values()) {
                totalDamage += damageMap.getOrDefault(player.getUniqueId(), 0.0);
            }
            sender.sendMessage(ChatColor.YELLOW + "Your current total damage: " + String.format(damageFormat, totalDamage));
        } else {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
        }
        return true;
    }

    private boolean handleTopCommand(CommandSender sender) {
        int topPlayersToShow = defaultBossConfig != null ? defaultBossConfig.topPlayersToShow : 3;
        Map<UUID, Double> totalDamageMap = new HashMap<>();
        
        for (Map<UUID, Double> damageMap : bossDamageMaps.values()) {
            for (Map.Entry<UUID, Double> entry : damageMap.entrySet()) {
                totalDamageMap.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        
        List<Map.Entry<UUID, Double>> topPlayers = getTopDamage(totalDamageMap, topPlayersToShow);
        sender.sendMessage(ChatColor.YELLOW + "Top " + topPlayersToShow + " damage dealers across all bosses:");
        for (int i = 0; i < topPlayers.size(); i++) {
            Map.Entry<UUID, Double> entry = topPlayers.get(i);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                sender.sendMessage(ChatColor.GOLD + String.valueOf(i + 1) + ". " + ChatColor.GREEN + player.getName() + 
                                   ChatColor.YELLOW + ": " + String.format(damageFormat, entry.getValue()));
            }
        }
        return true;
    }

    @EventHandler
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        ActiveMob activeMob = event.getMob();
        String mobInternalName = activeMob.getMobType();
        UUID mobUniqueId = activeMob.getUniqueId();

        if (!bossConfigs.containsKey(mobInternalName.toUpperCase())) {
            return;
        }

        BossConfig bossConfig = bossConfigs.get(mobInternalName.toUpperCase());
        if (bossConfig == null) {
            getLogger().info("Using default configuration for boss: " + mobInternalName);
            bossConfig = defaultBossConfig;
        }

        if (bossConfig == null || bossConfig.victoryMessage == null) {
            getLogger().warning("No victory message configured for boss: " + mobInternalName);
            return;
        }

        Map<UUID, Double> bossDamageMap = bossDamageMaps.getOrDefault(mobUniqueId, new HashMap<>());
        List<Map.Entry<UUID, Double>> topPlayers = getTopDamage(bossDamageMap, bossConfig.topPlayersToShow);

        String message = bossConfig.victoryMessage
            .replace("{boss_name}", activeMob.getDisplayName());

        StringBuilder topPlayersMessage = new StringBuilder();
        double maxHealth = bossMaxHealth.getOrDefault(mobUniqueId, 0.0);
        for (int i = 0; i < Math.min(bossConfig.topPlayersToShow, topPlayers.size()); i++) {
            Map.Entry<UUID, Double> entry = topPlayers.get(i);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                String format = i < bossConfig.topPlayersFormat.size() ? bossConfig.topPlayersFormat.get(i) : "&7{player_name}: &c{damage}";
                String damageString;
                if ("percentage".equalsIgnoreCase(bossConfig.damageDisplay) && maxHealth > 0) {
                    double percentage = (entry.getValue() / maxHealth) * 100;
                    damageString = String.format(percentageFormat, percentage);
                } else {
                    damageString = String.format(damageFormat, entry.getValue());
                }
                format = format.replace("{player_name}", player.getName())
                               .replace("{damage}", damageString);
                topPlayersMessage.append(format).append("\n");
            }
        }
        message = message.replace("{top_players}", topPlayersMessage.toString());

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
        
        bossDamageMaps.remove(mobUniqueId);
        bossMaxHealth.remove(mobUniqueId);
    }

    @EventHandler
    public void onMythicMobDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        LivingEntity entity = (LivingEntity) event.getEntity();

        try {
            ActiveMob activeMob = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
            if (activeMob != null && bossConfigs.containsKey(activeMob.getMobType().toUpperCase())) {
                UUID mobUniqueId = activeMob.getUniqueId();
                addDamage(mobUniqueId, player, event.getFinalDamage());
                
                if (!bossMaxHealth.containsKey(mobUniqueId)) {
                    bossMaxHealth.put(mobUniqueId, ((LivingEntity) activeMob.getEntity().getBukkitEntity()).getMaxHealth());
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error processing damage event: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void addDamage(UUID bossId, Player player, double damage) {
        UUID playerId = player.getUniqueId();
        bossDamageMaps.computeIfAbsent(bossId, k -> new HashMap<>())
                      .compute(playerId, (k, v) -> v == null ? damage : v + damage);
    }

    public List<Map.Entry<UUID, Double>> getTopDamage(Map<UUID, Double> damageMap, int limit) {
        return damageMap.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public double getPlayerDamage(UUID bossId, UUID playerId) {
        Map<UUID, Double> bossMap = bossDamageMaps.get(bossId);
        return bossMap != null ? bossMap.getOrDefault(playerId, 0.0) : 0.0;
    }

    public Map<UUID, Map<UUID, Double>> getAllDamageData() {
        return new HashMap<>(bossDamageMaps);
    }
    
    public double getBossMaxHealth(UUID bossId) {
        return bossMaxHealth.getOrDefault(bossId, 0.0);
    }

    private void displayAsciiArt() {
        String version = getDescription().getVersion();
        String enableMessage = String.format("v%s has been enabled!", version);

        String[] asciiArt = {
            " ____                                  _____               _             ",
            "|  _ \\  __ _ _ __ ___   __ _  __ _  __|_   _| __ __ _  ___| | _____ _ __",
            "| | | |/ _` | '_ ` _ \\ / _` |/ _` |/ _ \\| || '__/ _` |/ __| |/ / _ \\ '__|",
            "| |_| | (_| | | | | | | (_| | (_| |  __/| || | | (_| | (__|   <  __/ |  ",
            "|____/ \\__,_|_| |_| |_|\\__,_|\\__, |\\___|_||_|  \\__,_|\\___|_|\\_\\___|_|  ",
            "                             |___/                                      "
        };

        int maxAsciiWidth = Arrays.stream(asciiArt).mapToInt(String::length).max().orElse(0);
        int padding = 2;

        for (int i = 0; i < asciiArt.length; i++) {
            StringBuilder line = new StringBuilder(asciiArt[i]);
            while (line.length() < maxAsciiWidth) {
                line.append(" ");
            }
            line.append("  ");

            if (i == 2) {
                line.append("§9").append(enableMessage);
            }

            Bukkit.getConsoleSender().sendMessage(line.toString());
        }
    }
}