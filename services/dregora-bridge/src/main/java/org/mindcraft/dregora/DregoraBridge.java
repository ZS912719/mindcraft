package org.mindcraft.dregora;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

@Mod(modid = "mindcraft_dregora_bridge", name = "Mindcraft Dregora Bridge", version = "0.1.0",
    clientSideOnly = true, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public final class DregoraBridge {
    private static final Gson GSON = new Gson();
    private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
    private final LinkedHashMap<String, JsonObject> results = new LinkedHashMap<>();
    private volatile JsonObject state = disconnected();
    private volatile String session = UUID.randomUUID().toString();
    private HttpServer server;
    private Object previousWorld;
    private Object previousPlayer;
    private int previousDimension;
    private int controlTicks;
    private int snapshotTicks;
    private boolean releaseRequested;
    private String token;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws IOException {
        token = System.getenv("MINDCRAFT_BRIDGE_TOKEN");
        if (token == null || token.length() < 32) return;
        int port = Integer.parseInt(System.getProperty("mindcraft.bridge.port", "9091"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), task -> {
                Thread thread = new Thread(task, "mindcraft-bridge-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/v1/", this::handle);
        MinecraftForge.EVENT_BUS.register(this);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); executor.shutdownNow(); }));
    }

    private static JsonObject disconnected() {
        JsonObject out = new JsonObject();
        out.addProperty("protocol", 1);
        out.addProperty("connected", false);
        out.addProperty("timestamp", System.currentTimeMillis());
        return out;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, error("unauthorized"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/v1/state")) reply(exchange, 200, state);
            else if (method.equals("GET") && path.startsWith("/v1/actions/")) {
                synchronized (this) {
                    JsonObject result = results.get(path.substring("/v1/actions/".length()));
                    reply(exchange, result == null ? 404 : 200, result == null ? error("action_not_found") : result);
                }
            } else if (method.equals("POST") && path.equals("/v1/actions")) {
                if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                    reply(exchange, 403, error("browser_origin_not_allowed"));
                    return;
                }
                JsonObject action = new JsonParser().parse(readBody(exchange)).getAsJsonObject();
                validate(action);
                synchronized (this) {
                    String id = action.get("id").getAsString();
                    JsonObject existing = results.get(id);
                    if (existing != null) { reply(exchange, 200, existing); return; }
                    if (!action.get("session").getAsString().equals(session) || !connected()) {
                        reply(exchange, 409, error("stale_session"));
                        return;
                    }
                    if (action.get("type").getAsString().equals("stop")) {
                        cancelQueue("cancelled_by_stop");
                        releaseRequested = true;
                    }
                    if (queue.size() >= 32) { reply(exchange, 429, error("queue_full")); return; }
                    while (results.size() >= 256) {
                        String oldest = results.keySet().iterator().next();
                        if (results.get(oldest).get("status").getAsString().equals("pending")) {
                            reply(exchange, 429, error("results_full")); return;
                        }
                        results.remove(oldest);
                    }
                    action.addProperty("deadline", System.currentTimeMillis() + 2000);
                    queue.add(action);
                    JsonObject result = result(id, "pending", "queued");
                    results.put(id, result);
                    reply(exchange, 202, result);
                }
            } else if (method.equals("GET") && path.equals("/v1/catalog")) {
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getQuery();
                if (raw != null) for (String part : raw.split("&")) {
                    String[] pair = part.split("=", 2);
                    if (pair.length == 2) query.put(pair[0], pair[1]);
                }
                String kind = query.getOrDefault("kind", "items");
                if (!Arrays.asList("items", "blocks", "recipes").contains(kind)) throw new IllegalArgumentException("invalid_catalog");
                int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
                int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
                if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid_page");
                if (!connected()) { reply(exchange, 409, error("not_connected")); return; }
                reply(exchange, 200, Minecraft.getMinecraft().addScheduledTask(() -> GameState.catalog(kind, offset, limit)).get(2, TimeUnit.SECONDS));
            } else reply(exchange, 404, error("not_found"));
        } catch (TimeoutException exception) {
            reply(exchange, 503, error("game_thread_timeout"));
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException exception) {
            reply(exchange, 400, error("invalid_request"));
        } catch (Exception exception) {
            reply(exchange, 500, error("bridge_error"));
        } finally { exchange.close(); }
    }

    private boolean connected() {
        return state.has("connected") && state.get("connected").getAsBoolean()
            && System.currentTimeMillis() - state.get("timestamp").getAsLong() < 1500;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = exchange.getRequestBody().read(buffer)) != -1) {
            if (bytes.size() + count > 8192) throw new IllegalArgumentException("body_too_large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    static void validate(JsonObject action) {
        for (String key : new String[] {"id", "session", "type"}) {
            if (!action.has(key) || !action.get(key).isJsonPrimitive() || !action.getAsJsonPrimitive(key).isString())
                throw new IllegalArgumentException("invalid_action");
        }
        if (!action.get("id").getAsString().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid_action");
        UUID.fromString(action.get("session").getAsString());
        String type = action.get("type").getAsString();
        if (!Arrays.asList("stop", "move", "look", "select_slot", "attack", "use_item", "interact_block").contains(type))
            throw new IllegalArgumentException("unsupported_action");
        JsonObject args = action.has("args") ? action.getAsJsonObject("args") : new JsonObject();
        Set<String> allowed = new HashSet<>();
        if (type.equals("move")) {
            allowed.addAll(Arrays.asList("forward", "back", "left", "right", "jump", "sneak", "sprint", "ticks"));
            integer(args, "ticks", 1, 20);
            for (String key : allowed) if (!key.equals("ticks") && args.has(key)
                && (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isBoolean()))
                throw new IllegalArgumentException("invalid_control");
        } else if (type.equals("look")) {
            allowed.addAll(Arrays.asList("yaw", "pitch"));
            finite(args, "yaw", -360, 360); finite(args, "pitch", -90, 90);
        } else if (type.equals("select_slot")) { allowed.add("slot"); integer(args, "slot", 0, 8); }
        else if (type.equals("attack")) {
            allowed.addAll(Arrays.asList("entityId", "uuid"));
            integer(args, "entityId", 0, Integer.MAX_VALUE);
            UUID.fromString(args.get("uuid").getAsString());
        } else if (type.equals("use_item")) {
            allowed.addAll(Arrays.asList("hand", "ticks")); integer(args, "ticks", 1, 100);
            if (!args.has("hand") || !Arrays.asList("main", "off").contains(args.get("hand").getAsString()))
                throw new IllegalArgumentException("invalid_hand");
        }
        for (Map.Entry<String, JsonElement> field : args.entrySet()) if (!allowed.contains(field.getKey())) throw new IllegalArgumentException("unknown_argument");
        for (Map.Entry<String, JsonElement> field : action.entrySet()) if (!Arrays.asList("id", "session", "type", "args").contains(field.getKey()))
            throw new IllegalArgumentException("unknown_field");
    }

    private static double finite(JsonObject args, String key, double min, double max) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("invalid_number");
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("invalid_number");
        return value;
    }

    private static void integer(JsonObject args, String key, int min, int max) {
        double value = finite(args, key, min, max);
        if (value != Math.floor(value)) throw new IllegalArgumentException("invalid_integer");
    }

    @SubscribeEvent
    public synchronized void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        int dimension = mc.player == null ? 0 : mc.player.dimension;
        if (mc.world != previousWorld || mc.player != previousPlayer || dimension != previousDimension) {
            cancelQueue("session_changed");
            release(mc);
            session = UUID.randomUUID().toString();
            previousWorld = mc.world; previousPlayer = mc.player; previousDimension = dimension;
            snapshotTicks = 5;
        }
        if (releaseRequested || mc.player == null || !mc.player.isEntityAlive() || mc.currentScreen != null || mc.isGamePaused()) {
            release(mc); releaseRequested = false;
        } else if (controlTicks > 0 && --controlTicks == 0) release(mc);
        while (!queue.isEmpty()) {
            JsonObject action = queue.remove();
            String id = action.get("id").getAsString();
            String reason;
            if (!action.get("session").getAsString().equals(session)) reason = "stale_session";
            else if (System.currentTimeMillis() > action.get("deadline").getAsLong()) reason = "expired";
            else reason = execute(mc, action);
            results.put(id, result(id, reason.equals("dispatched") || reason.equals("stopped") ? "completed" : "rejected", reason));
        }
        if (++snapshotTicks >= 5) {
            state = GameState.snapshot(mc, session);
            snapshotTicks = 0;
        }
    }

    private String execute(Minecraft mc, JsonObject action) {
        String type = action.get("type").getAsString();
        JsonObject args = action.has("args") ? action.getAsJsonObject("args") : new JsonObject();
        if (type.equals("stop")) { release(mc); return "stopped"; }
        if (mc.player == null || mc.world == null || !mc.player.isEntityAlive()) return "not_alive";
        if (mc.currentScreen != null || mc.isGamePaused()) return "game_not_controllable";
        try {
            if (type.equals("move")) {
                release(mc);
                String[] names = {"forward", "back", "left", "right", "jump", "sneak", "sprint"};
                KeyBinding[] keys = {mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack, mc.gameSettings.keyBindLeft,
                    mc.gameSettings.keyBindRight, mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindSprint};
                for (int i = 0; i < names.length; i++) KeyBinding.setKeyBindState(keys[i].getKeyCode(), args.has(names[i]) && args.get(names[i]).getAsBoolean());
                controlTicks = args.get("ticks").getAsInt();
            } else if (type.equals("look")) {
                mc.player.rotationYaw = args.get("yaw").getAsFloat();
                mc.player.rotationPitch = args.get("pitch").getAsFloat();
            } else if (type.equals("select_slot")) mc.player.inventory.currentItem = args.get("slot").getAsInt();
            else if (type.equals("attack")) {
                Entity target = mc.world.getEntityByID(args.get("entityId").getAsInt());
                if (!(target instanceof EntityLivingBase) || target == mc.player || !target.isEntityAlive()
                    || !target.getUniqueID().toString().equals(args.get("uuid").getAsString())) return "invalid_target";
                if (mc.player.getDistance(target) > 3 || !mc.player.canEntityBeSeen(target)) return "target_unreachable";
                if (mc.player.getCooledAttackStrength(0) < 1) return "attack_cooldown";
                mc.playerController.attackEntity(mc.player, target);
                mc.player.swingArm(EnumHand.MAIN_HAND);
            } else if (type.equals("use_item")) {
                release(mc);
                EnumHand hand = args.get("hand").getAsString().equals("off") ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
                if (mc.player.getHeldItem(hand).isEmpty()) return "empty_hand";
                if (mc.playerController.processRightClick(mc.player, mc.world, hand) == net.minecraft.util.EnumActionResult.FAIL)
                    return "interaction_failed";
                // Minecraft cancels active item use unless the use key remains held.
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                controlTicks = args.get("ticks").getAsInt();
            } else if (type.equals("interact_block")) {
                RayTraceResult hit = mc.objectMouseOver;
                if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return "no_block_target";
                if (mc.player.getPositionEyes(1).distanceTo(hit.hitVec) > mc.playerController.getBlockReachDistance()) return "target_unreachable";
                if (mc.playerController.processRightClickBlock(mc.player, mc.world, hit.getBlockPos(), hit.sideHit, hit.hitVec, EnumHand.MAIN_HAND)
                    == net.minecraft.util.EnumActionResult.FAIL) return "interaction_failed";
            }
            return "dispatched";
        } catch (Exception exception) { release(mc); return "action_failed"; }
    }

    private void release(Minecraft mc) {
        for (KeyBinding key : new KeyBinding[] {mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
            mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight, mc.gameSettings.keyBindJump,
            mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindSprint,
            mc.gameSettings.keyBindUseItem}) KeyBinding.setKeyBindState(key.getKeyCode(), false);
        if (mc.player != null && mc.playerController != null && mc.player.isHandActive()) mc.playerController.onStoppedUsingItem(mc.player);
        controlTicks = 0;
    }

    private void cancelQueue(String reason) {
        while (!queue.isEmpty()) {
            JsonObject action = queue.remove();
            results.put(action.get("id").getAsString(), result(action.get("id").getAsString(), "rejected", reason));
        }
    }

    private static JsonObject result(String id, String status, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id); result.addProperty("status", status); result.addProperty("reason", reason);
        result.addProperty("effectVerified", false);
        return result;
    }

    private static JsonObject error(String reason) {
        JsonObject result = new JsonObject(); result.addProperty("error", reason); return result;
    }

    private static void reply(HttpExchange exchange, int code, JsonObject data) throws IOException {
        byte[] bytes = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
