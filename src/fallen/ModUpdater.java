package fallen;

import arc.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.mod.Mods.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;

import static mindustry.Vars.*;

/**
 * GL: checks the GitHub releases of the mod on start and offers to update. The game itself only finds updates of mods
 * listed in its mod browser and installed from there, so a mod imported from a file never showed one.
 */
public class ModUpdater{
    public static final String repo = "GLaziness/GL-AdminMode";

    public static void check(){
        if(!Core.settings.getBool("sam-update-check", true)) return;
        LoadedMod mod = mods.getMod("gl-admin-mode");
        if(mod == null || mod.meta.version == null) return;
        String current = mod.meta.version;

        Http.get("https://api.github.com/repos/" + repo + "/releases/latest", res -> {
            String latest = Jval.read(res.getResultAsString()).getString("tag_name", "");
            if(latest.startsWith("v")) latest = latest.substring(1);
            if(!newer(latest, current)) return;

            String version = latest;
            Core.app.post(() -> ui.showCustomConfirm(
                Core.bundle.get("sam.update.title"),
                Core.bundle.format("sam.update.text", version, current),
                Core.bundle.get("sam.update.yes"), Core.bundle.get("sam.update.later"),
                () -> update(mod), () -> {}
            ));
        }, e -> Log.warn("[GL Admin Mode] Update check failed: @", e.getMessage()));
    }

    /** Uses the game's own GitHub import, whose signature differs between game versions. */
    private static void update(LoadedMod mod){
        mod.setRepo(repo);
        ModsDialog dialog = ui.mods;
        try{
            for(Method m : ModsDialog.class.getMethods()){
                if(!m.getName().equals("githubImportMod")) continue;
                Class<?>[] p = m.getParameterTypes();
                if(p.length == 4 && p[2] == String.class && p[3] == boolean.class){ // v160: repo, isJava, release, forceEnable
                    m.invoke(dialog, repo, true, null, false);
                    return;
                }
            }
            for(Method m : ModsDialog.class.getMethods()){
                if(!m.getName().equals("githubImportMod")) continue;
                Class<?>[] p = m.getParameterTypes();
                if(p.length == 3 && p[2] == boolean.class){ // repo, isJava, forceEnable
                    m.invoke(dialog, repo, true, false);
                    return;
                }
                if(p.length == 2){ // v155: repo, isJava
                    m.invoke(dialog, repo, true);
                    return;
                }
            }
        }catch(Exception e){
            Log.err(e);
        }
        // could not start the import: open the release page instead
        Core.app.openURI("https://github.com/" + repo + "/releases/latest");
    }

    /** True when version a (like "2.10") is newer than b (like "2.9"). */
    static boolean newer(String a, String b){
        String[] x = a.split("\\."), y = b.split("\\.");
        for(int i = 0; i < Math.max(x.length, y.length); i++){
            int u = i < x.length ? Strings.parseInt(x[i].replaceAll("\\D", ""), 0) : 0;
            int v = i < y.length ? Strings.parseInt(y[i].replaceAll("\\D", ""), 0) : 0;
            if(u != v) return u > v;
        }
        return false;
    }
}
