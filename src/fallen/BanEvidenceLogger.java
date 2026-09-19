package fallen;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.world.*;
import mindustry.world.blocks.*;

import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Keeps the last minutes of builds, breaks and chat of every player. On a ban from the ban menu it appends
 * what the banned player did in the last minute to one text file, and gives the same data to the Discord report.
 * Based on SimpleAdminMode2.
 */
public final class BanEvidenceLogger{
    /** What goes to the local file, and how far back the buffer (and the Discord file) reaches. */
    public static final long window = 60_000L, buffer = 5 * 60_000L;
    private static final int maxEvents = 6000;
    private static final String defaultName = "gl-admin-evidence.txt";

    private static final Seq<Evt> events = new Seq<>();
    private static boolean loaded;

    private BanEvidenceLogger(){}

    public static boolean enabled(){
        return Core.settings.getBool("sam-evidence-enabled", true);
    }

    private static boolean collecting(){
        return enabled() || DiscordReport.enabled();
    }

    public static void init(){
        if(loaded) return;
        loaded = true;

        Events.on(BlockBuildBeginEvent.class, e -> {
            if(!collecting() || e.unit == null || e.unit.getPlayer() == null || e.tile == null) return;
            try{
                Block block = e.tile.build instanceof ConstructBlock.ConstructBuild cons && cons.current != null ? cons.current : e.tile.block();
                add(e.unit.getPlayer().id, e.unit.getPlayer().name, e.breaking ? Kind.breaking : Kind.building,
                    block.localizedName + " (" + e.tile.x + ", " + e.tile.y + ")");
            }catch(Throwable t){
                Log.err("[GL Admin] evidence", t);
            }
        });

        Events.on(PlayerChatEvent.class, e -> {
            if(!collecting() || e.player == null || e.message == null || e.message.startsWith("/")) return;
            add(e.player.id, e.player.name, Kind.chat, Strings.stripColors(e.message));
        });

        Events.on(WorldLoadEvent.class, e -> trim());
    }

    private static void add(int id, String name, Kind kind, String detail){
        events.add(new Evt(System.currentTimeMillis(), id, NameUtil.normalize(name), kind, detail));
        if(events.size > maxEvents) events.removeRange(0, events.size - maxEvents - 1);
        if(events.size % 200 == 0) trim();
    }

    private static void trim(){
        long cutoff = System.currentTimeMillis() - buffer;
        int drop = 0;
        while(drop < events.size && events.get(drop).time < cutoff) drop++;
        if(drop > 0) events.removeRange(0, drop - 1);
    }

    public static Fi file(){
        String path = Core.settings.getString("sam-evidence-path", "").trim();
        if(path.isEmpty()) return Core.files.local(defaultName);
        return path.contains(":") || path.startsWith("/") || path.startsWith("\\") ? new Fi(path) : Core.files.local(path);
    }

    /** Actions of the player (by entity id or a similar nick) in the last {@code span} milliseconds, oldest first. */
    public static Seq<Evt> gather(int playerId, String name, long span){
        long cutoff = System.currentTimeMillis() - span;
        String nick = NameUtil.normalize(name);
        return events.select(e -> e.time >= cutoff && ((playerId > 0 && e.playerId == playerId) || NameUtil.samePlayer(e.nick, nick)));
    }

    /** Text report: the player, the ban, then the builds, breaks and chat with the time of each line. */
    public static String format(Seq<Evt> list, String name, String uuid, String length, String reason, long now, String span){
        StringBuilder sb = new StringBuilder();
        String nick = NameUtil.normalize(name);
        sb.append(nick.isEmpty() ? Strings.stripColors(name) : nick).append('\n');
        if(uuid != null && !uuid.isEmpty()) sb.append("uuid: ").append(uuid).append('\n');
        if(reason != null && !reason.isEmpty()) sb.append(Core.bundle.get("sam.evidence.reason")).append(": ").append(Strings.stripColors(reason)).append('\n');
        if(span != null) sb.append(span).append('\n');
        section(sb, "sam.evidence.built", list, Kind.building);
        section(sb, "sam.evidence.broken", list, Kind.breaking);
        section(sb, "sam.evidence.chat", list, Kind.chat);
        sb.append(Core.bundle.get("sam.evidence.time")).append(": ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now)));
        if(length != null && !length.isEmpty()) sb.append(" (").append(length).append(')');
        return sb.append('\n').toString();
    }

    /** Appends the last minute of the player to the evidence file. */
    public static void writeOnBan(int playerId, String name, String uuid, String length, String reason){
        if(!enabled()) return;
        try{
            String text = format(gather(playerId, name, window), name, uuid, length, reason, System.currentTimeMillis(), null);
            Fi file = file();
            if(file.parent() != null && !file.parent().exists()) file.parent().mkdirs();
            file.writeString(text + "\n\n", true);
            ui.showInfoFade(Core.bundle.format("sam.evidence.written", file.name()));
        }catch(Throwable t){
            Log.err("[GL Admin] evidence write failed", t);
            ui.showInfoFade(Core.bundle.get("sam.evidence.failed"));
        }
    }

    private static void section(StringBuilder sb, String title, Seq<Evt> list, Kind kind){
        sb.append(Core.bundle.get(title)).append(":\n");
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        int count = 0;
        for(Evt e : list){
            if(e.kind != kind) continue;
            sb.append("  [").append(time.format(new Date(e.time))).append("] ").append(e.detail).append('\n');
            count++;
        }
        if(count == 0) sb.append("  ").append(Core.bundle.get("sam.evidence.none")).append('\n');
    }

    public enum Kind{building, breaking, chat}

    public static class Evt{
        public final long time;
        public final int playerId;
        public final String nick, detail;
        public final Kind kind;

        Evt(long time, int playerId, String nick, Kind kind, String detail){
            this.time = time;
            this.playerId = playerId;
            this.nick = nick;
            this.kind = kind;
            this.detail = detail == null ? "" : detail;
        }
    }
}
