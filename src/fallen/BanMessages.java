package fallen;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;

/** GL: funny chat announcements of a ban, picked at random from the bundle (sam.banmsg.1, sam.banmsg.2, ...). */
public class BanMessages{
    private static int last = -1;

    public static boolean enabled(){
        return Core.settings.getBool("sam-ban-announce", false);
    }

    /** @param time ban length as sent to /ban: "perm", "7d"... */
    public static String random(String name, String time){
        Seq<String> all = new Seq<>();
        for(int i = 1; Core.bundle.has("sam.banmsg." + i); i++) all.add(Core.bundle.get("sam.banmsg." + i));
        if(all.isEmpty()) return "";

        int pick = Mathf.random(all.size - 1);
        if(all.size > 1 && pick == last) pick = (pick + 1) % all.size;
        last = pick;
        return "[scarlet]\u26a0[] " + all.get(pick).replace("{0}", "[accent]" + Strings.stripColors(name) + "[]").replace("{1}", "[accent]" + term(time) + "[]");
    }

    private static String term(String time){
        if(time.equals("perm")) return Core.bundle.get("sam.ban.forever");
        if(time.endsWith("d") && Strings.canParseInt(time.substring(0, time.length() - 1))){
            return Core.bundle.format("sam.ban.days", Strings.parseInt(time.substring(0, time.length() - 1)));
        }
        return time;
    }
}
