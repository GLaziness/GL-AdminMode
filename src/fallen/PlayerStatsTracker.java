package fallen;

import arc.Events;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.*;
import static fallen.SimpleAdminMode.playerHistory;
import arc.Core;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.ConstructBlock;
import mindustry.ui.Fonts;

public class PlayerStatsTracker {
    public static void init() {
        Events.on(BlockBuildBeginEvent.class, e -> {
            try{
                if(Core.settings.getBool("sam-ag-build-warn", true) || e.breaking){
                    antiGrief(e);
                }
                if(e.unit == null || e.unit.getPlayer() == null || e.tile == null) return;
                PlayerData data = playerHistory.get(e.unit.getPlayer().id);
                if(data == null) return;

                // the block history is kept when either the history or the stats are on
                if(historyOn()){
                    short blockId;
                    if(e.tile.build instanceof ConstructBlock.ConstructBuild cons && cons.current != null){
                        blockId = cons.current.id;
                    }else{
                        blockId = e.tile.block().id;
                    }

                    int rotation = e.tile.build != null ? e.tile.build.rotation : 0;
                    Object config = e.tile.build != null ? e.tile.build.config() : null;

                    ActionsHistory.blocksplayersplans.addFirst(new ActionsHistory.BlockPlayerPlan(
                            e.tile.x, e.tile.y, (short) rotation,
                            blockId, config,
                            NameUtil.normalize(data.name), e.breaking, data.id
                    ));
                }

                if(e.breaking) data.breaks++;
                else data.builds++;
            }catch(Throwable t){
                Log.err("[GL Admin] build event", t);
            }
        });

        Events.on(ConfigEvent.class, e -> {
            if(!Core.settings.getBool("sam-show-stats", false) || e.player == null || e.tile == null) return;
            PlayerData data = playerHistory.get(e.player.id);
            if(data != null) {
                data.configs++;
                if(historyOn()){
                    ActionsHistory.blockconfplayersplans.addFirst(new ActionsHistory.BlockConfigPlayerPlan( (int)e.tile.x/8, (int)e.tile.y/8, e.tile.block.id, NameUtil.normalize(data.name), data.id));
                }
            }
        });

        Events.on(BuildRotateEvent.class, e -> {
            if(!Core.settings.getBool("sam-show-stats", false) || e.unit == null || e.unit.getPlayer() == null || e.build == null) return;
            PlayerData data = playerHistory.get(e.unit.getPlayer().id);
            if(data != null) {
                data.configs++;
                if(historyOn()){
                    ActionsHistory.blockconfplayersplans.addFirst(new ActionsHistory.BlockConfigPlayerPlan( (int)e.build.x/8, (int)e.build.y/8, e.build.block.id, NameUtil.normalize(data.name), data.id));
                }
            }
        });
    }

    private static boolean historyOn(){
        return Core.settings.getBool("sam-log-save", false) || Core.settings.getBool("sam-show-stats", false);
    }

    private static void antiGrief(BlockBuildBeginEvent e) {
        if (e.breaking || e.unit == null || e.unit.getPlayer() == null) return;
        if (!(e.tile.build instanceof ConstructBlock.ConstructBuild cons)) return;

        Block block = cons.current;
        String key = "";

        // 1. Определяем, на какой блок мы "наступили" и какой ключ настроек использовать
        if (block == Blocks.thoriumReactor) key = "thorium";
        else if (block == Blocks.incinerator) key = "incinerator";
        else if (block == Blocks.melter) key = "melter";

        // Если блок не в нашем списке — выходим
        if (key.isEmpty()) return;

        // 2. Проверяем, включен ли детектор именно для этого блока
        if (!Core.settings.getBool("sam-ag-" + key + "-enabled", true)) return;

        PlayerData data = playerHistory.get(e.unit.getPlayer().id);
        if (data == null || data.uuid.equals("Loading...")) return;

        // 3. Условия по кикам/входам (общие)
        int minJ = Core.settings.getInt("sam-ag-min-joins", 5);
        int maxK = Core.settings.getInt("sam-ag-max-kicks", 1);

        if (data.timesJoined < minJ && data.timesKicked >= maxK) {
            var cores = e.unit.team().cores();
            if (cores.isEmpty()) return;

            Building closestCore = cores.min(c -> c.dst(e.tile));

            // 4. Берем ИНДИВИДУАЛЬНЫЙ радиус для этого типа блока
            float radius = Core.settings.getInt("sam-ag-" + key + "-radius", 40);

            if (e.tile.dst(closestCore) < radius * Vars.tilesize) {
                Vars.player.sendMessage(Core.bundle.format("sam.ag.buildAlert",
                        e.unit.getPlayer().name, block.localizedName + " " + mindustry.ui.Fonts.getUnicodeStr(block.name) + "(" + Mathf.round(e.tile.getX()/8) + " , " + Mathf.round(e.tile.getY()/8) + ")"));
            }
        }
    }
}