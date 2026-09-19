package fallen;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Editor of the ban and kick announcements, laid out like the other GL dialogs. The idea comes from SimpleAdminMode2. */
public class BanKickMessageEditor extends BaseDialog{
    private static final String sampleLength = "7d";

    private BanKickMessages.Kind kind = BanKickMessages.Kind.ban;
    private Seq<String> list = new Seq<>();
    private final Table content = new Table();
    private float width;

    public BanKickMessageEditor(){
        super("@sam.bankick.title");
        // opened over the settings: a solid background, so their text does not show through
        background(Styles.black9);
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);
        cont.pane(content).scrollX(false).grow();
    }

    private void rebuild(){
        list = BanKickMessages.get(kind);
        width = Math.min(700f, Core.graphics.getWidth() / Scl.scl(1f) - 40f);
        content.clear();
        content.top().margin(6f).marginBottom(20f);

        content.table(Styles.grayPanel, t -> {
            t.left().margin(8f).defaults().left();
            t.check("@sam.settings.banAnnounceOn", BanKickMessages.enabled(), v -> Core.settings.put("sam-ban-announce", v)).row();
            t.add("@sam.bankick.help").color(Color.lightGray).wrap().growX().padTop(6f);
        }).width(width).row();

        content.table(tabs -> {
            tabs.defaults().height(44f).growX().pad(2f);
            for(BanKickMessages.Kind k : BanKickMessages.Kind.values()){
                tabs.button("@sam.bankick.tab." + k.name(), Styles.flatTogglet, () -> {
                    kind = k;
                    rebuild();
                }).checked(b -> kind == k);
            }
        }).width(width).padTop(10f).row();

        content.table(head -> {
            head.left();
            head.image(kind == BanKickMessages.Kind.ban ? Icon.hammer : Icon.exit).color(Pal.accent).size(22f).padRight(8f);
            head.add(Core.bundle.format("sam.bankick.count", list.size)).color(Pal.accent).growX().left();
            head.button(Icon.add, Styles.clearNonei, () -> edit(-1)).size(40f).tooltip(Core.bundle.get("sam.bankick.add"));
            head.button(Icon.refresh, Styles.clearNonei, () -> ui.showConfirm("@confirm", "@sam.bankick.reset.confirm", () -> {
                BanKickMessages.reset(kind);
                rebuild();
            })).size(40f).tooltip(Core.bundle.get("sam.bankick.reset"));
        }).width(width).padTop(12f).row();
        content.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();

        if(list.isEmpty()){
            content.add("@sam.bankick.empty").color(Color.lightGray).pad(12f).row();
        }
        for(int i = 0; i < list.size; i++){
            int index = i;
            content.table(Styles.black5, row -> {
                row.left().margin(6f, 10f, 6f, 4f);
                row.add(preview(list.get(index))).left().growX().wrap().minWidth(0f);
                row.button(Icon.pencil, Styles.clearNonei, () -> edit(index)).size(40f).tooltip(Core.bundle.get("sam.bankick.edit"));
                row.button(Icon.trash, Styles.clearNonei, () -> {
                    list.remove(index);
                    BanKickMessages.save(kind, list);
                    rebuild();
                }).size(40f).tooltip(Core.bundle.get("sam.bankick.delete")).get().getImage().setColor(Color.scarlet);
            }).width(width).minHeight(46f).padBottom(2f).row();
        }
    }

    private String preview(String template){
        return BanKickMessages.fill(template, player.name, kind == BanKickMessages.Kind.ban ? sampleLength : null);
    }

    /** Edits one template (index -1 adds a new one): a text field, buttons that insert the variables and a live preview. */
    private void edit(int index){
        BaseDialog dialog = new BaseDialog(index < 0 ? "@sam.bankick.add" : "@sam.bankick.edit");
        dialog.background(Styles.black9);
        float w = Math.min(600f, Core.graphics.getWidth() / Scl.scl(1f) - 40f);
        String start = index >= 0 ? list.get(index) : kind.prefix + BanKickMessages.nick + " ";

        dialog.cont.table(Styles.grayPanel, t -> {
            t.left().margin(10f).defaults().left();
            TextArea field = t.area(start, s -> {}).growX().height(110f).get();
            t.row();

            t.table(vars -> {
                vars.left().defaults().height(38f).pad(2f);
                vars.add("@sam.bankick.insert").color(Color.lightGray).padRight(6f);
                vars.button(BanKickMessages.nick, Styles.flatt, () -> insert(field, BanKickMessages.nick)).width(110f);
                if(kind == BanKickMessages.Kind.ban){
                    vars.button(BanKickMessages.time, Styles.flatt, () -> insert(field, BanKickMessages.time)).width(110f);
                }
            }).padTop(6f).row();

            t.add("@sam.bankick.preview").color(Pal.accent).padTop(10f).row();
            t.table(Styles.black5, p -> p.label(() -> preview(field.getText().replace("\n", " "))).left().growX().wrap().pad(8f)).growX().padTop(4f).row();

            dialog.buttons.defaults().size(210f, 54f);
            dialog.buttons.button("@back", Icon.left, dialog::hide);
            dialog.buttons.button("@sam.bankick.save", Icon.ok, () -> {
                String text = field.getText().replace("\r", "").replace("\n", " ").trim();
                if(text.isEmpty()) return;
                if(index < 0) list.add(text);
                else list.set(index, text);
                BanKickMessages.save(kind, list);
                dialog.hide();
                rebuild();
            });
        }).width(w);
        dialog.closeOnBack();
        dialog.show();
    }

    private static void insert(TextArea field, String text){
        int at = field.getCursorPosition();
        String old = field.getText();
        field.setText(old.substring(0, at) + text + old.substring(at));
        field.setCursorPosition(at + text.length());
        Core.scene.setKeyboardFocus(field);
    }
}
