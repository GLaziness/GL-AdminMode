package fallen;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * GL: sends every ban from the ban menu to a Discord channel through a webhook: an embed with the ban
 * and a text file with what the player did in the last minutes. Only bans on the servers from the list are sent.
 */
public final class DiscordReport{
    public static final String defaultServers = "80.66.89.54";
    private static final int color = 0xE0453A, testColor = 0x7FD3FF;

    /** Address of the server the client is connected to, resolved to an IP. */
    private static volatile String serverIp = "";
    private static volatile int serverPort;

    private DiscordReport(){}

    public static void init(){
        Events.on(ClientServerConnectEvent.class, e -> {
            serverIp = "";
            serverPort = e.port;
            String host = e.ip;
            Threads.daemon("gl-admin-resolve", () -> {
                try{
                    serverIp = InetAddress.getByName(host).getHostAddress();
                }catch(Throwable t){
                    serverIp = host;
                }
            });
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool("sam-discord", true);
    }

    public static String webhook(){
        return Core.settings.getString("sam-discord-webhook", "").trim();
    }

    public static boolean validWebhook(String url){
        return url.matches("https://(ptb\\.|canary\\.)?discord(app)?\\.com/api/webhooks/\\d+/[\\w-]+");
    }

    /** Whether bans on the current server go to Discord. */
    public static boolean allowedServer(){
        if(!net.client() || serverIp.isEmpty()) return false;
        for(String s : Core.settings.getString("sam-discord-servers", defaultServers).split("[,\\s]+")){
            if(!s.isEmpty() && (s.equals(serverIp) || s.equals(serverIp + ":" + serverPort))) return true;
        }
        return false;
    }

    /**
     * Reports a ban. Called right after the /ban command.
     * @param reasonText rule text or the custom reason, shown in the embed
     */
    public static void ban(PlayerData data, int playerId, String name, String uuid, String length, String reason, String reasonText, String scope, boolean auto){
        if(!enabled() || !validWebhook(webhook()) || !allowedServer()) return;

        long now = System.currentTimeMillis();
        String nick = clean(name);
        Seq<BanEvidenceLogger.Evt> recent = BanEvidenceLogger.gather(playerId, name, BanEvidenceLogger.window);
        Seq<BanEvidenceLogger.Evt> all = BanEvidenceLogger.gather(playerId, name, BanEvidenceLogger.buffer);

        StringBuilder fields = new StringBuilder();
        field(fields, "👤 " + Core.bundle.get("sam.discord.player"), "**" + md(nick) + "**\nUUID: `" + uuid + "`", false);
        field(fields, "🛡️ " + Core.bundle.get("sam.discord.admin"), md(clean(player.name)) + (auto ? " " + Core.bundle.get("sam.discord.auto") : ""), true);
        field(fields, "🌐 " + Core.bundle.get("sam.discord.scope"), scopeText(scope), true);
        field(fields, "📜 " + Core.bundle.get("sam.discord.reason"), md(clean(reasonText)), false);
        field(fields, "⏳ " + Core.bundle.get("sam.discord.length"), BanKickMessages.term(length), true);
        long until = expires(length, now);
        field(fields, "📅 " + Core.bundle.get("sam.discord.expires"), until < 0 ? Core.bundle.get("sam.discord.never") : "<t:" + until / 1000 + ":f>\n<t:" + until / 1000 + ":R>", true);
        field(fields, "🗺️ " + Core.bundle.get("sam.discord.map"), md(clean(state.map.name())) + "\n" + Core.bundle.format("sam.discord.wave", state.wave), true);

        field(fields, "📊 " + Core.bundle.get("sam.discord.lastMinute"), Core.bundle.format("sam.discord.counts",
            count(recent, BanEvidenceLogger.Kind.building), count(recent, BanEvidenceLogger.Kind.breaking), count(recent, BanEvidenceLogger.Kind.chat)), false);
        if(data != null){
            field(fields, "📈 " + Core.bundle.get("sam.discord.session"), Core.bundle.format("sam.discord.sessionStats",
                data.builds, data.breaks, data.configs, data.timesJoined, data.timesKicked), false);
        }

        Seq<String> chat = all.select(e -> e.kind == BanEvidenceLogger.Kind.chat).map(e -> e.detail);
        if(chat.any()){
            StringBuilder lines = new StringBuilder();
            for(int i = Math.max(0, chat.size - 6); i < chat.size; i++){
                lines.append(cut(chat.get(i).replace("`", "'"), 150)).append('\n');
            }
            field(fields, "💬 " + Core.bundle.get("sam.discord.chat"), "```\n" + cut(lines.toString(), 1000) + "```", false);
        }
        if(Core.settings.getBool("sam-ban-rollback", true)){
            field(fields, "↩️ " + Core.bundle.get("sam.discord.rollback"), Core.bundle.get("sam.discord.rollbackSent"), false);
        }

        String fileName = "ban-" + nick.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now)) + ".txt";
        String text = BanEvidenceLogger.format(all, name, uuid, length, reason, now, Core.bundle.get("sam.discord.fileSpan"));

        String embed = "{\"title\":" + json("🔨 " + Core.bundle.format("sam.discord.title", nick))
            + ",\"color\":" + color
            + ",\"fields\":[" + fields + "]"
            + ",\"footer\":{\"text\":" + json("GL Admin Mode · " + serverIp + ":" + serverPort) + "}"
            + ",\"timestamp\":" + json(iso(now)) + "}";
        send(webhook(), embed, fileName, text, false);
    }

    /** A sample report, sent from the settings on any server. */
    public static void test(){
        String url = webhook();
        if(!validWebhook(url)){
            ui.showInfoFade(Core.bundle.get("sam.discord.badUrl"));
            return;
        }
        StringBuilder fields = new StringBuilder();
        field(fields, Core.bundle.get("sam.discord.admin"), md(clean(player.name)), true);
        field(fields, Core.bundle.get("sam.discord.servers"), "`" + Core.settings.getString("sam-discord-servers", defaultServers) + "`", true);
        String embed = "{\"title\":" + json("✅ " + Core.bundle.get("sam.discord.testTitle"))
            + ",\"description\":" + json(Core.bundle.get("sam.discord.testText"))
            + ",\"color\":" + testColor + ",\"fields\":[" + fields + "]"
            + ",\"footer\":{\"text\":\"GL Admin Mode\"},\"timestamp\":" + json(iso(System.currentTimeMillis())) + "}";
        send(url, embed, null, null, true);
    }

    /** Posts the embed (and the file) as multipart/form-data on a background thread. */
    private static void send(String url, String embed, String fileName, String fileText, boolean notifySuccess){
        String payload = "{\"username\":\"GL Admin Mode\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[" + embed + "]"
            + (fileName != null ? ",\"attachments\":[{\"id\":0,\"filename\":" + json(fileName) + "}]" : "") + "}";

        Threads.daemon("gl-admin-discord", () -> {
            try{
                String boundary = "----gladmin" + Long.toHexString(System.nanoTime());
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                part(body, boundary, "payload_json", null, "application/json", payload.getBytes(StandardCharsets.UTF_8));
                if(fileName != null){
                    part(body, boundary, "files[0]", fileName, "text/plain; charset=utf-8", fileText.getBytes(StandardCharsets.UTF_8));
                }
                body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

                HttpURLConnection con = (HttpURLConnection)new URL(url + "?wait=true").openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(10000);
                con.setReadTimeout(15000);
                con.setRequestProperty("User-Agent", "GL-AdminMode");
                con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try(OutputStream out = con.getOutputStream()){
                    body.writeTo(out);
                }
                int code = con.getResponseCode();
                if(code >= 200 && code < 300){
                    if(notifySuccess) Core.app.post(() -> ui.showInfoFade(Core.bundle.get("sam.discord.sent")));
                    else Core.app.post(() -> ui.showInfoFade(Core.bundle.get("sam.discord.banSent")));
                }else{
                    String error = "";
                    try(InputStream in = con.getErrorStream()){
                        if(in != null) error = new String(readAll(in), StandardCharsets.UTF_8);
                    }
                    Log.err("[GL Admin] Discord webhook: @ @", code, error);
                    int c = code;
                    Core.app.post(() -> ui.showInfoFade(Core.bundle.format("sam.discord.failed", c)));
                }
                con.disconnect();
            }catch(Throwable t){
                Log.err("[GL Admin] Discord webhook", t);
                Core.app.post(() -> ui.showInfoFade(Core.bundle.format("sam.discord.failed", t.getClass().getSimpleName())));
            }
        });
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String fileName, String type, byte[] data) throws IOException{
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
            + (fileName != null ? "; filename=\"" + fileName.replace("\"", "_") + "\"" : "")
            + "\r\nContent-Type: " + type + "\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws IOException{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for(int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Unix time in milliseconds when the ban ends, -1 for a permanent one. Same units as /ban: a bare number is minutes. */
    static long expires(String length, long now){
        if(length.equals("perm")) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([shdwmy]?)").matcher(length);
        if(!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        long unit;
        switch(m.group(2)){
            case "s": unit = 1000L; break;
            case "h": unit = 3_600_000L; break;
            case "d": unit = 86_400_000L; break;
            case "w": unit = 7 * 86_400_000L; break;
            case "m": unit = 30 * 86_400_000L; break;
            case "y": unit = 365 * 86_400_000L; break;
            default: unit = 60_000L;
        }
        return now + n * unit;
    }

    private static String scopeText(String scope){
        String key = "sam.discord.scope." + scope;
        return Core.bundle.has(key) ? Core.bundle.get(key) : Strings.capitalize(scope);
    }

    private static int count(Seq<BanEvidenceLogger.Evt> list, BanEvidenceLogger.Kind kind){
        return list.count(e -> e.kind == kind);
    }

    private static String clean(String s){
        return s == null ? "?" : Strings.stripColors(s).trim();
    }

    /** Escapes the Discord markdown, so nicks like *x* or _x_ show as they are. */
    private static String md(String s){
        return s.replaceAll("([\\\\*_~`|>\\[\\]()])", "\\\\$1").replace("@", "@​");
    }

    private static void field(StringBuilder sb, String name, String value, boolean inline){
        if(sb.length() > 0) sb.append(',');
        sb.append("{\"name\":").append(json(cut(name, 250)))
            .append(",\"value\":").append(json(cut(value.isEmpty() ? "—" : value, 1024)))
            .append(",\"inline\":").append(inline).append('}');
    }

    private static String cut(String s, int max){
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String iso(long time){
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(time));
    }

    static String json(String s){
        StringBuilder sb = new StringBuilder("\"");
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            switch(c){
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
                default:
                    if(c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
