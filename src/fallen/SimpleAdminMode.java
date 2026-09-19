package fallen;

import arc.*;
import arc.graphics.Color;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.MobileInput;
import mindustry.mod.Mod;
import mindustry.net.Administration;
import mindustry.graphics.Pal;
import arc.scene.ui.layout.Scl;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.TraceDialog;

import static mindustry.Vars.*;

public class SimpleAdminMode extends Mod {
    private SimpleAdminList adminList = new SimpleAdminList();
    public static ObjectMap<Integer, PlayerData> playerHistory = new ObjectMap<>();
    private ObjectSet<Integer> autoTraceRequested = new ObjectSet<>();
    private IntSet knownPlayerIds = new IntSet();
    private static TraceDialog originalTraces;
    private static ObjectMap<Integer, Float> lastAutoTime = new ObjectMap<>();
    private static String lastMapName = "", lastMapAuthor = "", lastServerAddr = "";
    private static double lastPlaytime = 0f;

    /** Banner "an admin joined" under the core items, like the "core under attack" one. */
    private static final float adminBannerDuration = 240f;
    private static float adminBannerTime = 0f;
    private static String adminBannerText = "";
    private static final IntSet adminBannerShown = new IntSet();

    private static java.lang.reflect.Field chatMessagesField;
    private static int lastChatSize = -1;
    private static Object lastChatFirst;
    private static boolean triggerPadded = false;

    /**
     * Some clients (FOO and forks) fire Events for Trigger values without a bounds check, so a listener array made
     * only for low Trigger values crashes when a higher one fires. A no-op listener on the last value pads the array.
     */
    public static void ensureTriggerListenersSafe(){
        if(triggerPadded) return;
        triggerPadded = true;
        try{
            Trigger[] all = Trigger.values();
            if(all.length > 0) Events.run(all[all.length - 1], () -> {});
        }catch(Throwable t){
            Log.err("[GL Admin] Trigger pad failed", t);
        }
    }

    public SimpleAdminMode() {
        ensureTriggerListenersSafe();

        Events.on(ClientLoadEvent.class, e -> {
            if(mobile){
                // phone friendly defaults, only when the player never changed them
                if(!Core.settings.has("sam-btn-size")) Core.settings.put("sam-btn-size", 48);
                if(!Core.settings.has("sam-list-w")) Core.settings.put("sam-list-w", Math.min(420, Math.max(280, (int)(Core.scene.getWidth() / Scl.scl(1f) * 0.85f))));
            }
            PlayerStatsTracker.init();
            BanEvidenceLogger.init();
            HistoryRender.init();
            AntiAttemPatcher.load();
            adminList.build(Core.scene.root);
            setupSettings();
            Time.runTask(180f, ModUpdater::check); // GL: offer updates from the GitHub releases
            setupTraceOverride(); // Первый запуск

            // === ПОДМЕНА INPUT HANDLER ДЛЯ МОБИЛЬНЫХ ===
            if (Vars.mobile && !(Vars.control.input instanceof FreeCamMobileInput) && Core.settings.getBool("sam-freecam", false)) {
                MobileInput oldInput = (MobileInput) Vars.control.input;
                FreeCamMobileInput newInput = new FreeCamMobileInput();

                // Копируем важные поля состояния:
                newInput.block = oldInput.block;
                newInput.rotation = oldInput.rotation;
                newInput.mode = oldInput.mode;  // ← важно!
                newInput.selectPlans.addAll(oldInput.selectPlans);
                newInput.linePlans.addAll(oldInput.linePlans);
                newInput.down = oldInput.down;  // ← важно для pan()!
                newInput.manualShooting = oldInput.manualShooting;

                // Копируем целевые позиции, чтобы не было скачков:
                newInput.targetPos.set(oldInput.targetPos);
                newInput.movement.set(oldInput.movement);

                Vars.control.input = newInput;
            }

            HudButton.build(adminList);
            setupAdminBanner();
        });

//        Events.on(PlayerJoin.class, e -> {
//            Time.run(60f, () -> processPlayer(e.player));
//            Vars.player.sendMessage("PlayerJoin");
//
//        });//
//        Events.on(PlayerLeave.class, e -> {
//            PlayerData data = playerHistory.get(e.player.id);
//            if (data != null) data.online = false;
//            Vars.player.sendMessage("PlayerLeave");
//        });

        Events.on(WorldLoadEvent.class, e -> {
            String currentMap = Vars.state.map.name();
            String currentAuthor = Vars.state.map.author();
            String currentServer = Vars.net.client() ? (Vars.player.con != null ? Vars.player.con.address : "remote") : "local";
            double currentPlaytime = Vars.state.tick;

            boolean isSameSession = currentMap.equals(lastMapName) &&
                    currentAuthor.equals(lastMapAuthor) &&
                    currentServer.equals(lastServerAddr) &&
                    currentPlaytime >= (lastPlaytime - 600f);
            if (!isSameSession) {
                playerHistory.clear();
                autoTraceRequested.clear();
                knownPlayerIds.clear();
                lastAutoTime.clear();
                adminBannerShown.clear();
                ActionsHistory.clearactionhistory();
                HistoryRender.clear();

                Log.info("[SAM] New session detected (Map reset or change). History cleared.");
            } else {
                Log.info("[SAM] Reconnect/Sync detected. History preserved. Time delta: " + (currentPlaytime - lastPlaytime)/60f + "s");
            }
            lastMapName = currentMap;
            lastMapAuthor = currentAuthor;
            lastServerAddr = currentServer;
            lastPlaytime = currentPlaytime;

            setupTraceOverride();

            Timer.schedule(() -> {
                if(net.client() && player.unit() != null && Core.settings.getBool("sam-vanish", false)){
                    Call.sendChatMessage("/vanish 1");
                    Log.info("[#00ff]Vanish on");
                }
            }, 5f);

            if(Vars.state.isMenu() || !Vars.net.active()) return;
            Time.run(120f, () -> {
                for (Player p : Groups.player) processPlayer(p);
            });
        });

        Events.run(Trigger.update, () -> {
            if(Vars.state.isMenu() || !Vars.net.active()) return;
            if (net.active() && state.isGame() && Core.graphics.getFrameId() % 60 == 0) {
                try {
                    syncPlayers();
                } catch (Exception ex) {
                    Log.err("Ошибка в syncPlayers", ex);
                }            }
            if (net.active() && state.isGame() && Core.graphics.getFrameId() % 300 == 0) {
                checkGriefers();
            }
            if (Core.graphics.getFrameId() % 10 == 0) {
                scrubCannotTrace();
            }
        });
    }

    private void checkGriefers() {
        if (!Core.settings.getBool("sam-ag-enabled", false)) return;

        int minB = Core.settings.getInt("sam-ag-min-build", 10);
        int maxBr = Core.settings.getInt("sam-ag-max-break", 100);
        int maxCf = Core.settings.getInt("sam-ag-max-conf", 20);
        int minJ = Core.settings.getInt("sam-ag-min-joins", 5);
        int maxK = Core.settings.getInt("sam-ag-max-kicks", 1);

        for (PlayerData data : playerHistory.values()) {
            if (!data.online || data.uuid.equals("Loading...")) continue;

            // --- ЛОГИКА 1: ПРЕДУПРЕЖДЕНИЕ ---
            if (!data.griefWarned) {
                boolean suspicious = (data.builds < minB) &&
                        (data.breaks > maxBr) &&
                        (data.configs > maxCf) &&
                        (data.timesJoined < minJ) &&
                        (data.timesKicked >= maxK);

                if (suspicious) {
                    data.griefWarned = true;
                    Vars.player.sendMessage(Core.bundle.format("sam.ag.alert", data.name) + "\n" +
                            Core.bundle.format("sam.ag.stats", data.builds, data.breaks, data.configs, data.timesJoined, data.timesKicked));
                }
            }

            // --- ЛОГИКА 2: АВТО-ФРИЗ (Независимая) ---
            if (!data.player.admin && !data.autoFrozen && Core.settings.getBool("sam-ag-afr", false)) {
                if ((data.builds < minB * 2) && (data.breaks > maxBr * 2 || data.configs > maxCf * 2 )) {
                    data.autoFrozen = true;

                    Call.sendChatMessage("/freeze " + data.uuid);
                    Vars.player.sendMessage(Core.bundle.format("sam.ag.autoFreeze", data.name, maxBr * 2));
                }
            }
        }
    }

    private void setupSettings() {
        ui.settings.addCategory(Core.bundle.get("sam.settings.title"), Icon.admin, table -> {
            table.button("@sam.settings.open", Icon.settings, () -> new SimpleAdminSettings().show()).size(320f, 60f).pad(10f).row();
        });
    }

    private void addSlider(arc.scene.ui.layout.Table table, String bundleKey, String settingKey, int min, int max, int def) {
        table.table(t -> {
            t.left().defaults().left();
            t.add(Core.bundle.get(bundleKey)).row();

            t.table(s -> {
                s.slider(min, max, 1, Core.settings.getInt(settingKey, def), val -> {
                    Core.settings.put(settingKey, (int)val);
                }).width(350f).height(50f).padRight(10f);

                s.label(() -> String.valueOf(Core.settings.getInt(settingKey, def))).color(mindustry.graphics.Pal.accent).width(40f);
            }).row();
        }).padTop(10f).row();
    }

    /** Shows the "an admin joined" banner once per player and session. Based on SimpleAdminMode2. */
    public static void notifyAdminDetected(Player p){
        if(p == null || p == player || adminBannerShown.contains(p.id)) return;
        adminBannerShown.add(p.id);
        if(!Core.settings.getBool("sam-admin-banner", true)) return;
        adminBannerText = Core.bundle.format("sam.admin.joined", p.name);
        adminBannerTime = adminBannerDuration;
    }

    private void setupAdminBanner(){
        ui.hudGroup.fill(t -> {
            t.name = "gl-admin-banner";
            t.top();
            t.touchable = arc.scene.event.Touchable.disabled;
            t.update(() -> {
                if(adminBannerTime > 0f && !state.isPaused()) adminBannerTime -= Time.delta;
                // right under the core items, where "core under attack" shows up
                float top = 8f;
                if(ui.hudfrag.shown && Core.settings.getBool("coreitems", true) && ui.hudfrag.coreItems != null){
                    float h = ui.hudfrag.coreItems.getHeight() / Scl.scl(1f);
                    top = h > 1f ? h + 6f : 36f;
                }
                if(Core.settings.getBool("macnotch", false)) top += 32f;
                t.marginTop(top);
            });
            t.table(Styles.black6, banner -> {
                banner.image(Icon.admin).size(20f).padLeft(10f).update(i -> i.setColor(Pal.accent));
                banner.label(() -> adminBannerText).pad(8f).padRight(12f).update(l ->
                    l.color.set(Color.orange).lerp(Color.scarlet, arc.math.Mathf.absin(Time.time, 2f, 1f)));
            }).visible(() -> adminBannerTime > 0f && state.isGame() && ui.hudfrag.shown);
        });
    }

    private static boolean isCannotTrace(String message){
        if(message == null) return false;
        String clean = Strings.stripColors(message).toLowerCase();
        return clean.contains("cannot trace") || clean.contains("нельзя отследить") || clean.contains("невозможно отследить");
    }

    /** Hides the "you cannot trace this player" replies to the automatic traces of admins. */
    private static void scrubCannotTrace(){
        if(ui == null || ui.chatfrag == null) return;
        try{
            if(chatMessagesField == null){
                chatMessagesField = mindustry.ui.fragments.ChatFragment.class.getDeclaredField("messages");
                chatMessagesField.setAccessible(true);
            }
            if(!(chatMessagesField.get(ui.chatfrag) instanceof Seq<?> msgs) || msgs.isEmpty()) return;
            // new messages are inserted at the start: only scan when the newest one changed
            if(msgs.size == lastChatSize && msgs.first() == lastChatFirst) return;
            msgs.removeAll(m -> {
                if(m instanceof String str) return isCannotTrace(str);
                try{
                    return m != null && m.getClass().getField("message").get(m) instanceof String str && isCannotTrace(str);
                }catch(Throwable ignored){
                    return false;
                }
            });
            lastChatSize = msgs.size;
            lastChatFirst = msgs.isEmpty() ? null : msgs.first();
        }catch(Throwable ignored){
        }
    }

    private void setupTraceOverride() {
        if (originalTraces == null) {
            originalTraces = ui.traces;
        }

        // Если ui.traces сбросился
        if (!(ui.traces instanceof CustomTraceDialog)) {
            ui.traces = new CustomTraceDialog();
            PlayerData.setAutoTraceRequested(autoTraceRequested);
            Log.info("SimpleAdminMode: TraceDialog перехвачен");
        }
    }

    private void syncPlayers() {
        IntSet currentIds = new IntSet();
        for (Player p : Groups.player) {
            currentIds.add(p.id);
            processPlayer(p);
        }

        // Новые игроки
        currentIds.each(id -> {
            if (!knownPlayerIds.contains(id)) {
                Player p = Groups.player.getByID(id);
                if (p != null) {
                    Log.info("SimpleAdminMode: Новый игрок ID=@ (name=@)", id, p.name);
                    ui.showInfoFade("[green]+ [white]" + p.name);
                }
            }
        });

        // Вышедшие игроки
        knownPlayerIds.each(id -> {
            if (!currentIds.contains(id)) {
                Log.info("SimpleAdminMode: Игрок вышел ID=@ ", id);
                PlayerData data = playerHistory.get(id);
                if (data != null) {
                    data.online = false;
                    data.stopTraceRequests();
                    ui.showInfoFade("[red]- [white]" + data.name);
                }
                adminBannerShown.remove(id);
            }
        });

        knownPlayerIds.clear();
        knownPlayerIds.addAll(currentIds);
    }

    private void processPlayer(Player p) {
        PlayerData data = playerHistory.get(p.id);

        if (data == null) {
            data = new PlayerData(p);
            playerHistory.put(p.id, data);
            if(p.admin){
                // admins cannot be traced: do not spam the server with requests that fail
                data.uuid = "admin?";
                notifyAdminDetected(p);
            }else{
                data.startTraceRequests(p);
            }
        } else {
            data.name = p.name;
            data.player = p;
            data.online = true;
            if(p.admin && (data.uuid.equals("Loading...") || data.uuid.equals("none"))){
                data.stopTraceRequests();
                data.uuid = "admin?";
                notifyAdminDetected(p);
            }
        }
    }

    public class CustomTraceDialog extends TraceDialog {
        @Override
        public void show(Player player, Administration.TraceInfo info) {
            if (!handleTraceLogic(player, info)) {
                originalTraces.show(player, info);
            }
        }

        public void show(Player player, Administration.TraceInfo info, boolean offline) {//фикс дял клиента
            if (!handleTraceLogic(player, info)) {
                try {
                    var method = originalTraces.getClass().getMethod("show", Player.class, Administration.TraceInfo.class, boolean.class);
                    method.invoke(originalTraces, player, info, offline);
                } catch (Exception e) {
                    originalTraces.show(player, info);
                }
            }
        }

        private boolean handleTraceLogic(Player player, Administration.TraceInfo info) {
            PlayerData data = playerHistory.get(player.id);
            boolean wasAuto = autoTraceRequested.contains(player.id);
            if (data != null && info.uuid != null && !info.uuid.isEmpty()) {
                data.updateFrom(info);
            }
            float lastTime = lastAutoTime.get(player.id, 0f);
            boolean isDuplicate = (Time.time - lastTime < 1.5f);
            if (wasAuto || isDuplicate) {
                lastAutoTime.put(player.id, Time.time);
                Log.info("📋 [accent]" + player.name + "[white]: " + (data != null && data.uuid.equals("admin?") ? "админ (локально)" : "данные получены"));
                return true;
            }
            return false;
        }
    }
}