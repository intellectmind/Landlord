package cn.kurt6.landlord;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StatsManager {
    private final Landlord plugin;
    private final Map<UUID, PlayerStats> statsCache = new HashMap<>();
    private File statsFile;
    private YamlConfiguration statsConfig;

    public StatsManager(Landlord plugin) {
        this.plugin = plugin;
        loadStats();
    }

    private void loadStats() {
        statsFile = new File(plugin.getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            plugin.saveResource("stats.yml", false);
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    public void saveStats() {
        try {
            statsConfig.save(statsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存统计文件: " + e.getMessage());
        }
    }

    public PlayerStats getPlayerStats(String playerName) {
        String path = "players." + playerName;
        if (!statsConfig.contains(path)) {
            return new PlayerStats();
        }
        return new PlayerStats(
                statsConfig.getInt(path + ".gamesPlayed"),
                statsConfig.getInt(path + ".gamesWon"),
                statsConfig.getInt(path + ".gamesLost"),
                statsConfig.getInt(path + ".points"),
                statsConfig.getDouble(path + ".netMoney", 0) // 只保留净收益
        );
    }

    public void updatePlayerStats(String playerName, PlayerStats stats) {
        String path = "players." + playerName;
        statsConfig.set(path + ".gamesPlayed", stats.getGamesPlayed());
        statsConfig.set(path + ".gamesWon", stats.getGamesWon());
        statsConfig.set(path + ".gamesLost", stats.getGamesLost());
        statsConfig.set(path + ".points", stats.getPoints());
        statsConfig.set(path + ".netMoney", stats.getNetMoney()); // 只保留净收益
        saveStats();
    }

    public void showStats(Player player) {
        PlayerStats stats = getPlayerStats(player.getName());
        player.sendMessage(ChatColor.GOLD + "=== 你的游戏统计 ===");
        player.sendMessage(ChatColor.YELLOW + "总游戏场次: " + stats.getGamesPlayed());
        player.sendMessage(ChatColor.GREEN + "胜利场次: " + stats.getGamesWon());
        player.sendMessage(ChatColor.RED + "失败场次: " + stats.getGamesLost());
        player.sendMessage(ChatColor.AQUA + "当前积分: " + stats.getPoints());
        player.sendMessage(ChatColor.GOLD + "净收益金币: " + String.format("%.2f", stats.getNetMoney()));

        if (stats.getGamesPlayed() > 0) {
            int winRate = (int) ((double) stats.getGamesWon() / stats.getGamesPlayed() * 100);
            player.sendMessage(ChatColor.YELLOW + "胜率: " + winRate + "%");
        }
    }

    public YamlConfiguration getStatsConfig() {
        return statsConfig;
    }

    public static class PlayerStats {
        private int gamesPlayed;
        private int gamesWon;
        private int gamesLost;
        private int points;
        private double netMoney;

        public PlayerStats() {}

        public PlayerStats(int gamesPlayed, int gamesWon, int gamesLost, int points, double netMoney) {
            this.gamesPlayed = gamesPlayed;
            this.gamesWon = gamesWon;
            this.gamesLost = gamesLost;
            this.points = points;
            this.netMoney = netMoney;
        }

        // Getter 和 Setter 方法
        public int getGamesPlayed() { return gamesPlayed; }
        public void incrementGamesPlayed() { gamesPlayed++; }

        public int getGamesWon() { return gamesWon; }
        public void incrementGamesWon() { gamesWon++; }

        public int getGamesLost() { return gamesLost; }
        public void incrementGamesLost() { gamesLost++; }

        public int getPoints() { return points; }
        public void addPoints(int amount) { points += amount; }

        public double getNetMoney() { return netMoney; }
        public void addNetMoney(double amount) { netMoney += amount; }
    }
}