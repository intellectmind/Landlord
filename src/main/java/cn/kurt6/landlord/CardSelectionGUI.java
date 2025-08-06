package cn.kurt6.landlord;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class CardSelectionGUI implements Listener {
    private final Landlord plugin;
    private final GameRoom gameRoom;
    private final Map<Player, Inventory> openInventories = new HashMap<>();
    private final Map<Player, List<Integer>> selectedSlots = new HashMap<>();
    private final Set<Player> intentionallyClosing = new HashSet<>();

    public CardSelectionGUI(Landlord plugin, GameRoom gameRoom) {
        this.plugin = plugin;
        this.gameRoom = gameRoom;

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openGUI(Player player, List<Card> cards) {
        // 手牌为空检查
        if (cards == null || cards.isEmpty()) {
            return;
        }
        // 如果已经有打开的GUI或是托管玩家，则不再打开
        if (openInventories.containsKey(player) || gameRoom.isAutoPlay(player)) {
            return;
        }

        // 计算需要的行数 (每行9个物品)
        // 牌区域: 足够容纳所有牌的最小行数
        // 操作区: 固定1行
        // 信息区: 固定1行
        int cardRows = (int) Math.ceil(cards.size() / 9.0);
        int totalRows = cardRows + 2; // +1操作行 +1信息行

        Inventory inv = Bukkit.createInventory(player, totalRows * 9, ChatColor.GOLD + "选择要出的牌");

        // 添加牌到GUI (从第一行开始)
        for (int i = 0; i < cards.size(); i++) {
            inv.setItem(i, createCardItem(cards.get(i), false));
        }

        // 添加操作按钮 (倒数第二行)
        int buttonRow = (totalRows - 2) * 9;
        inv.setItem(buttonRow, createButton(Material.LIME_WOOL, ChatColor.GREEN + "✔ 确认出牌",
                Collections.singletonList(ChatColor.GRAY + "点击确认出牌")));
        inv.setItem(buttonRow + 1, createButton(Material.RED_WOOL, ChatColor.RED + "✖ 清空选择",
                Collections.singletonList(ChatColor.GRAY + "点击清空已选牌")));
        boolean canPass = !(gameRoom.getLastPlayedCards().isEmpty() && gameRoom.getPassCount() == 0);
        inv.setItem(buttonRow + 2, createButton(
                canPass ? Material.YELLOW_WOOL : Material.GRAY_WOOL,  // 如果可以过牌，黄色；否则灰色
                canPass ? ChatColor.YELLOW + "➜ 过牌" : ChatColor.GRAY + "➜ 不能过牌",
                Collections.singletonList(canPass ?
                        ChatColor.GRAY + "点击跳过本轮" :
                        ChatColor.RED + "第一轮必须出牌！")
        ));
        inv.setItem(buttonRow + 3, createButton(Material.BLUE_WOOL,
                gameRoom.isAutoPlay(player) ? ChatColor.RED + "⏹ 取消托管" : ChatColor.BLUE + "▶ 托管",
                Collections.singletonList(ChatColor.GRAY + "点击切换托管模式")));

        // 添加分隔线 (操作行剩余位置)
        for (int i = buttonRow + 4; i < buttonRow + 9; i++) {
            inv.setItem(i, createSeparator());
        }

        // 添加信息展示 (最后一行)
        int infoRow = (totalRows - 1) * 9;
        inv.setItem(infoRow, createInfoItem("当前手牌", cards.size() + "张", Material.PAPER));
        inv.setItem(infoRow + 1, createSelectedCardsInfoItem(player, Collections.emptyList())); // 初始化已选牌信息
        inv.setItem(infoRow + 2, createLastPlayedInfoItem()); // 显示上家出牌详情
        inv.setItem(infoRow + 3, createInfoItem("当前倍数", "x" + gameRoom.getMultiplier(), Material.GOLD_INGOT));
        inv.setItem(infoRow + 4, createInfoItem("当前玩家", gameRoom.getCurrentPlayer().getName(), Material.PLAYER_HEAD));

        // 添加分隔线 (信息行剩余位置)
        for (int i = infoRow + 5; i < infoRow + 9; i++) {
            inv.setItem(i, createSeparator());
        }

        player.openInventory(inv);
        openInventories.put(player, inv);
        selectedSlots.put(player, new ArrayList<>());
    }

    // 在玩家选择牌时实时校验
    private void updateSelectionValidity(Player player, List<Integer> selected) {
        Inventory inv = openInventories.get(player);
        if (inv == null) return;

        List<Card> cards = gameRoom.getPlayerCards(player);
        List<Card> selectedCards = new ArrayList<>();
        for (int index : selected) {
            if (index < cards.size()) {
                selectedCards.add(cards.get(index));
            }
        }

        // 检查牌型有效性
        GameLogic.CardPattern pattern = GameLogic.recognizePattern(selectedCards);
        boolean isValid = pattern.getType() != GameLogic.CardType.INVALID;

        // 检查是否能压过上家
        boolean canBeat = true;
        if (isValid && !gameRoom.getLastPlayedCards().isEmpty()) {
            GameLogic.CardPattern lastPattern = GameLogic.recognizePattern(gameRoom.getLastPlayedCards());
            canBeat = pattern.canBeat(lastPattern);
        }

        // 更新确认按钮状态
        ItemStack confirmButton = inv.getItem((inv.getSize()/9 - 2)*9);
        if (confirmButton != null) {
            ItemMeta meta = confirmButton.getItemMeta();
            if (selected.isEmpty()) {
                confirmButton.setType(Material.GRAY_WOOL);
                meta.setDisplayName(ChatColor.GRAY + "✖ 请选择牌");
                meta.setLore(Collections.singletonList(ChatColor.RED + "请先选择要出的牌"));
            } else if (isValid) {
                if (canBeat) {  // 只有能压过时才显示绿色确认按钮
                    confirmButton.setType(Material.LIME_WOOL);
                    meta.setDisplayName(ChatColor.GREEN + "✔ 确认出牌");
                    meta.setLore(Collections.singletonList(ChatColor.GRAY + "牌型: " + GameRoom.getPatternName(pattern.getType())));
                } else {
                    confirmButton.setType(Material.RED_WOOL);
                    meta.setDisplayName(ChatColor.RED + "✖ 无法压过");
                    meta.setLore(Collections.singletonList(ChatColor.RED + "无法压过上家的牌"));
                }
            } else {
                confirmButton.setType(Material.RED_WOOL);
                meta.setDisplayName(ChatColor.RED + "✖ 无效牌型");
                meta.setLore(Collections.singletonList(ChatColor.RED + "请选择有效牌型"));
            }
            confirmButton.setItemMeta(meta);
        }

        // 更新已选牌信息显示
        updateSelectedCardsInfo(player, selectedCards);
    }

    // 创建已选牌信息物品
    private ItemStack createSelectedCardsInfoItem(Player player, List<Card> selectedCards) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "已选牌");

        List<String> lore = new ArrayList<>();
        if (selectedCards.isEmpty()) {
            lore.add(ChatColor.GRAY + "无");
        } else {
            lore.add(ChatColor.GREEN.toString() + selectedCards.size() + "张:");
            StringBuilder cardNames = new StringBuilder();
            for (int i = 0; i < selectedCards.size(); i++) {
                if (i > 0) cardNames.append(" ");
                cardNames.append(selectedCards.get(i).toString());

                // 如果一行太长，换行显示
                if (cardNames.length() > 30 && i < selectedCards.size() - 1) {
                    lore.add(ChatColor.WHITE + cardNames.toString());
                    cardNames = new StringBuilder();
                }
            }
            if (cardNames.length() > 0) {
                lore.add(ChatColor.WHITE + cardNames.toString());
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // 更新已选牌信息
    private void updateSelectedCardsInfo(Player player, List<Card> selectedCards) {
        Inventory inv = openInventories.get(player);
        if (inv == null) return;

        int totalRows = inv.getSize() / 9;
        int infoRow = (totalRows - 1) * 9;

        ItemStack selectedInfo = createSelectedCardsInfoItem(player, selectedCards);
        inv.setItem(infoRow + 1, selectedInfo);
    }

    // 创建上家出牌信息物品
    private ItemStack createLastPlayedInfoItem() {
        List<Card> lastPlayed = gameRoom.getLastPlayedCards();
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "上家出牌");

        List<String> lore = new ArrayList<>();
        if (lastPlayed.isEmpty()) {
            lore.add(ChatColor.GRAY + "无");
        } else {
            // 识别牌型
            GameLogic.CardPattern pattern = GameLogic.recognizePattern(lastPlayed);
            String patternName = GameRoom.getPatternName(pattern.getType());

            lore.add(ChatColor.AQUA + patternName + " (" + lastPlayed.size() + "张):");
            StringBuilder cardNames = new StringBuilder();
            for (int i = 0; i < lastPlayed.size(); i++) {
                if (i > 0) cardNames.append(" ");
                cardNames.append(lastPlayed.get(i).toString());

                // 如果一行太长，换行显示
                if (cardNames.length() > 30 && i < lastPlayed.size() - 1) {
                    lore.add(ChatColor.WHITE + cardNames.toString());
                    cardNames = new StringBuilder();
                }
            }
            if (cardNames.length() > 0) {
                lore.add(ChatColor.WHITE + cardNames.toString());
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String getSelectedCountText(int count) {
        return count > 0 ? ChatColor.GREEN + String.valueOf(count) + "张" : ChatColor.GRAY + "无";
    }

    private String getLastPlayedText() {
        List<Card> lastPlayed = gameRoom.getLastPlayedCards();
        return lastPlayed.isEmpty() ? ChatColor.GRAY + "无" :
                ChatColor.YELLOW + String.valueOf(lastPlayed.size()) + "张";
    }

    private ItemStack createCardItem(Card card, boolean selected) {
        Material material = getCardMaterial(card);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // 设置显示名称（统一使用白色或选中颜色）
        meta.setDisplayName((selected ? ChatColor.GOLD + "★ " : ChatColor.WHITE) + card.toString());

        // 设置Lore
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "牌值: " + card.getValue());
        lore.add(ChatColor.GRAY + "花色: " + card.getSuit());
        lore.add("");
        lore.add(selected ? ChatColor.RED + "★ 已选中 (点击取消)" : ChatColor.GREEN + "点击选择");

        meta.setLore(lore);

        // 如果牌被选中，添加附魔效果
        if (selected) {
            meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS); // 隐藏"附魔"文字
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createButton(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoItem(String title, String value, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + title);
        meta.setLore(Collections.singletonList(ChatColor.WHITE + value));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSeparator() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private Material getCardMaterial(Card card) {
        // 根据牌值返回材质
        int value = card.getValue();

        if (value == 16) return Material.PAPER; // 小王
        if (value == 17) return Material.BOOK;  // 大王

        // 普通牌根据牌值返回不同材质
        switch (value) {
            case 3: return Material.WHITE_CONCRETE;
            case 4: return Material.ORANGE_CONCRETE;
            case 5: return Material.MAGENTA_CONCRETE;
            case 6: return Material.LIGHT_BLUE_CONCRETE;
            case 7: return Material.YELLOW_CONCRETE;
            case 8: return Material.LIME_CONCRETE;
            case 9: return Material.PINK_CONCRETE;
            case 10: return Material.GRAY_CONCRETE;
            case 11: return Material.LIGHT_GRAY_CONCRETE;
            case 12: return Material.CYAN_CONCRETE;
            case 13: return Material.PURPLE_CONCRETE;
            case 14: return Material.BLUE_CONCRETE;
            case 15: return Material.BROWN_CONCRETE;
            default: return Material.WHITE_CONCRETE; // 默认
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // 如果是托管玩家，直接返回
        if (gameRoom.isAutoPlay(player)) {
            event.setCancelled(true);
            return;
        }

        if (!openInventories.containsKey(player)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().getHolder() != null &&
                !(event.getClickedInventory().getHolder() instanceof Player)) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;

        // 获取库存标题的两种方式
        String title;
        title = ChatColor.stripColor(event.getView().getTitle());

        // 判断GUI类型
        if (title.equals("叫分选择(点击'1'或'2'可查看手牌和地主牌)")) {
            switch (event.getSlot()) {
                case 3: // 不叫按钮
                    gameRoom.handleBiddingCommand(player, "不叫");
                    player.closeInventory();
                    break;
                case 4: // 1分按钮
                case 5: // 2分按钮
                case 6: // 3分按钮
                    // 检查按钮是否被禁用（灰色）
                    if (clickedItem.getType() == Material.GRAY_WOOL) {
                        player.sendMessage(ChatColor.RED + "叫分必须高于当前最高分！");
                        return; // 直接返回，不处理无效点击
                    }
                    // 根据按钮位置确定叫分
                    String bidCommand = "";
                    switch (event.getSlot()) {
                        case 4: bidCommand = "1分"; break;
                        case 5: bidCommand = "2分"; break;
                        case 6: bidCommand = "3分"; break;
                    }
                    gameRoom.handleBiddingCommand(player, bidCommand);
                    player.closeInventory();
                    break;
                // 0-2是信息按钮，7-8是信息/空位，不需要处理
            }
        } else if (title.equals("选择要出的牌")) {
            // 非当前玩家点击牌时，拦截并提示
            if (!player.equals(gameRoom.getCurrentPlayer())) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "还没轮到你！");
                return;
            }

            List<Card> cards = gameRoom.getPlayerCards(player);
            if (cards == null) {
                player.closeInventory();
                return;
            }

            Inventory inv = openInventories.get(player);
            int slot = event.getSlot();
            List<Integer> selected = selectedSlots.get(player);

            int totalRows = inv.getSize() / 9;
            int buttonRow = (totalRows - 2) * 9;
            int infoRow = (totalRows - 1) * 9;

            if (slot < cards.size()) {
                toggleCardSelection(player, inv, cards, selected, slot);
            } else if (slot >= buttonRow && slot < infoRow) {
                handleButtonClick(player, clickedItem);
            }
        }
    }

    private void toggleCardSelection(Player player, Inventory inv, List<Card> cards,
                                     List<Integer> selected, int slot) {
        if (selected.contains(slot)) {
            selected.remove(Integer.valueOf(slot));
            inv.setItem(slot, createCardItem(cards.get(slot), false));
        } else {
            selected.add(slot);
            inv.setItem(slot, createCardItem(cards.get(slot), true));
        }

        // 实时更新选择有效性和已选牌信息
        updateSelectionValidity(player, selected);
    }

    private void updateSelectedCount(Player player, Inventory inv, int count) {
        int totalRows = inv.getSize() / 9;
        int infoRow = (totalRows - 1) * 9;

        ItemStack infoItem = inv.getItem(infoRow + 1);
        if (infoItem != null) {
            ItemMeta meta = infoItem.getItemMeta();
            meta.setLore(Collections.singletonList(ChatColor.WHITE + getSelectedCountText(count)));
            infoItem.setItemMeta(meta);
        }
    }

    private void handleButtonClick(Player player, ItemStack clickedItem) {
        if (clickedItem == null) return;

        // 检查是否是当前玩家（非当前玩家不能操作）
        if (!player.equals(gameRoom.getCurrentPlayer())) {
            player.sendMessage(ChatColor.RED + "还没轮到你！");
            player.closeInventory();
            return;
        }

        List<Integer> selected = selectedSlots.get(player);

        switch (clickedItem.getType()) {
            case LIME_WOOL: // 确认出牌
                handleConfirm(player, selected);
                break;
            case RED_WOOL: // 清空选择
                clearSelection(player, selected);
                break;
            case YELLOW_WOOL: // 过牌（仅当按钮是黄色时才允许）
                if (gameRoom.getLastPlayedCards().isEmpty() && gameRoom.getPassCount() == 0) {
                    player.sendMessage(ChatColor.RED + "第一轮必须出牌，不能直接过牌！");
                    return;
                }
                intentionallyClosing.add(player); // 标记为有意关闭
                gameRoom.passCard(player);
                player.closeInventory();
                break;
            case GRAY_WOOL: // 如果按钮是灰色（禁用状态），提示玩家
                if (clickedItem.getItemMeta().getDisplayName().contains("不能过牌")) {
                    player.sendMessage(ChatColor.RED + "第一轮必须出牌！");
                } else if (clickedItem.getItemMeta().getDisplayName().contains("请选择牌")) {
                    player.sendMessage(ChatColor.RED + "请先选择要出的牌！");
                }
                break;
            case BLUE_WOOL: // 托管
                gameRoom.toggleAutoPlay(player);
                player.closeInventory();
                break;
        }
    }

    private void handleConfirm(Player player, List<Integer> selected) {
        if (selected.isEmpty()) {
            player.sendMessage(ChatColor.RED + "请先选择要出的牌！");
            return;
        }

        List<Card> cards = gameRoom.getPlayerCards(player);
        List<Card> selectedCards = new ArrayList<>();
        List<Integer> selectedIndices = new ArrayList<>(selected);

        // 按索引降序排序，这样移除时不会影响后面的索引
        selectedIndices.sort(Collections.reverseOrder());
        for (int index : selectedIndices) {
            if (index < cards.size()) {
                selectedCards.add(cards.get(index));
            }
        }

        if (!gameRoom.getLastPlayedCards().isEmpty()) {
            GameLogic.CardPattern currentPattern = GameLogic.recognizePattern(selectedCards);
            GameLogic.CardPattern lastPattern = GameLogic.recognizePattern(gameRoom.getLastPlayedCards());

            if (!currentPattern.canBeat(lastPattern)) {
                player.sendMessage(ChatColor.RED + "无法压过上家的牌！请重新选择");
                return;
            }
        }

        intentionallyClosing.add(player); // 标记为有意关闭
        player.closeInventory();
        gameRoom.playSelectedCards(player, selectedCards, selectedIndices);
    }

    private void clearSelection(Player player, List<Integer> selected) {
        if (selected.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "当前没有选中的牌");
            return;
        }

        Inventory inv = openInventories.get(player);
        List<Card> cards = gameRoom.getPlayerCards(player);

        for (int slot : selected) {
            if (slot < cards.size()) {
                inv.setItem(slot, createCardItem(cards.get(slot), false));
            }
        }

        selected.clear();
        updateSelectionValidity(player, selected); // 更新选择状态
        player.sendMessage(ChatColor.YELLOW + "已清空所有选择");
    }

    public void openBiddingGUI(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, ChatColor.GOLD + "叫分选择(点击'1'或'2'可查看手牌和地主牌)");

        // 获取当前最高叫分
        int currentBid = gameRoom.getCurrentBidScore();
        List<Card> playerCards = gameRoom.getPlayerCards(player);
        List<Card> landlordCards = gameRoom.getLandlordCards();

        // 添加手牌信息按钮 (位置0)
        ItemStack handInfo = createInfoItem(
                ChatColor.GREEN + "手牌",
                playerCards.size() + "张",
                Material.BOOK
        );
        ItemMeta handMeta = handInfo.getItemMeta();
        List<String> handLore = new ArrayList<>();
        handLore.add(ChatColor.GRAY + "你的手牌详情:");
        for (Card card : playerCards) {
            handLore.add(ChatColor.WHITE + " - " + card.toString());
        }
        handMeta.setLore(handLore);
        handInfo.setItemMeta(handMeta);
        inv.setItem(0, handInfo);

        // 添加地主牌信息按钮 (位置1)
        ItemStack landlordInfo = createInfoItem(
                ChatColor.GOLD + "地主牌",
                "3张",
                Material.GOLD_NUGGET
        );
        ItemMeta landlordMeta = landlordInfo.getItemMeta();
        List<String> landlordLore = new ArrayList<>();
        landlordLore.add(ChatColor.GRAY + "地主牌详情:");
        for (Card card : landlordCards) {
            landlordLore.add(ChatColor.WHITE + " - " + card.toString());
        }
        landlordMeta.setLore(landlordLore);
        landlordInfo.setItemMeta(landlordMeta);
        inv.setItem(1, landlordInfo);

        // 位置2留空
        inv.setItem(2, createSeparator());

        // 不叫按钮 (位置3)
        inv.setItem(3, createButton(Material.RED_WOOL, ChatColor.RED + "不叫",
                Collections.singletonList(ChatColor.GRAY + "点击选择不叫")));

        // 1分按钮 (位置4)
        inv.setItem(4, createButton(
                currentBid < 1 ? Material.GREEN_WOOL : Material.GRAY_WOOL,
                currentBid < 1 ? ChatColor.GREEN + "1分" : ChatColor.GRAY + "1分(不可选)",
                Collections.singletonList(currentBid < 1 ?
                        ChatColor.GRAY + "点击叫1分" :
                        ChatColor.RED + "必须高于当前叫分")));

        // 2分按钮 (位置5)
        inv.setItem(5, createButton(
                currentBid < 2 ? Material.YELLOW_WOOL : Material.GRAY_WOOL,
                currentBid < 2 ? ChatColor.YELLOW + "2分" : ChatColor.GRAY + "2分(不可选)",
                Collections.singletonList(currentBid < 2 ?
                        ChatColor.GRAY + "点击叫2分" :
                        ChatColor.RED + "必须高于当前叫分")));

        // 3分按钮 (位置6)
        inv.setItem(6, createButton(
                currentBid < 3 ? Material.BLUE_WOOL : Material.GRAY_WOOL,
                currentBid < 3 ? ChatColor.BLUE + "3分" : ChatColor.GRAY + "3分(不可选)",
                Collections.singletonList(currentBid < 3 ?
                        ChatColor.GRAY + "点击叫3分" :
                        ChatColor.RED + "必须高于当前叫分")));

        // 位置7留空
        inv.setItem(7, createSeparator());

        // 当前最高分信息 (位置8)
        inv.setItem(8, createInfoItem("当前最高分",
                currentBid + "分 (" +
                        (gameRoom.getCurrentHighestBidder() != null ?
                                gameRoom.getCurrentHighestBidder().getName() : "无人") +
                        ")", Material.GOLD_INGOT));

        player.openInventory(inv);
        openInventories.put(player, inv);
    }

    private final Set<UUID> reopeningPlayers = new HashSet<>();

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 如果正在重新打开，直接返回
        if (reopeningPlayers.contains(playerId)) {
            reopeningPlayers.remove(playerId);
            return;
        }

        // 清理标记集合
        if (intentionallyClosing.remove(player)) {
            openInventories.remove(player);
            selectedSlots.remove(player);
            return;
        }

        // 不需要GUI的情况
        if (gameRoom.getGameState() == GameRoom.GameState.FINISHED ||
                gameRoom.isAutoPlay(player) ||  // 托管状态下不处理GUI
                !player.equals(gameRoom.getCurrentPlayer())) {
            openInventories.remove(player);
            selectedSlots.remove(player);
            return;
        }

        // 检查是否应该保持GUI打开
        List<Card> cards = gameRoom.getPlayerCards(player);
        if (cards == null || cards.isEmpty()) {
            openInventories.remove(player);
            selectedSlots.remove(player);
            return;
        }

        // 真正的意外关闭，准备重新打开
        openInventories.remove(player);
        selectedSlots.remove(player);

        // 如果是托管玩家，不重新打开GUI
        if (gameRoom.isAutoPlay(player)) {
            return;
        }

        // 标记为正在重新打开
        reopeningPlayers.add(playerId);

        // 延迟重新打开 - 使用Folia兼容的方式
        Runnable reopenTask = () -> {
            reopeningPlayers.remove(playerId);
            if (player.isOnline() &&
                    gameRoom.getPlayerCards(player) != null &&
                    !gameRoom.getPlayerCards(player).isEmpty() &&
                    player.equals(gameRoom.getCurrentPlayer()) &&
                    !intentionallyClosing.contains(player) &&
                    !gameRoom.isAutoPlay(player)) {
                openGUI(player, gameRoom.getPlayerCards(player));
            }
        };

        if (plugin.isFolia()) {
            // 使用Folia的调度方式
            player.getScheduler().runDelayed(plugin, task -> reopenTask.run(), null, 2L);
        } else {
            // 使用传统Bukkit调度方式
            Bukkit.getScheduler().runTaskLater(plugin, reopenTask, 2L);
        }
    }
}