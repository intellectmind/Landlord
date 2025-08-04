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

                // 四带二系列可以互相比较
                if ((this.type == CardType.FOUR_WITH_TWO_SINGLES ||
                        this.type == CardType.FOUR_WITH_TWO_PAIRS) &&
                        (other.type == CardType.FOUR_WITH_TWO_SINGLES ||
                                other.type == CardType.FOUR_WITH_TWO_PAIRS)) {
                    return this.mainValue > other.mainValue;
                }

                return this.mainValue > other.mainValue;
            }

            // 不同类型不能比较
            return false;
        }
    }

    /**
     * 识别牌型（优化版）
     */
    public static CardPattern recognizePattern(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return new CardPattern(CardType.INVALID, 0, cards, 0);
        }

        // 按牌值分组统计，并按牌值排序
        Map<Integer, Integer> valueCount = new TreeMap<>(Comparator.reverseOrder());
        for (Card card : cards) {
            valueCount.put(card.getValue(), valueCount.getOrDefault(card.getValue(), 0) + 1);
        }

        int size = cards.size();
        List<Integer> counts = new ArrayList<>(valueCount.values());
        counts.sort(Collections.reverseOrder());

        // 王炸识别
        if (isRocket(valueCount)) {
            return new CardPattern(CardType.ROCKET, 17, cards, 0);
        }

        // 炸弹识别
        if (isBomb(valueCount, size)) {
            return new CardPattern(CardType.BOMB, valueCount.keySet().iterator().next(), cards, 0);
        }

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
    private static boolean isBomb(Map<Integer, Integer> valueCount, int size) {
        return size == 4 &&
                valueCount.size() == 1 &&
                valueCount.values().iterator().next() == 4;
    }

    // 检查基础牌型（单牌、对子、三张）
    private static CardPattern checkBasicPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        if (valueCount.size() == 1) {
            int value = valueCount.keySet().iterator().next();
            switch (size) {
                case 1: return new CardPattern(CardType.SINGLE, value, cards, 0);
                case 2: return new CardPattern(CardType.PAIR, value, cards, 0);
                case 3: return new CardPattern(CardType.TRIPLE, value, cards, 0);
            }
        }
        return null;
    }

    // 检查三带系列牌型
    private static CardPattern checkTriplePatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        if (size == 4 || size == 5) {
            Optional<Map.Entry<Integer, Integer>> tripleEntry = valueCount.entrySet().stream()
                    .filter(e -> e.getValue() == 3)
                    .findFirst();

            if (tripleEntry.isPresent()) {
                int mainValue = tripleEntry.get().getKey();
                if (size == 4) {
                    // 三带一
                    return new CardPattern(CardType.TRIPLE_SINGLE, mainValue, cards, 0);
                } else if (size == 5 && valueCount.size() == 2) {
                    // 三带二
                    return new CardPattern(CardType.TRIPLE_PAIR, mainValue, cards, 0);
                }
            }
        }
        return null;
    }

    // 检查四带系列牌型
    private static CardPattern checkFourWithPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        Optional<Map.Entry<Integer, Integer>> fourEntry = valueCount.entrySet().stream()
                .filter(e -> e.getValue() == 4)
                .findFirst();

        if (!fourEntry.isPresent()) return null;

        int mainValue = fourEntry.get().getKey();

        if (size == 6) {
            // 四带二（单牌）
            int singleCount = (int) valueCount.values().stream()
                    .filter(c -> c == 1)
                    .count();
            if (singleCount == 2) {
                return new CardPattern(CardType.FOUR_WITH_TWO_SINGLES, mainValue, cards, 0);
            }
        } else if (size == 8) {
            // 四带两对
            int pairCount = (int) valueCount.values().stream()
                    .filter(c -> c == 2)
                    .count();
            if (pairCount == 2) {
                return new CardPattern(CardType.FOUR_WITH_TWO_PAIRS, mainValue, cards, 0);
            }
        }

        return null;
    }

    // 检查顺子系列牌型
    private static CardPattern checkStraightPatterns(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        List<Integer> values = new ArrayList<>(valueCount.keySet());
        if (containsJokers(values)) {
            return null;
        }

        // 单顺
        if (size >= 5 && valueCount.values().stream().allMatch(c -> c == 1)) {
            if (isConsecutive(values)) {
                return new CardPattern(CardType.STRAIGHT, values.get(0), cards, size);
            }
        }

        // 双顺
        if (size >= 6 && size % 2 == 0 &&
                valueCount.values().stream().allMatch(c -> c == 2)) {
            if (isConsecutive(values)) {
                return new CardPattern(CardType.PAIR_STRAIGHT, values.get(0), cards, size / 2);
            }
        }

        // 飞机
        return checkAirplanePattern(valueCount, size, cards);
    }

    // 检查飞机牌型
    private static CardPattern checkAirplanePattern(Map<Integer, Integer> valueCount, int size, List<Card> cards) {
        // 找出所有三张及以上的牌值
        Map<Integer, Integer> tripleOrMore = valueCount.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (tripleOrMore.size() < 2) return null;

        List<Integer> tripleValues = tripleOrMore.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        // 查找最长的连续三张序列
        List<Integer> longestSequence = findLongestConsecutiveSequence(tripleValues);
        if (longestSequence.size() < 2) return null;

        // 计算总牌数
        int totalTriples = longestSequence.size();
        int totalCards = totalTriples * 3;
        int extraCards = size - totalCards;

        // 验证带牌是否合法
        if (extraCards == 0) {
            return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, totalTriples);
        }
        // 飞机带单牌
        else if (extraCards == totalTriples) {
            // 确保带的是单牌
            int singleCount = (int) valueCount.entrySet().stream()
                    .filter(e -> !longestSequence.contains(e.getKey()))
                    .filter(e -> e.getValue() == 1)
                    .count();
            if (singleCount >= totalTriples) {
                return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, totalTriples);
            }
        }
        // 飞机带对子
        else if (extraCards == totalTriples * 2) {
            // 确保带的是对子
            int pairCount = (int) valueCount.entrySet().stream()
                    .filter(e -> !longestSequence.contains(e.getKey()))
                    .filter(e -> e.getValue() == 2)
                    .count();
            if (pairCount >= totalTriples) {
                return new CardPattern(CardType.TRIPLE_STRAIGHT, longestSequence.get(0), cards, totalTriples);
            }
        }

        return null;
    }

    // 查找最长连续序列
    private static List<Integer> findLongestConsecutiveSequence(List<Integer> values) {
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
     * 检查数值是否连续
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
     * 检查是否包含王牌
     */
    private static boolean containsJokers(List<Integer> values) {
        return values.contains(16) || values.contains(17);
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

        // 生成所有可能的组合并检查有效性
        for (int len = 1; len <= hand.size(); len++) {
            generateCombinations(hand, len, 0, new ArrayList<>(), plays);
        }

        return plays.stream()
                .filter(cards -> recognizePattern(cards).getType() != CardType.INVALID)
                .collect(Collectors.toList());
    }

    /**
     * 获取能够压过指定牌型的出牌组合
     */
    private static List<List<Card>> getBeatingPlays(List<Card> hand, CardPattern target) {
        List<List<Card>> beatingPlays = new ArrayList<>();

        // 王炸总是可以出
        List<Card> rockets = hand.stream()
                .filter(card -> card.getValue() == 16 || card.getValue() == 17)
                .collect(Collectors.toList());
        if (rockets.size() == 2) {
            beatingPlays.add(rockets);
        }

        // 如果目标不是炸弹，炸弹可以压过
        if (target.getType() != CardType.BOMB && target.getType() != CardType.ROCKET) {
            Map<Integer, List<Card>> valueGroups = hand.stream()
                    .collect(Collectors.groupingBy(Card::getValue));

            for (List<Card> group : valueGroups.values()) {
                if (group.size() == 4) {
                    beatingPlays.add(group);
                }
            }
        }

        // 同类型的更大牌
        List<List<Card>> allPlays = getAllValidPlays(hand);
        for (List<Card> play : allPlays) {
            CardPattern pattern = recognizePattern(play);
            if (pattern.canBeat(target)) {
                beatingPlays.add(play);
            }
        }

        return beatingPlays;
    }

    /**
     * 生成组合
     */
    private static void generateCombinations(List<Card> hand, int len, int start,
                                             List<Card> current, List<List<Card>> result) {
        if (current.size() == len) {
            result.add(new ArrayList<>(current));
            return;
        }

        for (int i = start; i < hand.size(); i++) {
            current.add(hand.get(i));
            generateCombinations(hand, len, i + 1, current, result);
            current.remove(current.size() - 1);
        }
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

            // 同类型按牌值排序
            if (patternA.getType() == patternB.getType()) {
                return Integer.compare(patternA.getMainValue(), patternB.getMainValue());
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
            case BOMB: return 9;
            case ROCKET: return 10;
            default: return 999;
        }
    }
}