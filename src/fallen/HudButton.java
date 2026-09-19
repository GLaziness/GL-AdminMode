package fallen;

import arc.*;
import arc.graphics.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * GL: the admin panel on the HUD. It sits on the left edge right under the panels already there
 * (the vanilla wave info, the GL Client side panel, panels of other mods) and follows them when they change size.
 */
public class HudButton{
    /** Clients that attach their own panels under the left panels skip elements with this name prefix. */
    public static final String name = "gl-admin-hud";

    private static Table panel;
    private static int builtSize = -1;

    public static void build(SimpleAdminList list){
        ui.hudGroup.fill(full -> {
            full.name = name;
            full.top().left();
            full.visible(() -> ui.hudfrag.shown && state.isGame());

            Cell<Table> cell = full.table(Tex.buttonEdge4, p -> panel = p).left();
            rebuild(list);

            Interval timer = new Interval();
            float[] lastPad = {-1f};
            full.update(() -> {
                int size = Core.settings.getInt("sam-btn-size", 40);
                if(size != builtSize) rebuild(list);

                if(!timer.get(20f)) return;
                float pad = Math.max((Core.scene.getHeight() - panelsBottom(full)) / Scl.scl(1f) + Core.settings.getInt("sam-hud-offset", 0), 0f);
                if(Math.abs(pad - lastPad[0]) > 0.5f){
                    lastPad[0] = pad;
                    cell.padTop(pad);
                    full.invalidate();
                }
            });
        });
    }

    private static void rebuild(SimpleAdminList list){
        builtSize = Core.settings.getInt("sam-btn-size", 40);
        float size = builtSize, icon = size * 0.62f;

        panel.clear();
        panel.margin(4f, 4f, 6f, 8f);
        panel.defaults().pad(1f);

        // players list: click toggles it, holding it sends /history
        ImageButton admin = panel.button(Icon.admin, Styles.clearNoneTogglei, icon, () -> {}).size(size).get();
        float[] hold = {0f};
        boolean[] held = {false};
        admin.update(() -> {
            admin.setChecked(list.shown());
            if(admin.isPressed()){
                hold[0] += Time.delta;
                if(hold[0] > 60f && !held[0]){
                    held[0] = true;
                    Call.sendChatMessage("/history");
                }
            }else{
                hold[0] = 0f;
            }
        });
        admin.clicked(() -> {
            if(!held[0]) list.toggle();
            held[0] = false;
        });
        tooltip(admin, "sam.hud.list");

        panel.table(info -> {
            info.left().defaults().left();
            info.add("GL Admin", Styles.outlineLabel).color(Pal.accent).fontScale(0.75f).row();
            info.label(() -> Core.bundle.format("sam.hud.players", Groups.player.size())).style(Styles.outlineLabel).color(Color.lightGray).fontScale(0.7f);
        }).height(size).padLeft(6f).padRight(6f);

        tooltip(panel.button(Icon.cancel, Styles.clearNonei, icon, () -> Call.sendChatMessage("/vote c")).size(size).get(), "sam.hud.voteCancel");
        tooltip(panel.button(Icon.settings, Styles.clearNonei, icon, () -> new SimpleAdminSettings().show()).size(size).get(), "sam.hud.settings");

        if(Vars.mobile && Core.settings.getBool("sam-freecam", false)){
            panel.button(Icon.move, Styles.clearNoneTogglei, icon, () -> {
                if(Vars.control.input instanceof FreeCamMobileInput fi) fi.setFreeCam(!fi.isFreeCam());
            }).update(b -> {
                boolean active = Vars.control.input instanceof FreeCamMobileInput fi && fi.isFreeCam();
                b.setChecked(active);
                b.getImage().setColor(active ? Color.cyan : Color.white);
            }).size(size);
        }
    }

    private static void tooltip(Element e, String key){
        if(!Vars.mobile) e.addListener(new Tooltip(t -> t.background(Styles.black8).margin(4f).add(Core.bundle.get(key))));
    }

    /** Lowest bottom edge (scene coordinates) of the panels stacked along the left edge of the HUD. */
    private static float panelsBottom(Element self){
        float[] bottom = {Core.scene.getHeight()};
        collect(ui.hudGroup, self, bottom);
        return bottom[0];
    }

    private static void collect(Group group, Element self, float[] bottom){
        for(Element e : group.getChildren()){
            if(e == self || !e.visible || e.getWidth() <= 0f || e.getHeight() <= 0f) continue;
            if("playerlist".equals(e.name)) continue; // our own players list

            if(e instanceof Table table && table.getBackground() != null){
                Vec2 pos = e.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                float top = pos.y + e.getHeight();
                // only tables covering the screen both ways are skipped: in a narrow window the panels are most of its width
                boolean small = e.getWidth() < Core.scene.getWidth() * 0.8f || e.getHeight() < Core.scene.getHeight() * 0.8f;
                if(pos.x <= Scl.scl(10f) && small && top >= Core.scene.getHeight() / 2f){
                    bottom[0] = Math.min(bottom[0], pos.y);
                }
            }

            if(e instanceof Group g) collect(g, self, bottom);
        }
    }
}
