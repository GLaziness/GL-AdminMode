package fallen;

import arc.Core;
import arc.Events;
import arc.struct.*;
import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.logic.LogicBlock;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Pattern;

import static fallen.SimpleAdminMode.playerHistory;

public class AntiAttemPatcher {

    private static final Pattern ATTEM_PATTERN = Pattern.compile(
            "(ubind @?[^ ]+)\\s+" +                // bind a unit
                    "sensor (\\S+) @unit @flag\\s+" +      // set _flag to unit flag
                    "op add (\\S+) \\3 1\\s+" +            // increment _attem by 1
                    "jump \\d+ greaterThanEq \\3 \\d+\\s+" + // break if _attem >= 83
                    "jump \\d+ (?:notEqual|always) ([^ ]+) \\2\\s+" + // loop if _flag != 0 (or always in some variants)
                    "set \\3 0",                           // _attem = 0
            Pattern.MULTILINE
    );

    // Сообщение в процессор
    private static final String WARNING_CODE =
            "print \"Stop build this, or you will get BANNED\"\n" +
                    "print \"https://mindustry.dev/attem\"\n";


    private static final float CONFIG_DELAY_SEC = 0.25f; // 250 мс задержки очереди
    private static final Queue<LogicBlock.LogicBuild> patchQueue = new ArrayDeque<>();
    private static boolean processingQueue = false;
    private static boolean loaded = false;

    /** Wait before the automatic freeze, extra wait per admin rank, and no second freeze of one uuid for this long (seconds). */
    private static final float freezeDelay = 1f, freezeStagger = 0.6f, freezeCooldown = 45f;
    private static final ObjectFloatMap<String> freezeHandledAt = new ObjectFloatMap<>();
    private static final ObjectSet<String> freezePending = new ObjectSet<>();


    public static void load() {
        if (loaded) return;
        loaded = true;

        // Скан при старте мира
        Events.on(EventType.WorldLoadEvent.class, e -> {
            freezeHandledAt.clear();
            freezePending.clear();
            if (Core.settings.getBool("sam-aa", true)) {
                Timer.schedule(() -> {
                    if (Vars.net.client() && Vars.player != null && Vars.player.unit() != null) {
                        scanExistingProcessors();
                    }
                }, 5f);//ЗАдержка для прогрузки
            }
        });

        // Скан при постройке процессоров
        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if (Core.settings.getBool("sam-aa", true)) {
                checkNewProcessor(event);
            }
        });

        // Скан при изменении процессоров
        Events.on(EventType.ConfigEvent.class, event -> {
            if (Core.settings.getBool("sam-aa", true)) {
                checkConfProcessor(event);
            }
        });
    }

    // Проверка построенного процессора
    private static void checkNewProcessor(EventType.BlockBuildEndEvent event) {
        if (event.breaking || event.tile == null || event.tile.build == null) return;
        if (!Vars.net.client()) return;
        PlayerData playerData;
            if(event.unit == null || event.unit.getPlayer() == null) {
                Player fake = Player.create();
                fake.name = "[gray]<?>Unknown[]";
                playerData  = new PlayerData(fake);
            }
            else {
                playerData  = playerHistory.get(event.unit.getPlayer().id);
            }
        Building build = event.tile.build;
        if (build instanceof LogicBlock.LogicBuild processor && build.team == Vars.player.team()) {
            if (containsBadCode(processor.code, playerData, processor)) {
                queuePatch(processor);
            }
        }
    }

    // Проверка изменённого процессора
    private static void checkConfProcessor(EventType.ConfigEvent event) {
        if (!Vars.net.client()  || Vars.player == null || event.tile == null) return;
        PlayerData playerData = null;
        if(event.player == null) {
            Player fake = Player.create();
            fake.name = "[gray]<?>Unknown[]";
            playerData  = new PlayerData(fake);
        }
        else {
            playerData  = playerHistory.get(event.player.id);
        }
        Building build = event.tile;
        if (build instanceof LogicBlock.LogicBuild processor && build.team == Vars.player.team()) {
            if (containsBadCode(processor.code, playerData, processor)) {
                queuePatch(processor);
            }
        }
    }

    // Проверка всех существующих процессоров
    private static void scanExistingProcessors() {
        if (Vars.player == null || Vars.player.team() == null) return;

        var builds = Vars.player.team().data().buildings;
        int patched = 0;

        for (Building build : builds) {
            if (build instanceof LogicBlock.LogicBuild processor && build.team == Vars.player.team()) {
                if (containsBadCode(processor.code, null, processor)) {
                    queuePatch(processor);
                    patched++;
                }
            }
        }
        if (patched > 0) {
            Vars.player.sendMessage(Core.bundle.format("sam.aa.patch-count", patched));
        }  //else Vars.player.sendMessage("AntiAttemPatcher: идиотов не обнаружено.");

    }

    // Добавление процессора в очередь на исправление
    private static void queuePatch(LogicBlock.LogicBuild processor) {
        if (!patchQueue.contains(processor)) {
            patchQueue.add(processor);
            processQueue();
        }
    }

    // Обработка очереди с задержками между запросами
    private static void processQueue() {
        if (patchQueue.isEmpty() || processingQueue) return;
        processingQueue = true;

        LogicBlock.LogicBuild processor = patchQueue.poll();
        patchProcessorImmediate(processor);


        Timer.schedule(() -> {
            processingQueue = false;
            processQueue();
        }, CONFIG_DELAY_SEC);
    }

    // Непосредственная замена кода и отправка на сервер
    private static void patchProcessorImmediate(LogicBlock.LogicBuild processor) {
        if (processor == null) return;

        //Генерируем сжатый массив байтов (код + линки процессора)
        byte[] compressedCode = LogicBlock.compress(WARNING_CODE, processor.relativeConnections());
        processor.updateCode(WARNING_CODE);
        Call.tileConfig(Vars.player, processor, compressedCode);

        Vars.player.sendMessage(Core.bundle.format("sam.aa.patch-cords", processor.tileX(), processor.tileY()));

    }

    // Проверка: содержит ли код один из запрещённых паттернов
    private static boolean containsBadCode(String code, PlayerData playerData, LogicBlock.LogicBuild processor) {
        if (code == null || code.isEmpty()) return false;
        boolean isBad = false;
        String dataName = (playerData != null) ? playerData.name : "[gray]<?>Unknown[]";
        String dataUuid = (playerData != null) ? playerData.uuid : null;

        if (ATTEM_PATTERN.matcher(code).find()) {
            Log.info("AntiAttemPatcher: Обнаружена Regex-сигнатура Attem83!");
            isBad = true;
        } else {
            for (String pattern : CONFIRMED_ATTEM) {
                if (code.contains(pattern)) {
                    Log.info("AntiAttemPatcher: Обнаружено совпадение по списку запрещенного кода.");
                    isBad = true;
                    break;
                }
            }
        }
        if (isBad) {

            if (dataUuid != null && !dataUuid.isEmpty() && !dataUuid.equals("Loading...") && !dataUuid.equals("none") && !dataUuid.equals("admin?")) {
                if(Core.settings.getBool("sam-aab", false)){
                    if(Core.settings.getBool("sam-oaa", false)) {
                        Call.sendChatMessage(Core.bundle.format("sam.aa.ban-mes", dataName, dataUuid, processor.tileX(), processor.tileY()));
                    } else {
                        Vars.player.sendMessage(Core.bundle.format("sam.aa.ban-mes", dataName, dataUuid, processor.tileX(), processor.tileY()));
                    }
                    Call.sendChatMessage("/ban " + dataUuid + " 1d here 5.2.3 Автоматический бан. https://mindustry.dev/attem" );
                    BanKickMessages.ban(dataName, "1d");
                } else {
                    scheduleSafeFreeze(dataName, dataUuid, processor.tileX(), processor.tileY(), playerData);
                }
            }
            return true;
        }
        return false;
    }
    /**
     * Freezes once, after a short wait staggered by the admin rank, so several admins with the mod
     * do not toggle the freeze of one player on and off. Skips players that are already frozen. From SimpleAdminMode2.
     */
    private static void scheduleSafeFreeze(String name, String uuid, int x, int y, PlayerData data){
        if(freezePending.contains(uuid) || recentlyHandled(uuid)) return;
        if(isFrozen(findPlayer(uuid, data))){
            freezeHandledAt.put(uuid, Time.time);
            Vars.player.sendMessage(Core.bundle.format("sam.aa.already-frozen", name));
            return;
        }

        freezePending.add(uuid);
        Timer.schedule(() -> {
            freezePending.remove(uuid);
            if(recentlyHandled(uuid)) return;
            freezeHandledAt.put(uuid, Time.time);
            if(isFrozen(findPlayer(uuid, data))){
                Vars.player.sendMessage(Core.bundle.format("sam.aa.already-frozen", name));
                return;
            }
            Call.sendChatMessage(Core.bundle.format("sam.aa.freeze-mes", name, uuid, x, y));
            // "true" only freezes and never unfreezes, unlike the plain /freeze toggle
            Call.sendChatMessage("/freeze " + uuid + " true");
            if(data != null) data.autoFrozen = true;
        }, freezeDelay + adminRank() * freezeStagger);
    }

    private static boolean recentlyHandled(String uuid){
        return Time.time - freezeHandledAt.get(uuid, -999999f) < freezeCooldown * 60f;
    }

    /** Rank among the online admins by id, 0 for the lowest: used only to stagger the freezes. */
    private static int adminRank(){
        int rank = 0;
        for(Player p : Groups.player){
            if(p.admin && p.id < Vars.player.id) rank++;
        }
        return rank;
    }

    private static Player findPlayer(String uuid, PlayerData data){
        if(data != null && data.player != null && data.online) return data.player;
        if(data != null && Groups.player.getByID(data.id) != null) return Groups.player.getByID(data.id);
        for(PlayerData pd : playerHistory.values()){
            if(uuid.equals(pd.uuid) && pd.player != null && pd.online) return pd.player;
        }
        return null;
    }

    /** Frozen players get a "block" unit or the freezing / unmoving status from the freeze plugins. */
    public static boolean isFrozen(Player p){
        if(p == null) return false;
        PlayerData pd = playerHistory.get(p.id);
        if(pd != null && pd.autoFrozen) return true;
        Unit u = p.unit();
        if(u == null) return false;
        return u.type == UnitTypes.block || u.hasEffect(StatusEffects.freezing) || u.hasEffect(StatusEffects.unmoving);
    }

    // Паттерны "плохого" кода(аттемы но не аттемы)
    private static final String[] CONFIRMED_ATTEM = new String[] {
            "read index cell1 1\n" +
                    "jump 43 greaterThan i 0\n" +
                    "ulocate building core false @copper outx outy found core\n" +
                    "ucontrol move outx outy 0 0 0\n" +
                    "ucontrol itemTake core itemNeed itemCap 0 0\n" +
                    "write outx cell1 5\n" +
                    "write outy cell1 6\n" +
                    "end\n" +
                    "jump 51 equal item @lead\n" +
                    "jump 51 equal item @coal\n" +
                    "jump 51 equal item @lead\n" +
                    "jump 51 equal item @graphite\n" +
                    "jump 51 equal item @metaglass\n" +
                    "jump 53 equal item @phase-fabric\n" +
                    "jump 53 equal item @surge-alloy\n" +
                    "jump 53 greaterThan index 7\n" +
                    "set container1 vault1",
            "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 125 greaterThan plast5 600\n" +
                    "sensor cPlast5 core @plastanium\n" +
                    "jump 125 equal cPlast5 0\n" +
                    "control config sorter1 @plastanium 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 131 greaterThan surge5 500\n" +
                    "sensor cSurge5 core @surge-alloy\n" +
                    "jump 131 equal cSurge5 0\n" +
                    "control config sorter1 @surge-alloy 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 137 greaterThan phase5 350\n" +
                    "sensor cPhase core @phase-fabric\n" +
                    "jump 137 equal cPhase 0\n" +
                    "control config sorter1 @phase-fabric 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 143 greaterThan titanium5 100\n" +
                    "sensor cTitanium core @titanium\n" +
                    "jump 143 equal cTitanium 0\n" +
                    "control config sorter1 @titanium 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "control config sorter1 null 0 0 0\n" +
                    "end\n" +
                    "control config sorter1 @titanium 0 0 0\n",
            "read max cell1 4\n" +
                    "jump 25 notEqual max 0\n" +
                    "print \"SET UNIT CAP HERE\"\n" +
                    "set max 32\n" +
                    "op mul fx @thisx -10000\n" +
                    "op add flag @thisy fx\n" +
                    "op ceil flag flag fx\n" +
                    "write flag cell1 0\n" +
                    "ubind UnitType\n" +
                    "jump 33 notEqual first null\n" +
                    "set first @unit\n" +
                    "jump 34 always first @unit\n" +
                    "jump 46 strictEqual first @unit\n" +
                    "op add i i 1\n" +
                    "sensor f @unit @flag",
            "jump 72 notEqual min-item null\n" +
                    "set Wait 1\n" +
                    "jump 0 always min-item null\n" +
                    "control config sorter1 min-item 0 0 0\n" +
                    "set Wait 0\n" +
                    "write index cell1 1\n" +
                    "write total cell1 7\n" +
                    "op div fullness total 14000\n" +
                    "write fullness cell1 8\n" +
                    "write c cell1 10\n" +
                    "write min cell1 11\n" +
                    "jump 0 always 0 false",
            "read amount1 cell1 index1\n" +
                    "read amount1 cell1 index1\n" +
                    "op add amount1 amount1 amount\n" +
                    "op add amount2 amount2 amount\n" +
                    "write amount1 cell1 index1\n" +
                    "write amount2 cell1 index2\n" +
                    "jump 17 always f 0\n" +
                    "set i 0\n" +
                    "jump 73 greaterThanEq j 13\n" +
                    "write 0 cell1 j\n" +
                    "op add j j 1\n" +
                    "jump 69 always j 13\n" +
                    "set j 0\n" +
                    "set first null\n" +
                    "end"};
}