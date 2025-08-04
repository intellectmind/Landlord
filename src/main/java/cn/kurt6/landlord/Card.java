package cn.kurt6.landlord;

public class Card {
    private final String suit;    // 花色 ♠♥♣♦
    private final int value;      // 牌值 3-15(2), 16(小王), 17(大王)
    
    public Card(String suit, int value) {
        this.suit = suit;
        this.value = value;
    }
    
    public String getSuit() {
        return suit;
    }
    
    public int getValue() {
        return value;
    }

    public String getDisplayName() {
        return switch (value) {
            case 17 -> "大王";
            case 16 -> "小王";
            case 15 -> "2";
            case 14 -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> String.valueOf(value);
        };
    }
    
    @Override
    public String toString() {
        if (value == 16) return "🃏小王";
        if (value == 17) return "🃏大王";

        String displayName = getDisplayName();
        String colorCode = suit.equals("♠") || suit.equals("♣") ? "§7" : "§c"; // 灰色或红色

        return colorCode + suit + displayName + "§r";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Card card = (Card) obj;
        return value == card.value && suit.equals(card.suit);
    }
    
    @Override
    public int hashCode() {
        return suit.hashCode() * 31 + value;
    }
}