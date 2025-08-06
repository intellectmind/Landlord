package cn.kurt6.landlord;

import java.util.*;
import java.util.stream.Collectors;

public class GameLogic {

    // 牌型枚举
    public enum CardType {
        SINGLE,        // 单牌
        PAIR,          // 对子
        TRIPLE,        // 三张
        TRIPLE_SINGLE, // 三带一
        TRIPLE_PAIR,   // 三带二
        FOUR_WITH_TWO_SINGLES,  // 四带二（带两张单牌）
        FOUR_WITH_TWO_PAIRS,    // 四带两对（带两对牌）
        STRAIGHT,      // 顺子(单)
        PAIR_STRAIGHT, // 连对
        TRIPLE_STRAIGHT,// 飞机
        BOMB,          // 炸弹
        ROCKET,        // 王炸
        INVALID        // 无效牌型
    }

    // 牌型信息类
    public static class CardPattern {
        private final CardType type;
        private final int mainValue;  // 主要牌值
        private final List<Card> cards;
        private final int length;     // 连牌长度

        public CardPattern(CardType type, int mainValue, List<Card> cards, int length) {
            this.type = type;
            this.mainValue = mainValue;
            this.cards = new ArrayList<>(cards);
            this.length = length;
        }

        public CardType getType() { return type; }
        public int getMainValue() { return mainValue; }
        public List<Card> getCards() { return cards; }
        public int getLength() { return length; }

        public boolean canBeat(CardPattern other) {
            if (other == null) return true;

            // 王炸最大
            if (this.type == CardType.ROCKET) return true;
            if (other.type == CardType.ROCKET) return false;

            // 炸弹大于非炸弹
            if (this.type == CardType.BOMB && other.type != CardType.BOMB) return true;
            if (this.type != CardType.BOMB && other.type == CardType.BOMB) return false;

            // 炸弹之间比较
            if (this.type == CardType.BOMB && other.type == CardType.BOMB) {
                return this.mainValue > other.mainValue;
            }

            // 同类型比较
            if (this.type == other.type) {
                // 顺子系列需要长度相同
                if (this.type == CardType.STRAIGHT || this.type == CardType.PAIR_STRAIGHT ||
                        this.type == CardType.TRIPLE_STRAIGHT) {
                    return this.length == other.length && this.mainValue > other.mainValue;
                }

                // 四带二系列只能同类型比较，不能互相比较
                if (this.type == CardType.FOUR_WITH_TWO_SINGLES &&
                        other.type == CardType.FOUR_WITH_TWO_SINGLES) {
                    return this.mainValue > other.mainValue;
                }
                if (this.type == CardType.FOUR_WITH_TWO_PAIRS &&
                        other.type == CardType.FOUR_WITH_TWO_PAIRS) {
                    return this.mainValue > other.mainValue;
                }

                return this.mainValue > other.mainValue;
            }

            // 不同类型不能比较
            return false;
        }
    }

    /**
     * 识别牌型
     */
    public static CardPattern recognizePattern(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return new CardPattern(CardType.INVALID, 0, cards, 0);
        }

        // 按牌值分组统计，并按牌值排序
        Map<Integer, Integer> valueCount = new TreeMap<>(Comparator.reverseOrder());
        for (Card card : cards) {
            int value = card.getValue();
            // 检查牌值是否有效（3-17）
            if (value < 3 || value > 17) {
                return new CardPattern(CardType.INVALID, 0, cards, 0);
            }
            valueCount.put(value, valueCount.getOrDefault(value, 0) + 1);
        }

        int size = cards.size();
        List<Integer> counts = new ArrayList<>(valueCount.values());
        counts.sort(Collections.reverseOrder());

        // 王炸识别
        if (isRocket(valueCount)) {
            return new CardPattern(CardType.ROCKET, 17, cards, 0);
        }

        // 炸弹识别
        CardPattern bombPattern = checkBomb(valueCount, size, cards);
        if (bombPattern != null) return bombPattern;

        // 单牌、对子、三张
        CardPattern basicPattern = checkBasicPatterns(valueCount, size, cards);
        if (basicPattern != null) return basicPattern;

        // 三带系列
        CardPattern triplePattern = checkTriplePatterns(valueCount, size, cards);
        if (triplePattern != null) return triplePattern;

        // 四带系列
        CardPattern fourWithPattern = checkFourWithPatterns(valueCount, size, cards);
        if (fourWithPattern != null) return fourWithPattern;

        // 顺子系列
        CardPattern straightPattern = checkStraightPatterns(valueCount, size, cards);
        if (straightPattern != null) return straightPattern;

        return new CardPattern(CardType.INVALID, 0, cards, 0);
    }

    // 检查王炸
    private static boolean isRocket(Map<Integer, Integer> valueCount) {
        return valueCount.size() == 2 &&
                valueCount.containsKey(16) &&
                valueCount.containsKey(17) &&
                valueCount.get(16) == 1 &&
                valueCount.get(17) == 1;
    }

    // 检查炸弹
    private static CardPattern checkBomb(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        if (size == 4 && valueCount.size() == 1) {
            int value = valueCount.keySet().iterator().next();
            int count = valueCount.get(value);
            if (count == 4 && value >= 3 && value <= 15) { // 炸弹不能是王牌，且牌值有效
                return new CardPattern(CardType.BOMB, value, cards, 0);
            }
        }
        return null;
    }

    // 检查基础牌型（单牌、对子、三张）
    private static CardPattern checkBasicPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        if (valueCount.size() == 1) {
            int value = valueCount.keySet().iterator().next();
            int count = valueCount.get(value);

            switch (size) {
                case 1:
                    if (count == 1 && value >= 3 && value <= 17) // 单牌可以是王
                        return new CardPattern(CardType.SINGLE, value, cards, 0);
                    break;
                case 2:
                    if (count == 2 && value >= 3 && value <= 15) // 对子不能是王牌
                        return new CardPattern(CardType.PAIR, value, cards, 0);
                    break;
                case 3:
                    if (count == 3 && value >= 3 && value <= 15) // 三张不能是王牌
                        return new CardPattern(CardType.TRIPLE, value, cards, 0);
                    break;
            }
        }
        return null;
    }

    // 检查三带系列牌型
    private static CardPattern checkTriplePatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        // 找出三张的牌
        List<Integer> tripleValues = valueCount.entrySet().stream()
                .filter(e -> e.getValue() == 3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (tripleValues.size() != 1) return null;

        int mainValue = tripleValues.get(0);
        // 三带牌的主牌不能是王牌
        if (mainValue >= 16) return null;

        if (size == 4) {
            // 三带一：必须有且只有一张单牌
            long singleCount = valueCount.entrySet().stream()
                    .filter(e -> e.getKey() != mainValue)
                    .filter(e -> e.getValue() == 1)
                    .count();
            if (singleCount == 1 && valueCount.size() == 2) {
                return new CardPattern(CardType.TRIPLE_SINGLE, mainValue, cards, 0);
            }
        } else if (size == 5) {
            // 三带二：必须有且只有一对牌
            long pairCount = valueCount.entrySet().stream()
                    .filter(e -> e.getKey() != mainValue)
                    .filter(e -> e.getValue() == 2)
                    .count();
            if (pairCount == 1 && valueCount.size() == 2) {
                return new CardPattern(CardType.TRIPLE_PAIR, mainValue, cards, 0);
            }
        }
        return null;
    }

    // 检查四带系列牌型
    private static CardPattern checkFourWithPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        // 找出四张的牌
        List<Integer> fourValues = valueCount.entrySet().stream()
                .filter(e -> e.getValue() == 4)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (fourValues.size() != 1) return null;

        int mainValue = fourValues.get(0);
        // 四带牌的主牌不能是王牌
        if (mainValue >= 16) return null;

        if (size == 6) {
            // 四带二（单牌）：必须带两张不同值的单牌
            long singleCount = valueCount.entrySet().stream()
                    .filter(e -> e.getKey() != mainValue)
                    .filter(e -> e.getValue() == 1)
                    .count();

            if (singleCount == 2 && valueCount.size() == 3) {
                // 确保带的单牌不是王牌（可以带王牌，但两个王不能同时带）
                boolean hasJokers = valueCount.containsKey(16) && valueCount.containsKey(17) &&
                        valueCount.get(16) == 1 && valueCount.get(17) == 1;
                if (!hasJokers) {
                    return new CardPattern(CardType.FOUR_WITH_TWO_SINGLES, mainValue, cards, 0);
                }
            }
        } else if (size == 8) {
            // 四带两对：必须带两对不同值的牌
            long pairCount = valueCount.entrySet().stream()
                    .filter(e -> e.getKey() != mainValue)
                    .filter(e -> e.getValue() == 2)
                    .count();

            if (pairCount == 2 && valueCount.size() == 3) {
                // 确保带的对子不是王牌
                boolean hasJokerPairs = valueCount.entrySet().stream()
                        .filter(e -> e.getKey() != mainValue)
                        .filter(e -> e.getValue() == 2)
                        .anyMatch(e -> e.getKey() >= 16);
                if (!hasJokerPairs) {
                    return new CardPattern(CardType.FOUR_WITH_TWO_PAIRS, mainValue, cards, 0);
                }
            }
        }

        return null;
    }

    // 检查顺子系列牌型
    private static CardPattern checkStraightPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        List<Integer> values = new ArrayList<>(valueCount.keySet());
        values.sort(Collections.reverseOrder());

        // 顺子不能包含2和王牌
        if (containsInvalidStraightCards(values)) {
            return null;
        }

        // 单顺：5张或更多连续单牌
        if (size >= 5 && valueCount.values().stream().allMatch(c -> c == 1)) {
            if (isConsecutive(values) && values.get(0) <= 14) { // 顺子最大到A(14)
                return new CardPattern(CardType.STRAIGHT, values.get(0), cards, size);
            }
        }

        // 双顺（连对）：3对或更多连续对子
        if (size >= 6 && size % 2 == 0 && valueCount.size() >= 3 &&
                valueCount.values().stream().allMatch(c -> c == 2)) {
            if (isConsecutive(values) && values.get(0) <= 14) {
                return new CardPattern(CardType.PAIR_STRAIGHT, values.get(0), cards, size / 2);
            }
        }

        // 飞机：2个或更多连续三张（可带牌）
        return checkAirplanePattern(valueCount, size, cards);
    }

    // 检查飞机牌型
    private static CardPattern checkAirplanePattern(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        // 找出所有三张的牌值（包括四张中的三张）
        List<Integer> tripleValues = valueCount.entrySet().stream()
                .filter(e -> e.getValue() >= 3) // 三张或四张都算
                .map(Map.Entry::getKey)
                .filter(v -> v <= 14) // 飞机不能包含2和王牌
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());

        if (tripleValues.size() < 2) return null;

        // 查找最长的连续三张序列
        List<Integer> longestSequence = findLongestConsecutiveSequence(tripleValues);
        if (longestSequence.size() < 2) return null;

        // 计算飞机部分的牌数
        int airplaneCount = longestSequence.size();
        int airplaneCards = airplaneCount * 3;

        // 计算额外带牌数
        int extraCards = size - airplaneCards;

        // 计算可用于带牌的牌
        Map<Integer, Integer> extraValueCount = new HashMap<>(valueCount);
        for (int value : longestSequence) {
            extraValueCount.put(value, extraValueCount.get(value) - 3);
            if (extraValueCount.get(value) == 0) {
                extraValueCount.remove(value);
            }
        }

        // 纯飞机（不带牌）
        if (extraCards == 0) {
            return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, airplaneCount);
        }
        // 飞机带单牌
        else if (extraCards == airplaneCount) {
            int availableSingles = (int) extraValueCount.values().stream()
                    .filter(count -> count >= 1)
                    .count();
            if (availableSingles >= airplaneCount) {
                return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, airplaneCount);
            }
        }
        // 飞机带对子
        else if (extraCards == airplaneCount * 2) {
            int availablePairs = (int) extraValueCount.values().stream()
                    .filter(count -> count >= 2)
                    .count();
            if (availablePairs >= airplaneCount) {
                return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, airplaneCount);
            }
        }

        return null;
    }

    // 查找最长连续序列
    private static List<Integer> findLongestConsecutiveSequence(List<Integer> values) {
        if (values.isEmpty()) return new ArrayList<>();

        List<Integer> longest = new ArrayList<>();
        List<Integer> current = new ArrayList<>();

        current.add(values.get(0));

        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) == values.get(i-1) - 1) {
                current.add(values.get(i));
            } else {
                if (current.size() > longest.size()) {
                    longest = new ArrayList<>(current);
                }
                current.clear();
                current.add(values.get(i));
            }
        }

        if (current.size() > longest.size()) {
            longest = new ArrayList<>(current);
        }

        return longest;
    }

    /**
     * 检查数值是否连续（降序）
     */
    private static boolean isConsecutive(List<Integer> values) {
        if (values.size() < 2) return false;

        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1) - values.get(i) != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查是否包含不能组成顺子的牌（2和王牌）
     */
    private static boolean containsInvalidStraightCards(List<Integer> values) {
        return values.contains(15) || values.contains(16) || values.contains(17); // 2, 小王, 大王
    }

    /**
     * 获取可以出的牌组合
     */
    public static List<List<Card>> getPossiblePlays(List<Card> hand, CardPattern lastPattern) {
        List<List<Card>> possiblePlays = new ArrayList<>();

        if (lastPattern == null) {
            // 首次出牌，可以出任何有效牌型
            possiblePlays.addAll(getAllValidPlays(hand));
        } else {
            // 需要压过上家的牌
            possiblePlays.addAll(getBeatingPlays(hand, lastPattern));
        }

        return possiblePlays;
    }

    /**
     * 获取所有有效的出牌组合
     */
    private static List<List<Card>> getAllValidPlays(List<Card> hand) {
        List<List<Card>> plays = new ArrayList<>();
        Set<String> addedPlays = new HashSet<>(); // 防重复

        // 生成所有可能的组合并检查有效性
        for (int len = 1; len <= Math.min(hand.size(), 20); len++) { // 限制最大长度避免性能问题
            generateCombinations(hand, len, 0, new ArrayList<>(), plays, addedPlays);
        }

        return plays;
    }

    /**
     * 获取能够压过指定牌型的出牌组合
     */
    private static List<List<Card>> getBeatingPlays(List<Card> hand, CardPattern target) {
        List<List<Card>> beatingPlays = new ArrayList<>();
        Set<String> addedPlays = new HashSet<>();

        // 1. 特殊牌型处理：王炸和炸弹
        if (target.getType() != CardType.ROCKET) {
            // 检查是否有王炸
            List<Card> rockets = hand.stream()
                    .filter(card -> card.getValue() == 16 || card.getValue() == 17)
                    .collect(Collectors.toList());
            if (rockets.size() == 2) {
                beatingPlays.add(rockets);
                return beatingPlays; // 王炸可以直接出，不需要其他选择
            }
        }

        // 2. 炸弹处理
        if (target.getType() != CardType.BOMB && target.getType() != CardType.ROCKET) {
            // 只有目标不是炸弹时才能用炸弹压
            Map<Integer, List<Card>> valueGroups = hand.stream()
                    .filter(card -> card.getValue() >= 3 && card.getValue() <= 15) // 炸弹不能是王牌
                    .collect(Collectors.groupingBy(Card::getValue));

            for (Map.Entry<Integer, List<Card>> entry : valueGroups.entrySet()) {
                if (entry.getValue().size() == 4) {
                    // 炸弹必须比目标炸弹大（如果是炸弹对炸弹）
                    if (target.getType() == CardType.BOMB) {
                        if (entry.getKey() > target.getMainValue()) {
                            beatingPlays.add(new ArrayList<>(entry.getValue()));
                        }
                    } else {
                        beatingPlays.add(new ArrayList<>(entry.getValue()));
                    }
                }
            }
        }

        // 3. 同类型牌比较
        List<List<Card>> allPlays = getAllValidPlays(hand);
        for (List<Card> play : allPlays) {
            CardPattern pattern = recognizePattern(play);

            // 检查是否是同类型且可以压过
            if (isSameTypeAndCanBeat(pattern, target)) {
                String playKey = getPlayKey(play);
                if (!addedPlays.contains(playKey)) {
                    beatingPlays.add(play);
                    addedPlays.add(playKey);
                }
            }
        }

        return beatingPlays;
    }

    /**
     * 检查是否是同类型且可以压过
     */
    private static boolean isSameTypeAndCanBeat(CardPattern pattern, CardPattern target) {
        // 不同类型不能比较（除了炸弹和火箭）
        if (pattern.getType() != target.getType() &&
                pattern.getType() != CardType.BOMB &&
                pattern.getType() != CardType.ROCKET) {
            return false;
        }

        // 特殊牌型处理
        switch (pattern.getType()) {
            case STRAIGHT:
            case PAIR_STRAIGHT:
            case TRIPLE_STRAIGHT:
                // 顺子系列必须长度相同
                return pattern.getLength() == target.getLength() &&
                        pattern.getMainValue() > target.getMainValue();

            case FOUR_WITH_TWO_SINGLES:
            case FOUR_WITH_TWO_PAIRS:
                // 四带二只能同类型比较
                return pattern.getType() == target.getType() &&
                        pattern.getMainValue() > target.getMainValue();

            default:
                // 其他类型只需主牌值更大
                return pattern.getMainValue() > target.getMainValue();
        }
    }

    /**
     * 生成组合
     */
    private static void generateCombinations(List<Card> hand, int len, int start,
                                             List<Card> current, List<List<Card>> result,
                                             Set<String> addedPlays) {
        if (current.size() == len) {
            CardPattern pattern = recognizePattern(current);
            if (pattern.getType() != CardType.INVALID) {
                String playKey = getPlayKey(current);
                if (!addedPlays.contains(playKey)) {
                    result.add(new ArrayList<>(current));
                    addedPlays.add(playKey);
                }
            }
            return;
        }

        for (int i = start; i < hand.size(); i++) {
            current.add(hand.get(i));
            generateCombinations(hand, len, i + 1, current, result, addedPlays);
            current.remove(current.size() - 1);
        }
    }

    /**
     * 生成牌组的唯一标识（用于去重）
     */
    private static String getPlayKey(List<Card> cards) {
        return cards.stream()
                .map(card -> String.valueOf(card.getValue()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * AI自动选择出牌
     */
    public static List<Card> autoSelectCards(List<Card> hand, CardPattern lastPattern) {
        List<List<Card>> possiblePlays = getPossiblePlays(hand, lastPattern);

        if (possiblePlays.isEmpty()) {
            return null; // 无法出牌
        }

        // 策略1：如果能一次出完，优先出
        for (List<Card> play : possiblePlays) {
            if (play.size() == hand.size()) {
                return play;
            }
        }

        // 策略2：根据剩余牌数调整出牌策略
        int remainingCards = hand.size();
        possiblePlays.sort((a, b) -> {
            CardPattern patternA = recognizePattern(a);
            CardPattern patternB = recognizePattern(b);

            // 炸弹和火箭最后出（除非能直接获胜）
            boolean isBombA = patternA.getType() == CardType.BOMB || patternA.getType() == CardType.ROCKET;
            boolean isBombB = patternB.getType() == CardType.BOMB || patternB.getType() == CardType.ROCKET;

            if (isBombA && !isBombB) {
                return remainingCards > 8 ? 1 : -1; // 牌多时炸弹留后，牌少时优先出
            }
            if (!isBombA && isBombB) {
                return remainingCards > 8 ? -1 : 1;
            }

            // 优先出能减少手牌种类的牌
            int typesA = countRemainingCardTypes(hand, a);
            int typesB = countRemainingCardTypes(hand, b);
            if (typesA != typesB) {
                return Integer.compare(typesA, typesB);
            }

            // 优先出较大的牌（在有多个选择时）
            if (patternA.getType() == patternB.getType()) {
                return Integer.compare(patternB.getMainValue(), patternA.getMainValue());
            }

            // 不同类型按优先级排序
            return Integer.compare(getTypePriority(patternA.getType()),
                    getTypePriority(patternB.getType()));
        });

        return possiblePlays.get(0);
    }

    // 计算出牌后剩余手牌的种类数
    private static int countRemainingCardTypes(List<Card> hand, List<Card> play) {
        List<Card> remaining = new ArrayList<>(hand);
        remaining.removeAll(play);
        return (int) remaining.stream()
                .map(Card::getValue)
                .distinct()
                .count();
    }

    /**
     * 获取牌型优先级(数字越小优先级越高)
     */
    private static int getTypePriority(CardType type) {
        switch (type) {
            case SINGLE: return 1;
            case PAIR: return 2;
            case TRIPLE: return 3;
            case TRIPLE_SINGLE: return 4;
            case TRIPLE_PAIR: return 5;
            case STRAIGHT: return 6;
            case PAIR_STRAIGHT: return 7;
            case TRIPLE_STRAIGHT: return 8;
            case FOUR_WITH_TWO_SINGLES: return 9;
            case FOUR_WITH_TWO_PAIRS: return 10;
            case BOMB: return 11;
            case ROCKET: return 12;
            default: return 999;
        }
    }
}