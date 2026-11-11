package fr.chunkhopper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Convertit un texte de config en Component.
 * Accepte en meme temps :
 *  - les codes classiques : &amp;c, &amp;b, &amp;l, &amp;r ...
 *  - les couleurs hexa    : &amp;#ff8800
 *  - le MiniMessage       : &lt;red&gt;, &lt;#ff8800&gt;, &lt;gradient:red:blue&gt;...&lt;/gradient&gt;
 * Placeholders : {nom} ou &lt;nom&gt; sont remplaces par leur valeur.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY = Pattern.compile("[&\u00a7]([0-9a-fA-Fk-oK-OrR])");

    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private Text() {}

    /** Remplace les placeholders {cle} et &lt;cle&gt; dans le texte brut. */
    public static String replace(String raw, Map<String, String> placeholders) {
        if (raw == null) return "";
        String out = raw;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
            out = out.replace("<" + e.getKey() + ">", e.getValue());
        }
        return out;
    }

    public static Component parse(String raw, Map<String, String> placeholders) {
        String s = replace(raw, placeholders);
        s = HEX.matcher(s).replaceAll("<#$1>");
        s = LEGACY.matcher(s).replaceAll(r -> "<" + CODES.get(Character.toLowerCase(r.group(1).charAt(0))) + ">");
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component parse(String raw) {
        return parse(raw, Map.of());
    }

    public static String roman(int n) {
        if (n <= 0) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                n -= v[i];
                sb.append(s[i]);
            }
        }
        return sb.toString();
    }
}
