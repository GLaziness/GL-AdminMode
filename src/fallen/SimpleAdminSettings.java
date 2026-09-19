package fallen;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/** Settings of the mod, laid out like the GL Client dialogs: accent section titles over dark panels. */
public class SimpleAdminSettings extends BaseDialog{
    private final Table all = new Table();
    private float width = 600f;

    public SimpleAdminSettings(){
        super("@sam.settings.title");
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);
        cont.pane(all).scrollX(false).grow();
    }

    public void rebuild(){
        all.clear();
        all.top().margin(10f).marginBottom(30f);
        width = Math.min(600f, Core.graphics.getWidth() / Scl.scl(1f) - 60f);

        section(Icon.menu, "@sam.settings.interface", t -> {
            slider(t, "@sam.settings.btnSize", "sam-btn-size", 30, 80, 1, 40);
            slider(t, "@sam.settings.hudY", "sam-hud-offset", -50, 300, 2, 4);
            slider(t, "@sam.settings.listW", "sam-list-w", 200, 1000, 10, 400);
            check(t, "@sam.settings.closeOutside", "sam-close-outside", true);
            check(t, "@sam.settings.closeListOnInfo", "sam-close-list", false);
            check(t, "@sam.settings.closeListOutside", "sam-close-listoutside", false);
            check(t, "@sam.settings.defaultSelectAll", "sam-default-select-all", false);
        });

        section(Icon.settings, "@sam.settings.functions", t -> {
            check(t, "@sam.settings.stats", "sam-show-stats", false);
            check(t, "@sam.settings.save", "sam-log-save", false);
            check(t, "@sam.settings.vanish", "sam-vanish", false);
            check(t, "@sam.settings.fastlang", "sam-fastlang", false);
            check(t, "@sam.settings.freecam", "sam-freecam", false);
            check(t, "@sam.settings.updateCheck", "sam-update-check", true);
        });

        section(Icon.hammer, "@sam.settings.ban", t -> {
            check(t, "@sam.settings.rollback", "sam-ban-rollback", true);
            hint(t, "@sam.settings.rollback.hint");
            check(t, "@sam.settings.evidence.enabled", "sam-evidence-enabled", true);
            hint(t, "@sam.settings.evidence.hint");
            t.table(f -> {
                f.left();
                f.add("@sam.settings.evidence.path").color(Color.lightGray).padRight(8f);
                TextField field = f.field(Core.settings.getString("sam-evidence-path", ""), text -> Core.settings.put("sam-evidence-path", text.trim())).growX().height(40f).get();
                field.setMessageText(BanEvidenceLogger.file().absolutePath());
                f.button(Icon.refresh, Styles.clearNonei, () -> {
                    Core.settings.remove("sam-evidence-path");
                    field.setText("");
                    ui.showInfoFade(Core.bundle.get("sam.settings.evidence.reset"));
                }).size(40f).padLeft(4f).tooltip(Core.bundle.get("sam.settings.evidence.reset"));
                if(!mobile){
                    f.button(Icon.folder, Styles.clearNonei, () -> Core.app.openFolder(BanEvidenceLogger.file().parent().absolutePath()))
                        .size(40f).tooltip(Core.bundle.get("sam.settings.evidence.open"));
                }
            }).growX().padTop(6f).row();
            check(t, "@sam.settings.adminBanner", "sam-admin-banner", true);
        });

        section(Icon.discord, "@sam.settings.discordTitle", t -> {
            check(t, "@sam.settings.discord", "sam-discord", true);
            hint(t, "@sam.settings.discord.hint");
            t.add("@sam.settings.discord.key").color(Color.lightGray).padTop(8f).row();
            t.table(f -> {
                TextField url = f.field(DiscordReport.key(), text -> Core.settings.put("sam-discord-key", text.trim())).growX().height(40f).get();
                url.setPasswordMode(true);
                url.setPasswordCharacter('*');
                url.setMessageText("glr_...");
                f.button(Icon.eyeSmall, Styles.clearNonei, () -> {
                    url.setPasswordMode(!url.isPasswordMode());
                    url.setText(url.getText());
                }).size(40f).padLeft(4f);
            }).growX().row();
            t.add("@sam.settings.discord.servers").color(Color.lightGray).padTop(8f).row();
            t.field(Core.settings.getString("sam-discord-servers", DiscordReport.defaultServers), text -> Core.settings.put("sam-discord-servers", text.trim()))
                .growX().height(40f).get().setMessageText(DiscordReport.defaultServers);
            t.row();
            t.button("@sam.settings.discord.test", Icon.upload, Styles.flatt, DiscordReport::test).height(40f).growX().padTop(8f).row();
        });

        section(Icon.chat, "@sam.settings.banAnnounce", t -> {
            check(t, "@sam.settings.banAnnounceOn", "sam-ban-announce", false);
            hint(t, "@sam.settings.banAnnounceHint");
            t.table(b -> {
                b.defaults().height(40f).growX().pad(2f);
                b.button("@sam.bankick.open", Icon.pencil, Styles.flatt, () -> new BanKickMessageEditor().show());
                b.button("@sam.settings.banAnnounceTest", Icon.eye, Styles.flatt, () -> {
                    player.sendMessage("[lightgray](" + Core.bundle.get("sam.settings.banAnnounceTest") + ")[] " + BanKickMessages.random(BanKickMessages.Kind.ban, player.name, "7d"));
                    player.sendMessage("[lightgray](" + Core.bundle.get("sam.settings.banAnnounceTest") + ")[] " + BanKickMessages.random(BanKickMessages.Kind.kick, player.name, null));
                });
            }).growX().padTop(6f).row();
        });

        section(Icon.warning, "@sam.settings.antiGrief", t -> {
            check(t, "@sam.settings.agEnabled", "sam-ag-enabled", false);
            check(t, "@sam.settings.agAFreeze", "sam-ag-afr", false);
            check(t, "@sam.settings.agBuildWarn", "sam-ag-build-warn", true);

            t.table(n -> {
                n.left().defaults().left().padTop(4f);
                number(n, "@sam.settings.agMinBuild", "sam-ag-min-build", 10);
                number(n, "@sam.settings.agMaxBreak", "sam-ag-max-break", 100);
                number(n, "@sam.settings.agMaxConf", "sam-ag-max-conf", 20);
                number(n, "@sam.settings.agMinJoins", "sam-ag-min-joins", 5);
                number(n, "@sam.settings.agMaxKicks", "sam-ag-max-kicks", 1);
            }).growX().padTop(8f).row();

            t.add("@sam.settings.agBlocks").color(Pal.accent).padTop(12f).row();
            block(t, Blocks.thoriumReactor, "thorium");
            block(t, Blocks.incinerator, "incinerator");
            block(t, Blocks.melter, "melter");
        });

        section(Icon.logic, "@sam.settings.iHateAttems", t -> {
            check(t, "@sam.settings.iHateAttems", "sam-aa", false);
            check(t, "@sam.settings.onAttemAlarms", "sam-oaa", false);
            check(t, "@sam.settings.aaBan", "sam-aab", false);
        });

        all.button("@sam.settings.resetSettings", Icon.refresh, Styles.flatt, () -> {
            Core.settings.put("sam-btn-size", 40);
            Core.settings.put("sam-hud-offset", 4);
            Core.settings.put("sam-list-w", 400);
            rebuild();
            ui.showInfoFade("@sam.settings.resetDone");
        }).width(width).height(50f).padTop(20f).row();
    }

    /** Accent title, accent line and a dark panel, same as the GL Client dialogs. */
    private void section(Drawable icon, String title, Cons<Table> content){
        all.table(head -> {
            head.left();
            head.image(icon).color(Pal.accent).size(24f).padRight(8f);
            head.add(title).color(Pal.accent).left();
        }).width(width).padTop(16f).left().row();
        all.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();

        all.table(Styles.grayPanel, t -> {
            t.left().top().margin(10f);
            t.defaults().left().growX();
            content.get(t);
        }).width(width).row();
    }

    private void check(Table t, String name, String key, boolean def){
        t.check(name, Core.settings.getBool(key, def), b -> Core.settings.put(key, b)).left().padTop(4f).row();
    }

    private void hint(Table t, String text){
        t.add(text).color(Color.lightGray).wrap().growX().left().padTop(4f).row();
    }

    /** Slider with its name and value written over it, like the GL Client sliders. */
    private void slider(Table t, String name, String key, int min, int max, int step, int def){
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(Core.settings.getInt(key, def));
        Label value = new Label(String.valueOf(Core.settings.getInt(key, def)), Styles.outlineLabel);
        Table content = new Table();
        content.add(name, Styles.outlineLabel).left().growX().wrap();
        content.add(value).padLeft(10f).right();
        content.margin(3f, 33f, 3f, 33f);
        content.touchable = Touchable.disabled;
        slider.changed(() -> {
            Core.settings.put(key, (int)slider.getValue());
            value.setText(String.valueOf((int)slider.getValue()));
        });
        t.stack(slider, content).growX().padTop(6f).row();
    }

    private void number(Table t, String name, String key, int def){
        t.add(name).color(Color.lightGray).growX().wrap();
        t.field(String.valueOf(Core.settings.getInt(key, def)), TextField.TextFieldFilter.digitsOnly, text -> {
            if(Strings.canParseInt(text)) Core.settings.put(key, Strings.parseInt(text));
        }).width(110f).padLeft(10f).get().setMessageText(String.valueOf(def));
        t.row();
    }

    private void block(Table t, Block block, String key){
        t.table(Styles.black5, b -> {
            b.left().margin(6f);
            b.table(head -> {
                head.left();
                head.image(block.uiIcon).size(32f).padRight(8f);
                head.check(block.localizedName, Core.settings.getBool("sam-ag-" + key + "-enabled", true),
                    v -> Core.settings.put("sam-ag-" + key + "-enabled", v)).left();
            }).growX().left().row();
            slider(b, "@sam.settings.agBlockRadius", "sam-ag-" + key + "-radius", 0, 100, 1, 10);
        }).growX().padTop(6f).row();
    }
}
