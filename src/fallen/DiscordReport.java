package fallen;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.security.cert.*;
import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * GL: sends every ban from the ban menu to a Discord channel: a card with the ban and a text file with what the player
 * did in the last minutes. It goes through the gl-reports relay, which keeps the webhook, and every admin uses his own key.
 * Only bans on the servers from the list are sent.
 */
public final class DiscordReport{
    public static final String defaultServers = "80.66.89.54";
    /** The relay (gl-reports on the GL server) and the SHA-256 of its certificate. */
    private static final String relayHost = "2.26.10.69";
    private static final int relayPort = 7161;
    private static final String relayPin = "a64e71432b57370ae1fb63ac82d5e94f883caf823b537248b088fb184f641851";
    private static final int color = 0xE0453A, testColor = 0x7FD3FF;

    /** Address of the server the client is connected to, resolved to an IP. */
    private static volatile String serverIp = "";
    private static volatile int serverPort;

    private static volatile String pendingKey;
    private static volatile boolean registering, blocked;
    private static volatile long lastRegister;

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

    /** The admin's own key for the relay; the mod gets it by itself. */
    public static String key(){
        String key = Core.settings.getString("sam-discord-key", "").trim();
        return key.isEmpty() && pendingKey != null ? pendingKey : key;
    }

    /** Forgets the key, the mod asks for a new one on the next visit to the server. */
    public static void resetKey(){
        Core.settings.remove("sam-discord-key");
        pendingKey = null;
        blocked = false;
        lastRegister = 0;
    }

    public static boolean validKey(String key){
        return key.matches("glr_[A-Za-z0-9_-]{20,64}");
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
        if(!enabled() || !allowedServer()) return;

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
            + ",\"timestamp\":" + json(iso(now)) + "}";
        send(embed, fileName, text, false);
    }

    /** A sample report, sent from the settings on any server. */
    public static void test(){
        if(!validKey(key())){
            ui.showInfoFade(Core.bundle.get("sam.discord.badKey"));
            return;
        }
        StringBuilder fields = new StringBuilder();
        field(fields, Core.bundle.get("sam.discord.admin"), md(clean(player.name)), true);
        field(fields, Core.bundle.get("sam.discord.servers"), "`" + Core.settings.getString("sam-discord-servers", defaultServers) + "`", true);
        String embed = "{\"title\":" + json("✅ " + Core.bundle.get("sam.discord.testTitle"))
            + ",\"description\":" + json(Core.bundle.get("sam.discord.testText"))
            + ",\"color\":" + testColor + ",\"fields\":[" + fields + "]"
            + ",\"timestamp\":" + json(iso(System.currentTimeMillis())) + "}";
        send(embed, null, null, true);
    }

    /**
     * Sends the report to the relay on the GL server, which posts it to Discord: the webhook link is kept there
     * and never reaches the players. The relay certificate is pinned, like the GL Client chat does.
     */
    private static void send(String embed, String fileName, String fileText, boolean test){
        String body = "{\"server\":" + json(serverIp + ":" + serverPort) + ",\"test\":" + test + ",\"embed\":" + embed
            + (fileName != null ? ",\"file_name\":" + json(fileName) + ",\"file\":" + json(fileText) : "") + "}";

        Threads.daemon("gl-admin-report", () -> {
            // no key yet (the admin bans right after joining): get it first
            if(!validKey(key()) && !register()){
                Core.app.post(() -> ui.showInfoFade(Core.bundle.format("sam.discord.failed", Core.bundle.get("sam.discord.error.nokey"))));
                return;
            }
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            String code = code(exchange("{\"t\":\"report\",\"v\":1,\"key\":" + json(key()) + ",\"size\":" + data.length + "}", data));
            // the key was removed on the server: forget it, the mod asks for a new one (unless its install is blocked)
            if("badkey".equals(code)){
                pendingKey = null;
                Core.app.post(() -> Core.settings.remove("sam-discord-key"));
            }
            Core.app.post(() -> {
                if(code == null) ui.showInfoFade(Core.bundle.get(test ? "sam.discord.sent" : "sam.discord.banSent"));
                else ui.showInfoFade(Core.bundle.format("sam.discord.failed", Core.bundle.get("sam.discord.error." + code, code)));
            });
        });
    }

    /** Asks for a key every few minutes while the player is an admin on an allowed server and has no key. */
    public static void update(){
        if(!enabled() || validKey(key()) || blocked || registering || !player.admin || !allowedServer()) return;
        if(Time.timeSinceMillis(lastRegister) < 5 * 60 * 1000L) return;
        lastRegister = Time.millis();
        registering = true;
        Threads.daemon("gl-admin-register", () -> {
            try{
                if(register()) Core.app.post(() -> ui.showInfoFade(Core.bundle.get("sam.discord.gotKey")));
            }finally{
                registering = false;
            }
        });
    }

    /** Gets a key from the relay (blocking, call off the main thread). The owner gets a Discord message about it. */
    private static boolean register(){
        if(!player.admin || !allowedServer()) return false;
        String answer = exchange("{\"t\":\"register\",\"v\":1,\"install\":" + json(install()) + ",\"nick\":" + json(clean(player.name))
            + ",\"server\":" + json(serverIp + ":" + serverPort) + ",\"admin\":true}", null);
        String code = code(answer);
        if(code == null){
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"key\":\\s*\"(glr_[\\w-]+)\"").matcher(answer);
            if(m.find()){
                String key = m.group(1);
                Core.app.post(() -> Core.settings.put("sam-discord-key", key));
                // the settings are written on the main thread, keep the key for the report that waits for it
                pendingKey = key;
                return true;
            }
            return false;
        }
        if(code.equals("blocked")) blocked = true;
        Log.warn("[GL Admin] report key refused: @", code);
        return false;
    }

    /** A random id of this install, so asking again gives the same admin a new key instead of a second one. */
    private static String install(){
        String id = Core.settings.getString("sam-discord-install", "");
        if(!id.matches("[0-9a-f]{32,64}")){
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            id = hex(bytes);
            Core.settings.put("sam-discord-install", id);
        }
        return id;
    }

    /** null when the answer is {"ok":true}, else its error code. */
    private static String code(String answer){
        if(answer == null) return "connect";
        if(answer.matches(".*\"ok\":\\s*true.*")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"code\":\\s*\"(\\w+)\"").matcher(answer);
        return m.find() ? m.group(1) : "noanswer";
    }

    /** One request to the relay over TLS with the pinned certificate: a header line and an optional body. Returns the answer line or null. */
    private static String exchange(String head, byte[] body){
        try{
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PinnedTrust()}, new SecureRandom());
            try(Socket raw = new Socket()){
                raw.connect(new InetSocketAddress(relayHost, relayPort), 10000);
                raw.setSoTimeout(30000);
                try(SSLSocket socket = (SSLSocket)ctx.getSocketFactory().createSocket(raw, relayHost, relayPort, true)){
                    socket.startHandshake();
                    OutputStream out = socket.getOutputStream();
                    out.write((head + "\n").getBytes(StandardCharsets.UTF_8));
                    if(body != null) out.write(body);
                    out.flush();
                    String answer = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    return answer == null ? "{\"ok\":false,\"code\":\"noanswer\"}" : answer;
                }
            }
        }catch(Throwable t){
            Log.err("[GL Admin] ban report", t);
            return null;
        }
    }

    private static String hex(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static class PinnedTrust implements X509TrustManager{
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException{
            throw new CertificateException("no client certificates");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException{
            try{
                if(chain == null || chain.length == 0 || !hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).equals(relayPin)){
                    throw new CertificateException("certificate pin mismatch");
                }
            }catch(NoSuchAlgorithmException e){
                throw new CertificateException(e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers(){
            return new X509Certificate[0];
        }
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
