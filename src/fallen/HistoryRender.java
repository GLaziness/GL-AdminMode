package fallen;
import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.util.*;
import mindustry.game.EventType;
import mindustry.game.EventType.*;
import mindustry.graphics.*;

import mindustry.world.Block;

import static mindustry.Vars.*;


public class HistoryRender {
    public static String targetNick = null;
    /** Player entity id of the target (-1: match by the nick only). Survives the freeze icon in the nick. */
    public static int targetPlayerId = -1;
    private static float brokenFade = 0f;
    static float alphaMult = 0.5f;
    private static float nickStartTime = 0f;
    private static float timerAlpha = 1f;

    public static void init() {
        alphaMult = Core.settings.getInt("fadedblockallplayers", 5) / 10f;

        Events.run(EventType.Trigger.draw, () -> {
            if (!state.isGame()) return;
            try{
                drawActionHistory();
            }catch(Throwable ignored){
                // never break the draw loop
            }
        });
    }

    public static void clear(){
        targetNick = null;
        targetPlayerId = -1;
    }

    public static void setTarget(PlayerData data){
        if(data == null) clear();
        else setTarget(data.name, data.id);
    }

    public static void setTarget(String nick, int playerId) {
        String norm = NameUtil.normalize(nick);
        boolean same = playerId > 0 ? playerId == targetPlayerId : norm.equals(targetNick);
        if (same && (targetNick != null || targetPlayerId > 0)) {clear(); return;}
        targetNick = norm;
        targetPlayerId = playerId;
        nickStartTime = Time.globalTime;
        timerAlpha = 1f;
        ui.hudfrag.showToast("[#ffaa55]" + Core.bundle.get("sam.history.show") + "\n[white]" + norm);
    }

    private static void drawActionHistory() {
        brokenFade = Mathf.lerpDelta(brokenFade, 1f, 0.1f);
        if (targetNick == null && targetPlayerId < 0) return;

        float elapsed = (Time.globalTime - nickStartTime) / 60f;
        if (elapsed > 10f) {
            clear();
            timerAlpha = 0f;
            return;
        } else if (elapsed > 7f) {
            // Плавное затухание: с 7 по 10 сек
            timerAlpha = (10f - elapsed) / 3f;
        } else {
            timerAlpha = 1f;
        }

        drawBlocks();
        drawConfigs();
    }

    private static boolean matches(int planPlayerId, String planNick){
        if(targetPlayerId > 0 && planPlayerId > 0 && planPlayerId == targetPlayerId) return true;
        if(targetNick == null || targetNick.isEmpty()) return false;
        return NameUtil.samePlayer(planNick, targetNick);
    }

    private static void drawBlocks() {

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (!matches(plan.playerId, plan.lastacs)) continue;

            Block b = content.block(plan.block);
            if (b == null) continue;

            float px = plan.x * tilesize + b.offset;
            float py = plan.y * tilesize + b.offset;
            if (!Core.camera.bounds(Tmp.r1).grow(tilesize * 2f).contains(px, py)) continue;

            Draw.z(Layer.overlayUI);
            Draw.alpha(0.5f * brokenFade * alphaMult * timerAlpha);

            Color mix = plan.wasbreaking ? Color.red : Color.green;
            Draw.mixcol(mix, 0.4f + Mathf.absin(Time.globalTime, 6f, 0.2f));

            Draw.rect(b.fullIcon, px, py, b.rotate ? plan.rotation * 90 : 0);
            Draw.reset();
        }
    }

    private static void drawConfigs() {

        for (ActionsHistory.BlockConfigPlayerPlan plan : ActionsHistory.blockconfplayersplans) {
            if (!matches(plan.playerId, plan.lastacs)) continue;

            Block b = content.block(plan.block);
            if (b == null) continue;

            float px = plan.x * tilesize + b.offset;
            float py = plan.y * tilesize + b.offset;

            if (!Core.camera.bounds(Tmp.r1).grow(tilesize * 2f).contains(px, py)) continue;

            Draw.z(Layer.overlayUI);
            Draw.alpha(0.45f * brokenFade * alphaMult * timerAlpha);

            Draw.mixcol(Color.blue, 0.5f + Mathf.absin(Time.globalTime, 6f, 0.2f));

            Draw.rect(b.fullIcon, px, py);
            Draw.reset();
        }
    }
}
