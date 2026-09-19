package fallen;

import arc.util.*;

/** Stable nicks, so the freeze icon or a server prefix added to a name does not break the history of a player. Based on SimpleAdminMode2. */
public final class NameUtil{
    private NameUtil(){}

    /** Strips colors, server prefixes like "[<T>] " and freeze/status symbols. */
    public static String normalize(String name){
        if(name == null) return "";
        String s = Strings.stripColors(name);
        if(s == null) return "";
        String[] parts = s.split("> ");
        if(parts.length > 1) s = parts[parts.length - 1];
        s = s.replace("❄️", "").replace("❄", "").replace("🧊", "").replace("☃", "").replace("⛄", "")
            .replace("✦", "").replace("★", "").replace("☆", "");
        s = s.replaceAll("^[\\s\\p{So}\\p{Cn}\\p{P}]+", "");
        return s.trim();
    }

    public static boolean samePlayer(String a, String b){
        if(a == null || b == null) return false;
        String na = normalize(a).toLowerCase(), nb = normalize(b).toLowerCase();
        if(na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }
}
