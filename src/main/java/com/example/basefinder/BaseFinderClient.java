package com.example.basefinder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BaseFinder (клиентский мод): ищет загруженные чанки, где много сундуков,
 * бочек, шалкеров и жителей. Показывает мини-карту, подсвечивает чанки
 * прямо в мире и пишет координаты вместе с высотой (Y).
 *
 * Команды:
 *   /basefinder toggle        - включить/выключить мод
 *   /basefinder world         - включить/выключить подсветку в мире
 *   /basefinder threshold N   - минимум предметов в чанке (по умолчанию 3)
 *   /basefinder list          - список найденных чанков с координатами
 */
public class BaseFinderClient implements ClientModInitializer {

    private static final int CELL = 6;          // размер клетки чанка на карте, px
    private static final int MAX_RADIUS = 12;   // радиус карты в чанках
    private static final int WORLD_RADIUS = 12; // дальность подсветки в мире, чанков
    private static final int MAX_BOXES = 12;    // сколько чанков подсвечивать одновременно

    // индексы в массиве результата
    private static final int CHEST = 0, BARREL = 1, SHULKER = 2, VILLAGER = 3, MIN_Y = 4, MAX_Y = 5;

    private static boolean enabled = true;
    private static boolean worldBoxes = true;
    private static int threshold = 3;

    /** chunkPos.toLong() -> {сундуки, бочки, шалкеры, жители, minY, maxY} */
    private static volatile Map<Long, int[]> results = new HashMap<>();
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.world == null || client.player == null) return;
            if (++tickCounter % 20 != 0) return; // раз в секунду
            scan(client);
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("basefinder", "radar"),
                (ctx, tickDelta) -> drawRadar(ctx)
        );

        WorldRenderEvents.AFTER_ENTITIES.register(BaseFinderClient::renderWorld);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("basefinder")
                        .then(ClientCommandManager.literal("toggle").executes(c -> {
                            enabled = !enabled;
                            c.getSource().sendFeedback(Text.literal("BaseFinder: " + (enabled ? "включён" : "выключен")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("world").executes(c -> {
                            worldBoxes = !worldBoxes;
                            c.getSource().sendFeedback(Text.literal("BaseFinder: подсветка в мире " + (worldBoxes ? "включена" : "выключена")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("threshold")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 1000))
                                        .executes(c -> {
                                            threshold = IntegerArgumentType.getInteger(c, "n");
                                            c.getSource().sendFeedback(Text.literal("BaseFinder: порог = " + threshold));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("list").executes(c -> {
                            List<Map.Entry<Long, int[]>> hits = hits();
                            if (hits.isEmpty()) {
                                c.getSource().sendFeedback(Text.literal("BaseFinder: ничего не найдено в загруженных чанках"));
                                return 1;
                            }
                            int shown = 0;
                            for (Map.Entry<Long, int[]> e : hits) {
                                if (shown++ >= 15) break;
                                ChunkPos cp = new ChunkPos(e.getKey());
                                int[] v = e.getValue();
                                String yRange = v[MIN_Y] == v[MAX_Y] ? "" : " (Y " + v[MIN_Y] + ".." + v[MAX_Y] + ")";
                                c.getSource().sendFeedback(Text.literal(
                                        "X " + (cp.getStartX() + 8) + " Y " + centerY(v) + " Z " + (cp.getStartZ() + 8) + yRange
                                                + " | сундуки " + v[CHEST] + ", бочки " + v[BARREL]
                                                + ", шалкеры " + v[SHULKER] + ", жители " + v[VILLAGER]));
                            }
                            return 1;
                        }))));
    }

    private static int score(int[] v) {
        return v[CHEST] + v[BARREL] + v[SHULKER] + v[VILLAGER];
    }

    private static int centerY(int[] v) {
        return (v[MIN_Y] + v[MAX_Y]) / 2;
    }

    private static List<Map.Entry<Long, int[]>> hits() {
        List<Map.Entry<Long, int[]>> list = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : results.entrySet()) {
            if (score(e.getValue()) >= threshold) list.add(e);
        }
        list.sort(Comparator.comparingInt((Map.Entry<Long, int[]> e) -> score(e.getValue())).reversed());
        return list;
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
        Map<Long, int[]> found = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz);
                if (chunk == null) continue;
                long key = chunk.getPos().toLong();
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int y = be.getPos().getY();
                    if (be instanceof ChestBlockEntity) add(found, key, CHEST, y);       // включая сундуки-ловушки
                    else if (be instanceof BarrelBlockEntity) add(found, key, BARREL, y);
                    else if (be instanceof ShulkerBoxBlockEntity) add(found, key, SHULKER, y);
                }
            }
        }

        for (Entity e : world.getEntities()) {
            if (e instanceof VillagerEntity) {
                add(found, e.getChunkPos().toLong(), VILLAGER, e.getBlockY());
            }
        }
        results = found;
    }

    // ------------------------------------------------------------------ мир

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || !worldBoxes || client.world == null || client.player == null) return;

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d cam = context.worldState().cameraRenderState.pos;
        ChunkPos me = client.player.getChunkPos();
        int worldTop = client.world.getBottomY() + client.world.getHeight();

        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
        int drawn = 0;
        for (Map.Entry<Long, int[]> e : hits()) {
            if (drawn >= MAX_BOXES) break;
            ChunkPos cp = new ChunkPos(e.getKey());
            if (Math.abs(cp.x - me.x) > WORLD_RADIUS || Math.abs(cp.z - me.z) > WORLD_RADIUS) continue;
            int[] v = e.getValue();

            double x0 = cp.getStartX();
            double z0 = cp.getStartZ();
            double yLow = v[MIN_Y] - 1;
            double yHigh = v[MAX_Y] + 2;

            // красный столб от находок до верха мира: виден с поверхности, даже если база под землёй
            VertexRendering.drawOutline(matrices, lines,
                    VoxelShapes.cuboid(x0, yLow, z0, x0 + 16, worldTop, z0 + 16),
                    -cam.x, -cam.y, -cam.z, 0xFFFF3030, 3.0f);
            // золотая рамка вокруг самих находок
            VertexRendering.drawOutline(matrices, lines,
                    VoxelShapes.cuboid(x0, yLow, z0, x0 + 16, yHigh, z0 + 16),
                    -cam.x, -cam.y, -cam.z, 0xFFFFD700, 5.0f);
            drawn++;
        }
    }

    // ------------------------------------------------------------ мини-карта

    private static void drawRadar(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.world == null || client.player == null) return;
        if (client.options.hudHidden) return;

        int radius = Math.min(MAX_RADIUS, client.options.getViewDistance().getValue());
        int size = (radius * 2 + 1) * CELL;
        int x0 = ctx.getScaledWindowWidth() - size - 6;
        int y0 = 6;
        ChunkPos me = client.player.getChunkPos();

        ctx.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xA0000000);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int[] v = results.get(ChunkPos.toLong(me.x + dx, me.z + dz));
                if (v == null) continue;
                int color = score(v) >= threshold ? 0xFFFF3030 : 0x60FFD700;
                int px = x0 + (dx + radius) * CELL;
                int py = y0 + (dz + radius) * CELL;
                ctx.fill(px, py, px + CELL - 1, py + CELL - 1, color);
            }
        }

        // игрок
        int cx = x0 + radius * CELL;
        int cy = y0 + radius * CELL;
        ctx.fill(cx + 1, cy + 1, cx + CELL - 2, cy + CELL - 2, 0xFFFFFFFF);

        // подписи для лучших чанков: X Y Z [счёт]
        int ty = y0 + size + 4;
        int shown = 0;
        for (Map.Entry<Long, int[]> e : hits()) {
            if (shown++ >= 4) break;
            ChunkPos cp = new ChunkPos(e.getKey());
            int[] v = e.getValue();
            String line = (cp.getStartX() + 8) + " " + centerY(v) + " " + (cp.getStartZ() + 8) + "  [" + score(v) + "]";
            ctx.drawTextWithShadow(client.textRenderer, line, x0, ty, 0xFFFFD700);
            ty += 10;
        }
    }
}
