package cn.kurt6.landlord;

import net.md_5.bungee.api.chat.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

public class GameRoom {
    private final String roomId;
    private final Landlord plugin;
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> readyStatus = new ConcurrentHashMap<>();
    private final Map<UUID, List<Card>> playerCards = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> autoPlay = new ConcurrentHashMap<>();
    private final Map<UUID, List<Integer>> selectedCards = new ConcurrentHashMap<>(); // 玩家选择的牌索引
    private final Map<UUID, BaseComponent[]> lastHandMessages = new ConcurrentHashMap<>(); // 玩家上一次的手牌消息
    private boolean moneyGame = false; // 是否开启金币赛
    private final CardSelectionGUI cardSelectionGUI;
    private static final int BIDDING_TIMEOUT = 30; // 叫分阶段固定30秒超时

    // 抢地主相关状态
    private final Map<UUID, Integer> bidStatus = new ConcurrentHashMap<>(); // 玩家叫分状态：0=不叫，1=1分，2=2分，3=3分
    private int currentBidScore = 0; // 当前最高叫分
    private Player currentHighestBidder = null; // 当前最高叫分者
    private List<Player> biddingOrder = new ArrayList<>(); // 叫分顺序
    private int biddingIndex = 0; // 当前叫分玩家索引
    private boolean biddingPhaseComplete = false; // 叫分阶段是否完成

    private BossBar scoreboardBossBar; // 用于显示计分板信息的BossBar
    private BossBar bossBar;
    private boolean gameStarted = false;
    private GameState gameState = GameState.WAITING;
    private Player roomOwner;
    private Player landlord;
    private List<Card> landlordCards = new ArrayList<>(); // 地主牌
    private List<Card> lastPlayedCards = new ArrayList<>();
    private Player currentPlayer;
    private Player lastPlayer;
    private int passCount = 0; // 连续过牌计数
    private int multiplier = 1; // 基础倍数
    private Map<UUID, BukkitTask> playerTimers = new ConcurrentHashMap<>();

    // 游戏状态枚举
    public enum GameState {
        WAITING,      // 等待玩家
        BIDDING,      // 叫分阶段
        PLAYING,      // 游戏进行中
        FINISHED      // 游戏结束
    }

    // 获取超时时间的方法
    private int getTurnTimeout() {
        return plugin.getTurnTimeout();
    }

    private void runTaskLater(Runnable task, long delay) {
        try {
            if (plugin.isFolia()) {
                // 使用Folia的调度方式
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delay);
            } else {
                // 使用传统Bukkit调度方式
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        task.run();
                    }
                }.runTaskLater(plugin, delay);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("调度任务时出现错误: " + e.getMessage());
        }
    }

    public GameRoom(String roomId, Player owner, Landlord plugin) {
        this.roomId = roomId;
        this.roomOwner = owner;
        this.plugin = plugin;
        this.bossBar = Bukkit.createBossBar("房间 " + roomId + " - 等待玩家加入",
                BarColor.BLUE, BarStyle.SOLID);
        // 只在计分板禁用时或Folia核心时创建scoreboardBossBar
        if (!plugin.isScoreboardEnabled() || plugin.isFolia()) {
            this.scoreboardBossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
        }
        this.cardSelectionGUI = new CardSelectionGUI(plugin, this);
    }

    public void addPlayer(Player player) {
        // 金币赛检查
        if (moneyGame && plugin.isBountyEnabled()) {
            double required = plugin.getMoneyMultiplier();
            if (!plugin.getEconomy().has(player, required)) {
                player.sendMessage(ChatColor.RED + "加入失败！金币赛需要至少 " + required + " 金币，你当前只有 " +
                        plugin.getEconomy().getBalance(player) + " 金币");
                return;
            }
        }

        players.put(player.getUniqueId(), player);
        readyStatus.put(player.getUniqueId(), false);
        autoPlay.put(player.getUniqueId(), false);
        selectedCards.put(player.getUniqueId(), new ArrayList<>());
        bidStatus.put(player.getUniqueId(), 0);
        bossBar.addPlayer(player);
        if (scoreboardBossBar != null) {
            scoreboardBossBar.addPlayer(player);
        }

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.GREEN + player.getName() + " 加入了房间！");

        sendGameButtons(player);

        if (players.size() == 3) {
            broadcastToRoom(ChatColor.YELLOW + "房间已满！所有玩家准备后即可开始游戏！");
        }
    }

    public void removePlayer(Player player) {
        // 如果是主动离开(通过命令)，且游戏已开始，则禁止
        if (isGameStarted() && player.isOnline()) {
            player.sendMessage(ChatColor.RED + "游戏进行中，无法主动离开房间！");
            return;
        }

        lastHandMessages.remove(player.getUniqueId());
        bossBar.removePlayer(player);
        // 只在 scoreboardBossBar 不为 null 时移除玩家
        if (scoreboardBossBar != null) {
            scoreboardBossBar.removePlayer(player);
        }

        // 清理玩家的记分板
        try {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                Scoreboard emptyScoreboard = manager.getNewScoreboard();
                if (emptyScoreboard != null) {
                    player.setScoreboard(emptyScoreboard);
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }

        if (isGameStarted()) {
            // 游戏开始后（BIDDING 或 PLAYING）玩家掉线，设置为托管
            autoPlay.put(player.getUniqueId(), true);
            broadcastToRoom(ChatColor.RED + player.getName() + " 掉线了，已自动托管！");

            // 如果是当前玩家掉线，根据阶段自动处理
            if (player.equals(currentPlayer)) {
                runTaskLater(() -> {
                    if (gameState == GameState.BIDDING) {
                        handleBiddingCommand(player, "不叫");  // 叫分阶段自动"不叫"
                    } else if (gameState == GameState.PLAYING) {
                        autoPlayCards(player);  // 出牌阶段自动出牌
                    }
                }, 5L);
            } else if (gameState == GameState.BIDDING) {
                // 叫分阶段但非当前玩家掉线，检查是否轮到他时自动处理
                runTaskLater(() -> {
                    if (gameState == GameState.BIDDING && player.equals(currentPlayer)) {
                        handleBiddingCommand(player, "不叫");
                    }
                }, 5L);
            }
        } else {
            // 游戏未开始，正常移除玩家
            players.remove(player.getUniqueId());
            readyStatus.remove(player.getUniqueId());
            playerCards.remove(player.getUniqueId());
            autoPlay.remove(player.getUniqueId());
            selectedCards.remove(player.getUniqueId());
            bidStatus.remove(player.getUniqueId());

            // 如果是房主离开，转移房主
            if (player.equals(roomOwner) && !players.isEmpty()) {
                roomOwner = players.values().iterator().next();
                broadcastToRoom(ChatColor.YELLOW + roomOwner.getName() + " 成为了新房主！");
            }

            broadcastToRoom(ChatColor.RED + player.getName() + " 离开了房间！");
        }

        updateBossBar();
        updateScoreboard();
    }

    public void toggleReady(Player player) {
        if (gameStarted) {
            player.sendMessage(ChatColor.RED + "游戏已开始，无法切换准备状态！");
            return;
        }

        // 金币赛检查
        if (moneyGame && plugin.isBountyEnabled()) {
            double required = plugin.getMoneyMultiplier();
            if (!plugin.getEconomy().has(player, required)) {
                player.sendMessage(ChatColor.RED + "准备失败！金币赛需要至少 " + required + " 金币，你当前只有 " +
                        plugin.getEconomy().getBalance(player) + " 金币");
                return;
            }
        }

        boolean ready = !readyStatus.get(player.getUniqueId());
        readyStatus.put(player.getUniqueId(), ready);

        String status = ready ? ChatColor.GREEN + "已准备" : ChatColor.RED + "未准备";
        broadcastToRoom(ChatColor.YELLOW + player.getName() + " " + status);

        updateBossBar();
        updateScoreboard();

        // 检查是否可以开始游戏
        if (players.size() == 3 && readyStatus.values().stream().allMatch(r -> r)) {
            startGame();
        }
    }

    private void startGame() {
        // 金币赛检查
        if (moneyGame && plugin.isBountyEnabled()) {
            double required = plugin.getMoneyMultiplier();
            for (Player p : players.values()) {
                if (!plugin.getEconomy().has(p, required)) {
                    broadcastToRoom(ChatColor.RED + "游戏无法开始！玩家 " + p.getName() +
                            " 金币不足 (需要: " + required + ", 当前: " + plugin.getEconomy().getBalance(p) + ")");
                    return;
                }
            }
        }

        gameStarted = true;
        gameState = GameState.BIDDING;
        multiplier = 1;
        currentBidScore = 0;
        currentHighestBidder = null;
        biddingPhaseComplete = false;
        biddingIndex = 0;

        // 初始化所有玩家的叫分状态
        for (UUID playerId : players.keySet()) {
            bidStatus.put(playerId, 0);
        }

        // 发牌
        dealCards();

        // 设置叫分顺序（随机选择起始玩家）
        biddingOrder = new ArrayList<>(players.values());
        Collections.shuffle(biddingOrder);

        updateBossBar();
        updateScoreboard();

        for (Player p : players.values()) {
            p.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
        }

        broadcastToRoom(ChatColor.GOLD + "游戏开始！进入叫分阶段！");
        broadcastToRoom(ChatColor.YELLOW + "叫分规则：可以叫1分、2分、3分或不叫，后叫分者必须比前面的分数高");

        // 显示地主牌（只显示不发给玩家）
        showLandlordCardsPreview();

        // 显示每个玩家的牌
        for (Player player : players.values()) {
            showPlayerCards(player, false); // 不启用选择功能
        }

        startBidding();
    }

    private void startBidding() {
        currentBidScore = 0;
        currentHighestBidder = null;
        biddingPhaseComplete = false;

        // 随机选择起始玩家
        biddingOrder = new ArrayList<>(players.values());
        Collections.shuffle(biddingOrder);
        biddingIndex = 0;

        // 开始第一轮叫分
        nextBiddingPlayer();
    }

    private void dealCards() {
        List<Card> deck = createDeck();
        Random random = ThreadLocalRandom.current();

        // 增强洗牌
        for (int i = 0; i < 3; i++) {
            Collections.shuffle(deck, random);
        }

        // 随机抽取地主牌
        landlordCards.clear();
        for (int i = 0; i < 3; i++) {
            int randomIndex = random.nextInt(deck.size());
            landlordCards.add(deck.remove(randomIndex));
        }

        // 动态随机发牌
        for (Player player : players.values()) {
            List<Card> cards = new ArrayList<>();
            for (int j = 0; j < 17; j++) {
                int randomIndex = random.nextInt(deck.size());
                cards.add(deck.remove(randomIndex));
            }
            // 排序手牌（按牌值降序，方便玩家查看）
            cards.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            playerCards.put(player.getUniqueId(), cards);
        }
    }

    private List<Card> createDeck() {
        List<Card> deck = new ArrayList<>();

        // 添加普通牌 A=14, K=13, Q=12, J=11
        String[] suits = {"♠", "♥", "♣", "♦"};
        for (String suit : suits) {
            for (int value = 3; value <= 14; value++) {
                deck.add(new Card(suit, value));
            }
            // 添加2 (值为15)
            deck.add(new Card(suit, 15));
        }

        // 添加小王和大王
        deck.add(new Card("", 16)); // 小王
        deck.add(new Card("", 17)); // 大王

        return deck;
    }

    public void handleGameCommand(Player player, String command) {
        if (!gameStarted || !players.containsKey(player.getUniqueId())) {
            return;
        }

        switch (gameState) {
            case BIDDING:
                handleBiddingCommand(player, command);
                break;
            case PLAYING:
                handlePlayingCommand(player, command);
                break;
        }
    }

    public void handleActionCommand(Player player, String action) {
        if (!players.containsKey(player.getUniqueId())) {
            return;
        }

        switch (gameState) {
            case BIDDING:
                if (action.equals("bid_0")) {
                    handleBiddingCommand(player, "不叫");
                } else if (action.equals("bid_1")) {
                    handleBiddingCommand(player, "1分");
                } else if (action.equals("bid_2")) {
                    handleBiddingCommand(player, "2分");
                } else if (action.equals("bid_3")) {
                    handleBiddingCommand(player, "3分");
                }
                break;
            case PLAYING:
                switch (action) {
                    case "select":
                        if (player.equals(currentPlayer)) {
                            player.sendMessage(ChatColor.YELLOW + "请点击手牌选择要出的牌！");
                            showPlayerCards(player, true);
                        } else {
                            player.sendMessage(ChatColor.RED + "还没轮到你出牌！");
                        }
                        break;
                    case "skip":
                        handlePlayingCommand(player, "过");
                        break;
                    case "auto":
                        boolean current = autoPlay.get(player.getUniqueId());
                        autoPlay.put(player.getUniqueId(), !current);
                        player.sendMessage(!current ?
                                ChatColor.YELLOW + "已开启托管，系统将自动出牌" :
                                ChatColor.YELLOW + "已取消托管");

                        // 如果开启托管，发送提示消息
                        if (!current) {
                            TextComponent message = new TextComponent(ChatColor.YELLOW + "可输入/landlord_action auto 取消托管");
                            TextComponent cancelButton = new TextComponent(ChatColor.RED + "【点击取消托管】");
                            cancelButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action auto"));
                            cancelButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    new ComponentBuilder("点击取消托管模式").create()));
                            player.spigot().sendMessage(message, cancelButton);
                        }

                        // 如果取消托管且是当前玩家，立即显示GUI
                        if (current && player.equals(currentPlayer)) {
                            showPlayerCards(player, true);
                        }
                        break;
                    case "confirm":
                        confirmSelectedCards(player);
                        break;
                    case "clear":
                        selectedCards.get(player.getUniqueId()).clear();
                        player.sendMessage(ChatColor.YELLOW + "已清空选择");
                        showPlayerCards(player, true);
                        break;
                }
                break;
        }
    }

    private void confirmSelectedCards(Player player) {
        if (!player.equals(currentPlayer)) {
            player.sendMessage(ChatColor.RED + "还没轮到你出牌！");
            return;
        }

        List<Integer> selected = selectedCards.get(player.getUniqueId());
        if (selected.isEmpty()) {
            player.sendMessage(ChatColor.RED + "请先选择要出的牌！");
            return;
        }

        List<Card> cards = playerCards.get(player.getUniqueId());
        List<Card> selectedCardsList = new ArrayList<>();

        // 获取选中的牌（按索引降序排列，这样移除时不会影响后面的索引）
        selected.sort(Collections.reverseOrder());
        for (int index : selected) {
            selectedCardsList.add(cards.get(index));
        }

        // 验证牌型
        GameLogic.CardPattern pattern = GameLogic.recognizePattern(selectedCardsList);
        if (pattern.getType() == GameLogic.CardType.INVALID) {
            player.sendMessage(ChatColor.RED + "无效的牌型！请重新选择");
            return;
        }

        // 检查是否能压过上家
        if (!lastPlayedCards.isEmpty()) {
            GameLogic.CardPattern lastPattern = GameLogic.recognizePattern(lastPlayedCards);
            if (!pattern.canBeat(lastPattern)) {
                player.sendMessage(ChatColor.RED + "无法压过上家的牌！请重新选择或选择过牌");
                return;
            }
        }

        // 出牌成功
        playSelectedCards(player, selectedCardsList, selected);
    }

    public void handleBiddingCommand(Player player, String command) {
        if (!player.equals(currentPlayer)) {
            return;
        }

        cancelCurrentTimer();

        int bidScore = 0;
        if (command.equals("1分")) bidScore = 1;
        else if (command.equals("2分")) bidScore = 2;
        else if (command.equals("3分")) bidScore = 3;
        else if (command.equals("不叫")) bidScore = 0;

        // 严格验证叫分
        if (bidScore > 0) {
            if (bidScore <= currentBidScore) {
                if (player.isOnline()) { // 只对在线玩家提示
                    player.sendMessage(ChatColor.RED + "叫分必须比当前最高分(" + currentBidScore + "分)更高！");
                    cardSelectionGUI.openBiddingGUI(player);
                }
                startBiddingTimer(player);
                return;
            }
            currentBidScore = bidScore;
            currentHighestBidder = player;
            broadcastToRoom(ChatColor.GREEN + player.getName() + " 叫了 " + bidScore + " 分！");

            if (bidScore == 3) {
                confirmLandlord(player, bidScore);
                return;
            }
        } else {
            broadcastToRoom(ChatColor.GRAY + player.getName() + " 不叫");
        }

        bidStatus.put(player.getUniqueId(), bidScore);
        nextBiddingPlayer();
    }

    private void nextBiddingPlayer() {
        cancelCurrentTimer();

        // 检查是否所有玩家都已叫分
        if (biddingIndex >= biddingOrder.size()) {
            if (currentHighestBidder == null) {
                broadcastToRoom(ChatColor.RED + "无人叫分，重新发牌！");
                resetGame();
                startGame();
                return;
            } else {
                confirmLandlord(currentHighestBidder, currentBidScore);
                return;
            }
        }

        // 设置当前叫分玩家
        currentPlayer = biddingOrder.get(biddingIndex);
        biddingIndex++;

        // 直接检测玩家是否在线
        if (!currentPlayer.isOnline()) {
            broadcastToRoom(ChatColor.RED + currentPlayer.getName() + " 已离线，自动跳过叫分");
            handleBiddingCommand(currentPlayer, "不叫");
            return;
        }

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 在 " + BIDDING_TIMEOUT + " 秒内叫分！");

        // 打开叫分GUI
        cardSelectionGUI.openBiddingGUI(currentPlayer);

        // 启动计时器
        startBiddingTimer(currentPlayer);
    }

    // 专用的叫分计时器方法
    private void startBiddingTimer(Player player) {
        cancelCurrentTimer(); // 清除旧计时器

        AtomicInteger secondsLeft = new AtomicInteger(BIDDING_TIMEOUT);

        if (plugin.isFolia()) {
            // 使用Folia的调度方式
            player.getScheduler().runAtFixedRate(plugin, task -> {
                handleBiddingTimerTick(player, secondsLeft);
            }, null, 20L, 20L);
        } else {
            // 使用传统Bukkit调度方式
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    handleBiddingTimerTick(player, secondsLeft);
                }
            }.runTaskTimer(plugin, 0L, 20L);
            playerTimers.put(player.getUniqueId(), task);
        }
    }

    // 专用的叫分计时器tick处理方法
    private void handleBiddingTimerTick(Player player, AtomicInteger secondsLeft) {
        // 游戏状态检查
        if (gameState != GameState.BIDDING || !gameStarted) {
            return;
        }

        // 检查玩家是否有效
        if (!player.isOnline() || currentPlayer == null || !currentPlayer.equals(player)) {
            return;
        }

        // 如果是托管玩家，直接处理
        if (autoPlay.get(player.getUniqueId())) {
            handleBiddingCommand(player, "不叫");
            return;
        }

        // 视觉提示（最后5秒）
        if (secondsLeft.get() <= 5) {
            player.sendTitle(
                    ChatColor.RED + "⚠ " + secondsLeft.get() + " ⚠",
                    ChatColor.YELLOW + "超时将自动不叫",
                    0, 25, 0
            );
            spawnParticles(player, Particle.GLOW_SQUID_INK, 10);
        }

        // 音效提示
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_HAT,
                0.5f,
                (float) (1.0 + (BIDDING_TIMEOUT - secondsLeft.get()) * 0.02)
        );

        // 超时处理
        if (secondsLeft.decrementAndGet() <= 0) {
            // 强制设置为不叫
            player.sendMessage(ChatColor.RED + "时间到！自动选择不叫");

            // 关闭当前打开的GUI
            player.closeInventory();
            forceCloseAllGUIs();

            // 处理自动不叫
            handleBiddingCommand(player, "不叫");
        }
    }

    private void confirmLandlord(Player player, int bidScore) {
        landlord = player;
        List<Card> cards = playerCards.get(landlord.getUniqueId());
        cards.addAll(landlordCards);
        cards.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        gameState = GameState.PLAYING;
        currentPlayer = landlord; // 确保设置当前玩家为地主
        passCount = 0;
        multiplier = bidScore;

        broadcastToRoom(ChatColor.GOLD + player.getName() + " 成为地主！叫分: " + bidScore + " 分");
        showLandlordCards();

        // 强制关闭所有GUI
        player.closeInventory();
        forceCloseAllGUIs();

        // 立即更新显示
        updateBossBar();  // 确保BossBar更新到PLAYING状态
        updateScoreboard(); // 立即更新计分板显示地主手牌数量

        // 延迟启动出牌阶段，确保GUI完全关闭
        runTaskLater(() -> {
            startTurnTimer(currentPlayer);
            // 显示地主的手牌
            showPlayerCards(landlord, true);
        }, 2L);
    }

    private void showLandlordCardsPreview() {
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "地主牌: ");
        for (int i = 0; i < landlordCards.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(landlordCards.get(i).toString());
        }
        broadcastToRoom(sb.toString());
    }

    private void handlePlayingCommand(Player player, String command) {
        switch (command) {
            case "出牌":
                if (!player.equals(currentPlayer)) {
                    player.sendMessage(ChatColor.RED + "还没轮到你出牌！");
                    return;
                }
                player.sendMessage(ChatColor.YELLOW + "请点击【选择出牌】按钮选择要出的牌！");
                break;

            case "过":
                if (!player.equals(currentPlayer)) {
                    player.sendMessage(ChatColor.RED + "还没轮到你！");
                    return;
                }
                if (lastPlayedCards.isEmpty() && passCount == 0) {
                    player.sendMessage(ChatColor.RED + "第一轮不能过牌！");
                    return;
                }
                passCard(player);
                break;

            case "托管":
                autoPlay.put(player.getUniqueId(), true);
                player.sendMessage(ChatColor.YELLOW + "已开启托管模式");
                break;

            case "取消托管":
                autoPlay.put(player.getUniqueId(), false);
                player.sendMessage(ChatColor.YELLOW + "已取消托管模式");
                break;
        }
    }

    public void playSelectedCards(Player player, List<Card> selectedCardsList, List<Integer> selectedIndices) {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted) {
            return;
        }

        List<Card> cards = playerCards.get(player.getUniqueId());

        // 从手牌中移除选中的牌
        for (int index : selectedIndices) {
            cards.remove(index);
        }

        // 更新游戏状态
        lastPlayedCards = new ArrayList<>(selectedCardsList);
        lastPlayer = player;
        passCount = 0;

        // 清空选择
        selectedCards.get(player.getUniqueId()).clear();

        // 显示出牌信息
        GameLogic.CardPattern pattern = GameLogic.recognizePattern(selectedCardsList);
        StringBuilder sb = new StringBuilder();
        for (Card card : selectedCardsList) {
            sb.append(card.toString()).append(" ");
        }

        for (Player p : players.values()) {
            p.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
        }

        broadcastToRoom(ChatColor.GREEN + player.getName() + " 出了 " +
                getPatternName(pattern.getType()) + ": " + sb.toString());

        // 检查是否是炸弹或火箭
        if (pattern.getType() == GameLogic.CardType.BOMB) {
            multiplier *= 2;
            showBombEffect(player, 2);
        } else if (pattern.getType() == GameLogic.CardType.ROCKET) {
            multiplier *= 4;
            showBombEffect(player, 4);
            // 王炸特殊处理
            passCount = 0;           // 重置过牌计数
        }

        // 检查是否获胜
        if (cards.isEmpty()) {
            if (player.equals(landlord)) {
                endGame("地主获胜！");
            } else {
                endGame("农民获胜！");
            }
            return;
        }

        // 下一个玩家
        nextPlayer();
        updateScoreboard();
        updateBossBar();

        // 强制更新所有玩家的手牌显示（包括对手）
        for (Player p : players.values()) {
            showPlayerCards(p, p.equals(currentPlayer)); // 只有当前玩家可以选牌
        }
    }

    private void showBombEffect(Player player, int times) {
        String title = ChatColor.RED + "★ " + (times == 2 ? "炸弹！" : "王炸！") + " ★";
        String subtitle = ChatColor.GOLD + "倍数 ×" + multiplier;

        for (Player p : players.values()) {
            // 标题动画
            p.sendTitle(title, subtitle, 10, 60, 10);

            // 粒子特效
            Location loc = p.getEyeLocation();

            // 爆炸效果
            p.spawnParticle(Particle.POOF, loc, 50, 1, 1, 1, 0.5);

            // 闪光效果
            p.spawnParticle(Particle.FLASH, loc, 1);

            // 音效
            p.playSound(loc,
                    Sound.ENTITY_DRAGON_FIREBALL_EXPLODE,
                    1.5f,
                    times == 2 ? 0.8f : 1.2f
            );

            // 火箭额外特效
            if (times == 4) {
                p.spawnParticle(Particle.FIREWORK, loc, 100, 0.5, 0.5, 0.5, 0.5);
                p.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 0.8f);
            }
        }
    }

    public static String getPatternName(GameLogic.CardType type) {
        switch (type) {
            case SINGLE: return "单牌";
            case PAIR: return "对子";
            case TRIPLE: return "三张";
            case TRIPLE_SINGLE: return "三带一";
            case TRIPLE_PAIR: return "三带二";
            case FOUR_WITH_TWO_SINGLES: return "四带二";
            case FOUR_WITH_TWO_PAIRS: return "四带两对";
            case STRAIGHT: return "顺子";
            case PAIR_STRAIGHT: return "连对";
            case TRIPLE_STRAIGHT: return "飞机";
            case BOMB: return "炸弹";
            case ROCKET: return "王炸";
            default: return "未知";
        }
    }

    public void passCard(Player player) {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted) {
            return;
        }

        cancelCurrentTimer();
        passCount++;
        broadcastToRoom(ChatColor.GRAY + player.getName() + " 选择过牌");

        // 检查上家是否出的是王炸
        boolean lastWasRocket = !lastPlayedCards.isEmpty() &&
                GameLogic.recognizePattern(lastPlayedCards).getType() == GameLogic.CardType.ROCKET;

        if (passCount >= 2 || lastWasRocket) {
            // 两人过牌或上家出王炸，重新开始
            lastPlayedCards.clear();
            passCount = 0;

            // 确保currentPlayer是最后一个出牌的人
            if (lastPlayer != null) {
                currentPlayer = lastPlayer;
            } else {
                // 如果没有lastPlayer（第一轮），保持当前顺序
                List<Player> playerList = new ArrayList<>(players.values());
                int nextIndex = (playerList.indexOf(player) + 1) % playerList.size();
                currentPlayer = playerList.get(nextIndex);
            }

            broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 出牌！");
            updateBossBar();
            updateScoreboard();
        } else {
            nextPlayer(); // 直接切换到下家
        }

        // 立即启动计时器或自动出牌
        if (autoPlay.get(currentPlayer.getUniqueId())) {
            runTaskLater(() -> autoPlayCards(currentPlayer), 5L); // 延迟5 ticks执行
        } else {
            startTurnTimer(currentPlayer);
        }
    }

    public void forceCloseAllGUIs() {
        for (Player player : players.values()) {
            // 如果不是当前玩家，强制关闭其GUI
            if (!player.equals(currentPlayer)) {
                player.closeInventory();
            }
        }
    }

    private void nextPlayer() {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted) {
            return;
        }
        cancelCurrentTimer();

        List<Player> playerList = new ArrayList<>(players.values());
        int nextIndex = (playerList.indexOf(currentPlayer) + 1) % playerList.size();
        currentPlayer = playerList.get(nextIndex);

        // 直接检查玩家是否在线
        if (!currentPlayer.isOnline()) {
            autoPlay.put(currentPlayer.getUniqueId(), true);
            broadcastToRoom(ChatColor.RED + currentPlayer.getName() + " 已掉线，自动托管！");
            autoPlayCards(currentPlayer);
            return;
        }

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 在 " + getTurnTimeout() + " 秒内出牌！");

        // 托管玩家直接自动出牌
        if (autoPlay.get(currentPlayer.getUniqueId())) {
            currentPlayer.sendMessage(ChatColor.YELLOW + "你处于托管状态，系统将自动出牌");
            // 发送取消托管按钮
            TextComponent mes = new TextComponent(ChatColor.RED + "可输入/landlord_action auto 退出托管");
            TextComponent cancelButton = new TextComponent(ChatColor.RED + "【取消托管】");
            cancelButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action auto"));
            cancelButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("点击取消托管").create()));
            currentPlayer.spigot().sendMessage(mes);
            currentPlayer.spigot().sendMessage(cancelButton);

            runTaskLater(() -> autoPlayCards(currentPlayer), 20L); // 延迟1秒执行自动出牌
        } else {
            // 非托管玩家显示GUI
            List<Card> cards = getPlayerCards(currentPlayer);
            if (cards == null || cards.isEmpty()) {
                return;
            }
            cardSelectionGUI.openGUI(currentPlayer, getPlayerCards(currentPlayer));
            startTurnTimer(currentPlayer);
        }
    }

    private void startTurnTimer(Player player) {
        cancelCurrentTimer(); // 清除旧计时器

        AtomicInteger secondsLeft = new AtomicInteger(getTurnTimeout());

        if (plugin.isFolia()) {
            // 使用Folia的调度方式
            player.getScheduler().runAtFixedRate(plugin, task -> {
                handleTimerTick(player, secondsLeft);
            }, null, 20L, 20L);
        } else {
            // 使用传统Bukkit调度方式
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    handleTimerTick(player, secondsLeft);
                }
            }.runTaskTimer(plugin, 0L, 20L);
            playerTimers.put(player.getUniqueId(), task);
        }
    }

    private void handleTimerTick(Player player, AtomicInteger secondsLeft) {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted || !player.isOnline()) {
            return;
        }

        // 检查玩家是否有效
        if (!player.isOnline() || currentPlayer == null || !currentPlayer.equals(player)) {
            return;
        }

        // 视觉提示（最后5秒）
        if (secondsLeft.get() <= 5) {
            player.sendTitle(
                    ChatColor.RED + "⚠ " + secondsLeft.get() + " ⚠",
                    ChatColor.YELLOW + "超时将自动托管",
                    0, 25, 0
            );
            spawnParticles(player, Particle.GLOW_SQUID_INK, 10);
        }

        // 音效提示
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_HAT,
                0.5f,
                (float) (1.0 + (getTurnTimeout() - secondsLeft.get()) * 0.02)
        );

        // 超时处理
        if (secondsLeft.decrementAndGet() <= 0) {
            // 强制设置为托管状态
            autoPlay.put(player.getUniqueId(), true);
            player.sendMessage(ChatColor.RED + "时间到！已自动托管");

            // 关闭当前打开的GUI
            player.closeInventory();
            forceCloseAllGUIs();

            // 处理自动出牌
            if (gameState == GameState.BIDDING) {
                handleBiddingCommand(player, "不叫");
            } else {
                autoPlayCards(player);
            }
        }
    }

    // 粒子生成
    private void spawnParticles(Player p, Particle particle, int count) {
        Location loc = p.getLocation().add(0, 1, 0);
        p.spawnParticle(particle, loc, count, 0.5, 0.5, 0.5, 0.1);
    }

    private void cancelCurrentTimer() {
        if (currentPlayer != null) {
            // 取消传统 Bukkit 任务
            BukkitTask task = playerTimers.remove(currentPlayer.getUniqueId());
            if (task != null) {
                task.cancel();
            }
        }
    }

    private void autoPlayCards(Player player) {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted || !players.containsKey(player.getUniqueId())) {
            return;
        }

        if (!player.equals(currentPlayer)) return;

        List<Card> cards = playerCards.get(player.getUniqueId());
        if (cards == null || cards.isEmpty()) return;

        if (!autoPlay.get(player.getUniqueId())) {
            autoPlay.put(player.getUniqueId(), true);
        }

        // 托管玩家不显示GUI，直接处理出牌逻辑
        runTaskLater(() -> {
            List<Card> selectedCards = GameLogic.autoSelectCards(cards,
                    lastPlayedCards.isEmpty() ? null : GameLogic.recognizePattern(lastPlayedCards));

            if (selectedCards != null && !selectedCards.isEmpty()) {
                playSelectedCards(player, selectedCards, getCardIndices(cards, selectedCards));
                // 托管玩家出牌后显示剩余手牌
                showPlayerCards(player, false);
            } else {
                passCard(player);
            }
        }, 20L); // 延迟1秒执行自动出牌
    }

    // 根据牌值获取手牌中的索引
    private List<Integer> getCardIndices(List<Card> hand, List<Card> selected) {
        List<Integer> indices = new ArrayList<>();
        List<Card> tempHand = new ArrayList<>(hand); // 避免直接修改原列表

        for (Card card : selected) {
            for (int i = 0; i < tempHand.size(); i++) {
                if (tempHand.get(i).equals(card)) {
                    indices.add(i);
                    tempHand.remove(i); // 防止重复匹配
                    break;
                }
            }
        }
        return indices;
    }

    private void updatePlayerStats(String result) {
        boolean isLandlordWin = result.contains("地主获胜");
        int gameMultiplier = this.multiplier;
        int farmerPointsChange = gameMultiplier;

        for (Player player : players.values()) {
            String playerName = player.getName();
            StatsManager.PlayerStats stats = plugin.getStatsManager().getPlayerStats(playerName);
            stats.incrementGamesPlayed();

            boolean isOnline = player.isOnline();

            if (player.equals(landlord)) {
                if (isLandlordWin) {
                    stats.incrementGamesWon();
                    // 地主获胜：在线加分，掉线不加分
                    if (isOnline) {
                        stats.addPoints(gameMultiplier * 2);
                    }
                } else {
                    stats.incrementGamesLost();
                    // 地主失败：无论是否在线都扣分
                    stats.addPoints(-gameMultiplier * 2);
                }
            } else {
                if (!isLandlordWin) {
                    stats.incrementGamesWon();
                    // 农民获胜：在线加分，掉线不加分
                    if (isOnline) {
                        stats.addPoints(farmerPointsChange);
                    }
                } else {
                    stats.incrementGamesLost();
                    // 农民失败：无论是否在线都扣分
                    stats.addPoints(-farmerPointsChange);
                }
            }

            plugin.getStatsManager().updatePlayerStats(playerName, stats);
        }
    }

    private void endGame(String reason) {
        if (gameState == GameState.FINISHED) return; // 防止重复调用
        // 立即取消所有任务（包括Folia任务）
        cancelCurrentTimer();
        playerTimers.values().forEach(task -> {
            if (task != null) task.cancel();
        });
        playerTimers.clear();

        gameState = GameState.FINISHED; // 立即设置状态

        // 游戏结束时强制关闭所有GUI
        forceCloseAllGUIs();

        // 重置所有玩家的准备状态和托管状态
        for (UUID playerId : players.keySet()) {
            readyStatus.put(playerId, false);
            autoPlay.put(playerId, false); // 清除托管状态
        }

        // 处理金币奖励
        if (moneyGame) {
            handleMoneyRewards(reason);
        }

        lastHandMessages.clear();
        playerTimers.values().forEach(BukkitTask::cancel);
        playerTimers.clear();

        gameStarted = false;

        // 更新玩家统计（区分在线和掉线玩家）
        updatePlayerStats(reason);

        for (Player p : players.values()) {
            p.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
        }

        broadcastToRoom(ChatColor.GOLD + "=== 本局积分结算 ===");
        broadcastToRoom(ChatColor.YELLOW + "最终倍数: x" + multiplier);

        boolean isLandlordWin = reason.contains("地主获胜");
        int farmerPoints = multiplier;

        // 地主积分通知
        Player landlordPlayer = this.landlord;
        boolean landlordOnline = landlordPlayer != null && landlordPlayer.isOnline();
        String landlordMsg = ChatColor.RED + "地主 " + landlordPlayer.getName() + ": " +
                (isLandlordWin ?
                        (landlordOnline ? ChatColor.GREEN + "+" + (multiplier * 2) : ChatColor.GRAY + "+0 (掉线)") :
                        ChatColor.RED + "-" + (multiplier * 2));
        broadcastToRoom(landlordMsg);

        // 农民积分通知
        for (Player player : players.values()) {
            if (!player.equals(landlordPlayer)) {
                boolean farmerOnline = player.isOnline();
                String farmerMsg = ChatColor.GREEN + "农民 " + player.getName() + ": " +
                        (!isLandlordWin ?
                                (farmerOnline ? ChatColor.GREEN + "+" + farmerPoints : ChatColor.GRAY + "+0 (掉线)") :
                                ChatColor.RED + "-" + farmerPoints);
                broadcastToRoom(farmerMsg);
            }
        }

        broadcastToRoom(ChatColor.GOLD + "=== 游戏结束 ===");
        broadcastToRoom(ChatColor.YELLOW + reason);

        // 创建可点击的「准备/取消准备」按钮
        TextComponent readyButton = new TextComponent("【准备/取消准备】");
        readyButton.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        readyButton.setBold(true);
        readyButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord ready"));
        readyButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击切换准备状态").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 确保所有玩家（包括托管状态玩家）都能收到消息
        for (Player player : players.values()) {
            player.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
            player.spigot().sendMessage(readyButton);

            // 如果玩家处于托管状态，发送取消托管的消息
            if (autoPlay.getOrDefault(player.getUniqueId(), false)) {
                player.sendMessage(ChatColor.YELLOW + "你的托管状态已自动取消");
            }
        }

        // 踢出离线玩家
        Set<UUID> offlinePlayers = new HashSet<>();
        for (Map.Entry<UUID, Player> entry : players.entrySet()) {
            if (!entry.getValue().isOnline()) {
                offlinePlayers.add(entry.getKey());
            }
        }

        for (UUID playerId : offlinePlayers) {
            Player player = players.get(playerId);
            removePlayer(player);
            plugin.removePlayerFromRoom(playerId); // 从主插件中移除玩家
        }

        // 重置游戏状态
        runTaskLater(this::resetGame, 40L);
    }

    private void resetGame() {
        // 重置准备状态
        for (UUID playerId : players.keySet()) {
            readyStatus.put(playerId, false);
            autoPlay.put(playerId, false); // 确保重置托管状态
        }

        lastHandMessages.clear();
        gameStarted = false;
        gameState = GameState.WAITING;
        landlord = null;
        landlordCards.clear();
        lastPlayedCards.clear();
        currentPlayer = null;
        lastPlayer = null;
        passCount = 0;
        multiplier = 1; // 重置倍数

        // 重置叫分相关状态
        currentBidScore = 0;
        currentHighestBidder = null;
        biddingOrder.clear();
        biddingIndex = 0;
        biddingPhaseComplete = false;

        // 清空玩家手牌和选择
        playerCards.clear();
        selectedCards.clear();
        for (UUID playerId : players.keySet()) {
            selectedCards.put(playerId, new ArrayList<>());
            bidStatus.put(playerId, 0);
            autoPlay.put(playerId, false); // 强制重置托管状态
        }

        // 取消所有计时器
        cancelCurrentTimer();
        playerTimers.values().forEach(BukkitTask::cancel);
        playerTimers.clear();

        // 更新显示
        updateBossBar();
        updateScoreboard();

        // 通知所有玩家托管状态已重置
        for (Player player : players.values()) {
            if (autoPlay.getOrDefault(player.getUniqueId(), false)) {
                player.sendMessage(ChatColor.YELLOW + "你的托管状态已自动取消");
            }
        }
    }

    public void showPlayerCards(Player player, boolean allowSelection) {
        // 游戏状态检查
        if (gameState == GameState.FINISHED || !gameStarted) {
            return;
        }

        // 如果是托管玩家，直接返回不显示手牌
        if (isAutoPlay(player)) {
            return;
        }

        // 如果不是当前玩家且允许选择，直接返回
        if (allowSelection && !player.equals(currentPlayer)) {
            return;
        }

        List<Card> cards = playerCards.get(player.getUniqueId());
        // 手牌为空检查
        if (cards == null || cards.isEmpty()) {
            return;
        }

        // 如果是当前玩家且允许选择，强制打开GUI
        if (allowSelection && player.equals(currentPlayer)) {
            cardSelectionGUI.openGUI(player, cards);
        } else {
            // 非当前玩家仍然显示手牌信息（仅限非托管玩家）
            ComponentBuilder builder = new ComponentBuilder("你的手牌:")
                    .color(net.md_5.bungee.api.ChatColor.GREEN);
            for (Card card : cards) {
                builder.append(" ").append(card.toString());
            }

            player.spigot().sendMessage(builder.create());
        }
    }

    public boolean isAutoPlay(Player player) {
        return autoPlay.getOrDefault(player.getUniqueId(), false);
    }

    public List<Card> getPlayerCards(Player player) {
        return playerCards.get(player.getUniqueId());
    }

    public void toggleAutoPlay(Player player) {
        boolean current = autoPlay.get(player.getUniqueId());
        autoPlay.put(player.getUniqueId(), !current);
        player.sendMessage(!current ? ChatColor.YELLOW + "已开启托管" : ChatColor.YELLOW + "已取消托管");

        if (!current && player.equals(currentPlayer)) {
            autoPlayCards(player);
        }
    }

    private void showLandlordCards() {
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "地主牌: ");
        for (int i = 0; i < landlordCards.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(landlordCards.get(i).toString());
        }
        broadcastToRoom(sb.toString());
        updateScoreboard();
    }

    private void sendGameButtons(Player player) {
        player.sendMessage(ChatColor.AQUA + "=== 游戏操作 ===");
        player.sendMessage(ChatColor.GREEN + "输入/ddz ready 准备/取消准备");

        // 创建可点击的「准备/取消准备」按钮
        TextComponent readyButton = new TextComponent("【准备/取消准备】");
        readyButton.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        readyButton.setBold(true);
        readyButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord ready"));
        readyButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击切换准备状态").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 发送按钮消息
        player.spigot().sendMessage(readyButton);
    }

    private void updateBossBar() {
        if (bossBar == null) return;

        String title;
        BarColor color;
        double progress;

        switch (gameState) {
            case WAITING:
                title = "房间 " + roomId + " - 等待玩家 (" + players.size() + "/3)" +
                        (moneyGame ? " " + ChatColor.GOLD + "[金币赛]" : "");
                color = BarColor.BLUE;
                progress = players.size() / 3.0;
                break;
            case BIDDING:
                title = "叫分阶段 - 当前: " + (currentPlayer != null ? currentPlayer.getName() : "无") +
                        " | 最高分: " + currentBidScore +
                        (moneyGame ? " " + ChatColor.GOLD + "[金币赛]" : "");
                color = BarColor.YELLOW;
                progress = 0.5;
                break;
            case PLAYING:
                // 确保当前玩家信息实时更新
                String currentPlayerName = currentPlayer != null ? currentPlayer.getName() : "无";
                title = "游戏中 - 当前: " + currentPlayerName +
                        " | 倍数: x" + multiplier +
                        (landlord != null ? " | 地主: " + landlord.getName() : "") +
                        (moneyGame ? " " + ChatColor.GOLD + "[金币赛]" : "");
                color = BarColor.GREEN;
                progress = 1.0;
                break;
            case FINISHED:
                title = "游戏结束 - 即将重置" +
                        (moneyGame ? " " + ChatColor.GOLD + "[金币赛]" : "");
                color = BarColor.RED;
                progress = 0.0;
                break;
            default:
                title = "房间 " + roomId +
                        (moneyGame ? " " + ChatColor.GOLD + "[金币赛]" : "");
                color = BarColor.WHITE;
                progress = 0.0;
                break;
        }

        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    private long lastScoreboardUpdate = 0;

    private void updateScoreboard() {
        // 如果游戏已结束，不更新计分板
        if (gameState == GameState.FINISHED) return;

        // 如果计分板被禁用或者是Folia核心，显示bossbar
        if (!plugin.isScoreboardEnabled() || plugin.isFolia()) {
            for (Player player : players.values()) {
                updateScoreboardBossBar(player);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScoreboardUpdate < 100) return;
        lastScoreboardUpdate = now;

        for (Player player : players.values()) {
            if (plugin.isFolia()) {
                updatePlayerScoreboardNow(player);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> updatePlayerScoreboardNow(player));
            }
        }
    }

    private void updatePlayerScoreboardNow(Player player) {
        try {
            // 检查玩家是否在线
            if (!player.isOnline()) return;

            // 使用玩家调度器确保线程安全
            player.getScheduler().run(plugin, task -> {
                try {
                    // 获取ScoreboardManager
                    ScoreboardManager manager = Bukkit.getScoreboardManager();
                    if (manager == null) {
                        plugin.getLogger().warning("ScoreboardManager未初始化！");
                        return;
                    }

                    // 创建新记分板
                    Scoreboard scoreboard = manager.getNewScoreboard();
                    Objective objective = scoreboard.registerNewObjective(
                            "landlord_" + player.getName(), // 唯一ID，避免冲突
                            "dummy",
                            ChatColor.GOLD + "斗地主"
                    );
                    objective.setDisplaySlot(DisplaySlot.SIDEBAR);

                    // 使用AtomicInteger替代基本类型int
                    AtomicInteger line = new AtomicInteger(10);

                    objective.getScore(ChatColor.YELLOW + "房间: " + roomId).setScore(line.getAndDecrement());
                    objective.getScore("").setScore(line.getAndDecrement());

                    if (gameStarted) {
                        if (gameState == GameState.BIDDING) {
                            objective.getScore(ChatColor.GOLD + "▶ 叫分阶段").setScore(line.getAndDecrement());
                            objective.getScore(ChatColor.WHITE + "当前叫分: " +
                                    (currentPlayer != null ? currentPlayer.getName() : "无")).setScore(line.getAndDecrement());
                            objective.getScore(ChatColor.AQUA + "最高分: " + currentBidScore + "分").setScore(line.getAndDecrement());
                        } else if (gameState == GameState.PLAYING) {
                            if (landlord != null) {
                                objective.getScore(ChatColor.RED + "地主: " + landlord.getName()).setScore(line.getAndDecrement());
                                objective.getScore(ChatColor.AQUA + "倍数: ×" + multiplier).setScore(line.getAndDecrement());
                            }
                            objective.getScore(ChatColor.WHITE + "当前出牌: " +
                                    (currentPlayer != null ? currentPlayer.getName() : "无")).setScore(line.getAndDecrement());
                        }

                        objective.getScore(ChatColor.GREEN + "手牌数量:").setScore(line.getAndDecrement());
                        players.values().forEach(p -> {
                            List<Card> cards = playerCards.get(p.getUniqueId());
                            // 确保获取最新的手牌数量
                            int count = cards != null ? cards.size() : 0;
                            objective.getScore(p.getName() + ": " + count + "张")
                                    .setScore(line.getAndDecrement());
                        });
                    } else {
                        objective.getScore(ChatColor.WHITE + "玩家列表:").setScore(line.getAndDecrement());
                        players.values().forEach(p -> {
                            boolean ready = readyStatus.getOrDefault(p.getUniqueId(), false);
                            objective.getScore((ready ? ChatColor.GREEN + "✓ " : ChatColor.RED + "✗ ") +
                                            p.getName())  // 直接显示玩家名
                                    .setScore(line.getAndDecrement());
                        });
                    }

                    // 最后设置记分板
                    player.setScoreboard(scoreboard);
                } catch (Exception e) {
                    updateScoreboardBossBar(player); // 回退到bossbar
                }
            }, null);
        } catch (Exception e) {
            plugin.getLogger().warning("调度计分板更新失败: " + e.getMessage());
        }
    }

    /**
     * 当计分板不可用时，通过新bossbar显示游戏信息
     */
    private void updateScoreboardBossBar(Player player) {
        if (gameState == GameState.FINISHED && lastHandMessages.containsKey(player.getUniqueId())) {
            return;
        }

        if (scoreboardBossBar == null) return;

        StringBuilder info = new StringBuilder();

        if (gameStarted) {
            info.append(ChatColor.AQUA).append("手牌数量: ");
            // 按固定顺序显示玩家信息（按加入顺序）
            List<Player> orderedPlayers = new ArrayList<>(players.values());
            // 按玩家名字排序确保顺序一致
            orderedPlayers.sort(Comparator.comparing(Player::getName));

            for (Player p : orderedPlayers) {
                List<Card> cards = playerCards.get(p.getUniqueId());
                int cardCount = cards != null ? cards.size() : 0;
                String name = p.getName();  // 直接使用玩家名，不再判断"你"
                info.append(name).append(":").append(cardCount).append(" ");
            }
        } else {
            info.append(ChatColor.WHITE).append("玩家状态: ");
            // 按固定顺序显示玩家信息（按加入顺序）
            List<Player> orderedPlayers = new ArrayList<>(players.values());
            // 按玩家名字排序确保顺序一致
            orderedPlayers.sort(Comparator.comparing(Player::getName));

            for (Player p : orderedPlayers) {
                boolean ready = readyStatus.get(p.getUniqueId());
                String status = ready ? ChatColor.GREEN + "✓" : ChatColor.RED + "✗";
                String name = p.getName();  // 直接使用玩家名
                info.append(status).append(name).append(" ");
            }
        }

        scoreboardBossBar.setTitle(info.toString());
        scoreboardBossBar.setProgress(1.0);
    }

    private void broadcastToRoom(String message) {
        for (Player player : players.values()) {
            player.sendMessage(message);
        }
    }

    public void cleanup() {
        lastHandMessages.clear(); // 清理上次的消息记录
        // 清理计时器和BossBar
        playerTimers.values().forEach(BukkitTask::cancel);
        playerTimers.clear();
        if (bossBar != null) {
            bossBar.removeAll();
        }
        if (scoreboardBossBar != null) {
            scoreboardBossBar.removeAll();
        }

        // 同步清理计分板
        for (Player player : players.values()) {
            try {
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            } catch (Exception e) {

            }
        }
    }

    // Getter方法
    /**
     * 获取当前游戏倍数
     * @return 当前游戏倍数
     */
    public int getMultiplier() {
        return multiplier;
    }

    /**
     * 获取上家出的牌
     * @return 上家出的牌列表
     */
    public List<Card> getLastPlayedCards() {
        return new ArrayList<>(lastPlayedCards); // 返回副本以避免外部修改
    }

    /**
     * 获取当前应该出牌的玩家
     * @return 当前玩家对象
     */
    public Player getCurrentPlayer() {
        return currentPlayer;
    }

    /**
     * 获取当前最高叫分
     * @return 当前最高叫分 (0=无人叫分, 1=1分, 2=2分, 3=3分)
     */
    public int getCurrentBidScore() {
        return currentBidScore;
    }

    /**
     * 获取当前最高叫分者
     * @return 当前最高叫分者Player对象，可能为null
     */
    public Player getCurrentHighestBidder() {
        return currentHighestBidder;
    }


    // 地主牌
    public List<Card> getLandlordCards() {
        return new ArrayList<>(landlordCards); // 返回副本避免外部修改
    }

    public Map<UUID, Boolean> getReadyStatus() {
        return readyStatus;
    }

    public int getPassCount() {
        return passCount;
    }

    public GameState getGameState() {
        return gameState;
    }

    public Player getRoomOwner() {
        return roomOwner;
    }

    public String getRoomId() {
        return roomId;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public void toggleMoneyGame(Player player) {
        if (!player.equals(roomOwner)) {
            player.sendMessage(ChatColor.RED + "只有房主可以设置金币赛！");
            return;
        }

        if (gameStarted) {
            player.sendMessage(ChatColor.RED + "游戏已经开始，无法修改金币赛设置！");
            return;
        }

        moneyGame = !moneyGame;
        String status = moneyGame ? ChatColor.GREEN + "已开启" : ChatColor.RED + "已关闭";
        int required = plugin.getMoneyMultiplier();

        if (moneyGame) {
            // 检查所有玩家金币是否足够
            boolean allHaveMoney = true;
            for (Player p : players.values()) {
                if (!plugin.getEconomy().has(p, required)) {
                    p.sendMessage(ChatColor.RED + "警告！金币赛需要至少 " + required + " 金币，你当前只有 " +
                            plugin.getEconomy().getBalance(p) + " 金币");
                    allHaveMoney = false;
                }
            }

            if (!allHaveMoney) {
                player.sendMessage(ChatColor.YELLOW + "已开启金币赛，但有些玩家金币不足！");
            }
        }

        broadcastToRoom(ChatColor.GOLD + "金币赛 " + status + ChatColor.GOLD +
                "！本局金币倍率: " + required + " (需要至少 " + required + " 金币)");

        // 更新bossbar显示
        updateBossBar();
    }

    private void handleMoneyRewards(String result) {
        if (!moneyGame || plugin.getEconomy() == null) return;

        boolean isLandlordWin = result.contains("地主获胜");
        int baseAmount = multiplier * plugin.getMoneyMultiplier();

        Player landlordPlayer = this.landlord;
        List<Player> farmers = players.values().stream()
                .filter(p -> !p.equals(landlordPlayer))
                .collect(Collectors.toList());

        if (isLandlordWin) {
            // 地主获胜，每个农民给地主baseAmount
            for (Player farmer : farmers) {
                forceTransferMoney(farmer, landlordPlayer, baseAmount, "输给地主");
            }
        } else {
            // 农民获胜，地主给每个农民baseAmount/2
            int farmerReward = baseAmount / 2;
            double landlordBalance = plugin.getEconomy().getBalance(landlordPlayer);
            double totalRequired = farmerReward * farmers.size();

            if (landlordBalance >= totalRequired) {
                // 地主金币足够，正常支付
                for (Player farmer : farmers) {
                    forceTransferMoney(landlordPlayer, farmer, farmerReward, "赢得比赛");
                }
            } else {
                // 地主金币不足，农民平分地主剩余金币
                double eachFarmerGets = landlordBalance / farmers.size();
                if (eachFarmerGets > 0) {
                    for (Player farmer : farmers) {
                        forceTransferMoney(landlordPlayer, farmer, eachFarmerGets, "平分地主剩余金币");
                    }
                    // 通知所有玩家
                    broadcastToRoom(ChatColor.RED + String.format("地主金币不足！农民平分了地主剩余的 %.2f 金币", landlordBalance));
                } else {
                    broadcastToRoom(ChatColor.RED + "地主金币不足，无法支付任何奖励！");
                }
            }
        }
    }

    // 强制转账（无论玩家是否在线）
    private void forceTransferMoney(Player from, Player to, double amount, String reason) {
        Economy econ = plugin.getEconomy();
        if (from == null || to == null) return;

        String fromName = from.getName();
        String toName = to.getName();

        // 检查余额是否足够
        double fromBalance = econ.getBalance(from);
        double actualAmount = Math.min(amount, fromBalance);

        if (actualAmount <= 0) {
            // 记录到日志
            plugin.getLogger().warning(fromName + " 金币不足，无法支付 " + amount + " 给 " + toName);
            return;
        }

        // 从from扣除金币
        EconomyResponse withdrawResponse = econ.withdrawPlayer(from, actualAmount);
        if (!withdrawResponse.transactionSuccess()) {
            plugin.getLogger().warning(fromName + " 金币扣除失败: " + withdrawResponse.errorMessage);
            return;
        }

        // 给to增加金币
        EconomyResponse depositResponse = econ.depositPlayer(to, actualAmount);
        if (!depositResponse.transactionSuccess()) {
            // 如果存款失败，退还金币
            econ.depositPlayer(from, actualAmount);
            plugin.getLogger().warning(toName + " 金币转账失败: " + depositResponse.errorMessage);
            return;
        }

        // 更新统计
        StatsManager.PlayerStats fromStats = plugin.getStatsManager().getPlayerStats(fromName);
        fromStats.addNetMoney(-actualAmount);
        plugin.getStatsManager().updatePlayerStats(fromName, fromStats);

        StatsManager.PlayerStats toStats = plugin.getStatsManager().getPlayerStats(toName);
        toStats.addNetMoney(actualAmount);
        plugin.getStatsManager().updatePlayerStats(toName, toStats);

        // 只给在线玩家发送消息
        if (from.isOnline()) {
            from.sendMessage(ChatColor.YELLOW + String.format("你支付了 %.2f 金币给 %s (%s)",
                    actualAmount, toName, reason));
        }
        if (to.isOnline()) {
            to.sendMessage(ChatColor.GREEN + String.format("你收到了 %.2f 金币来自 %s (%s)",
                    actualAmount, fromName, reason));
        }
    }

    public boolean isMoneyGame() {
        return moneyGame;
    }
}