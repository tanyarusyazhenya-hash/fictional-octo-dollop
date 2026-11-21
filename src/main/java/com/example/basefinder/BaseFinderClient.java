package com.example.basefinder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BaseFinder (клиентский мод): находит загруженные чанки, где много сундуков,
 * бочек и жителей, и показывает их на мини-карте в углу экрана.
 *
 * Команды:
 *   /basefinder toggle        - включить/выключить
 *   /basefinder threshold N   - минимум предметов в чанке (по умолчанию 3)
 *   /basefinder list          - список найденных чанков с координатами
 */
public class BaseFinderClient implements ClientModInitializer {

    private static final int CELL = 6;          // размер клетки чанка на карте, px
    private static final int MAX_RADIUS = 12;   // радиус карты в чанках

    private static boolean enabled = true;
    private static int threshold = 3;

    /** chunkPos.toLong() -> {сундуки, бочки, жители} */
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
                (ctx, tickCounter) -> drawRadar(ctx)
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("basefinder")
                        .then(ClientCommandManager.literal("toggle").executes(c -> {
                            enabled = !enabled;
                            c.getSource().sendFeedback(Text.literal("BaseFinder: " + (enabled ? "включён" : "выключен")));
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
                                c.getSource().sendFeedback(Text.literal(
                                        "X " + cp.getStartX() + " Z " + cp.getStartZ()
                                                + "  | сундуки " + v[0] + ", бочки " + v[1] + ", жители " + v[2]));
                            }
                            return 1;
                        }))));
    }

    private static int score(int[] v) {
        return v[0] + v[1] + v[2];
    }

    private static List<Map.Entry<Long, int[]>> hits() {
        List<Map.Entry<Long, int[]>> list = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : results.entrySet()) {
            if (score(e.getValue()) >= threshold) list.add(e);
        }
        list.sort(Comparator.comparingInt((Map.Entry<Long, int[]> e) -> score(e.getValue())).reversed());
        return list;
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
                int chests = 0, barrels = 0;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof ChestBlockEntity) chests++;      // включая сундуки-ловушки
                    else if (be instanceof BarrelBlockEntity) barrels++;
                }
                if (chests + barrels > 0) {
                    found.put(chunk.getPos().toLong(), new int[]{chests, barrels, 0});
                }
            }
        }

        for (Entity e : world.getEntities()) {
            if (e instanceof VillagerEntity) {
                found.computeIfAbsent(e.getChunkPos().toLong(), k -> new int[3])[2]++;
            }
        }
        results = found;
    }

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
                int s = score(v);
                int color = s >= threshold ? 0xFFFF3030 : 0x60FFD700;
                int px = x0 + (dx + radius) * CELL;
                int py = y0 + (dz + radius) * CELL;
                ctx.fill(px, py, px + CELL - 1, py + CELL - 1, color);
            }
        }

        // игрок
        int cx = x0 + radius * CELL;
        int cy = y0 + radius * CELL;
        ctx.fill(cx + 1, cy + 1, cx + CELL - 2, cy + CELL - 2, 0xFFFFFFFF);

        // подписи для лучших чанков
        int ty = y0 + size + 4;
        int shown = 0;
        for (Map.Entry<Long, int[]> e : hits()) {
            if (shown++ >= 4) break;
            ChunkPos cp = new ChunkPos(e.getKey());
            int[] v = e.getValue();
            String line = (cp.getStartX() + 8) + " " + (cp.getStartZ() + 8) + "  [" + score(v) + "]";
            ctx.drawTextWithShadow(client.textRenderer, line, x0, ty, 0xFFFFD700);
            ty += 10;
        }
    }
}
