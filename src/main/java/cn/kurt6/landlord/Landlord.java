package cn.kurt6.landlord;

import net.milkbowl.vault.economy.Economy;
import net.md_5.bungee.api.chat.*;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Landlord extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, GameRoom> playerRooms = new ConcurrentHashMap<>();
    private final Map<String, GameRoom> gameRooms = new ConcurrentHashMap<>();
    private int roomCounter = 1;
    private StatsManager statsManager;
    private int turnTimeout = 60; // 默认值
    private Economy econ;
    private boolean bountyEnabled;
    private int moneyMultiplier;
    private boolean scoreboardEnabled = true;

    @Override
    public void onEnable() {
        // bStats
        int pluginId = 26770;
        cn.kurt6.back.bStats.Metrics metrics = new cn.kurt6.back.bStats.Metrics(this, pluginId);

        // 保存默认配置
        saveDefaultConfig();
        // 加载配置
        loadConfig();

        // 初始化经济系统
        if (!setupEconomy()) {
            getLogger().severe("未找到Vault经济系统插件，金币赛功能将不可用！");
            bountyEnabled = false;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("landlord").setExecutor(this);
        getCommand("landlord").setTabCompleter(this);
        getCommand("landlord_action").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        // 初始化统计管理器
        statsManager = new StatsManager(this);

        getLogger().info("服务器类型: " + (isFolia() ? "Folia，该核心计分版暂不可用，将用聊天消息代替" : "Bukkit/Paper"));
        getLogger().info("斗地主插件已启用！");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    public void loadConfig() {
        reloadConfig();
        turnTimeout = getConfig().getInt("turn-timeout", 60);
        bountyEnabled = getConfig().getBoolean("bounty-enabled", false);
        moneyMultiplier = getConfig().getInt("money-multiplier", 100);
        scoreboardEnabled = getConfig().getBoolean("scoreboard-enabled", false);
    }

    public boolean isScoreboardEnabled() {
        return scoreboardEnabled;
    }

    public int getTurnTimeout() {
        return turnTimeout;
    }

    @Override
    public void onDisable() {
        // 清理所有房间的BossBar
        for (GameRoom room : gameRooms.values()) {
            room.cleanup();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("只有玩家才能使用此命令！");
            return true;
        }

        Player player = (Player) sender;

        // 处理按钮点击命令
        if (command.getName().equals("landlord_action")) {
            if (args.length > 0) {
                GameRoom room = playerRooms.get(player.getUniqueId());
                if (room != null) {
                    room.handleActionCommand(player, args[0]);
                }
            }
            return true;
        }

        // 处理主命令
        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                createRoom(player, args);
                break;
            case "join":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "请输入房间号！用法: /ddz join <房间号>");
                    return true;
                }
                joinRoom(player, args[1]);
                break;
            case "leave":
                leaveRoom(player);
                break;
            case "ready":
                toggleReady(player);
                break;
            case "list":
                if (args.length > 1) {
                    try {
                        int page = Integer.parseInt(args[1]);
                        player.setMetadata("landlord_page", new FixedMetadataValue(this, page));
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "页码必须是数字！");
                    }
                }
                listRooms(player);
                break;
            case "stats":
                statsManager.showStats(player);
                break;
            case "top":
                showTopPlayers(player, args.length > 1 ? Integer.parseInt(args[1]) : 10);
                break;
            case "money":
                toggleMoneyGame(player);
                break;
            default:
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void toggleMoneyGame(Player player) {
        if (!bountyEnabled) {
            player.sendMessage(ChatColor.RED + "金币赛功能未启用！");
            return;
        }

        GameRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) {
            player.sendMessage(ChatColor.RED + "你不在任何房间中！");
            return;
        }

        room.toggleMoneyGame(player);
    }

    private void showTopPlayers(CommandSender sender, int topN) {
        // 限制查询数量（1-100）
        topN = Math.max(1, Math.min(topN, 100));

        // 从 stats.yml 加载所有玩家数据
        ConfigurationSection playersSection = statsManager.getStatsConfig().getConfigurationSection("players");
        if (playersSection == null) {
            sender.sendMessage(ChatColor.YELLOW + "暂无玩家统计数据");
            return;
        }

        // 收集玩家数据
        List<PlayerStatsData> statsList = new ArrayList<>();
        for (String playerName : playersSection.getKeys(false)) {
            StatsManager.PlayerStats stats = statsManager.getPlayerStats(playerName);
            double winRate = stats.getGamesPlayed() > 0 ?
                    ((double) stats.getGamesWon() / stats.getGamesPlayed() * 100) : 0;

            statsList.add(new PlayerStatsData(
                    playerName,
                    stats.getPoints(),
                    stats.getGamesWon(),
                    stats.getGamesLost(),
                    stats.getNetMoney(),
                    winRate
            ));
        }

        if (statsList.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "暂无玩家统计数据");
            return;
        }

        // 按积分排序（降序）
        statsList.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));

        // 显示排行榜
        sender.sendMessage(ChatColor.GOLD + "=== 斗地主排行榜 TOP " + Math.min(topN, statsList.size()) + " ===");
        sender.sendMessage(ChatColor.YELLOW + String.format("%-4s %-12s %-6s %-4s %-4s %-10s %-6s",
                "排名", "玩家名称", "积分", "胜场", "败场", "净收益", "胜率"));

        int rank = 1;
        for (PlayerStatsData data : statsList) {
            if (rank > topN) break;

            String rankColor = getRankColor(rank);
            sender.sendMessage(String.format(
                    "%s%-4d %s%-12s %s%-6d %s%-4d %s%-4d %s%-10.2f %s%.1f%%",
                    rankColor, rank,
                    ChatColor.AQUA, data.getPlayerName(),
                    ChatColor.GOLD, data.getPoints(),
                    ChatColor.GREEN, data.getWins(),
                    ChatColor.RED, data.getLosses(),
                    ChatColor.YELLOW, data.getNetMoney(),
                    ChatColor.LIGHT_PURPLE, data.getWinRate()
            ));
            rank++;
        }

        // 显示当前玩家的排名（如果不在前N名）
        if (sender instanceof Player) {
            String playerName = sender.getName();
            int playerRank = getPlayerRank(statsList, playerName);
            if (playerRank > 0 && playerRank > topN) {
                PlayerStatsData playerData = statsList.get(playerRank - 1);
                sender.sendMessage(ChatColor.GRAY + "你的排名: " + playerRank +
                        " (积分: " + ChatColor.GOLD + playerData.getPoints() +
                        ChatColor.GRAY + " 胜场: " + ChatColor.GREEN + playerData.getWins() +
                        ChatColor.GRAY + " 败场: " + ChatColor.RED + playerData.getLosses() +
                        ChatColor.GRAY + " 净收益: " + ChatColor.YELLOW + String.format("%.2f", playerData.getNetMoney()) +
                        ChatColor.GRAY + " 胜率: " + ChatColor.LIGHT_PURPLE + String.format("%.1f%%", playerData.getWinRate()) + ")");
            }
        }
    }

    private static class PlayerStatsData {
        private final String playerName;
        private final int wins;
        private final int losses;
        private final double netMoney;
        private final double winRate;
        private final int points;

        public PlayerStatsData(String playerName, int points, int wins, int losses, double netMoney, double winRate) {
            this.playerName = playerName;
            this.points = points;
            this.wins = wins;
            this.losses = losses;
            this.netMoney = netMoney;
            this.winRate = winRate;
        }

        public int getPoints() { return points; }
        public String getPlayerName() { return playerName; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public double getNetMoney() { return netMoney; }
        public double getWinRate() { return winRate; }
    }

    // 获取玩家排名
    private int getPlayerRank(List<PlayerStatsData> statsList, String playerName) {
        for (int i = 0; i < statsList.size(); i++) {
            if (statsList.get(i).getPlayerName().equals(playerName)) {
                return i + 1;
            }
        }
        return -1; // 未上榜
    }

    // 排名颜色（金、银、铜、白）
    private String getRankColor(int rank) {
        switch (rank) {
            case 1: return ChatColor.GOLD.toString();
            case 2: return ChatColor.GRAY.toString();
            case 3: return ChatColor.RED.toString();
            default: return ChatColor.WHITE.toString();
        }
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 斗地主游戏帮助 ===");

        // 创建房间
        TextComponent createMsg = new TextComponent(ChatColor.YELLOW + "/ddz create <房间号 可选> - 创建房间");
        createMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击自动输入创建命令\n房间号规则: 字母/数字/_-，长度3-16").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        createMsg.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ddz create "));
        player.spigot().sendMessage(createMsg);

        // 加入房间
        TextComponent joinMsg = new TextComponent(ChatColor.YELLOW + "/ddz join <房间号> - 加入房间");
        joinMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击自动输入加入命令\nTab键可补全现有房间号").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        joinMsg.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ddz join "));
        player.spigot().sendMessage(joinMsg);

        // 离开房间
        TextComponent leaveMsg = new TextComponent(ChatColor.YELLOW + "/ddz leave - 离开房间");
        leaveMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击立即执行离开房间命令").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        leaveMsg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ddz leave"));
        player.spigot().sendMessage(leaveMsg);

        // 准备/取消准备
        TextComponent readyMsg = new TextComponent(ChatColor.YELLOW + "/ddz ready - 准备/取消准备");
        readyMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击立即切换准备状态").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        readyMsg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ddz ready"));
        player.spigot().sendMessage(readyMsg);

        // 查看房间列表
        TextComponent listMsg = new TextComponent(ChatColor.YELLOW + "/ddz list - 打开房间列表GUI");
        listMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击立即查看所有可用房间").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        listMsg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ddz list"));
        player.spigot().sendMessage(listMsg);

        // 查看个人统计
        TextComponent statsMsg = new TextComponent(ChatColor.YELLOW + "/ddz stats - 查看个人统计");
        statsMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击查看你的游戏统计数据").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        statsMsg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ddz stats"));
        player.spigot().sendMessage(statsMsg);

        // 查看积分排行榜
        TextComponent topMsg = new TextComponent(ChatColor.YELLOW + "/ddz top [数量] - 查看积分排行榜");
        topMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击查看前10名玩家\n可指定数量如/ddz top 5").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
        topMsg.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ddz top 10"));
        player.spigot().sendMessage(topMsg);

        // 金币赛开关
        if (bountyEnabled) {
            TextComponent moneyMsg = new TextComponent(ChatColor.YELLOW + "/ddz money - 房主开关金币赛");
            moneyMsg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("点击切换金币赛模式\n需要Vault经济系统支持").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
            moneyMsg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ddz money"));
            player.spigot().sendMessage(moneyMsg);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("landlord")) {
            if (args.length == 1) {
                List<String> subCommands = Arrays.asList("create", "join", "leave", "ready", "list", "stats", "top", "help", "money");
                for (String subCmd : subCommands) {
                    if (subCmd.startsWith(args[0].toLowerCase())) {
                        completions.add(subCmd);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                // 提示玩家可以输入自定义房间号（可选）
                completions.add("<房间号（可选）>");
                completions.add("规则: 字母/数字/_-，长度3-16");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
                // 补全已有的房间号
                for (String roomId : gameRooms.keySet()) {
                    if (roomId.startsWith(args[1])) {
                        completions.add(roomId);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
                completions.add("<页码>");
            }
        }
        return completions;
    }

    private void createRoom(Player player, String[] args) {
        if (playerRooms.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "你已经在一个房间中了！");
            return;
        }

        String roomId;
        if (args.length > 1) {
            // 使用玩家输入的房间号
            roomId = args[1];

            // 验证房间号合法性
            if (!isValidRoomId(roomId)) {
                player.sendMessage(ChatColor.RED + "房间号只能包含字母、数字、下划线(_)或横线(-)，且长度为3-16字符！");
                return;
            }

            if (gameRooms.containsKey(roomId)) {
                player.sendMessage(ChatColor.RED + "房间号已存在！");
                return;
            }
        } else {
            // 自动生成房间号
            roomId = "room" + roomCounter++;
        }

        GameRoom room = new GameRoom(roomId, player, this);
        gameRooms.put(roomId, room);
        playerRooms.put(player.getUniqueId(), room);

        player.sendMessage(ChatColor.GREEN + "房间创建成功！房间号: " + roomId);
        if (bountyEnabled) {
            player.sendMessage(ChatColor.RED + "房主可输入 /ddz money 开关金币赛");
        }
        room.addPlayer(player);
    }

    /**
     * 验证房间号是否合法
     * @param roomId 房间号
     * @return 是否合法（true=合法，false=非法）
     */
    private boolean isValidRoomId(String roomId) {
        // 正则规则：字母、数字、下划线、横线，长度3-16
        String regex = "^[a-zA-Z0-9_-]{3,16}$";
        return roomId.matches(regex);
    }

    private final Map<UUID, Long> lastJoinAttempt = new ConcurrentHashMap<>(); // 记录上次点击时间
    private void joinRoom(Player player, String roomId) {
        // 防止重复点击
        long now = System.currentTimeMillis();
        if (lastJoinAttempt.containsKey(player.getUniqueId())) {
            long lastClick = lastJoinAttempt.get(player.getUniqueId());
            if (now - lastClick < 100) {
                return;
            }
        }
        lastJoinAttempt.put(player.getUniqueId(), now);

        if (playerRooms.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "你已经在一个房间中了！");
            return;
        }

        GameRoom room = gameRooms.get(roomId);
        if (room == null) {
            player.sendMessage(ChatColor.RED + "房间不存在！");
            return;
        }

        // 游戏进行中禁止加入
        if (room.isGameStarted()) {
            player.sendMessage(ChatColor.RED + "游戏进行中，无法加入房间！");
            return;
        }

        if (room.getPlayerCount() >= 3) {
            player.sendMessage(ChatColor.RED + "房间已满！");
            return;
        }

        playerRooms.put(player.getUniqueId(), room);
        room.addPlayer(player);
        player.sendMessage(ChatColor.GREEN + "成功加入房间: " + roomId);
    }

    private void leaveRoom(Player player) {
        GameRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) {
            player.sendMessage(ChatColor.RED + "你不在任何房间中！");
            return;
        }

        // 游戏进行中禁止离开
        if (room.isGameStarted()) {
            player.sendMessage(ChatColor.RED + "游戏进行中，无法离开房间！");
            return;
        }

        room.removePlayer(player);
        playerRooms.remove(player.getUniqueId());
        player.sendMessage(ChatColor.YELLOW + "已离开房间");

        // 如果房间空了，删除房间
        if (room.getPlayerCount() == 0) {
            gameRooms.remove(room.getRoomId());
        }
    }

    private void toggleReady(Player player) {
        GameRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) {
            player.sendMessage(ChatColor.RED + "你不在任何房间中！");
            return;
        }

        // 金币检查由GameRoom处理
        room.toggleReady(player);
    }

    private void listRooms(Player player) {
        // 如果玩家在房间中，显示确认对话框
        if (playerRooms.containsKey(player.getUniqueId())) {
            GameRoom currentRoom = playerRooms.get(player.getUniqueId());

            // 创建确认GUI
            Inventory confirmGui = Bukkit.createInventory(player, 27, ChatColor.RED + "确认切换房间?");

            // 确认按钮
            ItemStack confirm = new ItemStack(Material.GREEN_WOOL);
            ItemMeta confirmMeta = confirm.getItemMeta();
            confirmMeta.setDisplayName(ChatColor.GREEN + "确认切换");
            confirmMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "你当前在房间: " + currentRoom.getRoomId(),
                    ChatColor.RED + "切换房间将自动离开当前房间"
            ));
            confirm.setItemMeta(confirmMeta);
            confirmGui.setItem(11, confirm);

            // 取消按钮
            ItemStack cancel = new ItemStack(Material.RED_WOOL);
            ItemMeta cancelMeta = cancel.getItemMeta();
            cancelMeta.setDisplayName(ChatColor.RED + "取消");
            cancel.setItemMeta(cancelMeta);
            confirmGui.setItem(15, cancel);

            player.openInventory(confirmGui);
        } else {
            // 不在房间中，直接打开房间列表
            openRoomListGUI(player, 1);
        }
    }

    private void openRoomListGUI(Player player, int page) {
        if (gameRooms.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "当前没有房间");
            player.closeInventory();
            return;
        }

        // 创建6行(54格)的GUI
        Inventory gui = Bukkit.createInventory(player, 54, ChatColor.GOLD + "房间列表 - 第 " + page + " 页");

        // 排序房间：等待中的在前，游戏中的在后
        List<GameRoom> sortedRooms = new ArrayList<>(gameRooms.values());
        sortedRooms.sort((r1, r2) -> {
            if (r1.isGameStarted() == r2.isGameStarted()) return 0;
            return r1.isGameStarted() ? 1 : -1;
        });

        // 每页显示45个房间(5行)
        int itemsPerPage = 45;
        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, sortedRooms.size());

        // 添加房间物品
        for (int i = startIndex; i < endIndex; i++) {
            GameRoom room = sortedRooms.get(i);
            gui.setItem(i - startIndex, createRoomItem(player, room));
        }

        // 添加分页按钮
        addPaginationButtons(gui, page, (int) Math.ceil((double) sortedRooms.size() / itemsPerPage));

        player.openInventory(gui);
    }

    private ItemStack createRoomItem(Player viewer, GameRoom room) {
        Material material;
        ChatColor color;
        String status;

        // 金币房特殊处理（带附魔效果）
        if (room.isMoneyGame()) {
            // 检查玩家金币是否足够
            double playerBalance = econ.getBalance(viewer);
            double required = getMoneyMultiplier();
            boolean hasEnough = playerBalance >= required;

            // 根据金币是否足够选择不同材质
            if (hasEnough) {
                material = room.isGameStarted() ? Material.GOLD_BLOCK : Material.EMERALD_BLOCK;
            } else {
                material = room.isGameStarted() ? Material.REDSTONE_BLOCK : Material.COAL_BLOCK;
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            // 金币足够的才有附魔光效
            if (hasEnough) {
                meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            status = room.isGameStarted() ? ChatColor.RED + "游戏中(金币房)" : ChatColor.GREEN + "等待中(金币房)";
            meta.setDisplayName((hasEnough ? ChatColor.GOLD : ChatColor.GRAY) + room.getRoomId());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "状态: " + status);
            lore.add(ChatColor.GRAY + "玩家: " + room.getPlayerCount() + "/3");
            lore.add(ChatColor.GRAY + "倍数: " + room.getMultiplier());
            lore.add(ChatColor.GOLD + "金币要求: " + required);
            lore.add(ChatColor.YELLOW + "我的金币: " + playerBalance +
                    (hasEnough ? ChatColor.GREEN + " (满足要求)" : ChatColor.RED + " (不满足要求)"));

            if (room.getRoomOwner() != null) {
                lore.add(ChatColor.GRAY + "房主: " + room.getRoomOwner().getName());
            }
            lore.add("");
            lore.add(hasEnough ? ChatColor.YELLOW + "点击加入房间" : ChatColor.GRAY + "金币不足无法加入");

            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
        // 普通房间
        else {
            material = room.isGameStarted() ? Material.RED_WOOL : Material.LIME_WOOL;
            color = room.isGameStarted() ? ChatColor.RED : ChatColor.GREEN;
            status = room.isGameStarted() ? "游戏中" : "等待中";

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color + room.getRoomId());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "状态: " + color + status);
            lore.add(ChatColor.GRAY + "玩家: " + room.getPlayerCount() + "/3");
            if (room.getRoomOwner() != null) {
                lore.add(ChatColor.GRAY + "房主: " + room.getRoomOwner().getName());
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "点击加入房间");

            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
    }

    private void addPaginationButtons(Inventory gui, int currentPage, int totalPages) {
        // 上一页按钮（位置48）
        if (currentPage > 1) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevPage.getItemMeta();
            prevMeta.setDisplayName(ChatColor.YELLOW + "上一页");
            prevPage.setItemMeta(prevMeta);
            gui.setItem(48, prevPage);
        }

        // 当前页信息（位置49）
        ItemStack pageInfo = new ItemStack(Material.PAPER);
        ItemMeta pageMeta = pageInfo.getItemMeta();
        pageMeta.setDisplayName(ChatColor.GOLD + "第 " + currentPage + "/" + totalPages + " 页");
        pageInfo.setItemMeta(pageMeta);
        gui.setItem(49, pageInfo);

        // 下一页按钮（位置50）
        if (currentPage < totalPages) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextPage.getItemMeta();
            nextMeta.setDisplayName(ChatColor.YELLOW + "下一页");
            nextPage.setItemMeta(nextMeta);
            gui.setItem(50, nextPage);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // 防止点击自己物品栏
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        event.setCancelled(true); // 先取消事件

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String title = event.getView().getTitle();

        // 处理确认对话框
        if (title.equals(ChatColor.RED + "确认切换房间?")) {
            handleConfirmDialogClick(player, clicked);
            return;
        }

        // 处理房间列表GUI
        if (title.startsWith(ChatColor.GOLD + "房间列表")) {
            handleRoomListClick(player, clicked, title);
        }
    }

    private void handleConfirmDialogClick(Player player, ItemStack clicked) {
        GameRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) return;

        // 游戏进行中禁止离开
        if (room.isGameStarted()) {
            player.sendMessage(ChatColor.RED + "游戏进行中，无法离开房间！");
            return;
        }

        String displayName = clicked.getItemMeta().getDisplayName();
        if (displayName.equals(ChatColor.GREEN + "确认切换")) {
            // 离开当前房间
            GameRoom currentRoom = playerRooms.get(player.getUniqueId());
            if (currentRoom != null) {
                currentRoom.removePlayer(player);
                playerRooms.remove(player.getUniqueId());

                // 如果房间空了，删除房间
                if (currentRoom.getPlayerCount() == 0) {
                    gameRooms.remove(currentRoom.getRoomId());
                }
            }
            // 打开房间列表
            openRoomListGUI(player, 1);
        } else if (displayName.equals(ChatColor.RED + "取消")) {
            player.closeInventory();
        }
    }

    private void handleRoomListClick(Player player, ItemStack clicked, String title) {
        String displayName = clicked.getItemMeta().getDisplayName();

        try {
            // 从标题获取当前页码
            int currentPage = Integer.parseInt(
                    ChatColor.stripColor(title.split(" - 第 ")[1].split(" 页")[0])
            );

            // 处理翻页按钮
            if (displayName.equals(ChatColor.YELLOW + "上一页")) {
                openRoomListGUI(player, currentPage - 1);
                return;
            } else if (displayName.equals(ChatColor.YELLOW + "下一页")) {
                openRoomListGUI(player, currentPage + 1);
                return;
            }

            // 处理房间点击
            if (clicked.getItemMeta().hasLore()) {
                List<String> lore = clicked.getItemMeta().getLore();
                if (lore.contains(ChatColor.YELLOW + "点击加入房间")) {
                    String roomId = ChatColor.stripColor(displayName);
                    joinRoomFromGUI(player, roomId);
                }
            }
        } catch (Exception e) {
            getLogger().warning("处理GUI点击时出错: " + e.getMessage());
        }
    }

    private void joinRoomFromGUI(Player player, String roomId) {
        // 获取目标房间
        GameRoom targetRoom = gameRooms.get(roomId);
        if (targetRoom == null) {
            player.sendMessage(ChatColor.RED + "房间不存在或已关闭！");
            return;
        }

        // 如果是金币房，检查玩家金币是否足够
        if (targetRoom.isMoneyGame() && isBountyEnabled()) {
            double required = getMoneyMultiplier();
            double playerBalance = econ.getBalance(player);

            if (playerBalance < required) {
                player.sendMessage(ChatColor.RED + "加入失败！该房间是金币赛，需要至少 " + required +
                        " 金币，你当前只有 " + playerBalance + " 金币");
                player.closeInventory();
                return;
            }
        }

        player.closeInventory();
        joinRoom(player, roomId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 玩家加入时的处理
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameRoom room = playerRooms.get(player.getUniqueId());
        if (room != null) {
            // 游戏进行中只标记为托管，不真正移除玩家
            if (room.isGameStarted()) {
                room.removePlayer(player); // 这会触发自动托管
            } else {
                // 游戏未开始，正常移除玩家
                room.removePlayer(player);
                playerRooms.remove(player.getUniqueId());

                if (room.getPlayerCount() == 0) {
                    gameRooms.remove(room.getRoomId());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        GameRoom room = playerRooms.get(player.getUniqueId());

        if (room != null && room.isGameStarted()) {
            String message = event.getMessage().toLowerCase();

            // 检查是否是游戏命令
            if (message.equals("出牌") || message.equals("过") || message.equals("抢地主") ||
                    message.equals("不抢") || message.equals("托管") || message.equals("取消托管")) {

                event.setCancelled(true);

                // 在主线程中处理游戏逻辑
                Bukkit.getScheduler().runTask(this, () -> {
                    room.handleGameCommand(player, message);
                });
            }
        }
    }

    public void removePlayerFromRoom(UUID playerId) {
        GameRoom room = playerRooms.get(playerId);
        if (room != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                room.removePlayer(player);
            }
            playerRooms.remove(playerId);
        }
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public Economy getEconomy() {
        return econ;
    }

    public boolean isBountyEnabled() {
        return bountyEnabled;
    }

    public int getMoneyMultiplier() {
        return moneyMultiplier;
    }
}