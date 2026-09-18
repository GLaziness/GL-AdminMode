package fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;


import static fallen.SimpleAdminMode.playerHistory;
import static mindustry.Vars.*;

public class SimpleAdminList{
    public Table content = new Table().marginRight(13f).marginLeft(13f);
    private Table mainTable;
    private Table infoPanel;
    private boolean visible = false;
    private Interval timer = new Interval();
    private TextField search;
    private String manualUuid = "";
    private float button_size = 40f;
    private float panelWidth = 400f;

    public void build(Group parent){
        content.name = "players";
        parent.fill(cont -> {
            cont.name = "playerlist";
            cont.visible(() -> visible);
            cont.touchable = Touchable.enabled;
            cont.clicked(() -> {
                if(Core.settings.getBool("sam-close-listoutside", false)) this.toggle();
            });
            cont.update(() -> {
                if(!(net.active() && state.isGame())){
                    visible = false;
                    return;
                }
                if(visible && timer.get(180)){
                    rebuild();
                    content.pack();
                    content.act(Core.graphics.getDeltaTime());
                    Core.app.post(() -> Core.scene.act(Math.min(Core.graphics.getDeltaTime(), 0.033f)));
                }
            });

            // GL: laid out like the GL Client windows: accent title and line, dark cards, compact icon buttons
            mainTable = cont.table(Styles.black8, pane -> {
                pane.touchable = Touchable.enabled;
                pane.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        event.stop();
                        return true;
                    }
                });
                pane.margin(10f);

                pane.table(head -> {
                    head.left();
                    head.image(Icon.players).color(Pal.accent).size(24f).padRight(8f);
                    head.label(() -> Core.bundle.format("sam.list.title", Groups.player.size(), playerHistory.size)).color(Pal.accent).growX().left();
                    head.button(Icon.settings, Styles.clearNonei, () -> new SimpleAdminSettings().show()).size(36f).tooltip(Core.bundle.get("sam.hud.settings"));
                    head.button(Icon.cancel, Styles.clearNonei, this::toggle).size(36f).tooltip(Core.bundle.get("close"));
                }).growX().row();
                pane.image().color(Pal.accent).height(3f).growX().padTop(4f).padBottom(6f).row();

                pane.table(s -> {
                    s.image(Icon.zoom).color(Color.lightGray).size(20f).padRight(6f);
                    search = s.field(null, text -> rebuild()).growX().name("search").maxTextLength(maxNameLength).get();
                    search.setMessageText(Core.bundle.get("players.search"));
                }).growX().padBottom(6f).row();

                pane.pane(content).grow().scrollX(false).row();

                // manual UUID
                pane.table(Styles.grayPanel, manual -> {
                    manual.margin(4f);
                    TextField field = manual.field("", text -> manualUuid = text).growX().height(40f).get();
                    field.setMessageText(Core.bundle.get("sam.list.manualUUID"));
                    manual.button(Icon.waves, Styles.clearNonei, () -> {
                        if(manualUuid.isEmpty()){
                            ui.showInfoFade(Core.bundle.get("sam.info.smallUUID"));
                            return;
                        }
                        Call.sendChatMessage("/freeze " + manualUuid);
                    }).size(40f).padLeft(4f).tooltip(Core.bundle.get("sam.list.freeze")).get().getImage().setColor(Color.sky);
                    manual.button(Icon.hammer, Styles.clearNonei, () -> {
                        if(manualUuid.isEmpty()){
                            ui.showInfoFade(Core.bundle.get("sam.info.smallUUID"));
                            return;
                        }
                        Player fake = Player.create();
                        fake.name = "[gray]Manual Entry[]";
                        new AdvancedBanDialog(fake, manualUuid).show();
                    }).size(40f).tooltip(Core.bundle.get("sam.list.ban")).get().getImage().setColor(Color.scarlet);
                }).growX().padTop(8f);
            }).touchable(Touchable.enabled).minWidth(panelWidth).get();
        });

        rebuild();
    }

    private static boolean hasUuid(PlayerData user){
        return !user.uuid.equals("Loading...") && !user.uuid.equals("none");
    }

    public void rebuild(){
        this.button_size = Core.settings.getInt("sam-btn-size", 40);
        this.panelWidth = Core.settings.getInt("sam-list-w", 400);
        if(mainTable != null){
            mainTable.setWidth(panelWidth);
            mainTable.invalidate();
        }
        float cardWidth = panelWidth - 30f;
        float bs = Math.max(button_size * 0.8f, 26f);

        Seq<PlayerData> filtered = Seq.with(playerHistory.values());
        if(search.getText().length() > 0){
            String query = search.getText().toLowerCase();
            filtered.retainAll(d -> Strings.stripColors(d.name).toLowerCase().contains(query));
        }
        filtered.sort((a, b) -> {
            if(a.online != b.online) return a.online ? -1 : 1; // online first
            boolean aAdmin = a.player != null && a.player.admin, bAdmin = b.player != null && b.player.admin;
            if(aAdmin != bAdmin) return aAdmin ? -1 : 1;       // admins next
            return Strings.stripColors(a.name).compareToIgnoreCase(Strings.stripColors(b.name));
        });

        content.clear();
        content.top();
        boolean lastWasOnline = true;

        for(PlayerData user : filtered){
            if(lastWasOnline && !user.online){
                content.table(d -> {
                    d.image().color(Pal.redLight).height(2f).growX();
                    d.add(Core.bundle.get("sam.list.offline")).color(Pal.redLight).padLeft(8f).padRight(8f);
                    d.image().color(Pal.redLight).height(2f).growX();
                }).width(cardWidth).padTop(6f).padBottom(4f).row();
            }
            lastWasOnline = user.online;

            Table card = new Table(Styles.grayPanel);
            card.left().margin(6f);
            card.touchable = Touchable.enabled;
            card.clicked(() -> {});

            // unit icon: spectate the player
            Unit unit = user.player == null ? null : user.player.unit();
            if(unit != null && unit.type != null){
                card.button(b -> b.image(unit.icon()).size(26f).scaling(Scaling.fit), Styles.cleart, () -> control.input.spectate(user.player.unit()))
                    .size(40f).padRight(6f);
            }else{
                card.button(Icon.cancelSmall, Styles.clearNonei, () -> {
                    if(user.player != null) Core.camera.position.set(user.player.x, user.player.y);
                }).size(40f).padRight(6f).get().getImage().setColor(Color.gray);
            }

            // name and a short line with UUID, language and block stats
            card.table(text -> {
                text.left().defaults().left();
                text.table(n -> {
                    n.left();
                    if(user.player != null && user.player.admin) n.image(Icon.adminSmall).color(Pal.accent).size(16f).padRight(4f);
                    Label name = n.add(user.name).minWidth(0f).growX().left().get();
                    name.setEllipsis(true);
                }).growX().row();

                String uuidText = user.uuid.equals("admin?") ? "[green]admin" :
                    user.uuid.equals("Loading...") ? "[gray]..." :
                    user.uuid.equals("none") ? "[gray]none" : "[lightgray]" + user.uuid;
                text.table(line -> {
                    line.left();
                    Label uuid = line.add("[gray]UUID []" + uuidText + (Core.settings.getBool("sam-fastlang", false) ? "  [gray]" + user.locale : ""))
                        .minWidth(0f).get();
                    uuid.setFontScale(0.8f);
                    uuid.setEllipsis(true);
                    if(Core.settings.getBool("sam-show-stats", false)){
                        line.button(st -> {
                            Label l = st.add(new Label(() -> "[green]+" + user.builds + " [red]-" + user.breaks + " [sky]~" + user.configs)).get();
                            l.setFontScale(0.8f);
                        }, Styles.cleart, () -> HistoryRender.setTarget(user.name)).height(22f).padLeft(8f);
                    }
                }).growX();
            }).minWidth(0f).growX();

            // actions
            card.table(a -> {
                a.right().defaults().size(bs);
                a.button(Icon.info, Styles.clearNonei, () -> {
                    showInfoPanel(user);
                    if(Core.settings.getBool("sam-close-list")) this.toggle();
                }).tooltip(Core.bundle.get("sam.list.showSaveData"));
                if(user.online){
                    a.button(Icon.menu, Styles.clearNonei, () -> showPlayerMenu(user)).tooltip(Core.bundle.get("sam.list.admActions"));
                    a.button(Icon.waves, Styles.clearNonei, () -> {
                        if(hasUuid(user)) Call.sendChatMessage("/freeze " + user.uuid);
                        else ui.showInfoFade(Core.bundle.get("sam.list.noUuid"));
                    }).tooltip(Core.bundle.get("sam.list.freeze")).get().getImage().setColor(Color.sky);
                }
                a.button(Icon.hammer, Styles.clearNonei, () -> {
                    if(hasUuid(user)){
                        Player p = Groups.player.getByID(user.id);
                        if(p == null) p = Player.create();
                        p.name = user.name;
                        new AdvancedBanDialog(p, user.uuid).show();
                    }else{
                        ui.showInfoFade(Core.bundle.get("sam.list.noUuid"));
                    }
                }).tooltip(Core.bundle.get("sam.list.ban")).get().getImage().setColor(Color.scarlet);
            }).padLeft(4f);

            content.add(card).width(cardWidth).padBottom(4f).row();
        }

        if(filtered.isEmpty()){
            content.add(Core.bundle.format("players.notfound")).color(Color.lightGray).pad(10f);
        }
    }

    private void showPlayerMenu(PlayerData user) {
        var dialog = new BaseDialog(user.name);
        dialog.title.setColor(Color.white);
        dialog.titleTable.remove();
        dialog.closeOnBack();

        var bstyle = Styles.defaultt;

        dialog.cont.add(user.name).left().row();
        dialog.cont.image(Tex.whiteui, Pal.accent).fillX().height(3f).pad(4f).row();

        dialog.cont.pane(t -> {
            t.defaults().size(220f, 55f).pad(3f);

            t.button("@player.ban", Icon.hammer, bstyle, () -> {
                ui.showConfirm("@confirm", Core.bundle.format("confirmban", user.name),
                        () -> Call.adminRequest(user.player, AdminAction.ban, null));
                dialog.hide();
            }).row();

            t.button("@player.kick", Icon.cancel, bstyle, () -> {
                ui.showConfirm("@confirm", Core.bundle.format("confirmkick", user.name),
                        () -> Call.adminRequest(user.player, AdminAction.kick, null));
                dialog.hide();
            }).row();

            t.button("@player.trace", Icon.zoom, bstyle, () -> {
                Call.adminRequest(user.player, AdminAction.trace, null);
                dialog.hide();
            }).row();

        }).row();

        dialog.cont.button("@back", Icon.left, dialog::hide).padTop(-1f).size(220f, 55f);
        dialog.show();
    }

    public boolean shown(){
        return visible;
    }

    public void toggle(){
        visible = !visible;
        if(visible){
            rebuild();
        }else{
            Core.scene.setKeyboardFocus(null);
            search.clearText();
        }
    }


    private void showInfoPanel(PlayerData data) {
        if(infoPanel != null) infoPanel.remove();

        infoPanel = new Table();
        infoPanel.setFillParent(true);
        infoPanel.touchable = Touchable.enabled;
        infoPanel.clicked(() -> {
            if(Core.settings.getBool("sam-close-outside", true)){
                infoPanel.remove();
                infoPanel = null;
            }
        });

        infoPanel.table(Tex.buttonTrans, t -> {
            t.touchable = Touchable.enabled;
            t.clicked(() -> {});

            t.addListener(new arc.scene.event.InputListener() {
                @Override
                public boolean touchDown(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button) {
                    event.stop();
                    return true;
                }
            });

            t.margin(12).defaults().left();

            t.table(h -> {
                h.add(new Image(Icon.infoSmall)).padRight(8);
                h.add(Core.bundle.format("sam.info.title", data.name)).growX();
                h.button(Icon.leftOpen, Styles.cleari, () -> {
                    infoPanel.remove();
                    infoPanel = null;
                    this.toggle();
                }).size(32);
                h.button(Icon.cancel, Styles.cleari, () -> {
                    infoPanel.remove();
                    infoPanel = null;
                }).size(32);
            }).growX().row();

            t.image().color(Pal.accent).fillX().height(2).padTop(4).padBottom(8).row();

            t.pane(p -> {
                p.defaults().left().growX().margin(2);

                addCopyRow(p, Core.bundle.get("sam.info.name"), data.name);
                addCopyRow(p, Core.bundle.get("sam.info.uuid"), data.uuid);
                addCopyRow(p, Core.bundle.get("sam.info.ip"), data.ip);
                addCopyRow(p, Core.bundle.get("sam.info.lang"), data.locale);
                addCopyRow(p, Core.bundle.get("sam.info.joins"), String.valueOf(data.timesJoined));
                addCopyRow(p, Core.bundle.get("sam.info.kicks"), String.valueOf(data.timesKicked));
                addCopyRow(p, Core.bundle.get("sam.info.mobile"), data.mobile ? Core.bundle.get("sam.info.yes") : Core.bundle.get("sam.info.no"));
                addCopyRow(p, Core.bundle.get("sam.info.modded"), data.modded ? Core.bundle.get("sam.info.yes") : Core.bundle.get("sam.info.no"));

                if(data.names.length > 1) {
                    p.add(Core.bundle.get("sam.info.history.name")).padTop(8).row();
                    for(String s : data.names) addCopyRow(p, "", s);
                }

                if(data.ips.length > 1) {
                    p.add(Core.bundle.get("sam.info.history.ip")).padTop(8).row();
                    for(String s : data.ips) addCopyRow(p, "", s);
                }
            }).size(340, 300).row();

            t.button(Core.bundle.get("sam.info.stats"), Icon.refresh, () -> {
                Call.sendChatMessage("/stats " + data.uuid);
                infoPanel.remove();
            }).margin(10).growX().height(45).padTop(10).row();

            if(data.online) {
                t.button(Core.bundle.get("sam.info.update"), Icon.refresh, () -> {
                    Player p = Groups.player.getByID(data.id);
                    if(p != null) Call.adminRequest(p, Packets.AdminAction.trace, null);
                    infoPanel.remove();
                }).margin(10).growX().height(45).padTop(10);
            }

        }).center();

        Core.scene.add(infoPanel);
    }

    private void addCopyRow(Table table, String label, String value) {
        if(value == null || value.isEmpty()) return;

        String displayText = label.isEmpty() ? "[white]" + value : "[lightgray]" + label + ": [accent]" + value;

        table.button(b -> {
            b.left().margin(4);
            b.add(displayText).left().wrap().growX().fontScale(0.9f);
        }, Styles.flatBordert, () -> {
            Core.app.setClipboardText(value);
            ui.showInfoFade("[accent]" + (label.isEmpty() ? value : label) + Core.bundle.get("sam.info.copy"));
        }).growX().height(32).padBottom(2).row();
    }

}