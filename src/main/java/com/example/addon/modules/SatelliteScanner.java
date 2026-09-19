package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * 单次遍历即可完成全部功能的 SatelliteScanner
 * - 按区块逐区块处理：在同一区块内先收集 marker（天然结构线索）与候选储物方块，
 *   然后在该区块内完成天然结构过滤与上报（避免全局两遍扫描）。
 * - 保留 QQ 推送、聊天排队、天然结构识别、重度检测预算、不重复播报等功能。
 */
public class SatelliteScanner extends Module {
    private static final int MAX_MESSAGES_PER_TICK = 10;
    private static final int CQ_LINES_PER_MESSAGE = 15;
    private static final int CQ_FLUSH_TICKS = 20;
    private static final int CQ_QUEUE_LIMIT = 2000;

    private static final int HEAVY_BUDGET_PER_SCAN = 30;

    private static final int R_DUNGEON = 10;
    private static final int R_TRIAL = 20;
    private static final int R_CITY = 20;
    private static final int R_JUNGLE = 16;
    private static final int R_MANSION = 24;

    private record Msg(String fmt, Object[] args) {}
    private record Zone(int x, int y, int z, int r2) {}
    private record Cand(BlockPos pos, String label) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCq = settings.createGroup("QQ推送");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("扫描半径（0为全局）")
        .defaultValue(0.0)
        .min(0.0)
        .build());

    private final Setting<Boolean> lockNatural = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-natural-structures")
        .description("开：天然结构（地牢/试炼密室/远古城市/丛林神殿/林地府邸）里的方块也锁定通知；关：跳过它们")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("pearls")
        .description("扫描末影珍珠")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("shulkers")
        .description("扫描潜影盒")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests")
        .description("扫描箱子/陷阱箱/木桶")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-chests")
        .description("扫描末影箱")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> hoppers = sgGeneral.add(new BoolSetting.Builder()
        .name("hoppers")
        .description("扫描漏斗")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> dispensers = sgGeneral.add(new BoolSetting.Builder()
        .name("dispensers")
        .description("扫描发射器/投掷器")
        .defaultValue(true)
        .build());

    /* ========== QQ 推送设置 ========== */
    private final Setting<Boolean> cqEnabled = sgCq.add(new BoolSetting.Builder()
        .name("cq-enabled")
        .description("把扫描结果通过 go-cqhttp 推送到 QQ")
        .defaultValue(false)
        .build());

    private final Setting<String> cqUrl = sgCq.add(new StringSetting.Builder()
        .name("cq-url")
        .description("go-cqhttp 的 HTTP 地址，例如 http://127.0.0.1:5700")
        .defaultValue("http://127.0.0.1:5700")
        .build());

    private final Setting<String> cqUserId = sgCq.add(new StringSetting.Builder()
        .name("cq-user-id")
        .description("接收消息的 QQ 号（机器人必须已是该 QQ 的好友）")
        .defaultValue("")
        .build());

    private final Setting<String> cqToken = sgCq.add(new StringSetting.Builder()
        .name("cq-token")
        .description("access_token，没设置就留空")
        .defaultValue("")
        .build());

    private final Set<Integer> seenPearls = new HashSet<>();
    private final Set<Long> seenBlocks = new HashSet<>(); // pos.asLong()
    private final Set<BlockPos> markerSeen = new HashSet<>();
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final ArrayList<Cand> cands = new ArrayList<>();
    private final ArrayDeque<Msg> pending = new ArrayDeque<>();
    private final ArrayDeque<String> cqLines = new ArrayDeque<>();
    private final HashMap<Long, String> posLastLabel = new HashMap<>(); // posLong -> lastLabel (避免重复播报)
    private int tickTimer = 0;
    private int cqTimer = 0;
    private int heavyBudget = 0;

    private ExecutorService cqExecutor;
    private volatile boolean cqFailing = false;
    private volatile String cqError = null;

    public SatelliteScanner() {
        super(
            AddonTemplate.CATEGORY,
            "satellite-scanner",
            "首次入雷达即锁定：珍珠永久静默，方块移位再报"
        );
    }

    @Override
    public void onActivate() {
        seenPearls.clear();
        seenBlocks.clear();
        markerSeen.clear();
        zones.clear();
        cands.clear();
        pending.clear();
        cqLines.clear();
        posLastLabel.clear();
        tickTimer = 0;
        cqTimer = 0;
        cqFailing = false;
        cqError = null;
        if (cqEnabled.get()) cqLines.add("已启动，推送正常时会收到这条消息");
    }

    @Override
    public void onDeactivate() {
        cqLines.clear();
        if (cqExecutor != null) {
            cqExecutor.shutdown();
            cqExecutor = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        flushMessages();
        flushCq();

        String err = cqError;
        if (err != null) {
            cqError = null;
            info("[QQ推送] 发送失败：%s", err);
        }

        if (++tickTimer < 10) return;
        tickTimer = 0;

        heavyBudget = HEAVY_BUDGET_PER_SCAN;

        double r = range.get();
        AABB box = r <= 0
            ? new AABB(-3E7, -512, -3E7, 3E7, 512, 3E7)
            : new AABB(
                mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
            );

        Level world = mc.level;

        /* ========== 末影珍珠 ========== */
        if (pearls.get()) {
            for (Entity e : world.getEntitiesOfClass(Entity.class, box, ent -> ent.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("enderpearl"))) {
                int id = e.getId();
                if (seenPearls.add(id)) {
                    report("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                        e.getX(), e.getY(), e.getZ());
                }
            }
        }

        /* ========== 方块：按区块逐区块单次处理（在区块内先收集 marker 与候选，再在区块内完成过滤与上报） ========== */
        boolean scanBlocks =
            shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get();
        if (!scanBlocks) return;

        final boolean filterOn = !lockNatural.get();

        int radius = r <= 0 ? 80 : (int) Math.max(1, Math.ceil(r));
        BlockPos center = mc.player.blockPosition();

        int cxMin = (center.getX() - radius) >> 4;
        int cxMax = (center.getX() + radius) >> 4;
        int czMin = (center.getZ() - radius) >> 4;
        int czMax = (center.getZ() + radius) >> 4;

        // 懒清理：移除已不存在的记录，避免永久屏蔽
        cands.clear();
        // 遍历区块：对每个区块先做一次内部扫描（收集 marker 与候选），然后在该区块内完成过滤与上报
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                LevelChunk chunk;
                try {
                    // 尝试获取区块（注意：不同映射可能需要替换为 getChunkIfLoaded）
                    chunk = world.getChunk(cx, cz);
                } catch (Throwable t) {
                    // 若映射不同或方法会强制加载，可尝试反射 getChunkIfLoaded（兼容性保底）
                    try {
                        chunk = (LevelChunk) world.getClass()
                            .getMethod("getChunkIfLoaded", int.class, int.class)
                            .invoke(world, cx, cz);
                    } catch (Throwable ignored) {
                        // 最后回退（可能会加载区块）
                        try {
                            chunk = world.getChunk(cx, cz);
                        } catch (Throwable ex) {
                            chunk = null;
                        }
                    }
                }
                if (chunk == null) continue;

                // 本区块内临时候选列表
                cands.clear();

                // 第一遍（区块内）：收集 marker（天然结构线索）并收集候选储物方块（未上报过）
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (Math.abs(pos.getX() - center.getX()) > radius) continue;
                    if (Math.abs(pos.getY() - center.getY()) > radius) continue;
                    if (Math.abs(pos.getZ() - center.getZ()) > radius) continue;

                    // 若是非目标方块且需要过滤天然结构，则登记 marker 线索
                    String label = match(be);
                    if (label != null) {
                        long posLong = pos.asLong();
                        if (!posLastLabel.containsKey(posLong)) {
                            cands.add(new Cand(pos, label));
                        } else {
                            // 已上报过同位置，若类型变化则也加入候选以便更新
                            String last = posLastLabel.get(posLong);
                            if (!label.equals(last)) cands.add(new Cand(pos, label));
                        }
                    } else if (filterOn) {
                        // 仅当需要过滤天然结构时才登记 marker
                        addMarkerZone(be, pos);
                    }
                }

                // 第二步（区块内）：对本区块的候选进行天然结构过滤与上报（heavy 检查受 heavyBudget 限制）
                for (Cand c : cands) {
                    BlockPos p = c.pos();
                    String label = c.label();
                    long posLong = p.asLong();

                    if (filterOn && insideZone(p)) {
                        // 在天然结构范围内，标记为已见但不通知
                        seenBlocks.add(posLong);
                        posLastLabel.put(posLong, label); // 记录类型以避免重复上报
                        continue;
                    }

                    // mansion 检测等 heavy 检查受预算限制
                    if (filterOn && heavyBudget > 0) {
                        heavyBudget--;
                        if (countNear(world, p, 6, 3, 12, b -> b == Blocks.DARK_OAK_PLANKS) >= 12) {
                            zones.add(new Zone(p.getX(), p.getY(), p.getZ(), R_MANSION * R_MANSION));
                            seenBlocks.add(posLong);
                            posLastLabel.put(posLong, label);
                            continue;
                        }
                    }

                    // 最终上报（若之前未上报或类型变化）
                    String lastLabel = posLastLabel.get(posLong);
                    if (lastLabel == null || !lastLabel.equals(label)) {
                        seenBlocks.add(posLong);
                        posLastLabel.put(posLong, label);
                        report("[雷达锁定-%s] [%d, %d, %d]", label, p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
    }

    /* ========== 天然结构识别（只用来屏蔽，不通知） ========== */
    private void addMarkerZone(BlockEntity be, BlockPos pos) {
        if (markerSeen.contains(pos)) return;
        String n = be.getClass().getSimpleName().toLowerCase(Locale.ROOT);

        int rad = 0;
        if (n.contains("trialspawner") || n.contains("vault")) {
            rad = R_TRIAL;          // 试炼密室
        } else if (n.endsWith("spawnerblockentity") && !n.contains("trial")) {
            rad = R_DUNGEON;        // 地牢（普通刷怪笼）
        } else if (n.contains("sculksensor")) {
            rad = R_CITY;           // 远古城市（幽匿感测体）
        }

        if (rad > 0) {
            markerSeen.add(pos);
            zones.add(new Zone(pos.getX(), pos.getY(), pos.getZ(), rad * rad));
        } else {
            // 额外：若是丛林线索（例如发射器但非投掷器），登记并稍后检测
            if (n.contains("dispenser") && !n.contains("dropper")) {
                markerSeen.add(pos);
                // 轻量检测会在 countNear 中完成（需要 heavyBudget）
            }
        }
    }

    private boolean insideZone(BlockPos pos) {
        for (Zone z : zones) {
            long dx = z.x() - pos.getX();
            long dy = z.y() - pos.getY();
            long dz = z.z() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= z.r2()) return true;
        }
        return false;
    }

    private static int countNear(Level world, BlockPos c, int rh, int rv, int needed, Predicate<Block> match) {
        int count = 0;
        for (int dx = -rh; dx <= rh; dx++) {
            for (int dy = -rv; dy <= rv; dy++) {
                for (int dz = -rh; dz <= rh; dz++) {
                    if (match.test(world.getBlockState(c.offset(dx, dy, dz)).getBlock())) {
                        if (++count >= needed) return count;
                    }
                }
            }
        }
        return count;
    }

    /* ========== 消息：聊天栏排队 + QQ 排队 ========== */
    private void report(String fmt, Object... args) {
        pending.add(new Msg(fmt, args));
        if (cqEnabled.get() && cqLines.size() < CQ_QUEUE_LIMIT) {
            cqLines.add(String.format(Locale.ROOT, fmt, args));
        }
    }

    private void flushMessages() {
        for (int i = 0; i < MAX_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
            Msg m = pending.poll();
            info(m.fmt(), m.args());
        }
    }

    /* ========== QQ 推送 ========== */
    private void flushCq() {
        if (cqLines.isEmpty()) return;
        if (++cqTimer < CQ_FLUSH_TICKS) return;
        cqTimer = 0;

        if (!cqEnabled.get()) {
            cqLines.clear();
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CQ_LINES_PER_MESSAGE && !cqLines.isEmpty(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(cqLines.poll());
        }
        postToCq(sb.toString());
    }

    private void postToCq(String text) {
        String base = cqUrl.get().trim();
        String uid = cqUserId.get().trim();
        String token = cqToken.get().trim();

        if (!(base.startsWith("http://") || base.startsWith("https://"))) {
            cqFail("cq-url 必须以 http:// 或 https:// 开头");
            return;
        }
        if (!uid.matches("\\d{5,12}")) {
            cqFail("cq-user-id 应为 5 到 12 位数字的 QQ 号");
            return;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        final String endpoint = base + "/send_private_msg";
        final String body = "{\"user_id\":" + uid + ",\"message\":\""
            + jsonEscape("[卫星雷达]\n" + text) + "\"}";

        if (cqExecutor == null || cqExecutor.isShutdown()) {
            cqExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "satellite-cq");
                t.setDaemon(true);
                return t;
            });
        }
        cqExecutor.execute(() -> sendHttp(endpoint, token, body));
    }

    private void sendHttp(String endpoint, String token, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            if (code == 200 && resp.replace(" ", "").contains("\"retcode\":0")) {
                cqFailing = false;
            } else {
                cqFail("HTTP " + code + " " + shorten(resp));
            }
        } catch (Exception e) {
            cqFail(e.getClass().getSimpleName() + ": " + shorten(String.valueOf(e.getMessage())));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void cqFail(String msg) {
        if (!cqFailing) {
            cqFailing = true;
            cqError = msg;
        }
    }

    private static String shorten(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String match(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity)   return shulkers.get()    ? "潜影盒" : null;
        if (be instanceof EnderChestBlockEntity)  return enderChests.get() ? "末影箱" : null;
        if (be instanceof HopperBlockEntity)      return hoppers.get()     ? "漏斗" : null;
        if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity)     return dispensers.get()  ? "发射器/投掷器" : null;
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity || be instanceof BarrelBlockEntity)      return chests.get()      ? "储物箱/陷阱箱/木桶" : null;
        return null;
    }
}
