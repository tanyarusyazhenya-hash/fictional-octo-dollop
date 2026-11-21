package com.example.basefinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BaseFinder (клиентский мод).
 *
 * Ищет загруженные чанки с сундуками, бочками, шалкерами и жителями, запоминает
 * найденные базы в файл (по каждому миру отдельно), умеет ставить метки с именами,
 * показывает стрелку и расстояние до ближайшей базы и подсвечивает чанки в мире.
 *
 * Горячие клавиши (меняются в настройках управления): B, N, M, V.
 * Команды: /basefinder help
 */
public class BaseFinderClient implements ClientModInitializer {

    // ------------------------------------------------------------ данные

    public static class Config {
        public boolean enabled = true;
        public boolean worldBoxes = true;
        public int threshold = 3;
        public int preset = 0; // -1 = свои цвета
        public int colorFound = 0xFFFF3030; // столб над чанком, который сейчас найден
        public int colorBox = 0xFFFFD700;   // рамка вокруг находок
        public int colorSaved = 0xFF30C0FF; // столб над сохранённой базой вне видимости
        public int colorMark = 0xFF40FF60;  // метки
    }

    public static class Base {
        public String dim;
        public int cx, cz;
        public int minY, maxY;
        public int score, chests, barrels, shulkers, villagers;
        int x() { return cx * 16 + 8; }
        int z() { return cz * 16 + 8; }
        int y() { return (minY + maxY) / 2; }
    }

    public static class Mark {
        public String name;
        public String dim;
        public int x, y, z;
    }

    public static class Saved {
        public List<Base> bases = new ArrayList<>();
        public List<Mark> marks = new ArrayList<>();
    }

    record Target(String label, int x, int y, int z) {}

    // индексы в массиве результата текущего скана
    private static final int CHEST = 0, BARREL = 1, SHULKER = 2, VILLAGER = 3, MIN_Y = 4, MAX_Y = 5;

    private static final int[][] PRESETS = {
            // found,      box,        saved,      mark
            {0xFFFF3030, 0xFFFFD700, 0xFF30C0FF, 0xFF40FF60}, // красный + золото
            {0xFF00E5FF, 0xFFFFFFFF, 0xFF7C4DFF, 0xFFFFEA00}, // голубой + белый
            {0xFF76FF03, 0xFFFFEA00, 0xFF00B0FF, 0xFFFF4081}, // зелёный + жёлтый
            {0xFFFF4081, 0xFFFFFFFF, 0xFFB388FF, 0xFF69F0AE}, // розовый + белый
    };
    private static final String[] PRESET_NAMES = {"красный/золото", "голубой/белый", "зелёный/жёлтый", "розовый/белый"};
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static final int CELL = 6;
    private static final int MAX_RADIUS = 12;
    private static final int WORLD_RADIUS = 16;
    private static final int MAX_BOXES = 12;
    private static final int MAX_MARK_PILLARS = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Config config = new Config();
    private static final Map<String, Base> bases = new LinkedHashMap<>();
    private static final List<Mark> marks = new ArrayList<>();
    private static Map<Long, int[]> results = new HashMap<>();

    private static String worldId = null;
    private static boolean dirty = false;
    private static String targetName = null; // null = ближайшая цель
    private static int tickCounter = 0;

    private static KeyBinding keyToggle, keyWorld, keyMark, keyColor;

    // ------------------------------------------------------------ запуск

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("basefinder", "main"));
        keyToggle = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.basefinder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        keyWorld = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.basefinder.world", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
        keyMark = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.basefinder.mark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
        keyColor = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.basefinder.color", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

        ClientTickEvents.END_CLIENT_TICK.register(BaseFinderClient::onTick);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            saveWorld();
            worldId = null;
            bases.clear();
            marks.clear();
            results = new HashMap<>();
            targetName = null;
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("basefinder", "radar"),
                (ctx, tickDelta) -> drawHud(ctx));

        WorldRenderEvents.AFTER_ENTITIES.register(BaseFinderClient::renderWorld);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        while (keyToggle.wasPressed()) {
            config.enabled = !config.enabled;
            saveConfig();
            msg(client, "BaseFinder: " + (config.enabled ? "включён" : "выключен"));
        }
        while (keyWorld.wasPressed()) {
            config.worldBoxes = !config.worldBoxes;
            saveConfig();
            msg(client, "Подсветка в мире: " + (config.worldBoxes ? "вкл" : "выкл"));
        }
        while (keyColor.wasPressed()) {
            applyPreset((config.preset + 1) % PRESETS.length);
            msg(client, "Цвета: " + PRESET_NAMES[config.preset]);
        }
        while (keyMark.wasPressed()) {
            Mark m = addMark(client, null);
            msg(client, "Метка «" + m.name + "»: " + m.x + " " + m.y + " " + m.z);
        }

        if (!config.enabled) return;

        tickCounter++;
        if (tickCounter % 20 == 0) {
            ensureWorldLoaded(client);
            scan(client);
        }
        if (tickCounter % 100 == 0 && dirty) saveWorld();
    }

    private static void msg(MinecraftClient client, String text) {
        client.player.sendMessage(Text.literal(text), true);
    }

    // ------------------------------------------------------------ хранение

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("basefinder");
    }

    private static void loadConfig() {
        try {
            Path p = dir().resolve("config.json");
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) config = c;
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), GSON.toJson(config));
        } catch (IOException ignored) {
        }
    }

    private static String currentWorldId(MinecraftClient client) {
        String name = "unknown";
        try {
            if (client.getServer() != null) {
                name = client.getServer().getSaveProperties().getLevelName();
            } else {
                ServerInfo si = client.getCurrentServerEntry();
                if (si != null) name = si.address;
            }
        } catch (Exception ignored) {
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + Integer.toHexString(name.hashCode());
    }

    private static Path worldFile(String id) {
        return dir().resolve("world-" + id + ".json");
    }

    private static void ensureWorldLoaded(MinecraftClient client) {
        String id = currentWorldId(client);
        if (id.equals(worldId)) return;
        if (worldId != null) saveWorld();
        worldId = id;
        bases.clear();
        marks.clear();
        try {
            Path p = worldFile(id);
            if (Files.exists(p)) {
                Saved s = GSON.fromJson(Files.readString(p), Saved.class);
                if (s != null) {
                    if (s.bases != null) for (Base b : s.bases) bases.put(baseKey(b.dim, b.cx, b.cz), b);
                    if (s.marks != null) marks.addAll(s.marks);
                }
            }
        } catch (Exception ignored) {
        }
        dirty = false;
    }

    private static void saveWorld() {
        if (worldId == null) return;
        try {
            Saved s = new Saved();
            s.bases.addAll(bases.values());
            s.marks.addAll(marks);
            Files.createDirectories(dir());
            Files.writeString(worldFile(worldId), GSON.toJson(s));
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    private static String baseKey(String dim, int cx, int cz) {
        return dim + "|" + cx + "," + cz;
    }

    private static String dimOf(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    // ------------------------------------------------------------ скан

    private static int score(int[] v) {
        return v[CHEST] + v[BARREL] + v[SHULKER] + v[VILLAGER];
    }

    private static void add(Map<Long, int[]> found, long key, int type, int y) {
        int[] v = found.computeIfAbsent(key, k -> new int[]{0, 0, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE});
        v[type]++;
        if (y < v[MIN_Y]) v[MIN_Y] = y;
        if (y > v[MAX_Y]) v[MAX_Y] = y;
    }

    private static void scan(MinecraftClient client) {
        ClientWorld world = client.world;
        ChunkPos center = client.player.getChunkPos();
        int radius = client.options.getViewDistance().getValue();
        String dim = dimOf(world);
        Map<Long, int[]> found = new HashMap<>();
        Set<Long> scanned = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz);
                if (chunk == null) continue;
                long key = chunk.getPos().toLong();
                scanned.add(key);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int y = be.getPos().getY();
                    if (be instanceof ChestBlockEntity) add(found, key, CHEST, y);
                    else if (be instanceof BarrelBlockEntity) add(found, key, BARREL, y);
                    else if (be instanceof ShulkerBoxBlockEntity) add(found, key, SHULKER, y);
                }
            }
        }
        for (Entity e : world.getEntities()) {
            if (e instanceof VillagerEntity) add(found, e.getChunkPos().toLong(), VILLAGER, e.getBlockY());
        }
        results = found;

        // обновляем сохранённые базы: найденные добавляем, пропавшие в просканированных чанках убираем
        for (long key : scanned) {
            ChunkPos cp = new ChunkPos(key);
            String bk = baseKey(dim, cp.x, cp.z);
            int[] v = found.get(key);
            if (v != null && score(v) >= config.threshold) {
                Base b = bases.get(bk);
                boolean isNew = b == null;
                if (isNew) {
                    b = new Base();
                    b.dim = dim;
                    b.cx = cp.x;
                    b.cz = cp.z;
                    bases.put(bk, b);
                }
                if (isNew || b.score != score(v) || b.minY != v[MIN_Y] || b.maxY != v[MAX_Y]) dirty = true;
                b.score = score(v);
                b.chests = v[CHEST];
                b.barrels = v[BARREL];
                b.shulkers = v[SHULKER];
                b.villagers = v[VILLAGER];
                b.minY = v[MIN_Y];
                b.maxY = v[MAX_Y];
            } else if (bases.remove(bk) != null) {
                dirty = true;
            }
        }
    }

    // ------------------------------------------------------------ метки и цели

    private static Mark addMark(MinecraftClient client, String name) {
        if (name == null || name.isBlank()) {
            int n = marks.size() + 1;
            name = "Метка " + n;
            while (findMark(name) != null) name = "Метка " + (++n);
        }
        Mark m = new Mark();
        m.name = name;
        m.dim = dimOf(client.world);
        m.x = client.player.getBlockX();
        m.y = client.player.getBlockY();
        m.z = client.player.getBlockZ();
        Mark old = findMark(name);
        if (old != null) marks.remove(old);
        marks.add(m);
        dirty = true;
        saveWorld();
        return m;
    }

    private static Mark findMark(String name) {
        for (Mark m : marks) if (m.name.equalsIgnoreCase(name)) return m;
        return null;
    }

    private static double dist(MinecraftClient client, int x, int y, int z) {
        double dx = x + 0.5 - client.player.getX();
        double dy = y - client.player.getY();
        double dz = z + 0.5 - client.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Base> basesHere(MinecraftClient client) {
        String dim = dimOf(client.world);
        List<Base> list = new ArrayList<>();
        for (Base b : bases.values()) if (b.dim.equals(dim)) list.add(b);
        list.sort(Comparator.comparingDouble(b -> dist(client, b.x(), b.y(), b.z())));
        return list;
    }

    private static Target currentTarget(MinecraftClient client) {
        String dim = dimOf(client.world);
        if (targetName != null) {
            Mark m = findMark(targetName);
            if (m != null && m.dim.equals(dim)) return new Target(m.name, m.x, m.y, m.z);
        }
        Target best = null;
        double bestD = Double.MAX_VALUE;
        for (Base b : bases.values()) {
            if (!b.dim.equals(dim)) continue;
            double d = dist(client, b.x(), b.y(), b.z());
            if (d < bestD) {
                bestD = d;
                best = new Target("База", b.x(), b.y(), b.z());
            }
        }
        for (Mark m : marks) {
            if (!m.dim.equals(dim)) continue;
            double d = dist(client, m.x, m.y, m.z);
            if (d < bestD) {
                bestD = d;
                best = new Target(m.name, m.x, m.y, m.z);
            }
        }
        return best;
    }

    private static String arrow(MinecraftClient client, int tx, int tz) {
        double dx = tx + 0.5 - client.player.getX();
        double dz = tz + 0.5 - client.player.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
        int idx = (int) Math.round(rel / 45.0);
        idx = ((idx % 8) + 8) % 8;
        return ARROWS[idx];
    }

    // ------------------------------------------------------------ цвета

    private static void applyPreset(int i) {
        config.preset = i;
        config.colorFound = PRESETS[i][0];
        config.colorBox = PRESETS[i][1];
        config.colorSaved = PRESETS[i][2];
        config.colorMark = PRESETS[i][3];
        saveConfig();
    }

    // ------------------------------------------------------------ мир

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.enabled || !config.worldBoxes || client.world == null || client.player == null) return;

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d cam = context.worldState().cameraRenderState.pos;
        ChunkPos me = client.player.getChunkPos();
        int worldTop = client.world.getBottomY() + client.world.getHeight();
        String dim = dimOf(client.world);
        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());

        int drawn = 0;
        for (Base b : basesHere(client)) {
            if (drawn >= MAX_BOXES) break;
            if (Math.abs(b.cx - me.x) > WORLD_RADIUS || Math.abs(b.cz - me.z) > WORLD_RADIUS) continue;
            int[] live = results.get(ChunkPos.toLong(b.cx, b.cz));
            boolean isLive = live != null && score(live) >= config.threshold;
            double x0 = b.cx * 16.0;
            double z0 = b.cz * 16.0;
            double yLow = b.minY - 1;
            double yHigh = b.maxY + 2;
            // столб до верха мира: виден с поверхности, даже если база под землёй
            VertexRendering.drawOutline(matrices, lines,
                    VoxelShapes.cuboid(x0, yLow, z0, x0 + 16, worldTop, z0 + 16),
                    -cam.x, -cam.y, -cam.z, isLive ? config.colorFound : config.colorSaved, 3.0f);
            // рамка вокруг самих находок
            VertexRendering.drawOutline(matrices, lines,
                    VoxelShapes.cuboid(x0, yLow, z0, x0 + 16, yHigh, z0 + 16),
                    -cam.x, -cam.y, -cam.z, config.colorBox, 5.0f);
            drawn++;
        }

        int pillars = 0;
        for (Mark m : marks) {
            if (pillars >= MAX_MARK_PILLARS) break;
            if (!m.dim.equals(dim)) continue;
            if (dist(client, m.x, m.y, m.z) > 300) continue;
            VertexRendering.drawOutline(matrices, lines,
                    VoxelShapes.cuboid(m.x, m.y - 1, m.z, m.x + 1, worldTop, m.z + 1),
                    -cam.x, -cam.y, -cam.z, config.colorMark, 4.0f);
            pillars++;
        }
    }

    // ------------------------------------------------------------ HUD

    private static void drawHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.enabled || client.world == null || client.player == null) return;
        if (client.options.hudHidden) return;

        int radius = Math.min(MAX_RADIUS, client.options.getViewDistance().getValue());
        int size = (radius * 2 + 1) * CELL;
        int x0 = ctx.getScaledWindowWidth() - size - 6;
        int y0 = 6;
        ChunkPos me = client.player.getChunkPos();
        String dim = dimOf(client.world);

        ctx.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xA0000000);

        // находки ниже порога (бледно)
        for (Map.Entry<Long, int[]> e : results.entrySet()) {
            if (score(e.getValue()) >= config.threshold) continue;
            ChunkPos cp = new ChunkPos(e.getKey());
            cell(ctx, x0, y0, radius, cp.x - me.x, cp.z - me.z, 0x60FFD700);
        }
        // сохранённые базы
        for (Base b : bases.values()) {
            if (!b.dim.equals(dim)) continue;
            int[] live = results.get(ChunkPos.toLong(b.cx, b.cz));
            boolean isLive = live != null && score(live) >= config.threshold;
            cell(ctx, x0, y0, radius, b.cx - me.x, b.cz - me.z, isLive ? config.colorFound : (config.colorSaved & 0x00FFFFFF) | 0xB0000000);
        }
        // метки
        for (Mark m : marks) {
            if (!m.dim.equals(dim)) continue;
            cell(ctx, x0, y0, radius, (m.x >> 4) - me.x, (m.z >> 4) - me.z, config.colorMark);
        }
        // игрок
        int cx = x0 + radius * CELL;
        int cy = y0 + radius * CELL;
        ctx.fill(cx + 1, cy + 1, cx + CELL - 2, cy + CELL - 2, 0xFFFFFFFF);

        int ty = y0 + size + 4;

        // стрелка к цели
        Target t = currentTarget(client);
        if (t != null) {
            double d = dist(client, t.x(), t.y(), t.z());
            int dy = t.y() - client.player.getBlockY();
            String dyText = Math.abs(dy) < 3 ? "" : (dy > 0 ? "  вверх " + dy : "  вниз " + (-dy));
            String line = arrow(client, t.x(), t.z()) + " " + t.label() + ": " + (int) d + " м" + dyText;
            ctx.drawTextWithShadow(client.textRenderer, line, x0, ty, config.colorMark | 0xFF000000);
            ty += 11;
        }

        // ближайшие базы: X Y Z [счёт]
        int shown = 0;
        for (Base b : basesHere(client)) {
            if (shown++ >= 3) break;
            String line = b.x() + " " + b.y() + " " + b.z() + "  [" + b.score + "]  " + (int) dist(client, b.x(), b.y(), b.z()) + " м";
            ctx.drawTextWithShadow(client.textRenderer, line, x0, ty, 0xFFFFD700);
            ty += 10;
        }
    }

    private static void cell(DrawContext ctx, int x0, int y0, int radius, int dx, int dz, int color) {
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return;
        int px = x0 + (dx + radius) * CELL;
        int py = y0 + (dz + radius) * CELL;
        ctx.fill(px, py, px + CELL - 1, py + CELL - 1, color);
    }

    // ------------------------------------------------------------ команды

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher) {
        var root = ClientCommandManager.literal("basefinder");

        root.then(ClientCommandManager.literal("help").executes(c -> {
            String[] lines = {
                    "/basefinder list - сохранённые базы (X Y Z, расстояние)",
                    "/basefinder mark [имя] - метка на вашей позиции; /basefinder unmark <имя>; /basefinder marks",
                    "/basefinder target [имя] - стрелка к метке (без имени - к ближайшей цели)",
                    "/basefinder forget - стереть все сохранённые базы этого мира",
                    "/basefinder threshold N - минимум предметов в чанке (сейчас " + config.threshold + ")",
                    "/basefinder world - подсветка в мире; /basefinder toggle - вкл/выкл мод",
                    "/basefinder color preset <0-3> | color <found|box|saved|mark> <RRGGBB>",
                    "Клавиши: B - вкл/выкл, N - подсветка, M - метка, V - цвета"};
            for (String l : lines) c.getSource().sendFeedback(Text.literal(l));
            return 1;
        }));

        root.then(ClientCommandManager.literal("toggle").executes(c -> {
            config.enabled = !config.enabled;
            saveConfig();
            c.getSource().sendFeedback(Text.literal("BaseFinder: " + (config.enabled ? "включён" : "выключен")));
            return 1;
        }));

        root.then(ClientCommandManager.literal("world").executes(c -> {
            config.worldBoxes = !config.worldBoxes;
            saveConfig();
            c.getSource().sendFeedback(Text.literal("Подсветка в мире: " + (config.worldBoxes ? "вкл" : "выкл")));
            return 1;
        }));

        root.then(ClientCommandManager.literal("threshold")
                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 1000)).executes(c -> {
                    config.threshold = IntegerArgumentType.getInteger(c, "n");
                    saveConfig();
                    c.getSource().sendFeedback(Text.literal("Порог = " + config.threshold));
                    return 1;
                })));

        root.then(ClientCommandManager.literal("list").executes(c -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return 0;
            List<Base> list = basesHere(client);
            if (list.isEmpty()) {
                c.getSource().sendFeedback(Text.literal("Баз пока нет. Походите по миру: найденные чанки запоминаются сами."));
                return 1;
            }
            int n = 0;
            for (Base b : list) {
                if (n++ >= 15) break;
                String yRange = b.minY == b.maxY ? "" : " (Y " + b.minY + ".." + b.maxY + ")";
                c.getSource().sendFeedback(Text.literal(
                        "X " + b.x() + " Y " + b.y() + " Z " + b.z() + yRange + " | " + (int) dist(client, b.x(), b.y(), b.z()) + " м"
                                + " | сундуки " + b.chests + ", бочки " + b.barrels
                                + ", шалкеры " + b.shulkers + ", жители " + b.villagers));
            }
            return 1;
        }));

        root.then(ClientCommandManager.literal("forget").executes(c -> {
            bases.clear();
            dirty = true;
            saveWorld();
            c.getSource().sendFeedback(Text.literal("Сохранённые базы этого мира стёрты"));
            return 1;
        }));

        root.then(ClientCommandManager.literal("mark")
                .executes(c -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.world == null || client.player == null) return 0;
                    Mark m = addMark(client, null);
                    c.getSource().sendFeedback(Text.literal("Метка «" + m.name + "»: " + m.x + " " + m.y + " " + m.z));
                    return 1;
                })
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.world == null || client.player == null) return 0;
                    Mark m = addMark(client, StringArgumentType.getString(c, "name"));
                    c.getSource().sendFeedback(Text.literal("Метка «" + m.name + "»: " + m.x + " " + m.y + " " + m.z));
                    return 1;
                })));

        root.then(ClientCommandManager.literal("unmark")
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> {
                    Mark m = findMark(StringArgumentType.getString(c, "name"));
                    if (m == null) {
                        c.getSource().sendFeedback(Text.literal("Такой метки нет"));
                        return 0;
                    }
                    marks.remove(m);
                    if (targetName != null && targetName.equalsIgnoreCase(m.name)) targetName = null;
                    dirty = true;
                    saveWorld();
                    c.getSource().sendFeedback(Text.literal("Метка «" + m.name + "» удалена"));
                    return 1;
                })));

        root.then(ClientCommandManager.literal("marks").executes(c -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (marks.isEmpty()) {
                c.getSource().sendFeedback(Text.literal("Меток нет. Клавиша M или /basefinder mark имя"));
                return 1;
            }
            for (Mark m : marks) {
                String d = client.player != null && client.world != null && m.dim.equals(dimOf(client.world))
                        ? " | " + (int) dist(client, m.x, m.y, m.z) + " м" : " | другое измерение";
                c.getSource().sendFeedback(Text.literal("«" + m.name + "»: X " + m.x + " Y " + m.y + " Z " + m.z + d));
            }
            return 1;
        }));

        root.then(ClientCommandManager.literal("target")
                .executes(c -> {
                    targetName = null;
                    c.getSource().sendFeedback(Text.literal("Стрелка показывает на ближайшую цель"));
                    return 1;
                })
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> {
                    Mark m = findMark(StringArgumentType.getString(c, "name"));
                    if (m == null) {
                        c.getSource().sendFeedback(Text.literal("Такой метки нет"));
                        return 0;
                    }
                    targetName = m.name;
                    c.getSource().sendFeedback(Text.literal("Стрелка показывает на «" + m.name + "»"));
                    return 1;
                })));

        var color = ClientCommandManager.literal("color");
        color.then(ClientCommandManager.literal("preset")
                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(0, PRESETS.length - 1)).executes(c -> {
                    applyPreset(IntegerArgumentType.getInteger(c, "n"));
                    c.getSource().sendFeedback(Text.literal("Цвета: " + PRESET_NAMES[config.preset]));
                    return 1;
                })));
        for (String part : new String[]{"found", "box", "saved", "mark"}) {
            color.then(ClientCommandManager.literal(part)
                    .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(c -> {
                        String hex = StringArgumentType.getString(c, "hex").replace("#", "");
                        int rgb;
                        try {
                            rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
                        } catch (NumberFormatException ex) {
                            c.getSource().sendFeedback(Text.literal("Нужен цвет вида RRGGBB, например FF3030"));
                            return 0;
                        }
                        int argb = 0xFF000000 | rgb;
                        switch (part) {
                            case "found" -> config.colorFound = argb;
                            case "box" -> config.colorBox = argb;
                            case "saved" -> config.colorSaved = argb;
                            default -> config.colorMark = argb;
                        }
                        config.preset = -1;
                        saveConfig();
                        c.getSource().sendFeedback(Text.literal("Цвет «" + part + "» = " + hex.toUpperCase()));
                        return 1;
                    })));
        }
        root.then(color);

        dispatcher.register(root);
    }
}
