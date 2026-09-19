package fallen;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;

/**
 * GL: funny chat announcements of bans and kicks. Templates hold {name} for the nick and {time} for the ban length.
 * The built-in ones come from the bundle (sam.banmsg.N, sam.kickmsg.N); edited lists are kept in the settings.
 * Editor and templates are based on SimpleAdminMode2.
 */
public final class BanKickMessages{
    public static final String nick = "{name}", time = "{time}";
    /** Longer lines are split by words, the server cuts chat messages at about 150 characters. */
    public static final int maxChat = 140;

    public enum Kind{
        ban("sam-ban-msgs", "sam.banmsg.", "[scarlet]⚠[] "),
        kick("sam-kick-msgs", "sam.kickmsg.", "[orange]⚠[] ");

        final String key, bundle;
        /** Colored mark put before the built-in lines. */
        public final String prefix;

        Kind(String key, String bundle, String prefix){
            this.key = key;
            this.bundle = bundle;
            this.prefix = prefix;
        }
    }

    private static final IntIntMap last = new IntIntMap();

    private BanKickMessages(){}

    public static boolean enabled(){
        return Core.settings.getBool("sam-ban-announce", false);
    }

    public static void ban(String name, String length){
        if(enabled()) sendLater(random(Kind.ban, name, length));
    }

    public static void kick(String name){
        if(enabled()) sendLater(random(Kind.kick, name, null));
    }

    /** A random template of this kind filled in, "" when the list is empty. */
    public static String random(Kind kind, String name, String length){
        Seq<String> all = get(kind);
        if(all.isEmpty()) return "";
        int pick = Mathf.random(all.size - 1);
        if(all.size > 1 && pick == last.get(kind.ordinal(), -1)) pick = (pick + 1) % all.size;
        last.put(kind.ordinal(), pick);
        return fill(all.get(pick), name, length);
    }

    public static String fill(String template, String name, String length){
        String n = Strings.stripColors(name == null ? "?" : name);
        String out = template.contains(nick) ? template.replace(nick, n) : template + " " + n;
        return out.replace(time, length == null ? "" : term(length));
    }

    public static Seq<String> get(Kind kind){
        String raw = Core.settings.getString(kind.key, "");
        if(raw == null || raw.isEmpty()) return defaults(kind);
        Seq<String> out = new Seq<>();
        for(String line : raw.split("\n")){
            if(!line.isEmpty()) out.add(line);
        }
        return out.isEmpty() ? defaults(kind) : out;
    }

    public static void save(Kind kind, Seq<String> list){
        StringBuilder sb = new StringBuilder();
        for(String s : list){
            s = s.replace("\r", "").replace("\n", " ");
            if(s.isEmpty()) continue;
            if(sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        Core.settings.put(kind.key, sb.toString());
    }

    public static void reset(Kind kind){
        Core.settings.remove(kind.key);
    }

    public static Seq<String> defaults(Kind kind){
        Seq<String> out = new Seq<>();
        for(int i = 1; Core.bundle.has(kind.bundle + i); i++){
            out.add(kind.prefix + Core.bundle.get(kind.bundle + i).replace("{0}", "[accent]" + nick + "[]").replace("{1}", "[accent]" + time + "[]"));
        }
        return out;
    }

    /** Ban length as sent to /ban ("perm", "7d", "12h"...) in words. */
    public static String term(String length){
        if(length.equals("perm")) return Core.bundle.get("sam.ban.forever");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([shdwmy]?)").matcher(length);
        if(!m.matches()) return length;
        String unit = m.group(2).isEmpty() ? "min" : m.group(2);
        long n = Long.parseLong(m.group(1));
        // "one|few|many" word forms: 1 день, 3 дня, 7 дней; 1 day, 7 days
        String[] forms = Core.bundle.get("sam.ban.unit." + unit).split("\\|");
        int form = forms.length >= 3 ? plural(n) : n == 1 ? 0 : 1;
        return n + " " + forms[Math.min(form, forms.length - 1)];
    }

    /** Russian plural rule, used when the bundle gives three forms: 0 one, 1 few, 2 many. */
    private static int plural(long n){
        if(n % 10 == 1 && n % 100 != 11) return 0;
        if(n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return 1;
        return 2;
    }

    /** Sent a bit after the command, so the server does not count it as spam. */
    private static void sendLater(String message){
        if(message.isEmpty()) return;
        Seq<String> parts = split(message);
        for(int i = 0; i < parts.size; i++){
            String part = parts.get(i);
            Time.runTask(90f + i * 30f, () -> Call.sendChatMessage(part));
        }
    }

    static Seq<String> split(String text){
        Seq<String> out = new Seq<>();
        StringBuilder cur = new StringBuilder();
        for(String word : text.split(" ")){
            if(word.isEmpty()) continue;
            while(word.length() > maxChat){
                if(cur.length() > 0){
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(word.substring(0, maxChat));
                word = word.substring(maxChat);
            }
            if(cur.length() > 0 && cur.length() + 1 + word.length() > maxChat){
                out.add(cur.toString());
                cur.setLength(0);
            }
            if(cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if(cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
