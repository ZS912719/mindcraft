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
    private final Map<String, JsonObject> eligibility = new HashMap<>();
    private final Map<String, JsonObject> before = new HashMap<>();
    private final Map<String, JsonObject> verifying = new HashMap<>();
    private String activeAction;
    private final boolean npcBackend = "npc".equals(System.getenv("MINDCRAFT_BRIDGE_BACKEND"));

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
            if ((method.equals("GET") && path.equals("/v1/npcs"))
                || (method.equals("POST") && path.equals("/v1/npcs/commands"))) {
                // Explicit singleplayer test mode may inspect NPCs while using legacy fixture commands.
                if (!npcBackend && !"1".equals(System.getenv("MINDCRAFT_BRIDGE_TEST_MODE"))) {
                    reply(exchange, 409, error("npc_backend_required")); return;
                }
                if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                    reply(exchange, 403, error("browser_origin_not_allowed")); return;
                }
                JsonObject request = method.equals("POST") ? new JsonParser().parse(readBody(exchange)).getAsJsonObject() : null;
                if (request != null) org.mindcraft.dregora.npc.NpcService.validate(request);
                CompletableFuture<JsonObject> future = new CompletableFuture<>();
                long deadline = System.currentTimeMillis() + 2000;
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null || mc.getIntegratedServer() == null) {
                        future.completeExceptionally(new IllegalStateException("singleplayer_required")); return;
                    }
                    net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
                    UUID owner = mc.player.getUniqueID();
                    integrated.addScheduledTask(() -> {
                        try {
                            future.complete(request == null ? org.mindcraft.dregora.npc.NpcService.state(integrated, owner)
                                : org.mindcraft.dregora.npc.NpcService.execute(integrated, owner, request, deadline));
                        } catch (Exception exception) { future.completeExceptionally(exception); }
                    });
                });
                try { reply(exchange, 200, future.get(2, TimeUnit.SECONDS)); }
                catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException)
                        reply(exchange, 409, error(cause.getMessage()));
                    else throw exception;
                }
                return;
            }
            if (method.equals("GET") && path.equals("/v1/state")) reply(exchange, 200, state);
            else if (method.equals("GET") && path.equals("/v1/test/state")) {
                if (!"1".equals(System.getenv("MINDCRAFT_BRIDGE_TEST_MODE"))) {
                    reply(exchange, 403, error("test_mode_required")); return;
                }
                CompletableFuture<JsonObject> future = new CompletableFuture<>();
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null || mc.getIntegratedServer() == null) {
                        future.completeExceptionally(new IllegalStateException("singleplayer_required")); return;
                    }
                    net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
                    UUID playerId = mc.player.getUniqueID();
                    integrated.addScheduledTask(() -> {
                        try { future.complete(TestState.read(integrated, playerId)); }
                        catch (Exception exception) { future.completeExceptionally(exception); }
                    });
                });
                reply(exchange, 200, future.get(2, TimeUnit.SECONDS));
            }
            else if (method.equals("GET") && path.startsWith("/v1/actions/")) {
                synchronized (this) {
                    JsonObject result = results.get(path.substring("/v1/actions/".length()));
                    reply(exchange, result == null ? 404 : 200, result == null ? error("action_not_found") : result);
                }
            } else if (method.equals("POST") && path.equals("/v1/actions")) {
                if (npcBackend) { reply(exchange, 409, error("player_backend_disabled")); return; }
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
        if (!Arrays.asList("stop", "move", "look", "select_slot", "attack", "use_item", "interact_block", "equip_armor", "test_command", "test_lock", "test_advancement").contains(type))
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
        else if (type.equals("equip_armor")) { allowed.add("slot"); integer(args, "slot", 0, 35); }
        else if (type.equals("attack")) {
            allowed.addAll(Arrays.asList("entityId", "uuid"));
            integer(args, "entityId", 0, Integer.MAX_VALUE);
            UUID.fromString(args.get("uuid").getAsString());
        } else if (type.equals("use_item")) {
            allowed.addAll(Arrays.asList("hand", "ticks")); integer(args, "ticks", 1, 100);
            if (!args.has("hand") || !Arrays.asList("main", "off").contains(args.get("hand").getAsString()))
                throw new IllegalArgumentException("invalid_hand");
        } else if (type.equals("test_advancement")) {
            allowed.add("granted");
            if (!args.has("granted") || !args.get("granted").isJsonPrimitive() || !args.getAsJsonPrimitive("granted").isBoolean())
                throw new IllegalArgumentException("invalid_advancement_fixture");
        } else if (type.equals("test_lock")) {
            allowed.addAll(Arrays.asList("slot", "requirements")); integer(args, "slot", 0, 35);
            if (!args.has("requirements") || !args.get("requirements").isJsonArray()) throw new IllegalArgumentException("invalid_requirements");
            JsonArray requirements = args.getAsJsonArray("requirements");
            if (requirements.size() < 1 || requirements.size() > 8) throw new IllegalArgumentException("invalid_requirements");
            for (JsonElement requirement : requirements) if (!requirement.isJsonPrimitive() || !requirement.getAsJsonPrimitive().isString()
                || requirement.getAsString().length() > 256) throw new IllegalArgumentException("invalid_requirements");
        } else if (type.equals("test_command")) {
            allowed.add("command");
            if (!args.has("command") || !args.get("command").isJsonPrimitive()
                || !args.getAsJsonPrimitive("command").isString()) throw new IllegalArgumentException("invalid_command");
            String command = args.get("command").getAsString();
            if (command.length() > 2048 || command.contains("\n") || command.contains("\r")
                || !command.matches("(give|replaceitem|summon|effect|tp|kill|gamemode|time|weather|difficulty|gamerule|reskillable|fill|advancement|mindcraft_npc) .+"))
                throw new IllegalArgumentException("invalid_command");
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
        // NPC mode must never alter the human player's key bindings or camera.
        if (npcBackend) return;
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
            if (!reason.equals("server_pending")) {
                JsonObject outcome = result(id, reason.equals("dispatched") || reason.equals("stopped") ? "completed" : "rejected", reason);
                JsonObject gate = eligibility.remove(id);
                if (gate != null) outcome.add("eligibility", gate);
                JsonObject prior = before.remove(id);
                if (reason.equals("dispatched") && prior != null) {
                    outcome.addProperty("status", "pending"); outcome.addProperty("reason", "verifying_effect");
                    action.add("before", prior);
                    int ticks = action.get("type").getAsString().equals("use_item") ? action.getAsJsonObject("args").get("ticks").getAsInt() : 0;
                    action.addProperty("verifyAt", System.currentTimeMillis() + ticks * 50L + 1000);
                    verifying.put(id, action);
                }
                results.put(id, outcome);
                if (!outcome.get("status").getAsString().equals("pending") && id.equals(activeAction)) activeAction = null;
            }
        }
        for (String id : new ArrayList<>(verifying.keySet())) {
            JsonObject action = verifying.get(id);
            if (!action.get("session").getAsString().equals(session) || mc.player == null) {
                verifying.remove(id); results.put(id, result(id, "rejected", "session_changed")); continue;
            }
            if (System.currentTimeMillis() < action.get("verifyAt").getAsLong()) continue;
            verifying.remove(id);
            if (mc.getIntegratedServer() != null) {
                net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
                UUID playerId = mc.player.getUniqueID();
                integrated.addScheduledTask(() -> {
                    JsonObject after = null;
                    try {
                        net.minecraft.entity.player.EntityPlayerMP player = integrated.getPlayerList().getPlayerByUUID(playerId);
                        if (player != null) after = ActionObservation.read(player, action);
                    } catch (Exception ignored) {}
                    finishVerification(id, action, after);
                });
            } else finishVerification(id, action, ActionObservation.read(mc.player, action));
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
            String actionId = action.get("id").getAsString();
            if (Arrays.asList("attack", "use_item", "interact_block", "equip_armor").contains(type)) {
                if (activeAction != null && !activeAction.equals(actionId)) return "action_in_progress";
                activeAction = actionId;
            }
            if (Arrays.asList("attack", "use_item", "interact_block", "equip_armor").contains(type) && !eligibility.containsKey(actionId))
                return preflight(mc, action);
            if (eligibility.containsKey(actionId)) {
                JsonObject gate = eligibility.get(actionId);
                if (!gate.get("allowed").getAsBoolean()) return gate.get("status").getAsString().equals("unknown")
                    ? "requirements_unknown" : gate.get("status").getAsString().equals("changed")
                    ? "preflight_subject_changed" : "requirements_not_met";
                if (!gate.get("fingerprint").getAsString().equals(fingerprint(mc, action))) return "preflight_subject_changed";
            }
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
                return CombatState.attack(mc, target);
            } else if (type.equals("use_item")) {
                release(mc);
                EnumHand hand = args.get("hand").getAsString().equals("off") ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
                if (mc.player.getHeldItem(hand).isEmpty()) return "empty_hand";
                if (mc.playerController.processRightClick(mc.player, mc.world, hand) == net.minecraft.util.EnumActionResult.FAIL)
                    return "interaction_failed";
                // Minecraft cancels active item use unless the use key remains held.
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                controlTicks = args.get("ticks").getAsInt();
            } else if (type.equals("equip_armor")) {
                int slot = args.get("slot").getAsInt();
                net.minecraft.item.ItemStack stack = mc.player.inventory.getStackInSlot(slot);
                if (!(stack.getItem() instanceof net.minecraft.item.ItemArmor)) return "not_armor";
                net.minecraft.inventory.EntityEquipmentSlot armorSlot = ((net.minecraft.item.ItemArmor) stack.getItem()).armorType;
                if (!mc.player.getItemStackFromSlot(armorSlot).isEmpty()) return "armor_slot_occupied";
                if (mc.player.openContainer != mc.player.inventoryContainer || !mc.player.inventory.getItemStack().isEmpty())
                    return "inventory_not_ready";
                mc.playerController.windowClick(mc.player.inventoryContainer.windowId, slot < 9 ? slot + 36 : slot, 0,
                    net.minecraft.inventory.ClickType.QUICK_MOVE, mc.player);
            } else if (type.equals("test_command") || type.equals("test_lock") || type.equals("test_advancement")) {
                if (!"1".equals(System.getenv("MINDCRAFT_BRIDGE_TEST_MODE")) || mc.getIntegratedServer() == null)
                    return "test_mode_required";
                net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
                UUID playerId = mc.player.getUniqueID();
                String actionSession = session, id = action.get("id").getAsString();
                String command = type.equals("test_command") ? args.get("command").getAsString() : "give";
                integrated.addScheduledTask(() -> {
                    synchronized (DregoraBridge.this) {
                        if (!results.containsKey(id) || !results.get(id).get("status").getAsString().equals("pending")) return;
                    }
                    String outcome = "test_command_failed";
                    String diagnostic = null;
                    try {
                        net.minecraft.entity.player.EntityPlayerMP player = integrated.getPlayerList().getPlayerByUUID(playerId);
                        if (!session.equals(actionSession)) outcome = "stale_session";
                        else if (System.currentTimeMillis() > action.get("deadline").getAsLong()) outcome = "expired";
                        else if (player == null || !player.canUseCommand(2, command.split(" ")[0])) outcome = "cheats_required";
                        else if (type.equals("test_advancement")) {
                            Class<?> holderType = Class.forName("codersafterdark.reskillable.api.data.RequirementHolder");
                            net.minecraft.advancements.AdvancementList list = (net.minecraft.advancements.AdvancementList)
                                holderType.getMethod("getAdvancementList").invoke(null);
                            net.minecraft.util.ResourceLocation fixtureId = new net.minecraft.util.ResourceLocation("mindcraft", "test_requirements");
                            if (list.getAdvancement(fixtureId) == null) {
                                java.lang.reflect.Constructor<net.minecraft.advancements.Advancement.Builder> constructor =
                                    net.minecraft.advancements.Advancement.Builder.class.getDeclaredConstructor(net.minecraft.util.ResourceLocation.class,
                                        net.minecraft.advancements.DisplayInfo.class, net.minecraft.advancements.AdvancementRewards.class,
                                        Map.class, String[][].class);
                                constructor.setAccessible(true);
                                net.minecraft.advancements.Criterion criterion = new net.minecraft.advancements.Criterion(
                                    new net.minecraft.advancements.critereon.ImpossibleTrigger.Instance());
                                // AdvancementList removes resolved entries from the supplied map.
                                list.loadAdvancements(new HashMap<>(Collections.singletonMap(fixtureId, constructor.newInstance(null, null,
                                    net.minecraft.advancements.AdvancementRewards.EMPTY, Collections.singletonMap("manual", criterion),
                                    new String[][] {{"manual"}}))));
                            }
                            net.minecraft.advancements.Advancement advancement = list.getAdvancement(fixtureId);
                            if (args.get("granted").getAsBoolean()) player.getAdvancements().grantCriterion(advancement, "manual");
                            else player.getAdvancements().revokeCriterion(advancement, "manual");
                            outcome = "dispatched";
                        } else if (type.equals("test_lock")) {
                            net.minecraft.item.ItemStack fixture = player.inventory.getStackInSlot(args.get("slot").getAsInt()).copy();
                            if (!fixture.hasTagCompound() || !fixture.getTagCompound().hasKey("MindcraftRequirementFixture"))
                                outcome = "fixture_marker_required";
                            else {
                                Class<?> holderType = Class.forName("codersafterdark.reskillable.api.data.RequirementHolder");
                                JsonArray requirements = args.getAsJsonArray("requirements");
                                String[] expressions = new String[requirements.size()];
                                for (int i = 0; i < expressions.length; i++) expressions[i] = requirements.get(i).getAsString();
                                Object holder = holderType.getMethod("fromStringList", String[].class).invoke(null, (Object) expressions);
                                if ((Boolean) holderType.getMethod("hasNone").invoke(holder)) outcome = "invalid_fixture_requirements";
                                else {
                                    Class<?> keyType = Class.forName("codersafterdark.reskillable.api.data.LockKey");
                                    net.minecraft.nbt.NBTTagCompound marker = new net.minecraft.nbt.NBTTagCompound();
                                    marker.setTag("MindcraftRequirementFixture", fixture.getTagCompound().getTag("MindcraftRequirementFixture").copy());
                                    Object key = Class.forName("codersafterdark.reskillable.api.data.GenericNBTLockKey")
                                        .getConstructor(net.minecraft.nbt.NBTTagCompound.class).newInstance(marker);
                                    Class.forName("codersafterdark.reskillable.base.LevelLockHandler")
                                        .getMethod("addLockByKey", keyType, holderType).invoke(null, key, holder);
                                    outcome = "dispatched";
                                }
                            }
                        } else if (integrated.getCommandManager().executeCommand(player, command) > 0) outcome = "dispatched";
                    } catch (Exception exception) { diagnostic = exception.getClass().getSimpleName() + ": " + exception.getMessage(); }
                    synchronized (DregoraBridge.this) {
                        if (!session.equals(actionSession)) outcome = "stale_session";
                        if (!results.containsKey(id) || !results.get(id).get("status").getAsString().equals("pending")) return;
                        JsonObject outcomeResult = result(id, outcome.equals("dispatched") ? "completed" : "rejected", outcome);
                        if (diagnostic != null) outcomeResult.addProperty("diagnostic", diagnostic);
                        results.put(id, outcomeResult);
                    }
                });
                return "server_pending";
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

    private String fingerprint(Minecraft mc, JsonObject action) {
        String type = action.get("type").getAsString();
        JsonObject args = action.has("args") ? action.getAsJsonObject("args") : new JsonObject();
        net.minecraft.item.ItemStack stack = type.equals("equip_armor") ? mc.player.inventory.getStackInSlot(args.get("slot").getAsInt())
            : mc.player.getHeldItem(type.equals("use_item") && args.get("hand").getAsString().equals("off") ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND);
        String value = stack.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString();
        if (type.equals("interact_block")) {
            RayTraceResult hit = mc.objectMouseOver;
            if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return value + "no_block";
            value += hit.getBlockPos().toString() + hit.sideHit + Requirements.identity(Requirements.blockStack(mc.world, hit.getBlockPos()));
            if (stack.isEmpty()) value += mc.player.getHeldItemOffhand().writeToNBT(new net.minecraft.nbt.NBTTagCompound());
        }
        return value;
    }

    private String preflight(Minecraft mc, JsonObject action) {
        String type = action.get("type").getAsString(), id = action.get("id").getAsString();
        JsonObject args = action.has("args") ? action.getAsJsonObject("args") : new JsonObject();
        net.minecraft.item.ItemStack stack = (type.equals("equip_armor") ? mc.player.inventory.getStackInSlot(args.get("slot").getAsInt())
            : mc.player.getHeldItem(type.equals("use_item") && args.get("hand").getAsString().equals("off") ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND)).copy();
        net.minecraft.item.ItemStack offhand = mc.player.getHeldItemOffhand().copy();
        final int sourceSlot = type.equals("equip_armor") ? args.get("slot").getAsInt()
            : type.equals("use_item") && args.get("hand").getAsString().equals("off") ? 40 : mc.player.inventory.currentItem;
        net.minecraft.util.math.BlockPos blockPos = null;
        if (type.equals("interact_block")) {
            RayTraceResult hit = mc.objectMouseOver;
            if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return "no_block_target";
            blockPos = hit.getBlockPos();
            JsonArray coordinates = new JsonArray(); coordinates.add(blockPos.getX()); coordinates.add(blockPos.getY()); coordinates.add(blockPos.getZ());
            action.add("blockPos", coordinates);
        }
        final net.minecraft.util.math.BlockPos pos = blockPos;
        final String signature = fingerprint(mc, action);
        if (type.equals("equip_armor") && stack.getItem() instanceof net.minecraft.item.ItemArmor) {
            action.addProperty("expectedArmorSlot", ((net.minecraft.item.ItemArmor) stack.getItem()).armorType.getName());
            action.addProperty("expectedArmor", stack.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString());
        }
        if (mc.getIntegratedServer() == null) {
            JsonObject gate = check(mc.player, stack, offhand, pos); gate.addProperty("fingerprint", signature);
            eligibility.put(id, gate); before.put(id, ActionObservation.read(mc.player, action));
            return execute(mc, action);
        }
        net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
        UUID playerId = mc.player.getUniqueID();
        final int dimension = mc.player.dimension;
        integrated.addScheduledTask(() -> {
            JsonObject gate = new JsonObject(), observation = null;
            try {
                net.minecraft.entity.player.EntityPlayerMP player = integrated.getPlayerList().getPlayerByUUID(playerId);
                if (player == null || player.dimension != dimension) throw new IllegalStateException("player_changed");
                net.minecraft.item.ItemStack authoritative = player.inventory.getStackInSlot(sourceSlot).copy();
                net.minecraft.item.ItemStack authoritativeOffhand = player.getHeldItemOffhand().copy();
                if (type.equals("equip_armor") && action.has("expectedArmor"))
                    action.addProperty("expectedArmor", authoritative.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString());
                gate = check(player, authoritative, authoritativeOffhand, pos);
                if (!net.minecraft.item.ItemStack.areItemStacksEqual(stack, authoritative)
                    || (pos != null && stack.isEmpty() && !net.minecraft.item.ItemStack.areItemStacksEqual(offhand, authoritativeOffhand))) {
                    gate.addProperty("allowed", false); gate.addProperty("status", "changed");
                }
                observation = ActionObservation.read(player, action);
            } catch (Exception exception) {
                gate.addProperty("allowed", false); gate.addProperty("status", "unknown");
            }
            gate.addProperty("fingerprint", signature);
            synchronized (DregoraBridge.this) {
                if (!session.equals(action.get("session").getAsString()) || !results.containsKey(id)
                    || !results.get(id).get("status").getAsString().equals("pending")) return;
                eligibility.put(id, gate);
                if (observation != null) before.put(id, observation);
                queue.add(action);
            }
        });
        return "server_pending";
    }

    private static JsonObject check(net.minecraft.entity.player.EntityPlayer player, net.minecraft.item.ItemStack stack,
        net.minecraft.item.ItemStack offhand, net.minecraft.util.math.BlockPos pos) {
        JsonObject gate = new JsonObject(); JsonArray subjects = new JsonArray();
        JsonObject item = Requirements.read(player, stack, true); item.addProperty("role", "item"); subjects.add(item);
        if (pos != null) {
            if (stack.isEmpty()) {
                JsonObject other = Requirements.read(player, offhand, true); other.addProperty("role", "offhand_fallback"); subjects.add(other);
            }
            JsonObject block = Requirements.read(player, Requirements.blockStack(player.world, pos), true);
            block.addProperty("role", "block"); subjects.add(block);
        }
        boolean allowed = true, unknown = false;
        for (JsonElement subject : subjects) {
            allowed &= subject.getAsJsonObject().get("allowed").getAsBoolean();
            unknown |= subject.getAsJsonObject().get("status").getAsString().equals("unknown");
        }
        gate.add("subjects", subjects); gate.addProperty("allowed", allowed);
        gate.addProperty("status", unknown ? "unknown" : "available"); return gate;
    }

    private synchronized void finishVerification(String id, JsonObject action, JsonObject after) {
        JsonObject outcome = results.get(id);
        if (outcome == null || !outcome.get("status").getAsString().equals("pending")) return;
        if (id.equals(activeAction)) activeAction = null;
        if (!session.equals(action.get("session").getAsString())) { results.put(id, result(id, "rejected", "session_changed")); return; }
        outcome.addProperty("status", "completed"); outcome.addProperty("reason", "dispatched");
        if (after != null && after.get("dimension").equals(action.getAsJsonObject("before").get("dimension"))) {
            JsonObject verification = ActionObservation.compare(action.getAsJsonObject("before"), after, action.get("type").getAsString());
            outcome.add("verification", verification); outcome.add("effectVerified", verification.get("effectVerified"));
        } else {
            JsonObject verification = new JsonObject(); verification.addProperty("status", "unavailable");
            outcome.add("verification", verification);
        }
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
        for (Map.Entry<String, JsonObject> entry : results.entrySet())
            if (entry.getValue().get("status").getAsString().equals("pending"))
                entry.setValue(result(entry.getKey(), "rejected", reason));
        eligibility.clear(); before.clear(); verifying.clear(); activeAction = null;
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
