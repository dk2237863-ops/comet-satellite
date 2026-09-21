package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SatelliteScanner extends Module {
    private static final int MAX_MESSAGES_PER_TICK = 10;

    /* Qmsg酱 推送 */
    private static final int QM_QUEUE_LIMIT = 5000;
    private static final int QM_SENT_LIMIT = 50000;
    private static final int QM_MAX_REJECTS = 3;
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");

    /* 性能相关 */
    private static final int MAX_RANGE = 512;
    private static final int CHUNKS_PER_TICK = 256;
    private static final int SWEEP_GAP_TICKS = 10;
    private static final int PROBES_PER_TICK = 8;
    private static final int DECISIONS_PER_TICK = 500;
    private static final int CACHE_RESET_SWEEPS = 60;
    private static final int ENTITY_SCAN_TICKS = 10;
    private static final int VILLAGER_CAP = 5000;

    /* 天然结构：屏蔽半径（格）/ 半径内“正常”的储物方块数量上限，超过就当作玩家基地 */
    private static final int R_DUNGEON = 10, L_DUNGEON = 4;
    private static final int R_TRIAL = 30, L_TRIAL = 40;
    private static final int R_CITY = 30, L_CITY = 40;
    private static final int R_JUNGLE = 16, L_JUNGLE = 8;
    private static final int R_DESERT = 24, L_DESERT = 8;
    private static final int R_MANSION = 24, L_MANSION = 14;
    private static final int R_SHIP = 14, L_SHIP = 6;
    private static final int R_OCEAN = 14, L_OCEAN = 6;
    private static final int R_PORTAL = 12, L_PORTAL = 4;
    private static final int R_STRONGHOLD = 24, L_STRONGHOLD = 15;
    private static final int R_VILLAGE = 40, L_VILLAGE = 30;
    private static final int R_BURIED = 6, L_BURIED = 2;

    /* 村庄证据的搜索半径 */
    private static final int BELL_NEAR = 64;
    private static final int BEDS_NEAR = 40;
    private static final int VILLAGERS_NEAR = 48;

    /* 周围这个范围内有末影箱/潜影盒/珍珠点，就算“有人活动” */
    private static final int PLAYER_MARK_RADIUS = 100;

    /* 区域自动扫描 */
    private static final int ROUTE_ARRIVE_DIST = 24;
    private static final int ROUTE_IDLE_LIMIT = 6;      // 每次 10 tick，共约 3 秒没在寻路就重试
    private static final int ROUTE_MAX_RETRY = 3;
    private static final int MIN_SPACING = 48;
    private static final int MAX_SPACING = 400;
    private static final int DEFAULT_LOADED_BLOCKS = 128;

    private static final int PHASE_WAIT = 0;
    private static final int PHASE_SWEEP = 1;
    private static final int PHASE_RESOLVE = 2;

    private enum Kind {
        OTHER, SHULKER, ENDER, HOPPER, DISPENSER, DROPPER, CHEST, TRAPPED, BARREL,
        SPAWNER, TRIAL, VAULT, SCULK, BELL, BED
    }

    private record Msg(String fmt, Object[] args) {}
    private record Cand(BlockPos pos, Kind kind) {}
    private record Hit(int rad, int limit) {}

    private static final class Zone {
        final int x, y, z, r2, limit;
        int cnt = 0;
        int stamp = -1;

        Zone(int x, int y, int z, int r2, int limit) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.r2 = r2;
            this.limit = limit;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAuto = settings.createGroup("区域自动扫描");
    private final SettingGroup sgQm = settings.createGroup("QQ推送");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("扫描半径，0 或超过 512 都按 512 算（实际受服务器视距限制）")
        .defaultValue(0.0)
        .min(0.0)
        .build());

    private final Setting<Boolean> lockNatural = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-natural-structures")
        .description("开：天然结构里的方块也锁定通知；关：跳过它们（附近100格有末影箱/潜影盒/珍珠点除外）")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> strictMarker = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-marker-rule")
        .description("开：任何箱子类方块，100格内没有末影箱/潜影盒/珍珠点就当误报不报")
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
        .description("扫描箱子")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> trappedChests = sgGeneral.add(new BoolSetting.Builder()
        .name("trapped-chests")
        .description("扫描陷阱箱")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels")
        .description("扫描木桶")
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

    /* ========== 区域自动扫描设置 ========== */
    private final Setting<Boolean> useBaritone = sgAuto.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("开：区域扫描时用 Baritone 自动走航线（走地面）；关：只在聊天栏提示下一个航点，自己飞过去")
        .defaultValue(true)
        .build());

    /* ========== Qmsg酱 推送设置 ========== */
    private final Setting<Boolean> qmEnabled = sgQm.add(new BoolSetting.Builder()
        .name("qmsg-enabled")
        .description("把扫描结果通过 Qmsg酱 推送到 QQ（一条一条发，发过的不再发）")
        .defaultValue(false)
        .build());

    private final Setting<String> qmKey = sgQm.add(new StringSetting.Builder()
        .name("qmsg-key")
        .description("Qmsg酱 控制台里的 API Key（不要泄露）")
        .defaultValue("")
        .build());

    private final Setting<Integer> qmInterval = sgQm.add(new IntSetting.Builder()
        .name("qmsg-interval-seconds")
        .description("两条消息之间的间隔（秒）。Qmsg酱要求同一 Key 至少间隔 5 秒，每天最多 500 条")
        .defaultValue(15)
        .min(5)
        .max(300)
        .build());

    private final Setting<String> qmGroup = sgQm.add(new StringSetting.Builder()
        .name("qmsg-group")
        .description("目标QQ群号，留空则发到你的QQ单聊（群需先在Qmsg酱控制台绑定）")
        .defaultValue("")
        .build());

    private final Setting<String> qmHost = sgQm.add(new StringSetting.Builder()
        .name("qmsg-host")
        .description("接口域名，默认官方地址")
        .defaultValue("https://qmsg.zendee.cn")
        .build());

    /* ========== 状态 ========== */
    private final java.util.Set<Integer> seenPearls = new HashSet<>();
    private final HashSet<BlockPos> seenBlocks = new HashSet<>();      // 已报告
    private final HashSet<BlockPos> suppressed = new HashSet<>();      // 已判为误报/天然结构而跳过
    private final HashSet<BlockPos> queued = new HashSet<>();
    private final HashSet<BlockPos> markSeen = new HashSet<>();
    private final HashSet<BlockPos> bellSeen = new HashSet<>();
    private final HashSet<BlockPos> bedSeen = new HashSet<>();
    private final HashSet<BlockPos> markerSeen = new HashSet<>();
    private final HashSet<BlockPos> storageSeen = new HashSet<>();
    private final HashSet<Long> negCells = new HashSet<>();
    private final HashMap<Long, Integer> chunkCount = new HashMap<>();
    private final HashMap<Class<?>, Kind> kindCache = new HashMap<>();
    private final HashMap<Class<?>, Boolean> pearlClassCache = new HashMap<>();
    private final HashMap<Class<?>, Boolean> villagerClassCache = new HashMap<>();
    private final HashMap<Class<?>, Boolean> emptyChunkCache = new HashMap<>();
    private final HashMap<Integer, BlockPos> villagerSeen = new HashMap<>();
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final ArrayList<BlockPos> marks = new ArrayList<>();       // 末影箱/潜影盒位置
    private final ArrayList<BlockPos> pearlMarks = new ArrayList<>();  // 珍珠点
    private final ArrayList<BlockPos> bells = new ArrayList<>();
    private final ArrayList<BlockPos> beds = new ArrayList<>();
    private final ArrayList<BlockPos> storage = new ArrayList<>();     // 所有储物方块位置，用于判断数量是否正常
    private final ArrayList<Cand> unresolved = new ArrayList<>();
    private final ArrayDeque<BlockPos> dispQueue = new ArrayDeque<>();
    private final ArrayDeque<Msg> pending = new ArrayDeque<>();

    /* Qmsg 队列 */
    private final ArrayDeque<String> qmLines = new ArrayDeque<>();
    private final HashSet<String> qmQueued = new HashSet<>();
    private final java.util.Set<String> qmSent = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> qmRetry = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Integer> qmRejects = new ConcurrentHashMap<>();
    private int qmCooldown = 0;

    /* 区域自动扫描状态 */
    private final BaritoneBridge bridge = new BaritoneBridge();
    private final ArrayList<int[]> route = new ArrayList<>();
    private boolean regionActive = false;
    private int regMinX, regMaxX, regMinZ, regMaxZ;
    private int routeIdx = 0;
    private int routeRetry = 0;
    private int routeIdle = 0;
    private boolean cmdRegistered = false;
    private boolean cmdErrorShown = false;
    private boolean baritoneWarned = false;
    private int cmdRetryTimer = 0;

    private int entityTimer = 0;
    private int phase = PHASE_WAIT;
    private int waitTicks = 0;
    private int lastCfg = -1;
    private int sweepCount = 0;
    private int resolveId = 0;
    private int sweepCx0, sweepCz0, sweepW, sweepIdx, sweepTotal;
    private int sweepPcx, sweepPcz, sweepMaxDist, lastMaxDist, lastReportedRange;

    private ExecutorService qmExecutor;
    private volatile boolean qmFailing = false;
    private volatile String qmError = null;

    public SatelliteScanner() {
        super(
            AddonTemplate.CATEGORY,
            "satellite-scanner",
            "首次入雷达即锁定：珍珠永久静默，方块移位再报"
        );
        tryRegisterBaritoneCommand();
    }

    @Override
    public void onActivate() {
        seenPearls.clear();
        seenBlocks.clear();
        pearlMarks.clear();
        villagerSeen.clear();
        resetBlockState();
        pending.clear();
        qmLines.clear();
        qmQueued.clear();
        qmRetry.clear();
        qmRejects.clear();
        qmCooldown = 0;
        entityTimer = 0;
        lastCfg = -1;
        lastMaxDist = 0;
        lastReportedRange = 0;
        regionActive = false;
        route.clear();
        qmFailing = false;
        qmError = null;
        tryRegisterBaritoneCommand();
        if (qmEnabled.get()) {
            qmLines.add("已启动 " + timeStr() + "，推送正常时会收到这条消息");
        }
    }

    @Override
    public void onDeactivate() {
        stopRegion(null);
        qmLines.clear();
        qmQueued.clear();
        if (qmExecutor != null) {
            qmExecutor.shutdown();
            qmExecutor = null;
        }
    }

    private void resetBlockState() {
        suppressed.clear();
        queued.clear();
        markerSeen.clear();
        markSeen.clear();
        bellSeen.clear();
        bedSeen.clear();
        storageSeen.clear();
        negCells.clear();
        chunkCount.clear();
        zones.clear();
        marks.clear();
        bells.clear();
        beds.clear();
        storage.clear();
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
        flushQmsg();

        String err = qmError;
        if (err != null) {
            qmError = null;
            info("[Qmsg推送] 发送失败：%s", err);
        }

        if (!cmdRegistered && ++cmdRetryTimer >= 200) {
            cmdRetryTimer = 0;
            tryRegisterBaritoneCommand();
            if (!cmdRegistered && bridge.available() && !cmdErrorShown) {
                cmdErrorShown = true;
                info("[区域扫描] 没能向 Baritone 注册 # 指令：%s（仍可在聊天栏输入 #scan，由本模块直接处理）", bridge.lastError());
            }
        }

        if (++entityTimer >= ENTITY_SCAN_TICKS) {
            entityTimer = 0;
            scanEntities(mc);
            routeTick(mc);
        }

        scanBlocksStep(mc);
    }

    /* 备用：Baritone 没注册成功时，直接在聊天发送阶段拦截 #scan */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSendMessage(SendMessageEvent event) {
        String m = event.message;
        if (m == null) return;
        String t = m.trim();
        String low = t.toLowerCase(Locale.ROOT);
        for (String p : new String[]{"#scan", "#satscan"}) {
            if (low.equals(p) || low.startsWith(p + " ")) {
                event.cancel();
                handleCommand(t.substring(p.length()).trim());
                return;
            }
        }
    }

    /* ========== 指令：#scan x z, x z ========== */
    private void tryRegisterBaritoneCommand() {
        if (cmdRegistered || !bridge.available()) return;
        cmdRegistered = bridge.registerCommand(
            List.of("scan", "satscan"),
            "扫描两个坐标之间的矩形区域（卫星雷达）",
            List.of(
                "用法：#scan x z, x z   （第一个是起点，第二个是终点）",
                "例如：#scan 100 200, 500 800",
                "#scan stop    停止区域扫描",
                "#scan status  查看进度"
            ),
            this::handleCommand
        );
    }

    private void handleCommand(String raw) {
        String s = raw == null ? "" : raw.trim();
        String low = s.toLowerCase(Locale.ROOT);

        if (low.isEmpty() || low.equals("help")) {
            showUsage();
            return;
        }
        if (low.equals("stop") || low.equals("cancel")) {
            stopRegion("已停止区域扫描");
            return;
        }
        if (low.equals("status")) {
            showStatus();
            return;
        }

        ArrayList<Double> nums = new ArrayList<>();
        for (String p : s.split("[\\s,;]+")) {
            if (p.isEmpty()) continue;
            try {
                nums.add(Double.parseDouble(p));
            } catch (NumberFormatException e) {
                info("无法识别的参数：%s", p);
                showUsage();
                return;
            }
        }

        double x1, z1, x2, z2;
        if (nums.size() == 4) {
            x1 = nums.get(0);
            z1 = nums.get(1);
            x2 = nums.get(2);
            z2 = nums.get(3);
        } else if (nums.size() == 6) {
            // x y z, x y z：忽略 y
            x1 = nums.get(0);
            z1 = nums.get(2);
            x2 = nums.get(3);
            z2 = nums.get(5);
        } else {
            showUsage();
            return;
        }

        if (!isActive()) toggle(); // 扫描需要模块处于开启状态
        startRegion((int) Math.floor(x1), (int) Math.floor(z1), (int) Math.floor(x2), (int) Math.floor(z2));
    }

    private void showUsage() {
        info("用法：#scan x z, x z（第一个是起点，第二个是终点），例如 #scan 100 200, 500 800");
        info("#scan stop 停止；#scan status 查看进度");
    }

    private void showStatus() {
        if (!regionActive) {
            info("当前没有区域扫描任务");
            return;
        }
        info("区域扫描进行中：X %d ~ %d，Z %d ~ %d，航点 %d/%d",
            regMinX, regMaxX, regMinZ, regMaxZ, Math.min(routeIdx + 1, route.size()), route.size());
    }

    private void startRegion(int x1, int z1, int x2, int z2) {
        regMinX = Math.min(x1, x2);
        regMaxX = Math.max(x1, x2);
        regMinZ = Math.min(z1, z2);
        regMaxZ = Math.max(z1, z2);

        int loaded = lastMaxDist > 0 ? lastMaxDist * 16 : DEFAULT_LOADED_BLOCKS;
        int spacing = Math.max(MIN_SPACING, Math.min(MAX_SPACING, (int) (loaded * 1.6)));
        buildRoute(spacing);

        routeIdx = 0;
        routeRetry = 0;
        routeIdle = 0;
        regionActive = true;
        baritoneWarned = false;

        info("区域扫描开始：X %d ~ %d，Z %d ~ %d，共 %d 个航点（航线间隔 %d 格）",
            regMinX, regMaxX, regMinZ, regMaxZ, route.size(), spacing);
        pushQq(String.format(Locale.ROOT, "区域扫描开始 X %d~%d Z %d~%d %s",
            regMinX, regMaxX, regMinZ, regMaxZ, timeStr()));
        issueWaypoint();
    }

    // 蛇形航线：沿较长的一边来回走，每条航线间隔 spacing 格
    private void buildRoute(int spacing) {
        route.clear();
        int w = regMaxX - regMinX;
        int d = regMaxZ - regMinZ;
        boolean lanesAlongX = w >= d;

        int laneMin = lanesAlongX ? regMinZ : regMinX;
        int laneMax = lanesAlongX ? regMaxZ : regMaxX;
        int runMin = lanesAlongX ? regMinX : regMinZ;
        int runMax = lanesAlongX ? regMaxX : regMaxZ;

        ArrayList<Integer> centers = new ArrayList<>();
        if (laneMax - laneMin <= spacing) {
            centers.add((laneMin + laneMax) / 2);
        } else {
            int half = spacing / 2;
            int c = laneMin + half;
            while (true) {
                centers.add(Math.min(c, laneMax));
                if (c + half >= laneMax) break;
                c += spacing;
            }
        }

        boolean forward = true;
        for (int lane : centers) {
            int a = forward ? runMin : runMax;
            int b = forward ? runMax : runMin;
            if (lanesAlongX) {
                route.add(new int[]{a, lane});
                route.add(new int[]{b, lane});
            } else {
                route.add(new int[]{lane, a});
                route.add(new int[]{lane, b});
            }
            forward = !forward;
        }
    }

    private void issueWaypoint() {
        if (routeIdx >= route.size()) {
            finishRegion();
            return;
        }
        int[] wp = route.get(routeIdx);
        info("前往航点 %d/%d：X %d / Z %d", routeIdx + 1, route.size(), wp[0], wp[1]);

        if (useBaritone.get()) {
            if (!bridge.available()) {
                if (!baritoneWarned) {
                    baritoneWarned = true;
                    info("没有检测到 Baritone，请手动前往每个航点，扫描仍然会进行");
                }
            } else if (!bridge.goToXZ(wp[0], wp[1])) {
                info("Baritone 寻路失败：%s（可手动前往该航点）", bridge.lastError());
            }
        }
    }

    private void routeTick(Minecraft mc) {
        if (!regionActive) return;
        if (routeIdx >= route.size()) {
            finishRegion();
            return;
        }

        int[] wp = route.get(routeIdx);
        double dx = mc.player.getX() - wp[0];
        double dz = mc.player.getZ() - wp[1];
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= ROUTE_ARRIVE_DIST) {
            routeIdx++;
            routeRetry = 0;
            routeIdle = 0;
            issueWaypoint();
            return;
        }

        if (useBaritone.get() && bridge.available()) {
            if (bridge.isPathing()) {
                routeIdle = 0;
            } else if (++routeIdle >= ROUTE_IDLE_LIMIT) {
                routeIdle = 0;
                if (++routeRetry > ROUTE_MAX_RETRY) {
                    info("航点 %d 无法到达，已跳过", routeIdx + 1);
                    routeIdx++;
                    routeRetry = 0;
                    issueWaypoint();
                } else {
                    bridge.goToXZ(wp[0], wp[1]);
                }
            }
        }
    }

    private void finishRegion() {
        stopRegion(null);
        info("区域扫描完成");
        pushQq("区域扫描完成 " + timeStr());
    }

    private void stopRegion(String msg) {
        if (regionActive) {
            regionActive = false;
            route.clear();
            if (useBaritone.get() && bridge.available()) bridge.stop();
        }
        if (msg != null) info("%s", msg);
    }

    private boolean inRegion(int x, int z) {
        return !regionActive || (x >= regMinX && x <= regMaxX && z >= regMinZ && z <= regMaxZ);
    }

    private static String timeStr() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /* ========== 实体：末影珍珠（也当作“有人活动”的标记）+ 村民（村庄证据） ========== */
    private void scanEntities(Minecraft mc) {
        final boolean wantVillagers = !lockNatural.get();

        double r = range.get();
        double rr = r <= 0 ? MAX_RANGE : Math.min(r, MAX_RANGE);
        // 垂直方向几乎不限，保证在高空飞行时也能看到地下深处的珍珠
        AABB box = new AABB(
            mc.player.getX() - rr, -1024, mc.player.getZ() - rr,
            mc.player.getX() + rr, 2048, mc.player.getZ() + rr
        );

        Level world = mc.level;
        for (Entity e : world.getEntitiesOfClass(Entity.class, box, ent -> {
            Class<?> kk = ent.getClass();
            return isPearlClass(kk) || (wantVillagers && isVillagerClass(kk));
        })) {
            Class<?> k = e.getClass();
            if (isPearlClass(k)) {
                int id = e.getId();
                if (seenPearls.add(id)) {
                    pearlMarks.add(e.blockPosition());
                    if (pearls.get() && inRegion((int) Math.floor(e.getX()), (int) Math.floor(e.getZ()))) {
                        report("[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                            e.getX(), e.getY(), e.getZ());
                    }
                }
            } else if (villagerSeen.size() < VILLAGER_CAP || villagerSeen.containsKey(e.getId())) {
                villagerSeen.put(e.getId(), e.blockPosition());
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

    private boolean isVillagerClass(Class<?> c) {
        Boolean b = villagerClassCache.get(c);
        if (b == null) {
            b = c.getSimpleName().toLowerCase(Locale.ROOT).equals("villager");
            villagerClassCache.put(c, b);
        }
        return b;
    }

    private boolean isEmptyChunkClass(Class<?> c) {
        Boolean b = emptyChunkCache.get(c);
        if (b == null) {
            b = c.getSimpleName().equals("EmptyLevelChunk");
            emptyChunkCache.put(c, b);
        }
        return b;
    }

    /* ========== 方块：分帧扫描 → 判断 → 报告（整列区块，没有高度限制） ========== */
    private void scanBlocksStep(Minecraft mc) {
        if (!(shulkers.get() || chests.get() || trappedChests.get() || barrels.get()
            || enderChests.get() || hoppers.get() || dispensers.get())) return;
        Level world = mc.level;

        int cfg = (shulkers.get() ? 1 : 0) | (chests.get() ? 2 : 0) | (enderChests.get() ? 4 : 0)
            | (hoppers.get() ? 8 : 0) | (dispensers.get() ? 16 : 0) | (lockNatural.get() ? 32 : 0)
            | (barrels.get() ? 64 : 0) | (trappedChests.get() ? 128 : 0) | (strictMarker.get() ? 256 : 0);
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
        // 上一轮已经知道真正加载了多远，就不再去查那些肯定没加载的区块
        if (lastMaxDist > 0) rc = Math.min(rc, lastMaxDist + 2);

        BlockPos c = mc.player.blockPosition();
        sweepPcx = c.getX() >> 4;
        sweepPcz = c.getZ() >> 4;
        sweepCx0 = sweepPcx - rc;
        sweepCz0 = sweepPcz - rc;
        sweepW = 2 * rc + 1;
        sweepTotal = sweepW * sweepW;
        sweepIdx = 0;
        sweepMaxDist = 0;

        if (++sweepCount >= CACHE_RESET_SWEEPS) {
            sweepCount = 0;
            chunkCount.clear();
            negCells.clear();
            storage.clear();
            storageSeen.clear();
            suppressed.clear(); // 让之前被当作误报的方块用最新的标记信息重新判断一次
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
        if (sweepIdx >= sweepTotal) {
            if (sweepMaxDist > 0) {
                lastMaxDist = sweepMaxDist;
                int blocks = sweepMaxDist * 16 + 8;
                if (Math.abs(blocks - lastReportedRange) >= 16) {
                    lastReportedRange = blocks;
                    info("[卫星雷达] 实际已加载范围约 %d 格（服务器没发给你的区块扫不到，想扫更远需要调大视距）", blocks);
                }
            }
            resolveId++;
            phase = PHASE_RESOLVE;
        }
    }

    private void scanChunk(Level world, int cx, int cz) {
        var chunk = world.getChunk(cx, cz);

        // 统计真正加载了多远（空区块不算）
        if (!isEmptyChunkClass(chunk.getClass())) {
            int d = Math.max(Math.abs(cx - sweepPcx), Math.abs(cz - sweepPcz));
            if (d > sweepMaxDist) sweepMaxDist = d;
        }

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
                    if (markSeen.add(pos)) marks.add(pos);
                    addCand(pos, k);
                }
                case HOPPER, DROPPER, CHEST, TRAPPED, BARREL -> {
                    regStorage(pos);
                    addCand(pos, k);
                }
                case DISPENSER -> {
                    regStorage(pos);
                    if (filterOn && !markerSeen.contains(pos)) dispQueue.add(pos);
                    addCand(pos, k);
                }
                case SPAWNER -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_DUNGEON, L_DUNGEON);
                }
                case TRIAL, VAULT -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_TRIAL, L_TRIAL);
                }
                case SCULK -> {
                    if (filterOn && markerSeen.add(pos)) addZone(pos, R_CITY, L_CITY);
                }
                case BELL -> {
                    if (bellSeen.add(pos)) bells.add(pos);
                }
                case BED -> {
                    if (bedSeen.add(pos)) beds.add(pos);
                }
                default -> { }
            }
        }
    }

    private void regStorage(BlockPos pos) {
        if (storageSeen.add(pos)) storage.add(pos);
    }

    private void addCand(BlockPos pos, Kind k) {
        if (labelOf(k) == null) return;
        if (!inRegion(pos.getX(), pos.getZ())) return;
        if (seenBlocks.contains(pos) || suppressed.contains(pos)) return;
        if (queued.add(pos)) unresolved.add(new Cand(pos, k));
    }

    private void resolveStep(Level world) {
        final boolean filterOn = !lockNatural.get();
        final boolean strict = strictMarker.get();
        int probes = PROBES_PER_TICK;

        // 先判断丛林神殿（发射器附近有大量苔石）
        while (!dispQueue.isEmpty() && probes > 0) {
            BlockPos p = dispQueue.poll();
            if (!markerSeen.add(p)) continue;
            probes--;
            if (countNear(world, p, 5, 3, 6, b -> b == Blocks.MOSSY_COBBLESTONE) >= 6) {
                addZone(p, R_JUNGLE, L_JUNGLE);
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
            if (label != null && !inRegion(p.getX(), p.getZ())) label = null;

            if (label != null) {
                boolean suppress = false;

                // 末影箱和潜影盒本身就是“有人活动”的标记，始终通知
                if (c.kind() != Kind.SHULKER && c.kind() != Kind.ENDER) {
                    boolean hasMarker = markNear(p);

                    if (strict && !hasMarker) {
                        suppress = true; // 附近没有末影箱/潜影盒/珍珠点：当作误报
                    } else if (filterOn && !hasMarker) {
                        // 附近没有标记，才需要判断是不是天然结构里的
                        int st = zoneState(p);
                        if (st == 0) {
                            long cell = cellKey(p);
                            if (!negCells.contains(cell)) {
                                if (probes <= 0) break; // 本 tick 检查额度用完，下个 tick 继续
                                probes--;
                                Hit h = probeNatural(world, p);
                                if (h != null) {
                                    addZone(p, h.rad(), h.limit());
                                    st = zoneState(p);
                                } else {
                                    negCells.add(cell);
                                }
                            }
                        }
                        if (st == 2) suppress = true; // 处在数量正常的天然结构范围内
                    }
                    // 有标记（末影箱/潜影盒/珍珠点）：不管是不是天然结构，都要报
                }

                if (suppress) {
                    suppressed.add(p);
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
    private Hit probeNatural(Level world, BlockPos c) {
        boolean waterlogged = !world.getFluidState(c).isEmpty();

        int tnt = 0, oak = 0, planks = 0, path = 0;
        int crying = 0, bricks = 0, chiseled = 0;
        int water2 = 0, sandNear = 0;

        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    Block b = world.getBlockState(c.offset(dx, dy, dz)).getBlock();

                    // 土径：水平 8 格、垂直 3 格
                    if (b == Blocks.DIRT_PATH) {
                        if (dy >= -3 && dy <= 3) path++;
                        continue;
                    }

                    // 箱子周围 2 格内的水源（沉船/海底遗迹）
                    if (b == Blocks.WATER) {
                        if (dx >= -2 && dx <= 2 && dy >= -2 && dy <= 2 && dz >= -2 && dz <= 2) water2++;
                        continue;
                    }

                    // 埋藏的宝藏：紧贴箱子周围 26 格里全是沙/砂砾/砂岩
                    if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1 && dz >= -1 && dz <= 1
                        && (b == Blocks.SAND || b == Blocks.GRAVEL || b == Blocks.SANDSTONE)) {
                        sandNear++;
                    }

                    // 其余几项只统计中心 13x11x13 范围
                    if (dx < -6 || dx > 6 || dz < -6 || dz > 6) continue;

                    if (b == Blocks.TNT) {
                        tnt++;
                    } else if (b == Blocks.CRYING_OBSIDIAN) {
                        crying++;
                    } else if (b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS) {
                        bricks++;
                    } else if (b == Blocks.CHISELED_STONE_BRICKS) {
                        chiseled++;
                    } else if (b == Blocks.DARK_OAK_PLANKS) {
                        oak++;
                        planks++;
                    } else if (isPlanks(b)) {
                        planks++;
                    }
                }
            }
        }

        boolean nearWater = waterlogged || water2 >= 3;

        if (tnt >= 4) return new Hit(R_DESERT, L_DESERT);                          // 沙漠神殿
        if (crying >= 1) return new Hit(R_PORTAL, L_PORTAL);                       // 废弃传送门
        if (oak >= 12) return new Hit(R_MANSION, L_MANSION);                       // 林地府邸/掠夺者前哨站
        if (planks >= 6 && nearWater) return new Hit(R_SHIP, L_SHIP);              // 沉船（要有水源）
        if (bricks + chiseled >= 4 && nearWater) return new Hit(R_OCEAN, L_OCEAN); // 海底遗迹
        if (bricks >= 10) return new Hit(R_STRONGHOLD, L_STRONGHOLD);              // 要塞
        if (villageEvidence(c, path)) return new Hit(R_VILLAGE, L_VILLAGE);        // 村庄
        if (sandNear >= 18) return new Hit(R_BURIED, L_BURIED);                    // 埋藏的宝藏
        return null;
    }

    // 村庄：环境证据（土径/村民）+ 结构证据（钟/床）组合判断
    private boolean villageEvidence(BlockPos c, int path) {
        boolean pathOk = path >= 8;
        int villagers = countWithin(villagerSeen.values(), c, VILLAGERS_NEAR, 2);
        boolean env = pathOk || villagers >= 2;
        boolean structure = countWithin(bells, c, BELL_NEAR, 1) >= 1
            || countWithin(beds, c, BEDS_NEAR, 3) >= 3;
        return (env && structure) || (pathOk && villagers >= 2);
    }

    private static int countWithin(Iterable<BlockPos> list, BlockPos c, int rad, int needed) {
        long lim = (long) rad * rad;
        int cnt = 0;
        for (BlockPos p : list) {
            long dx = p.getX() - c.getX();
            long dy = p.getY() - c.getY();
            long dz = p.getZ() - c.getZ();
            if (dx * dx + dy * dy + dz * dz <= lim) {
                if (++cnt >= needed) return cnt;
            }
        }
        return cnt;
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

    private void addZone(BlockPos p, int rad, int limit) {
        int r2 = rad * rad;
        for (Zone z : zones) {
            if (z.r2 == r2) {
                long dx = z.x - p.getX();
                long dy = z.y - p.getY();
                long dz = z.z - p.getZ();
                if (dx * dx + dy * dy + dz * dz <= 64) return; // 同类标记靠得很近，不重复添加
            }
        }
        zones.add(new Zone(p.getX(), p.getY(), p.getZ(), r2, limit));
    }

    // 0 = 不在任何天然结构范围内；1 = 在范围内但储物数量超标（像玩家基地）；2 = 在范围内且数量正常
    private int zoneState(BlockPos pos) {
        boolean covered = false;
        for (Zone z : zones) {
            long dx = z.x - pos.getX();
            long dy = z.y - pos.getY();
            long dz = z.z - pos.getZ();
            if (dx * dx + dy * dy + dz * dz > z.r2) continue;
            covered = true;
            if (zoneCount(z) <= z.limit) return 2;
        }
        return covered ? 1 : 0;
    }

    private int zoneCount(Zone z) {
        if (z.stamp != resolveId) {
            int cnt = 0;
            for (BlockPos s : storage) {
                long dx = z.x - s.getX();
                long dy = z.y - s.getY();
                long dz = z.z - s.getZ();
                if (dx * dx + dy * dy + dz * dz <= z.r2) cnt++;
            }
            z.cnt = cnt;
            z.stamp = resolveId;
        }
        return z.cnt;
    }

    // 周围 100 格内有末影箱、潜影盒或珍珠点
    private boolean markNear(BlockPos pos) {
        return countWithin(marks, pos, PLAYER_MARK_RADIUS, 1) >= 1
            || countWithin(pearlMarks, pos, PLAYER_MARK_RADIUS, 1) >= 1;
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
        if (be instanceof TrappedChestBlockEntity) return Kind.TRAPPED;
        if (be instanceof ChestBlockEntity) return Kind.CHEST;
        if (be instanceof BarrelBlockEntity) return Kind.BARREL;

        String n = be.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (n.contains("trialspawner")) return Kind.TRIAL;
        if (n.contains("vault")) return Kind.VAULT;
        if (n.endsWith("spawnerblockentity")) return Kind.SPAWNER;
        if (n.contains("sculksensor") || n.contains("sculkshrieker") || n.contains("sculkcatalyst")) return Kind.SCULK;
        if (n.contains("bell")) return Kind.BELL;
        if (n.endsWith("bedblockentity")) return Kind.BED;
        return Kind.OTHER;
    }

    private String labelOf(Kind k) {
        return switch (k) {
            case SHULKER -> shulkers.get() ? "潜影盒" : null;
            case ENDER -> enderChests.get() ? "末影箱" : null;
            case HOPPER -> hoppers.get() ? "漏斗" : null;
            case DISPENSER, DROPPER -> dispensers.get() ? "发射器/投掷器" : null;
            case CHEST -> chests.get() ? "箱子" : null;
            case TRAPPED -> trappedChests.get() ? "陷阱箱" : null;
            case BARREL -> barrels.get() ? "木桶" : null;
            default -> null;
        };
    }

    /* ========== 消息：聊天栏即时显示；QQ 排队 ========== */
    private void report(String fmt, Object... args) {
        pending.add(new Msg(fmt, args));
        pushQq(String.format(Locale.ROOT, fmt, args));
    }

    private void pushQq(String raw) {
        if (!qmEnabled.get()) return;
        String text = softenDigits(raw);
        // 已发过的、已在队列里的都不再入队
        if (qmLines.size() < QM_QUEUE_LIMIT && !qmSent.contains(text) && qmQueued.add(text)) {
            qmLines.add(text);
        }
    }

    private void flushMessages() {
        for (int i = 0; i < MAX_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
            Msg m = pending.poll();
            info(m.fmt(), m.args());
        }
    }

    // Qmsg酱会拦截"连续数字"，所以把 4 位及以上的数字加上千位分隔，例如 12345 -> 12,345
    private static String softenDigits(String s) {
        Matcher m = LONG_DIGITS.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String d = m.group();
            StringBuilder g = new StringBuilder();
            int len = d.length();
            for (int i = 0; i < len; i++) {
                if (i > 0 && (len - i) % 3 == 0) g.append(',');
                g.append(d.charAt(i));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(g.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /* ========== Qmsg酱 推送：一条一条发，多的排队，发过的不再发 ========== */
    private void flushQmsg() {
        // 之前发送失败的，放回队列最前面等下一次重试
        String failed;
        while ((failed = qmRetry.poll()) != null) {
            if (qmQueued.add(failed)) qmLines.addFirst(failed);
        }

        if (qmCooldown > 0) {
            qmCooldown--;
            return;
        }
        if (qmLines.isEmpty()) return;

        if (!qmEnabled.get()) {
            qmLines.clear();
            qmQueued.clear();
            return;
        }

        // 取出下一条没发过的
        String text = null;
        while (!qmLines.isEmpty()) {
            String t = qmLines.poll();
            qmQueued.remove(t);
            if (!qmSent.contains(t)) {
                text = t;
                break;
            }
        }
        if (text == null) return;

        qmCooldown = qmInterval.get() * 20;

        if (!postToQmsg(text)) {
            // 配置不对，没发出去：放回队首，下个间隔再试
            qmLines.addFirst(text);
            qmQueued.add(text);
        }
    }

    private boolean postToQmsg(String text) {
        String host = qmHost.get().trim();
        String key = qmKey.get().trim();
        String group = qmGroup.get().trim();

        if (!(host.startsWith("http://") || host.startsWith("https://"))) {
            qmFail("qmsg-host 必须以 http:// 或 https:// 开头");
            return false;
        }
        if (!key.matches("[A-Za-z0-9_-]{8,64}")) {
            qmFail("qmsg-key 格式不对，请到 Qmsg酱 控制台复制 API Key");
            return false;
        }
        if (!group.isEmpty() && !group.matches("\\d{5,12}")) {
            qmFail("qmsg-group 应为群号数字，或者留空");
            return false;
        }
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        final String endpoint = host + "/v3/jsend/" + key;

        StringBuilder json = new StringBuilder("{\"msg\":\"")
            .append(jsonEscape("[卫星雷达]\n" + text)).append("\"");
        if (!group.isEmpty()) json.append(",\"group\":\"").append(group).append("\"");
        json.append("}");
        final String body = json.toString();

        if (qmExecutor == null || qmExecutor.isShutdown()) {
            qmExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "satellite-qmsg");
                t.setDaemon(true);
                return t;
            });
        }
        qmExecutor.execute(() -> sendHttp(endpoint, body, text));
        return true;
    }

    private void sendHttp(String endpoint, String body, String text) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            if (code == 200 && resp.replace(" ", "").contains("\"success\":true")) {
                qmFailing = false;
                qmRejects.remove(text);
                markSent(text); // 提交成功，记为已发，以后不再发
                return;
            }

            // 服务器回复了但没成功
            boolean violation = resp.contains("违规") || resp.contains("敏感") || resp.contains("禁止");
            if (violation) {
                int n = qmRejects.merge(text, 1, Integer::sum);
                if (n >= QM_MAX_REJECTS) {
                    // 这条消息被判违规，反复发也没用，跳过，别卡住后面的
                    qmRejects.remove(text);
                    markSent(text);
                    qmFail("这条消息被判违规，已跳过：" + shorten(resp));
                    return;
                }
            }
            qmFail("HTTP " + code + " " + shorten(resp));
            qmRetry.add(text); // 其他原因（限额、限流等）会一直重试，等恢复后继续发
        } catch (Exception e) {
            qmFail(e.getClass().getSimpleName() + ": " + shorten(String.valueOf(e.getMessage())));
            qmRetry.add(text);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void markSent(String text) {
        if (qmSent.size() > QM_SENT_LIMIT) qmSent.clear();
        qmSent.add(text);
    }

    private void qmFail(String msg) {
        if (!qmFailing) {
            qmFailing = true;
            qmError = msg;
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
