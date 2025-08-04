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

    // 抢地主相关状态
    private final Map<UUID, Integer> bidStatus = new ConcurrentHashMap<>(); // 玩家叫分状态：0=不叫，1=1分，2=2分，3=3分
    private int currentBidScore = 0; // 当前最高叫分
    private Player currentHighestBidder = null; // 当前最高叫分者
    private List<Player> biddingOrder = new ArrayList<>(); // 叫分顺序
    private int biddingIndex = 0; // 当前叫分玩家索引
    private boolean biddingPhaseComplete = false; // 叫分阶段是否完成

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

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.GREEN + player.getName() + " 加入了房间！");

        sendGameButtons(player);

        if (players.size() == 3) {
            broadcastToRoom(ChatColor.YELLOW + "房间已满！所有玩家准备后即可开始游戏！");
        }
    }

    public void removePlayer(Player player) {
        lastHandMessages.remove(player.getUniqueId());
        bossBar.removePlayer(player);

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
            plugin.getLogger().warning("重置计分板时出现错误: " + e.getMessage());
        }

        if (gameStarted) {
            // 游戏开始后玩家离开，设置为托管状态
            autoPlay.put(player.getUniqueId(), true);
            broadcastToRoom(ChatColor.RED + player.getName() + " 掉线了，已自动托管！");

            // 如果是当前玩家掉线，立即触发自动出牌
            if (player.equals(currentPlayer)) {
                runTaskLater(() -> autoPlayCards(player), 5L);
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
        currentPlayer = biddingOrder.get(biddingIndex);

        // 初始化显示
        updateBossBar();
        updateScoreboard();

        broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 叫分！");
        sendBiddingButtons(currentPlayer);
        startTurnTimer(currentPlayer);
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
                        player.sendMessage(current ? ChatColor.YELLOW + "已取消托管" : ChatColor.YELLOW + "已开启托管");
                        sendPlayingButtons(player);

                        // 如果开启托管且当前是该玩家出牌，立即执行自动出牌
                        if (!current && player.equals(currentPlayer)) {
                            autoPlayCards(player);
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

    public void handleCardSelection(Player player, int cardIndex) {
        if (!gameStarted || gameState != GameState.PLAYING || !player.equals(currentPlayer)) {
            return;
        }

        List<Card> cards = playerCards.get(player.getUniqueId());
        List<Integer> selected = selectedCards.get(player.getUniqueId());

        if (cardIndex < 0 || cardIndex >= cards.size()) {
            return;
        }

        if (selected.contains(cardIndex)) {
            // 取消选择
            selected.remove(Integer.valueOf(cardIndex));
            player.sendMessage(ChatColor.YELLOW + "取消选择: " + cards.get(cardIndex).toString());
        } else {
            // 选择牌
            selected.add(cardIndex);
            player.sendMessage(ChatColor.GOLD + "选择: " + cards.get(cardIndex).toString());
        }

        showPlayerCards(player, true); // 刷新显示
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

    private void handleBiddingCommand(Player player, String command) {
        if (!player.equals(currentPlayer)) {
            player.sendMessage(ChatColor.RED + "还没轮到你叫分！");
            return;
        }

        cancelCurrentTimer();

        int bidScore = 0;
        if (command.equals("1分")) bidScore = 1;
        else if (command.equals("2分")) bidScore = 2;
        else if (command.equals("3分")) bidScore = 3;
        else if (command.equals("不叫")) bidScore = 0;

        if (bidScore > 0) {
            if (bidScore <= currentBidScore) {
                player.sendMessage(ChatColor.RED + "叫分必须比当前最高分(" + currentBidScore + "分)更高！");
                sendBiddingButtons(player);
                startTurnTimer(player);
                return;
            }
            currentBidScore = bidScore;
            currentHighestBidder = player;
            broadcastToRoom(ChatColor.GREEN + player.getName() + " 叫了 " + bidScore + " 分！");

            // 立即更新显示
            updateBossBar();
            updateScoreboard();

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
        biddingIndex++;

        if (biddingIndex >= biddingOrder.size()) {
            if (currentHighestBidder == null) {
                resetGame();
                startGame();
                return;
            } else {
                confirmLandlord(currentHighestBidder, currentBidScore);
                return;
            }
        }

        currentPlayer = biddingOrder.get(biddingIndex);

        // 更新显示
        updateBossBar();
        updateScoreboard();

        broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 叫分！");
        sendBiddingButtons(currentPlayer);
        startTurnTimer(currentPlayer);
    }

    private void confirmLandlord(Player player, int bidScore) {
        landlord = player;
        List<Card> cards = playerCards.get(landlord.getUniqueId());
        cards.addAll(landlordCards);
        cards.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        gameState = GameState.PLAYING;
        currentPlayer = landlord;
        passCount = 0;

        // 根据叫分确定倍数
        multiplier = bidScore;

        for (Player p : players.values()) {
            p.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
        }

        broadcastToRoom(ChatColor.GOLD + player.getName() + " 成为地主！叫分: " + bidScore + " 分");
        broadcastToRoom(ChatColor.YELLOW + "当前倍数: " + multiplier);
        showLandlordCards();

        for (Player p : players.values()) {
            showPlayerCards(p, true);
            sendPlayingButtons(p);
        }

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.YELLOW + "请地主 " + currentPlayer.getName() + " 先出牌！");
        startTurnTimer(currentPlayer);
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
                sendPlayingButtons(player);
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
                sendPlayingButtons(player);
                break;

            case "取消托管":
                autoPlay.put(player.getUniqueId(), false);
                player.sendMessage(ChatColor.YELLOW + "已取消托管模式");
                sendPlayingButtons(player);
                break;
        }
    }

    private void playSelectedCards(Player player, List<Card> selectedCardsList, List<Integer> selectedIndices) {
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
            lastPlayedCards.clear();  // 清空上家出牌
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

    private String getPatternName(GameLogic.CardType type) {
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

    private void passCard(Player player) {
        cancelCurrentTimer();
        passCount++;
        broadcastToRoom(ChatColor.GRAY + player.getName() + " 选择过牌");

        if (passCount >= 2) {
            // 两人过牌，重新开始
            lastPlayedCards.clear();
            currentPlayer = lastPlayer;
            passCount = 0;
            broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 出牌！");
            showPlayerCards(currentPlayer, true);
            updateBossBar();
            updateScoreboard();
        } else {
            nextPlayer(); // 直接切换到下家
        }

        updateBossBar();

        // 立即启动计时器或自动出牌
        if (autoPlay.get(currentPlayer.getUniqueId())) {
            runTaskLater(() -> autoPlayCards(currentPlayer), 5L); // 延迟5 ticks执行
        } else {
            startTurnTimer(currentPlayer);
        }
    }

    private void nextPlayer() {
        cancelCurrentTimer();

        List<Player> playerList = new ArrayList<>(players.values());
        int nextIndex = (playerList.indexOf(currentPlayer) + 1) % playerList.size();
        currentPlayer = playerList.get(nextIndex);

        updateBossBar();
        updateScoreboard();
        broadcastToRoom(ChatColor.YELLOW + "请 " + currentPlayer.getName() + " 在 " + getTurnTimeout() + " 秒内出牌！");

        // 如果玩家托管，直接自动出牌，不启动计时器
        if (autoPlay.get(currentPlayer.getUniqueId())) {
            runTaskLater(() -> autoPlayCards(currentPlayer), 5L); // 延迟5 ticks执行
        } else {
            sendPlayingButtons(currentPlayer);
            startTurnTimer(currentPlayer); // 正常玩家才启动计时器
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
            player.sendMessage(ChatColor.RED + "时间到！已自动托管");
            if (gameState == GameState.BIDDING) {
                handleBiddingCommand(player, "不叫"); // 叫分阶段超时默认不叫
            } else {
                // 托管按钮
                TextComponent autoButton = new TextComponent(net.md_5.bungee.api.ChatColor.RED + "【取消托管】");
                autoButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder("点击取消托管").color(net.md_5.bungee.api.ChatColor.GRAY).create()));
                autoButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action auto"));
                player.spigot().sendMessage(autoButton);

                // 发送按钮
                BaseComponent[] components = new BaseComponent[]{
                        new TextComponent("操作: "),
                        autoButton
                };

                player.spigot().sendMessage(components);
                autoPlayCards(player); // 游戏阶段超时自动出牌
            }
            cancelCurrentTimer();
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
        // 确保开启托管
        autoPlay.put(player.getUniqueId(), true);

        List<Card> cards = playerCards.get(player.getUniqueId());
        if (cards == null || cards.isEmpty()) return;

        // 自动出牌逻辑
        List<Card> selectedCards = GameLogic.autoSelectCards(cards, lastPlayedCards.isEmpty() ? null : GameLogic.recognizePattern(lastPlayedCards));
        if (selectedCards != null && !selectedCards.isEmpty()) {
            playSelectedCards(player, selectedCards, getCardIndices(cards, selectedCards));
        } else {
            passCard(player); // 如果无法出牌，则过牌
        }
    }

    // 辅助方法：根据牌值获取手牌中的索引
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
        // 重置所有玩家的准备状态
        for (UUID playerId : players.keySet()) {
            readyStatus.put(playerId, false);
        }

        // 处理金币奖励
        if (moneyGame) {
            handleMoneyRewards(reason);
        }

        lastHandMessages.clear();
        playerTimers.values().forEach(BukkitTask::cancel);
        playerTimers.clear();

        gameStarted = false;
        gameState = GameState.FINISHED;

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

        for (Player player : players.values()) {
            player.spigot().sendMessage(new TextComponent(" ")); // 空消息占位
            player.spigot().sendMessage(readyButton);
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
        runTaskLater(this::resetGame, 100L);
    }

    private void resetGame() {
        // 重置准备状态
        for (UUID playerId : players.keySet()) {
            readyStatus.put(playerId, false);
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

        // 重置叫分相关状态
        currentBidScore = 0;
        currentHighestBidder = null;
        biddingOrder.clear();
        biddingIndex = 0;
        biddingPhaseComplete = false;

        // 清空玩家手牌
        playerCards.clear();
        for (UUID playerId : players.keySet()) {
            readyStatus.put(playerId, false);
            autoPlay.put(playerId, false);
            selectedCards.put(playerId, new ArrayList<>());
            bidStatus.put(playerId, 0);
        }

        // 不发送任何广播消息，避免显示房间状态
        updateBossBar(); // 仅更新BossBar（如果需要）
        updateScoreboard(); // 仅更新计分板（如果需要）

        // 不要重置 autoPlay，保持托管状态
        for (UUID playerId : players.keySet()) {
            // 保持托管状态，除非玩家手动取消
            if (autoPlay.getOrDefault(playerId, false)) {
                players.get(playerId).sendMessage(ChatColor.YELLOW + "你仍处于托管状态！输入 /ddz auto 取消");
            }
        }
    }

    private void showPlayerCards(Player player, boolean allowSelection) {
        List<Card> cards = playerCards.get(player.getUniqueId());
        if (cards == null) return;

        ComponentBuilder builder = new ComponentBuilder("你的手牌")
                .color(net.md_5.bungee.api.ChatColor.GREEN)
                .append(allowSelection ? " (点击选择):" : ":");

        List<Integer> selectedIndices = selectedCards.get(player.getUniqueId());

        // 显示每张牌
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            boolean isSelected = selectedIndices.contains(i);

            TextComponent cardComponent = new TextComponent("[" + card.toString() + "]");
            cardComponent.setColor(isSelected ?
                    net.md_5.bungee.api.ChatColor.GOLD :
                    net.md_5.bungee.api.ChatColor.WHITE);
            cardComponent.setBold(isSelected);

            if (allowSelection) {
                cardComponent.setClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/landlord_card " + i
                ));
                cardComponent.setHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(isSelected ? "取消选择" : "选择此牌")
                                .color(net.md_5.bungee.api.ChatColor.YELLOW)
                                .create()
                ));
            }

            builder.append(" ").append(cardComponent);
        }

        // 显示上家出牌信息
        if (!lastPlayedCards.isEmpty() && lastPlayer != null) {
            builder.append("\n").append("上家出牌: ")
                    .color(net.md_5.bungee.api.ChatColor.GRAY)
                    .append(lastPlayer.getName() + " 出了: ")
                    .color(net.md_5.bungee.api.ChatColor.YELLOW);

            for (Card card : lastPlayedCards) {
                builder.append(card.toString()).append(" ");
            }
        }

        // 发送更新后的消息
        BaseComponent[] newMessage = builder.create();
        player.spigot().sendMessage(newMessage);
        lastHandMessages.put(player.getUniqueId(), newMessage);

        // 显示操作按钮（如果有选中的牌）
        if (gameState == GameState.PLAYING && !selectedIndices.isEmpty() && player.equals(currentPlayer)) {
            showActionButtons(player);
        }
    }

    private void showActionButtons(Player player) {
        List<Card> cards = playerCards.get(player.getUniqueId());
        List<Integer> selectedIndices = selectedCards.get(player.getUniqueId());

        // 1. 清除旧的操作按钮消息
        player.spigot().sendMessage(new TextComponent(" ")); // 空消息占位

        // 2. 显示选中的牌
        ComponentBuilder selectedBuilder = new ComponentBuilder("已选择: ")
                .color(net.md_5.bungee.api.ChatColor.AQUA);

        for (int index : selectedIndices) {
            selectedBuilder.append(cards.get(index).toString()).append(" ");
        }

        player.spigot().sendMessage(selectedBuilder.create());

        // 3. 显示操作按钮
        TextComponent confirmBtn = new TextComponent("【确认出牌】");
        confirmBtn.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        confirmBtn.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/landlord_action confirm"));

        // 过牌按钮
        TextComponent skipButton = new TextComponent("【过牌】");
        skipButton.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        skipButton.setBold(true);
        skipButton.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/landlord_action skip"));

        TextComponent clearBtn = new TextComponent("【清空选择】");
        clearBtn.setColor(net.md_5.bungee.api.ChatColor.RED);
        clearBtn.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/landlord_action clear"));

        player.spigot().sendMessage(new ComponentBuilder("操作: ")
                .append(confirmBtn)
                .append(" ")
                .append(skipButton)
                .append(" ")
                .append(clearBtn)
                .create());
    }

    private void showLandlordCards() {
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "地主牌: ");
        for (int i = 0; i < landlordCards.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(landlordCards.get(i).toString());
        }
        broadcastToRoom(sb.toString());
    }

    private void sendGameButtons(Player player) {
        player.sendMessage(ChatColor.AQUA + "=== 游戏操作 ===");

        // 创建可点击的「准备/取消准备」按钮
        TextComponent readyButton = new TextComponent("【准备/取消准备】");
        readyButton.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        readyButton.setBold(true);
        readyButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord ready"));
        readyButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击切换准备状态").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 发送按钮消息
        player.spigot().sendMessage(readyButton);
        player.sendMessage(ChatColor.WHITE + "或者输入 /ddz ready - 准备/取消准备");
    }

    private void sendBiddingButtons(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 叫分阶段 ===");
        player.sendMessage(ChatColor.YELLOW + "当前最高分: " + currentBidScore + " 分");

        // 创建可点击的叫分按钮
        List<BaseComponent> components = new ArrayList<>();
        components.add(new TextComponent("请选择: "));

        // 不叫按钮
        TextComponent passButton = new TextComponent("【不叫】");
        passButton.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        passButton.setBold(true);
        passButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action bid_0"));
        passButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击选择不叫").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
        components.add(passButton);
        components.add(new TextComponent("  "));

        // 动态生成可用的叫分按钮
        for (int score = currentBidScore + 1; score <= 3; score++) {
            TextComponent bidButton = new TextComponent("【" + score + "分】");
            bidButton.setColor(score == 1 ? net.md_5.bungee.api.ChatColor.GREEN :
                    score == 2 ? net.md_5.bungee.api.ChatColor.YELLOW : net.md_5.bungee.api.ChatColor.RED);
            bidButton.setBold(true);
            bidButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action bid_" + score));
            bidButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("点击叫" + score + "分").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
            components.add(bidButton);
            if (score < 3) {
                components.add(new TextComponent("  "));
            }
        }

        // 发送按钮
        player.spigot().sendMessage(components.toArray(new BaseComponent[0]));
    }

    private void sendPlayingButtons(Player player) {
        player.sendMessage(ChatColor.GREEN + "=== 游戏操作 ===");

        // 出牌按钮
        TextComponent playButton = new TextComponent("【选择出牌】");
        playButton.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        playButton.setBold(true);
        playButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action select"));
        playButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击选择要出的牌").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 过牌按钮
        TextComponent skipButton = new TextComponent("【过牌】");
        skipButton.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        skipButton.setBold(true);
        skipButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action skip"));
        skipButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击过牌").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 托管按钮
        boolean isAuto = autoPlay.get(player.getUniqueId());
        TextComponent autoButton = new TextComponent(isAuto ? "【取消托管】" : "【托管】");
        autoButton.setColor(isAuto ? net.md_5.bungee.api.ChatColor.RED : net.md_5.bungee.api.ChatColor.BLUE);
        autoButton.setBold(true);
        autoButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/landlord_action auto"));
        autoButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(isAuto ? "点击取消托管" : "点击开启托管").color(net.md_5.bungee.api.ChatColor.YELLOW).create()));

        // 发送按钮
        BaseComponent[] components = new BaseComponent[]{
                new TextComponent("操作: "),
                playButton,
                new TextComponent("  "),
                skipButton,
                new TextComponent("  "),
                autoButton
        };
        player.spigot().sendMessage(components);
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

        // 如果计分板被禁用，直接发送聊天消息
        if (!plugin.isScoreboardEnabled()) {
            for (Player player : players.values()) {
                sendScoreboardInfo(player);
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
                            int count = playerCards.getOrDefault(p.getUniqueId(), Collections.emptyList()).size();
                            objective.getScore((p.equals(player) ? "你" : p.getName()) + ": " + count + "张")
                                    .setScore(line.getAndDecrement());
                        });
                    } else {
                        objective.getScore(ChatColor.WHITE + "玩家列表:").setScore(line.getAndDecrement());
                        players.values().forEach(p -> {
                            boolean ready = readyStatus.getOrDefault(p.getUniqueId(), false);
                            objective.getScore((ready ? ChatColor.GREEN + "✓ " : ChatColor.RED + "✗ ") +
                                            (p.equals(player) ? "你" : p.getName()))
                                    .setScore(line.getAndDecrement());
                        });
                    }

                    // 最后设置记分板
                    player.setScoreboard(scoreboard);
                } catch (Exception e) {
                    sendScoreboardInfo(player); // 回退到聊天消息
                }
            }, null);
        } catch (Exception e) {
            plugin.getLogger().warning("调度计分板更新失败: " + e.getMessage());
        }
    }

    private java.util.logging.Logger getLogger() {
        return plugin.getLogger();
    }

    /**
     * 当计分板不可用时，通过聊天消息发送游戏信息
     */
    private void sendScoreboardInfo(Player player) {
        // 如果游戏已结束且已经发送过消息，则不再发送
        if (gameState == GameState.FINISHED && lastHandMessages.containsKey(player.getUniqueId())) {
            return;
        }

        player.sendMessage(ChatColor.GOLD + "=== 游戏状态 ===");
        player.sendMessage(ChatColor.YELLOW + "房间: " + roomId);

        if (gameStarted) {
            if (gameState == GameState.BIDDING) {
                player.sendMessage(ChatColor.GOLD + "叫分阶段 - 最高分: " + currentBidScore);
            }

            if (landlord != null) {
                player.sendMessage(ChatColor.RED + "地主: " + landlord.getName());
                player.sendMessage(ChatColor.AQUA + "倍数: x" + multiplier);
            }

            if (currentPlayer != null) {
                player.sendMessage(ChatColor.WHITE + "当前玩家: " + ChatColor.GREEN + currentPlayer.getName());
            }

            player.sendMessage(ChatColor.AQUA + "手牌数量:");
            for (Player p : players.values()) {
                List<Card> cards = playerCards.get(p.getUniqueId());
                int cardCount = cards != null ? cards.size() : 0;
                String name = p.equals(player) ? "你" : p.getName();
                player.sendMessage("  " + name + ": " + cardCount);
            }
        } else {
            player.sendMessage(ChatColor.WHITE + "玩家列表:");
            for (Player p : players.values()) {
                boolean ready = readyStatus.get(p.getUniqueId());
                String status = ready ? ChatColor.GREEN + "✓ 已准备" : ChatColor.RED + "✗ 未准备";
                String name = p.equals(player) ? "你" : p.getName();
                player.sendMessage("  " + status + " " + name);
            }
        }
        player.sendMessage(ChatColor.GOLD + "================");
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

        // 同步清理计分板
        for (Player player : players.values()) {
            try {
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            } catch (Exception e) {
//                plugin.getLogger().warning("清理计分板时出现错误: " + e.getMessage());
            }
        }
    }

    // Getter方法
    public String getRoomId() {
        return roomId;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public Player getRoomOwner() {
        return roomOwner;
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

    private void transferMoney(Player from, Player to, double amount, String reason) {
        Economy econ = plugin.getEconomy();
        if (from == null || to == null || !from.isOnline() || !to.isOnline()) return;

        String fromName = from.getName();
        String toName = to.getName();

        // 检查余额是否足够
        double fromBalance = econ.getBalance(from);
        double actualAmount = Math.min(amount, fromBalance);

        if (actualAmount <= 0) {
            from.sendMessage(ChatColor.RED + "你的金币不足，无法支付！");
            return;
        }

        // 从from扣除金币
        EconomyResponse withdrawResponse = econ.withdrawPlayer(from, actualAmount);
        if (!withdrawResponse.transactionSuccess()) {
            from.sendMessage(ChatColor.RED + "金币扣除失败: " + withdrawResponse.errorMessage);
            return;
        }

        // 给to增加金币
        EconomyResponse depositResponse = econ.depositPlayer(to, actualAmount);
        if (!depositResponse.transactionSuccess()) {
            // 如果存款失败，退还金币
            econ.depositPlayer(from, actualAmount);
            to.sendMessage(ChatColor.RED + "金币转账失败: " + depositResponse.errorMessage);
            return;
        }

        // 更新统计
        StatsManager.PlayerStats fromStats = plugin.getStatsManager().getPlayerStats(fromName);
        fromStats.addNetMoney(-actualAmount); // 支出记为负
        plugin.getStatsManager().updatePlayerStats(fromName, fromStats);

        StatsManager.PlayerStats toStats = plugin.getStatsManager().getPlayerStats(toName);
        toStats.addNetMoney(actualAmount); // 收入记为正
        plugin.getStatsManager().updatePlayerStats(toName, toStats);

        from.sendMessage(ChatColor.YELLOW + String.format("你支付了 %.2f 金币给 %s (%s)", actualAmount, toName, reason));
        to.sendMessage(ChatColor.GREEN + String.format("你收到了 %.2f 金币来自 %s (%s)", actualAmount, fromName, reason));
    }

    public boolean isMoneyGame() {
        return moneyGame;
    }
}