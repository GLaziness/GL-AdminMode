package fallen;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.*;

import java.text.*;
import java.util.*;

import static fallen.SimpleAdminMode.playerHistory;
import static mindustry.Vars.*;

/**
 * Writes the whole chat and every block action of the players (build, break, configure, rotate) to one text file
 * per day, so there is a record to look at later. Works on the vanilla game too: it only uses the game's own
 * events and the chat window, nothing from GL Client.
 */
public final class SessionJournal{
    private static final StringBuilder pending = new StringBuilder();
    private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US), day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static java.lang.reflect.Field chatField;
    /** The chat lines already written (by identity: the same text can come again as a new message). */
    private static Set<Object> seenChat = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean loaded;

    private SessionJournal(){}

    public static boolean enabled(){
        return Core.settings.getBool("sam-journal", true);
    }

    public static Fi folder(){
        return Core.settings.getDataDirectory().child("gl-admin-journal");
    }

    public static void init(){
        if(loaded) return;
        loaded = true;

        Events.on(WorldLoadEvent.class, e -> {
            if(!enabled() || !net.client()) return;
            line(Core.bundle.format("sam.journal.world", state.map == null ? "?" : Strings.stripColors(state.map.name())));
            skipOldChat();
        });

        Events.on(BlockBuildBeginEvent.class, e -> {
            if(!enabled() || !net.client() || e.unit == null || e.unit.getPlayer() == null || e.tile == null) return;
            Block block = e.tile.build instanceof ConstructBlock.ConstructBuild cons && cons.current != null ? cons.current : e.tile.block();
            line(Core.bundle.format(e.breaking ? "sam.journal.break" : "sam.journal.build", who(e.unit.getPlayer()), block.localizedName, e.tile.x, e.tile.y));
        });

        Events.on(ConfigEvent.class, e -> {
            if(!enabled() || !net.client() || e.player == null || e.tile == null) return;
            line(Core.bundle.format("sam.journal.config", who(e.player), e.tile.block.localizedName, e.tile.tileX(), e.tile.tileY(), value(e.value)));
        });

        Events.on(BuildRotateEvent.class, e -> {
            if(!enabled() || !net.client() || e.unit == null || e.unit.getPlayer() == null || e.build == null) return;
            line(Core.bundle.format("sam.journal.rotate", who(e.unit.getPlayer()), e.build.block.localizedName, e.build.tileX(), e.build.tileY()));
        });

        Events.run(Trigger.update, () -> {
            if(enabled() && net.client() && state.isGame()) readChat();
        });

        // written in small portions, and whatever is left when the game closes
        arc.util.Timer.schedule(SessionJournal::flush, 3f, 3f);
        Events.on(DisposeEvent.class, e -> flush());
        Core.app.post(SessionJournal::cleanup);
    }

    /** The name without colors and, when known, the UUID: nicks can be changed, the UUID stays. */
    private static String who(Player p){
        String name = NameUtil.normalize(p.name);
        if(name.isEmpty()) name = Strings.stripColors(p.name);
        PlayerData data = playerHistory.get(p.id);
        return data != null && data.uuid != null && data.uuid.length() > 8 ? name + " [" + data.uuid + "]" : name;
    }

    private static String value(Object v){
        if(v == null) return "-";
        if(v instanceof UnlockableContent c) return c.localizedName;
        if(v instanceof byte[] b) return Core.bundle.format("sam.journal.bytes", b.length);
        String s = Strings.stripColors(String.valueOf(v)).replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    @SuppressWarnings("unchecked")
    private static Seq<Object> chat(){
        if(ui == null || ui.chatfrag == null) return null;
        try{
            if(chatField == null){
                chatField = mindustry.ui.fragments.ChatFragment.class.getDeclaredField("messages");
                chatField.setAccessible(true);
            }
            return chatField.get(ui.chatfrag) instanceof Seq<?> s ? (Seq<Object>)s : null;
        }catch(Throwable t){
            return null;
        }
    }

    /** The chat of the previous server is already written: only what comes after joining counts. */
    private static void skipOldChat(){
        remember(chat());
    }

    private static void remember(Seq<Object> msgs){
        seenChat.clear();
        if(msgs != null) for(int i = 0; i < Math.min(msgs.size, 100); i++) seenChat.add(msgs.get(i));
    }

    /** New messages are put at the start of the list: everything before the first one already seen is new. */
    private static void readChat(){
        Seq<Object> msgs = chat();
        if(msgs == null || msgs.isEmpty() || seenChat.contains(msgs.first())) return;
        int end = 0;
        while(end < msgs.size && end < 100 && !seenChat.contains(msgs.get(end))) end++;
        for(int i = end - 1; i >= 0; i--){
            String text = text(msgs.get(i));
            if(text != null && !text.isEmpty() && !SimpleAdminMode.isCannotTrace(text)) line(Core.bundle.format("sam.journal.chat", text));
        }
        remember(msgs);
    }

    private static String text(Object m){
        if(m instanceof String s) return Strings.stripColors(s);
        try{
            return m == null ? null : Strings.stripColors(String.valueOf(m.getClass().getField("message").get(m)));
        }catch(Throwable t){
            return Strings.stripColors(String.valueOf(m));
        }
    }

    private static void line(String text){
        synchronized(pending){
            pending.append('[').append(time.format(new Date())).append("] ").append(text.replace('\n', ' ')).append('\n');
        }
    }

    public static synchronized void flush(){
        String text;
        synchronized(pending){
            if(pending.length() == 0) return;
            text = pending.toString();
            pending.setLength(0);
        }
        try{
            Fi file = folder().child(day.format(new Date()) + ".txt");
            file.parent().mkdirs();
            file.writeString(text, true);
        }catch(Throwable t){
            Log.err("[GL Admin] journal write failed", t);
        }
    }

    /** Removes the days older than the setting. */
    private static void cleanup(){
        try{
            long cutoff = System.currentTimeMillis() - Core.settings.getInt("sam-journal-days", 14) * 24L * 60 * 60 * 1000;
            for(Fi f : folder().list()){
                if(f.extEquals("txt") && f.lastModified() < cutoff) f.delete();
            }
        }catch(Throwable t){
            Log.err("[GL Admin] journal cleanup", t);
        }
    }
}
