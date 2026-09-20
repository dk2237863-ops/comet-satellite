package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public class SatelliteScanner extends Module {
    private static final int MAX_MESSAGES_PER_TICK = 10;
    private static final int CQ_LINES_PER_MESSAGE = 15;
    private static final int CQ_FLUSH_TICKS = 20;
    private static final int CQ_QUEUE_LIMIT = 2000;

    /* 性能相关 */
    private static final int MAX_RANGE = 512;
    private static final int CHUNKS_PER_TICK = 256;
    private static final int SWEEP_GAP_TICKS = 10;
    private static final int PROBES_PER_TICK = 12;
    private static final int DECISIONS_PER_TICK = 500;
    private static final int CACHE_RESET_SWEEPS = 60;

    /* 天然结构屏蔽半径（格） */
    private static final int R_DUNGEON = 10;
    private static final int R_TRIAL = 30;
    private static final int R_CITY = 30;
    private static final int R_VILLAGE = 56;
    private static final int R_JUNGLE = 16;
    private static final int R_DESERT = 24;
    private static final int R_MANSION = 24;
    private static final int R_SHIP = 14;

    /* 天然结构里如果这个范围内有末影箱/潜影盒，就必须通知 */
    private static final int PLAYER_MARK_RADIUS = 100;

    private static final int PHASE_WAIT = 0;
    private static final int PHASE_SWEEP = 1;
    private static final int PHASE_RESOLVE = 2;

    private enum Kind {
        OTHER, SHULKER, ENDER, HOPPER, DISPENSER, DROPPER, CHEST,
        SPAWNER, TRIAL, VAULT, SCULK, BELL
    }

    private record Msg(String fmt, Object[] args) {}
    private record Zone(int x, int y, int z, int r2) {}
    private record Cand(BlockPos pos, Kind kind) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCq = settings.createGroup("QQ推送");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("扫描半径，0 或超过 512 都按 512 算")
        .defaultValue(0.0)
        .min(0.0)
        .build());

    private final Setting<Boolean> lockNatural = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-natural-structures")
        .description("开：天然结构里的方块也锁定通知；关：跳过它们（末影箱/潜影盒100格内除外）")
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

    /* ========== 状态 ========== */
    private final java.util.Set<Integer> seenPearls = new HashSet<>();
    private final HashSet<BlockPos> seenBlocks = new HashSet<>();      // 已报告
    private final HashSet<BlockPos> suppressed = new HashSet<>();      // 已判为天然结构而跳过
    private final HashSet<BlockPos> queued = new HashSet<>();
    private final HashSet<BlockPos> markerSeen = new HashSet<>();
    private final HashSet<BlockPos> markSeen = new HashSet<>();
    private final HashSet<Long> negCells = new HashSet<>();
    private final HashMap<Long, Integer> chunkCount = new HashMap<>();
    private final HashMap<Class<?>, Kind> kindCache = new HashMap<>();
    private final HashMap<Class<?>, Boolean> pearlClassCache = new HashMap<>();
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final ArrayList<BlockPos> marks = new ArrayList<>();       // 末影箱/潜影盒位置
    private final ArrayList<Cand> unresolved = new ArrayList<>();
    private final ArrayDeque<BlockPos> dispQueue = new ArrayDeque<>();
    private final ArrayDeque<Msg> pending = new ArrayDeque<>();
    private final ArrayDeque<String> cqLines = new ArrayDeque<>();

    private int pearlTimer = 0;
    private int cqTimer = 0;
    private int phase = PHASE_WAIT;
    private int waitTicks = 0;
    private int lastCfg = -1;
    private int sweepCount = 0;
    private int sweepCx0, sweepCz0, sweepW, sweepIdx, sweepTotal;

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
        resetBlockState();
        pending.clear();
        cqLines.clear();
        pearlTimer = 0;
        cqTimer = 0;
        lastCfg = -1;
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

    private void resetBlockState() {
        suppressed.clear();
        queued.clear();
        markerSeen.clear();
        markSeen.clear();
        negCells.clear();
        chunkCount.clear();
        zones.clear();
        marks.clear();
        unresolved.clear();
        dispQueue.clear();
        phase = PHASE_WAIT;
        waitTicks = 0;
        sweepCount = 0;
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

        if (++pearlTimer >= 10) {
            pearlTimer = 0;
            if (pearls.get()) scanPearls(mc);
        }

        scanBlocksStep(mc);
    }

    /* ========== 末影珍珠 ========== */
    private void scanPearls(Minecraft mc) {
        double r = range.get();
        double rr = r <= 0 ? MAX_RANGE : Math.min(r, MAX_RANGE);
        AABB box = new AABB(
            mc.player.getX() - rr, mc.player.getY() - rr, mc.player.getZ() - rr,
            mc.player.getX() + rr, mc.player.getY() + rr, mc.player.getZ() + rr
        );

        Level world = mc.level;
        for (Entity e : world.getEntitiesOfClass(Entity.class, box, ent -> isPearlClass(ent.getClass()))) {
            int id = e.getId();
            if (seenPearls.add(id)) {
                report("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                    e.getX(), e.getY(), e.getZ());
            }
        }
    }

    private boolean isPearlClass(Class<?> c) {
        Boolean b = pearlClassCache.get(c);
        if (b == null) {
            b = c.getSimpleName().toLowerCase(Locale.ROOT).contains("enderpearl");
            pearlClassCache.put(c, b);
        }
        return b;
    }

    /* ========== 方块：分帧扫描 → 判断 → 报告 ========== */
    private void scanBlocksStep(Minecraft mc) {
        if (!(shulkers.get() || chests.get() || enderChests.get() || hoppers.get() || dispensers.get())) return;
        Level world = mc.level;

        int cfg = (shulkers.get() ? 1 : 0) | (chests.get() ? 2 : 0) | (enderChests.get() ? 4 : 0)
            | (hoppers.get() ? 8 : 0) | (dispensers.get() ? 16 : 0) | (lockNatural.get() ? 32 : 0);
        if (cfg != lastCfg) {
            resetBlockState();
            lastCfg = cfg;
        }

        switch (phase) {
            case PHASE_WAIT -> {
                if (++waitTicks >= SWEEP_GAP_TICKS) startSweep(mc);
            }
            case PHASE_SWEEP -> sweepStep(world);
            default -> resolveStep(world);
        }
    }

    private void startSweep(Minecraft mc) {
        double r = range.get();
        int radius = r <= 0 ? MAX_RANGE : (int) Math.min(r, MAX_RANGE);
        int rc = (radius + 15) >> 4;
        BlockPos c = mc.player.blockPosition();

        sweepCx0 = (c.getX() >> 4) - rc;
        sweepCz0 = (c.getZ() >> 4) - rc;
        sweepW = 2 * rc + 1;
        sweepTotal = sweepW * sweepW;
        sweepIdx = 0;

        if (++sweepCount >= CACHE_RESET_SWEEPS) {
            sweepCount = 0;
            chunkCount.clear();
            negCells.clear();
        }
        phase = PHASE_SWEEP;
    }

    private void sweepStep(Level world) {
        int n = 0;
        while (n < CHUNKS_PER_TICK && sweepIdx < sweepTotal) {
            int cx = sweepCx0 + sweepIdx % sweepW;
            int cz = sweepCz0 + sweepIdx / sweepW;
            sweepIdx++;
            n++;
            scanChunk(world, cx, cz);
        }
        if (sweepIdx >= sweepTotal) phase = PHASE_RESOLVE;
    }

    private void scanChunk(Level world, int cx, int cz) {
        var chunk = world.getChunk(cx, cz);
        var map = chunk.getBlockEntities();
        int size = map.size();

        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Integer prev = chunkCount.get(key);
        if (prev != null && prev == size) return; // 方块实体数量没变，跳过
        chunkCount.put(key, size);
        if (size == 0) return;

        final boolean filterOn = !lockNatural.get();

        for (BlockEntity be : map.values()) {
            Kind k = kindOf(be);
            if (k == Kind.OTHER) continue;
            BlockPos pos = be.getBlockPos();

            switch (k) {
                case SHULKER, ENDER -> {
                    if (filterOn && markSeen.add(pos)) marks.add(pos);
                    addCand(pos, k);
                }
                case HOPPER, DROPPER, CHEST -> addCand(pos, k);
                case DISPENSER -> {
                    if (filterOn && !markerSeen.contains(pos)) dispQueue.add(pos);
                    addCand(pos, k);
                }
                case SPAWNER -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_DUNGEON);
                }
                case TRIAL, VAULT -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_TRIAL);
                }
                case SCULK -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_CITY);
                }
                case BELL -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_VILLAGE);
                }
                default -> { }
            }
        }
    }

    private void addCand(BlockPos pos, Kind k) {
        if (labelOf(k) == null) return;
        if (seenBlocks.contains(pos) || suppressed.contains(pos)) return;
        if (queued.add(pos)) unresolved.add(new Cand(pos, k));
    }

    private void resolveStep(Level world) {
        final boolean filterOn = !lockNatural.get();
        int probes = PROBES_PER_TICK;

        // 先判断丛林神殿（发射器附近有大量苔石）
        while (!dispQueue.isEmpty() && probes > 0) {
            BlockPos p = dispQueue.poll();
            if (!markerSeen.add(p)) continue;
            probes--;
            if (countNear(world, p, 5, 3, 6, b -> b == Blocks.MOSSY_COBBLESTONE) >= 6) {
                addZone(p, R_JUNGLE);
            }
        }
        if (!dispQueue.isEmpty()) return;

        int decisions = 0;
        int done = 0;
        int n = unresolved.size();
        for (; done < n; done++) {
            if (decisions++ >= DECISIONS_PER_TICK) break;

            Cand c = unresolved.get(done);
            BlockPos p = c.pos();
            String label = labelOf(c.kind());

            if (label != null) {
                boolean natural = false;

                // 末影箱和潜影盒不是这些天然结构里会出现的东西，始终通知
                if (filterOn && c.kind() != Kind.SHULKER && c.kind() != Kind.ENDER) {
                    natural = insideZone(p);
                    if (!natural) {
                        long cell = cellKey(p);
                        if (!negCells.contains(cell)) {
                            if (probes <= 0) break; // 本 tick 检查额度用完，下个 tick 继续
                            probes--;
                            int rad = probeNatural(world, p);
                            if (rad > 0) {
                                addZone(p, rad);
                                natural = true;
                            } else {
                                negCells.add(cell);
                            }
                        }
                    }
                }

                if (natural && !markNear(p)) {
                    suppressed.add(p); // 天然结构里的，跳过不报
                } else {
                    seenBlocks.add(p);
                    report("[雷达锁定-%s] [%d, %d, %d]",
                        label, p.getX(), p.getY(), p.getZ());
                }
            }
            queued.remove(p);
        }

        if (done > 0) unresolved.subList(0, done).clear();
        if (unresolved.isEmpty()) {
            phase = PHASE_WAIT;
            waitTicks = 0;
        }
    }

    /* ========== 天然结构判断（只用来屏蔽，不通知） ========== */

    // 沙漠神殿（TNT）、林地府邸（深色橡木木板）、沉船（含水的箱子 + 木板）
    private int probeNatural(Level world, BlockPos c) {
        int tnt = 0, oak = 0, planks = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    Block b = world.getBlockState(c.offset(dx, dy, dz)).getBlock();
                    if (b == Blocks.TNT) {
                        tnt++;
                    } else if (b == Blocks.DARK_OAK_PLANKS) {
                        oak++;
                        planks++;
                    } else if (isPlanks(b)) {
                        planks++;
                    }
                }
            }
        }
        if (tnt >= 4) return R_DESERT;
        if (oak >= 12) return R_MANSION;
        if (planks >= 6 && !world.getFluidState(c).isEmpty()) return R_SHIP;
        return 0;
    }

    private static boolean isPlanks(Block b) {
        return b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.BIRCH_PLANKS
            || b == Blocks.JUNGLE_PLANKS || b == Blocks.ACACIA_PLANKS || b == Blocks.DARK_OAK_PLANKS;
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

    private void addZone(BlockPos p, int rad) {
        int r2 = rad * rad;
        for (Zone z : zones) {
            if (z.r2() == r2) {
                long dx = z.x() - p.getX();
                long dy = z.y() - p.getY();
                long dz = z.z() - p.getZ();
                if (dx * dx + dy * dy + dz * dz <= 64) return; // 同类标记靠得很近，不重复添加
            }
        }
        zones.add(new Zone(p.getX(), p.getY(), p.getZ(), r2));
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

    // 周围 100 格内有末影箱/潜影盒
    private boolean markNear(BlockPos pos) {
        long lim = (long) PLAYER_MARK_RADIUS * PLAYER_MARK_RADIUS;
        for (BlockPos m : marks) {
            long dx = m.getX() - pos.getX();
            long dy = m.getY() - pos.getY();
            long dz = m.getZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= lim) return true;
        }
        return false;
    }

    private static long cellKey(BlockPos p) {
        long x = p.getX() >> 3;
        long y = p.getY() >> 3;
        long z = p.getZ() >> 3;
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    /* ========== 方块实体分类（按类缓存，只判断一次） ========== */
    private Kind kindOf(BlockEntity be) {
        Class<?> c = be.getClass();
        Kind k = kindCache.get(c);
        if (k == null) {
            k = classify(be);
            kindCache.put(c, k);
        }
        return k;
    }

    private Kind classify(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity) return Kind.SHULKER;
        if (be instanceof EnderChestBlockEntity) return Kind.ENDER;
        if (be instanceof HopperBlockEntity) return Kind.HOPPER;
        if (be instanceof DropperBlockEntity) return Kind.DROPPER;
        if (be instanceof DispenserBlockEntity) return Kind.DISPENSER;
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity || be instanceof BarrelBlockEntity) {
            return Kind.CHEST;
        }

        String n = be.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (n.contains("trialspawner")) return Kind.TRIAL;
        if (n.contains("vault")) return Kind.VAULT;
        if (n.endsWith("spawnerblockentity")) return Kind.SPAWNER;
        if (n.contains("sculksensor") || n.contains("sculkshrieker") || n.contains("sculkcatalyst")) return Kind.SCULK;
        if (n.contains("bell")) return Kind.BELL;
        return Kind.OTHER;
    }

    private String labelOf(Kind k) {
        return switch (k) {
            case SHULKER -> shulkers.get() ? "潜影盒" : null;
            case ENDER -> enderChests.get() ? "末影箱" : null;
            case HOPPER -> hoppers.get() ? "漏斗" : null;
            case DISPENSER, DROPPER -> dispensers.get() ? "发射器/投掷器" : null;
            case CHEST -> chests.get() ? "储物箱/陷阱箱/木桶" : null;
            default -> null;
        };
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
}
