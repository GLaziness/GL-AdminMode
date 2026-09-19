package fallen;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/**
 * Ban menu: rule sections with collapsible categories, a length slider on every rule and a custom reason.
 * After a ban it can save the evidence, roll back the player's blocks and announce the ban in the chat.
 * The rules layout, evidence and rollback come from SimpleAdminMode2, laid out like the other GL dialogs.
 */
public class AdvancedBanDialog extends BaseDialog{
    /** Opened categories, kept between dialogs. */
    private static final ObjectSet<String> expanded = new ObjectSet<>();

    private final String name, uuid;
    private final int playerId;
    private String scope = Core.settings.getBool("sam-default-select-all", false) ? "all" : "here";
    private final Table rules = new Table();
    private float width;

    public AdvancedBanDialog(Player player, String uuid){
        super(Core.bundle.format("sam.ban.title", Strings.stripColors(player.name)));
        this.name = player.name;
        this.uuid = uuid;
        this.playerId = player.id;
        addCloseButton();
        shown(this::build);
        onResize(this::build);
    }

    private void build(){
        cont.clear();
        width = Math.min(760f, Core.graphics.getWidth() / Scl.scl(1f) - 40f);

        cont.pane(all -> {
            all.top().margin(6f).marginBottom(20f);

            // player and where the ban applies
            all.table(Styles.grayPanel, head -> {
                head.left().margin(8f);
                head.button("[lightgray]UUID [accent]" + uuid, Styles.cleart, () -> {
                    Core.app.setClipboardText(uuid);
                    ui.showInfoFade("[accent]UUID" + Core.bundle.get("sam.info.copy"));
                }).left().height(32f).row();
                head.table(st -> {
                    st.left().defaults().height(38f).minWidth(72f).pad(2f);
                    ButtonGroup<Button> group = new ButtonGroup<>();
                    for(String s : new String[]{"here", "all", "attack", "survival", "pvp"}){
                        st.button(s.equals("here") || s.equals("all") ? s.toUpperCase() : Strings.capitalize(s), Styles.flatTogglet, () -> scope = s)
                            .group(group).checked(scope.equals(s));
                        if(mobile && s.equals("all")) st.row();
                    }
                }).left().padTop(4f);
            }).width(width).row();

            all.add(rules).width(width).row();
            rebuildRules();

            section(all, Icon.pencil, "@sam.ban.custom.title");
            all.table(Styles.grayPanel, this::customReason).width(width).row();

            // what happens after the ban
            section(all, Icon.settings, "@sam.ban.after");
            all.table(Styles.grayPanel, t -> {
                t.left().margin(8f).defaults().left().padTop(2f);
                t.check("@sam.settings.rollback", Core.settings.getBool("sam-ban-rollback", true), v -> Core.settings.put("sam-ban-rollback", v)).row();
                t.check("@sam.settings.evidence.enabled", BanEvidenceLogger.enabled(), v -> Core.settings.put("sam-evidence-enabled", v)).row();
                t.check("@sam.settings.banAnnounceOn", BanKickMessages.enabled(), v -> Core.settings.put("sam-ban-announce", v)).row();
            }).width(width).row();
        }).scrollX(false).grow();
    }

    private void section(Table all, Drawable icon, String title){
        all.table(head -> {
            head.left();
            head.image(icon).color(Pal.accent).size(22f).padRight(8f);
            head.add(title).color(Pal.accent).left();
        }).width(width).padTop(14f).left().row();
        all.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();
    }

    private void rebuildRules(){
        rules.clear();
        rules.top().left();

        section(rules, Icon.hammer, "@sam.ban.section.grief");
        category("2.1", new Rule[]{
            rule("2.1.1", 3, 14, 7), rule("2.1.2", 14, 90, 30), rule("2.1.2p", 1, 14, 3), rule("2.1.3", 14, 90, 30),
            rule("2.1.3p", 1, 14, 3), rule("2.1.4", 7, 30, 14), rule("2.1.5", 30, 90, 30), rule("2.1.6", 14, 30, 14)
        });
        category("2.2", new Rule[]{rule("2.2.1", 1, 14, 3), rule("2.2.2", 1, 7, 3), rule("2.2.3", 1, 7, 3), rule("2.2.4", 1, 14, 3)});
        category("2.3", new Rule[]{perm("2.3.1"), rule("2.3.2", 3, 7, 5)});

        section(rules, Icon.image, "@sam.ban.section.visual");
        category("3", new Rule[]{rule("3.1", 14, 30, 14), rule("3.2.1", 30, 90, 45), rule("3.2.2", 14, 30, 14), rule("3.3", 7, 14, 7)});

        section(rules, Icon.chat, "@sam.ban.section.chat");
        category("4", new Rule[]{
            rule("4.1", 1, 3, 1), rule("4.2", 2, 7, 3), rule("4.3", 3, 7, 3), perm("4.4"), rule("4.5", 3, 14, 7), perm("4.6"), rule("4.7", 7, 30, 14)
        });

        section(rules, Icon.warning, "@sam.ban.section.other");
        category("5.1", new Rule[]{rule("5.1.1", 2, 2, 2), rule("5.1.2", 2, 30, 10)});
        ruleRow(rules, perm("5.2"));
        ruleRow(rules, rule("5.3", 7, 30, 14));
        ruleRow(rules, rule("5.4", 3, 14, 7));
    }

    /** A category that opens its rules on a click. */
    private void category(String id, Rule[] children){
        boolean open = expanded.contains(id);
        rules.button(b -> {
            b.left().margin(6f, 10f, 6f, 10f);
            b.image(open ? Icon.downOpen : Icon.rightOpen).color(open ? Pal.accent : Color.lightGray).size(18f).padRight(8f);
            b.add(Core.bundle.get(key(id))).color(open ? Pal.accent : Color.white).left().growX().wrap();
        }, Styles.flatBordert, () -> {
            if(!expanded.add(id)) expanded.remove(id);
            rebuildRules();
        }).width(width).minHeight(46f).padBottom(2f).row();

        if(open){
            rules.table(t -> {
                for(Rule r : children) ruleRow(t, r);
            }).width(width - 12f).padLeft(12f).padBottom(4f).row();
        }
    }

    /** Rule text, the length slider (its last step is "forever") and the ban button. */
    private void ruleRow(Table table, Rule r){
        table.table(Styles.black5, row -> {
            row.left().margin(4f, 10f, 4f, 4f);
            row.add(Core.bundle.get(key(r.id))).left().growX().wrap().minWidth(0f);

            int[] value = {r.def};
            Prov<String> length = () -> r.perm || value[0] > r.max ? "perm" : value[0] + "d";
            if(!r.perm){
                row.table(s -> {
                    Label label = s.add("").width(56f).get();
                    label.setAlignment(Align.center);
                    Runnable update = () -> label.setText("[accent]" + (value[0] > r.max ? Core.bundle.get("sam.ban.perm") : value[0] + "d"));
                    update.run();
                    s.slider(r.min, r.max + 1, 1, r.def, v -> {
                        value[0] = (int)v;
                        update.run();
                    }).width(mobile ? 90f : 120f);
                }).padLeft(6f);
            }else{
                row.add("[scarlet]" + Core.bundle.get("sam.ban.perm")).width(56f).padLeft(6f).get().setAlignment(Align.center);
            }
            row.button(Icon.hammer, Styles.clearNonei, () -> ban(length.get(), r.id.replace("p", "")))
                .size(42f).tooltip(Core.bundle.get("sam.list.ban")).get().getImage().setColor(Color.scarlet);
        }).width(width - (table == rules ? 0f : 12f)).minHeight(46f).padBottom(2f).row();
    }

    private void customReason(Table t){
        t.left().top().margin(8f).defaults().left();
        t.add("@sam.ban.custom.hint").color(Color.lightGray).wrap().growX().padBottom(6f).row();

        TextField reason = t.field("", s -> {}).growX().height(42f).get();
        reason.setMessageText(Core.bundle.get("sam.ban.custom.reason.hint"));
        t.row();

        t.table(r -> {
            r.left();
            r.add("@sam.ban.custom.time").color(Color.lightGray).padRight(8f);
            TextField time = r.field("1d", s -> {}).width(110f).height(42f).get();
            time.setMessageText("30m, 12h, 3d, perm");
            r.button("@sam.ban.custom.apply", Icon.hammer, Styles.flatt, () -> {
                String text = reason.getText().trim(), length = time.getText().trim().toLowerCase();
                if(text.isEmpty()){
                    ui.showInfoFade(Core.bundle.get("sam.ban.custom.empty"));
                }else if(!validTime(length.isEmpty() ? "1d" : length)){
                    ui.showInfoFade(Core.bundle.get("sam.ban.custom.badtime"));
                }else{
                    ban(length.isEmpty() ? "1d" : length, text);
                }
            }).growX().height(42f).padLeft(8f);
        }).growX().padTop(6f);
    }

    /** perm, a number (minutes) or a number with s/h/d/w/m(onths)/y. */
    static boolean validTime(String time){
        return time.equals("perm") || time.matches("\\d+[shdwmy]?");
    }

    private void ban(String length, String reason){
        String cmd = Strings.format("/ban @ @ @ @", uuid, length, scope, reason);
        Call.sendChatMessage(cmd);
        player.sendMessage("[gray][" + Core.bundle.get("sam.ban.sent") + "]: [white]" + cmd);

        BanEvidenceLogger.writeOnBan(playerId, name, uuid, length, reason);
        if(Core.settings.getBool("sam-ban-rollback", true)){
            // the server rolls back by the same id the ban used
            Time.runTask(120f, () -> {
                Call.sendChatMessage("/rollback " + uuid);
                player.sendMessage("[gray][" + Core.bundle.get("sam.ban.sent") + "]: [white]/rollback " + uuid);
            });
        }
        BanKickMessages.ban(name, length);
        hide();
    }

    private static String key(String id){
        return "sam.ban.rule." + id.replace('.', '_');
    }

    private static Rule rule(String id, int min, int max, int def){
        return new Rule(id, min, max, def, false);
    }

    private static Rule perm(String id){
        return new Rule(id, 0, 0, 0, true);
    }

    private static class Rule{
        final String id;
        final int min, max, def;
        final boolean perm;

        Rule(String id, int min, int max, int def, boolean perm){
            this.id = id;
            this.min = min;
            this.max = max;
            this.def = def;
            this.perm = perm;
        }
    }
}
