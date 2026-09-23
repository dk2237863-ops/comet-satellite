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
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.function.Consumer;

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
    private static final int ENTITY_SCAN_TICKS = 20;
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
        final BlockPos c;
        final int r, r2, limit;
        int cnt = 0;
        int stamp = -1;

        Zone(BlockPos c, int r, int limit) {
            this.c = c;
            this.r = r;
            this.r2 = r * r;
            this.limit = limit;
        }
    }

    /** 按 64x64 网格分桶的位置索引：半径查询只看附近几个桶，不再遍历整个列表。 */
    private static final class PosIndex {
        private final HashMap<Long, ArrayList<BlockPos>> cells = new HashMap<>();

        private static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        }

        void add(BlockPos p) {
            cells.computeIfAbsent(key(p.getX() >> 6, p.getZ() >> 6), k -> new ArrayList<>()).add(p);
        }

        void clear() {
            cells.clear();
        }

        int countWithin(BlockPos c, int rad, int needed) {
            long lim = (long) rad * rad;
            int x0 = (c.getX() - rad) >> 6, x1 = (c.getX() + rad) >> 6;
            int z0 = (c.getZ() - rad) >> 6, z1 = (c.getZ() + rad) >> 6;
            int cnt = 0;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    ArrayList<BlockPos> list = cells.get(key(cx, cz));
                    if (list == null) continue;
                    for (int i = 0, n = list.size(); i < n; i++) {
                        BlockPos p = list.get(i);
                        long dx = p.getX() - c.getX();
                        long dy = p.getY() - c.getY();
                        long dz = p.getZ() - c.getZ();
                        if (dx * dx + dy * dy + dz * dz <= lim && ++cnt >= needed) return cnt;
                    }
                }
            }
            return cnt;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgContainers = settings.createGroup("容器扫描");
    private final SettingGroup sgAuto = settings.createGroup("区域自动扫描（鞘翅）");
    private final SettingGroup sgQm = settings.createGroup("QQ推送");

    /* ========== 通用 ========== */
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
        .description("开：任何箱子类方块，100格内没有末影箱/潜影盒/珍珠点就当误报不报。区域扫描时强制开启")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("pearls")
        .description("扫描末影珍珠")
        .defaultValue(true)
        .build());

    /* ========== 容器开关 ========== */
    private final Setting<Boolean> scanContainers = sgContainers.add(new BoolSetting.Builder()
        .name("scan-containers")
        .description("容器扫描总开关：关掉就完全不扫方块容器（珍珠不受影响）")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> shulkers = sgContainers.add(new BoolSetting.Builder()
        .name("shulkers").description("潜影盒").defaultValue(true).build());

    private final Setting<Boolean> enderChests = sgContainers.add(new BoolSetting.Builder()
        .name("ender-chests").description("末影箱").defaultValue(true).build());

    private final Setting<Boolean> chests = sgContainers.add(new BoolSetting.Builder()
        .name("chests").description("箱子").defaultValue(true).build());

    private final Setting<Boolean> trappedChests = sgContainers.add(new BoolSetting.Builder()
        .name("trapped-chests").description("陷阱箱").defaultValue(true).build());

    private final Setting<Boolean> barrels = sgContainers.add(new BoolSetting.Builder()
        .name("barrels").description("木桶").defaultValue(true).build());

    private final Setting<Boolean> hoppers = sgContainers.add(new BoolSetting.Builder()
        .name("hoppers").description("漏斗").defaultValue(true).build());

    private final Setting<Boolean> dispensers = sgContainers.add(new BoolSetting.Builder()
        .name("dispensers").description("发射器").defaultValue(true).build());

    private final Setting<Boolean> droppers = sgContainers.add(new BoolSetting.Builder()
        .name("droppers").description("投掷器").defaultValue(true).build());

    /* ========== 区域自动扫描（鞘翅） ========== */
    private final Setting<Integer> cruiseAltitude = sgAuto.add(new IntSetting.Builder()
        .name("cruise-altitude")
        .description("固定高度巡航时用的 Y。开启 low-altitude-terrain-follow 后这个值不再生效")
        .defaultValue(330).min(150).max(1000).build());

    private final Setting<Boolean> lowFlight = sgAuto.add(new BoolSetting.Builder()
        .name("low-altitude-terrain-follow")
        .description("开：不再固定高度巡航，改为贴着地形低飞，前方地形升高会提前爬升。基于地形采样，不是真正的碰撞检测，遇到陡崖/尖塔可能反应不及，请配合足够大的 hover-height / climb-lookahead")
        .defaultValue(false)
        .build());

    private final Setting<Integer> hoverHeight = sgAuto.add(new IntSetting.Builder()
        .name("hover-height")
        .description("低飞模式下，目标高度 = 前方看到的最高地形 + 这个值。飞得快就调大一点，留够反应余量")
        .defaultValue(30).min(8).max(120).build());

    private final Setting<Integer> climbLookahead = sgAuto.add(new IntSetting.Builder()
        .name("climb-lookahead")
        .description("低飞模式下往前看多远（格）来判断要不要提前爬升，默认 30 格。越大越安全，但飞行轨迹会更早被远处的山影响，显得没那么贴地")
        .defaultValue(30).min(16).max(256).build());

    private final Setting<Integer> climbTolerance = sgAuto.add(new IntSetting.Builder()
        .name("climb-tolerance")
        .description("低飞模式的容错：前方地形比脚下地面高出超过这个值（格）才提前爬升，小于这个值的小起伏（土坡、单棵树）忽略不理，避免飞行高度反复抖动")
        .defaultValue(10).min(0).max(80).build());

    private final Setting<Integer> laneSpacing = sgAuto.add(new IntSetting.Builder()
        .name("lane-spacing")
        .description("航线间隔（格），0 = 按已加载范围自动算。珍珠只在服务器实体追踪范围（约64格）内能看到，找珍珠建议设 128")
        .defaultValue(0).min(0).max(MAX_SPACING).build());

    private final Setting<Integer> minRockets = sgAuto.add(new IntSetting.Builder()
        .name("min-rockets")
        .description("背包+副手火箭少于这个数就降落补给（背包 -> 末影箱 -> 下线）")
        .defaultValue(6).min(1).max(64).build());

    private final Setting<Integer> landBelow = sgAuto.add(new IntSetting.Builder()
        .name("land-below-durability")
        .description("鞘翅剩余耐久低于这个值就降落修补。从高空降下来要几十秒，每秒掉1点，别设太低")
        .defaultValue(80).min(40).max(300).build());

    private final Setting<Integer> repairPct = sgAuto.add(new IntSetting.Builder()
        .name("repair-to-percent")
        .description("经验瓶修到多少 %（鞘翅必须有经验修补）")
        .defaultValue(90).min(50).max(100).build());

    private final Setting<Boolean> useChest = sgAuto.add(new BoolSetting.Builder()
        .name("use-ender-chest")
        .description("背包没有火箭/经验瓶时，落地放下末影箱去拿；有精准采集镐会收回末影箱")
        .defaultValue(true).build());

    private final Setting<Integer> supplySlot = sgAuto.add(new IntSetting.Builder()
        .name("supply-hotbar-slot")
        .description("临时周转用的快捷栏格子（1-9），里面的东西可能被换走，请留空")
        .defaultValue(9).min(1).max(9).build());

    private final Setting<Integer> rocketSlot = sgAuto.add(new IntSetting.Builder()
        .name("rocket-hotbar-slot")
        .description("火箭固定放这个快捷栏格子（1-9），不占副手，副手留给你自己放的图腾等物品")
        .defaultValue(8).min(1).max(9).build());

    private final Setting<Double> minSpeed = sgAuto.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("速度（格/tick）低于这个值就放一发火箭")
        .defaultValue(1.0).min(0.5).max(1.4).build());

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
    private final HashMap<Integer, BlockPos> villagerSeen = new HashMap<>();
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final PosIndex marks = new PosIndex();       // 末影箱/潜影盒
    private final PosIndex pearlMarks = new PosIndex();  // 珍珠点
    private final PosIndex bells = new PosIndex();
    private final PosIndex beds = new PosIndex();
    private final PosIndex storage = new PosIndex();     // 所有储物方块，用来判断数量是否正常
    private final ArrayDeque<Cand> unresolved = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> dispQueue = new ArrayDeque<>();
    private final ArrayDeque<Msg> pending = new ArrayDeque<>();
    private boolean rescan = false; // 新发现了“有人活动”的标记，需要把之前判为误报的重新看一遍

    /* Qmsg 队列 */
    private final ArrayDeque<String> qmLines = new ArrayDeque<>();
    private final HashSet<String> qmQueued = new HashSet<>();
    private final java.util.Set<String> qmSent = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> qmRetry = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Integer> qmRejects = new ConcurrentHashMap<>();
    private int qmCooldown = 0;

    /* 区域自动扫描状态 */
    private final BaritoneBridge bridge = new BaritoneBridge(); // 现在只用来注册 #scan 指令
    private final ElytraPilot pilot;
    private final ArrayList<int[]> route = new ArrayList<>();
    private boolean regionActive = false;
    private int regMinX, regMaxX, regMinZ, regMaxZ;
    private boolean cmdRegistered = false;
    private boolean cmdErrorShown = false;
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
        pilot = new ElytraPilot(
            msg -> info("%s", msg),
            msg -> pushQq(msg + " " + timeStr()),
            this::pushQqNow,
            this::pilotEnded
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
        info("[配置确认] 低空地形跟随=%s 悬停高度=%d 爬升前瞻=%d 爬升容错=%d 固定巡航高度=%d",
            lowFlight.get(), hoverHeight.get(), climbLookahead.get(), climbTolerance.get(), cruiseAltitude.get());
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
        rescan = false;
        phase = PHASE_WAIT;
        waitTicks = 0;
        sweepCount = 0;
    }

    private boolean strictNow() {
        return strictMarker.get() || regionActive;
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

        if (regionActive) {
            applyPilotCfg();
            pilot.tick(mc);
        }

        if (++entityTimer >= ENTITY_SCAN_TICKS) {
            entityTimer = 0;
            scanEntities(mc);
        }

        scanBlocksStep(mc);
    }

    private void applyPilotCfg() {
        pilot.altitude = cruiseAltitude.get();
        pilot.minRockets = minRockets.get();
        pilot.landBelow = landBelow.get();
        pilot.repairPct = repairPct.get();
        pilot.supplySlot = supplySlot.get() - 1;
        pilot.rocketSlot = rocketSlot.get() - 1;
        pilot.minSpeed = minSpeed.get();
        pilot.useChest = useChest.get();
        pilot.lowFlight = lowFlight.get();
        pilot.hoverHeight = hoverHeight.get();
        pilot.climbLookahead = climbLookahead.get();
        pilot.climbTolerance = climbTolerance.get();
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
            "扫描两个坐标之间的矩形区域（卫星雷达，鞘翅高空飞行）",
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
            boolean wasActive = regionActive;
            stopRegion(wasActive ? "已停止区域扫描，请自己接管飞行" : "当前没有区域扫描任务");
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
            regMinX, regMaxX, regMinZ, regMaxZ, Math.min(pilot.waypointIndex() + 1, route.size()), route.size());
    }

    private void startRegion(int x1, int z1, int x2, int z2) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) {
            info("鞘翅高空扫描目前只支持主世界");
            return;
        }

        applyPilotCfg();
        String problem = pilot.preflight(mc.player);
        if (problem != null) {
            info("无法开始区域扫描：%s", problem);
            return;
        }

        regMinX = Math.min(x1, x2);
        regMaxX = Math.max(x1, x2);
        regMinZ = Math.min(z1, z2);
        regMaxZ = Math.max(z1, z2);

        int spacing;
        if (laneSpacing.get() > 0) {
            spacing = Math.max(MIN_SPACING, Math.min(MAX_SPACING, laneSpacing.get()));
        } else {
            int loaded = lastMaxDist > 0 ? lastMaxDist * 16 : DEFAULT_LOADED_BLOCKS;
            spacing = Math.max(MIN_SPACING, Math.min(MAX_SPACING, (int) (loaded * 1.6)));
        }
        buildRoute(spacing);

        regionActive = true;
        info("区域扫描开始：X %d ~ %d，Z %d ~ %d，共 %d 个航点（航线间隔 %d 格，%s，已强制开启标记规则）",
            regMinX, regMaxX, regMinZ, regMaxZ, route.size(), spacing,
            lowFlight.get() ? ("低空地形跟随，悬停高度 " + hoverHeight.get()) : ("固定高度 Y=" + cruiseAltitude.get()));
        pushQq(String.format(Locale.ROOT, "区域扫描开始 X %d~%d Z %d~%d %s",
            regMinX, regMaxX, regMinZ, regMaxZ, timeStr()));
        pilot.start(new ArrayList<>(route));
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

    // 鞘翅驾驶结束：completed=true 是跑完并已降落；false 是中途下线/出错
    private void pilotEnded(boolean completed) {
        regionActive = false;
        route.clear();
        if (completed) {
            info("区域扫描完成，已降落");
            pushQq("区域扫描完成 " + timeStr());
        }
    }

    private void stopRegion(String msg) {
        if (regionActive) {
            regionActive = false;
            route.clear();
        }
        pilot.stop(Minecraft.getInstance());
        if (msg != null) info("%s", msg);
    }

    private boolean inRegion(int x, int z) {
        return !regionActive || (x >= regMinX && x <= regMaxX && z >= regMinZ && z <= regMaxZ);
    }

    // 区块整个都在“区域 + 标记半径”之外，不用看
    private boolean chunkFarFromRegion(int cx, int cz) {
        int x0 = cx << 4, x1 = x0 + 15, z0 = cz << 4, z1 = z0 + 15;
        int m = PLAYER_MARK_RADIUS;
        return x1 < regMinX - m || x0 > regMaxX + m || z1 < regMinZ - m || z0 > regMaxZ + m;
    }

    private static String timeStr() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /* ========== 实体：末影珍珠（也当作“有人活动”的标记）+ 村民（村庄证据） ========== */
    private void scanEntities(Minecraft mc) {
        final boolean wantVillagers = !lockNatural.get() && !strictNow();

        double r = range.get();
        double rr = r <= 0 ? MAX_RANGE : Math.min(r, MAX_RANGE);
        // 实体不会出现在已加载范围之外，查询框收紧一点
        if (lastMaxDist > 0) rr = Math.min(rr, lastMaxDist * 16 + 16);
        // 垂直方向几乎不限（服务器只按水平距离追踪实体），高空飞行也能看到地下深处的珍珠
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
                    rescan = true;
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

    /* ========== 方块：分帧扫描 → 判断 → 报告（整列区块，一直到基岩层，没有高度限制） ========== */
    private void scanBlocksStep(Minecraft mc) {
        if (!scanContainers.get()) return;
        if (!(shulkers.get() || chests.get() || trappedChests.get() || barrels.get()
            || enderChests.get() || hoppers.get() || dispensers.get() || droppers.get())) return;
        Level world = mc.level;

        int cfg = (shulkers.get() ? 1 : 0) | (chests.get() ? 2 : 0) | (enderChests.get() ? 4 : 0)
            | (hoppers.get() ? 8 : 0) | (dispensers.get() ? 16 : 0) | (lockNatural.get() ? 32 : 0)
            | (barrels.get() ? 64 : 0) | (trappedChests.get() ? 128 : 0) | (strictNow() ? 256 : 0)
            | (droppers.get() ? 512 : 0);
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
            rescan = true;
            negCells.clear();
            storage.clear();
            storageSeen.clear();
        }
        if (rescan) {
            // 发现了新的标记（或定期重置）：之前当作误报的，用最新的标记信息重新判断一次
            rescan = false;
            chunkCount.clear();
            suppressed.clear();
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
        if (!world.hasChunk(cx, cz)) return; // 没加载的区块直接跳过，不去取空区块对象

        // 统计真正加载了多远
        int d = Math.max(Math.abs(cx - sweepPcx), Math.abs(cz - sweepPcz));
        if (d > sweepMaxDist) sweepMaxDist = d;

        if (regionActive && chunkFarFromRegion(cx, cz)) return;

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
                    if (markSeen.add(pos)) {
                        marks.add(pos);
                        rescan = true;
                    }
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
        final boolean strict = strictNow();
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
        Cand c;
        while ((c = unresolved.peek()) != null && decisions++ < DECISIONS_PER_TICK) {
            BlockPos p = c.pos();
            String label = inRegion(p.getX(), p.getZ()) ? labelOf(c.kind()) : null;

            if (label != null) {
                boolean suppress = false;

                // 末影箱和潜影盒本身就是“有人活动”的标记，始终通知
                if (c.kind() != Kind.SHULKER && c.kind() != Kind.ENDER) {
                    boolean hasMarker = markNear(p);

                    if (strict && !hasMarker) {
                        suppress = true; // 100格内没有末影箱/潜影盒/珍珠点：当作误报
                    } else if (filterOn && !hasMarker) {
                        // 附近没有标记，才需要判断是不是天然结构里的
                        int st = zoneState(p);
                        if (st == 0) {
                            long cell = cellKey(p);
                            if (!negCells.contains(cell)) {
                                if (probes <= 0) break; // 本 tick 检查额度用完，下个 tick 继续（这个候选还留在队首）
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
            unresolved.poll();
            queued.remove(p);
        }

        if (unresolved.isEmpty()) {
            phase = PHASE_WAIT;
            waitTicks = 0;
        }
    }

    /* ========== 天然结构判断（只用来屏蔽，不通知；区域扫描时用不到） ========== */
    private Hit probeNatural(Level world, BlockPos c) {
        boolean waterlogged = !world.getFluidState(c).isEmpty();

        int tnt = 0, oak = 0, planks = 0, path = 0;
        int crying = 0, bricks = 0, chiseled = 0;
        int water2 = 0, sandNear = 0;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        final int cx = c.getX(), cy = c.getY(), cz = c.getZ();

        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    Block b = world.getBlockState(m.set(cx + dx, cy + dy, cz + dz)).getBlock();

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
        boolean structure = bells.countWithin(c, BELL_NEAR, 1) >= 1
            || beds.countWithin(c, BEDS_NEAR, 3) >= 3;
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
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int dx = -rh; dx <= rh; dx++) {
            for (int dy = -rv; dy <= rv; dy++) {
                for (int dz = -rh; dz <= rh; dz++) {
                    if (match.test(world.getBlockState(m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz)).getBlock())) {
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
                long dx = z.c.getX() - p.getX();
                long dy = z.c.getY() - p.getY();
                long dz = z.c.getZ() - p.getZ();
                if (dx * dx + dy * dy + dz * dz <= 64) return; // 同类标记靠得很近，不重复添加
            }
        }
        zones.add(new Zone(p, rad, limit));
    }

    // 0 = 不在任何天然结构范围内；1 = 在范围内但储物数量超标（像玩家基地）；2 = 在范围内且数量正常
    private int zoneState(BlockPos pos) {
        boolean covered = false;
        for (Zone z : zones) {
            long dx = z.c.getX() - pos.getX();
            long dy = z.c.getY() - pos.getY();
            long dz = z.c.getZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz > z.r2) continue;
            covered = true;
            if (zoneCount(z) <= z.limit) return 2;
        }
        return covered ? 1 : 0;
    }

    private int zoneCount(Zone z) {
        if (z.stamp != resolveId) {
            z.cnt = storage.countWithin(z.c, z.r, z.limit + 1); // 超过上限就不用继续数了
            z.stamp = resolveId;
        }
        return z.cnt;
    }

    // 周围 100 格内有末影箱、潜影盒或珍珠点
    private boolean markNear(BlockPos pos) {
        return marks.countWithin(pos, PLAYER_MARK_RADIUS, 1) >= 1
            || pearlMarks.countWithin(pos, PLAYER_MARK_RADIUS, 1) >= 1;
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
            case DISPENSER -> dispensers.get() ? "发射器" : null;
            case DROPPER -> droppers.get() ? "投掷器" : null;
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

    // 紧急消息（自动下线）：不排队，直接发。下线后模块 tick 不会再跑，排队的发不出去
    private void pushQqNow(String raw) {
        if (!qmEnabled.get()) return;
        postToQmsg(softenDigits(raw + " " + timeStr()));
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

/**
 * 鞘翅自动驾驶：起飞 -> 在固定高度或地形跟随低飞沿航线巡航 -> 降落。
 * 补给流程：耐久低 / 火箭少 -> 降落 -> 背包里拿经验瓶修补、拿火箭 -> 背包没有就放末影箱去拿 -> 还是没有就（落地后）下线。
 * 火箭固定放在 rocketSlot 这个快捷栏格子，不占副手；副手留给你自己放的图腾（脚本会自动把背包里的图腾放进副手，
 * 但从不会把它换走）。经验瓶/末影箱/镐临时用 supplySlot 这个快捷栏格子。
 */
private static final class ElytraPilot {
    private enum Mode { IDLE, PREP, TAKEOFF, CRUISE, LAND, REPAIR, CHEST }

    /* ===== 参数（由模块每 tick 写入） ===== */
    int altitude = 330;      // 固定高度模式使用；低飞模式下不生效
    int minRockets = 6;
    int landBelow = 80;      // 鞘翅剩余耐久 <= 这个值就降落修补
    int repairPct = 90;      // 修到多少 %
    int supplySlot = 8;      // 快捷栏下标 0~8（临时周转：经验瓶/末影箱/镐）
    int rocketSlot = 7;      // 快捷栏下标 0~8（固定放火箭，不占副手）
    double minSpeed = 1.0;   // 格/tick，低于就放火箭
    boolean useChest = true;
    boolean lowFlight = false;  // 开：地形跟随低飞；关：固定 altitude 高空巡航
    int hoverHeight = 30;       // 低飞模式：目标高度 = 前方地形最高点 + 这个值
    int climbLookahead = 30;    // 低飞模式：往飞行方向看多远来判断要不要提前爬升
    int climbTolerance = 10;    // 低飞模式：前方地形比脚下高出超过这个值才提前爬升，否则忽略小起伏

    private static final int ARRIVE_DIST = 40;
    private static final int ROCKET_GAP = 15;

    private final Consumer<String> say;
    private final Consumer<String> qq;
    private final Consumer<String> qqUrgent;
    private final Consumer<Boolean> onEnd; // true = 航线完成并已降落；false = 中止/下线

    private Mode mode = Mode.IDLE;
    private List<int[]> route = List.of();
    private int idx;
    private long tick, lastRocket;
    private boolean finishing, repairing, triedChest;
    private int prepWait, equipTries, timer;
    private double takeoffY = -1e9;

    // 起飞
    private int tk, tkWait, tkTicks;
    // 末影箱
    private int cs, csWait, csTries, takePass, mineTicks, pickWait;
    private boolean mining;
    private BlockPos chestPos;

    ElytraPilot(Consumer<String> say, Consumer<String> qq, Consumer<String> qqUrgent, Consumer<Boolean> onEnd) {
        this.say = say;
        this.qq = qq;
        this.qqUrgent = qqUrgent;
        this.onEnd = onEnd;
    }

    boolean active() { return mode != Mode.IDLE; }
    int waypointIndex() { return idx; }

    /** 开始前检查，返回 null 表示可以开始，否则是原因。 */
    String preflight(LocalPlayer p) {
        if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && firstSlot(p, Items.ELYTRA) < 0) {
            return "身上和背包里都没有鞘翅";
        }
        if (count(p, Items.FIREWORK_ROCKET) < minRockets && !(useChest && firstSlot(p, Items.ENDER_CHEST) >= 0)) {
            return "火箭不足 " + minRockets + " 个，而且没有末影箱可以补";
        }
        return null;
    }

    void start(List<int[]> r) {
        route = r;
        idx = 0;
        mode = Mode.PREP;
        tick = 0;
        lastRocket = -100;
        takeoffY = -1e9;
        finishing = repairing = triedChest = false;
        prepWait = equipTries = 0;
    }

    void stop(Minecraft mc) {
        if (mode != Mode.IDLE) {
            mode = Mode.IDLE;
            jump(mc, false);
        }
    }

    void tick(Minecraft mc) {
        if (mode == Mode.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (p.isDeadOrDying()) {
            mode = Mode.IDLE;
            onEnd.accept(false);
            return;
        }
        tick++;
        switch (mode) {
            case PREP -> prep(mc, p);
            case TAKEOFF -> takeoff(mc, p);
            case CRUISE -> cruise(mc, p);
            case LAND -> land(mc, p);
            case REPAIR -> repair(mc, p);
            case CHEST -> chest(mc, p);
            default -> { }
        }
    }

    /* ========== 地面决策：修鞘翅 -> 补火箭 -> 起飞 ========== */
    private void prep(Minecraft mc, LocalPlayer p) {
        if (p.isFallFlying()) {
            mode = Mode.CRUISE;
            return;
        }
        if (!p.onGround()) {
            if (++prepWait > 200) logout(mc, "降落后一直不在地面（可能落水）");
            return;
        }
        prepWait = 0;

        if (finishing) {
            mode = Mode.IDLE;
            jump(mc, false);
            onEnd.accept(true);
            return;
        }
        if (!ensureElytra(mc, p)) return;

        ItemStack el = p.getItemBySlot(EquipmentSlot.CHEST);
        int max = el.getMaxDamage();
        int left = max - el.getDamageValue();

        if (repairing || left <= landBelow) {
            int target = max * repairPct / 100;
            if (left >= target) {
                repairing = false;
                say(String.format(Locale.ROOT, "鞘翅已修好：%d/%d", left, max));
            } else if (!hasEnchant(el, true)) {
                logout(mc, "鞘翅没有经验修补，耐久只剩 " + left);
                return;
            } else if (count(p, Items.EXPERIENCE_BOTTLE) > 0) {
                repairing = true;
                timer = 0;
                mode = Mode.REPAIR;
                say(String.format(Locale.ROOT, "开始用经验瓶修补鞘翅：%d/%d", left, max));
                return;
            } else if (useChest && !triedChest) {
                startChest();
                return;
            } else if (left <= landBelow + 10) {
                logout(mc, "没有经验瓶，鞘翅耐久只剩 " + left);
                return;
            } else {
                repairing = false; // 修不了但还能飞，先继续
            }
        }

        int rockets = count(p, Items.FIREWORK_ROCKET);
        if (rockets < minRockets) {
            if (useChest && !triedChest) {
                startChest();
                return;
            }
            logout(mc, "火箭不足（背包和末影箱都没有），当前 " + rockets);
            return;
        }

        triedChest = false;
        tk = 0;
        tkWait = 0;
        tkTicks = 0;
        takeoffY = p.getY();
        mode = Mode.TAKEOFF;
        say("起飞" + (lowFlight ? "（地形跟随低飞）" : "，目标高度 Y=" + altitude));
    }

    private boolean ensureElytra(Minecraft mc, LocalPlayer p) {
        if (p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return true;
        int s = firstSlot(p, Items.ELYTRA);
        if (s < 0 || ++equipTries > 20) {
            logout(mc, "没有可用的鞘翅");
            return false;
        }
        InvUtils.move().from(s).toArmor(2); // 2 = 胸甲位
        return false;
    }

    /* ========== 起飞：跳 -> 松开 -> 空中再按跳展开鞘翅 -> 放火箭 ========== */
    private void takeoff(Minecraft mc, LocalPlayer p) {
        if (p.isFallFlying()) {
            jump(mc, false);
            fireRocket(mc, p);
            mode = Mode.CRUISE;
            return;
        }
        if (++tkTicks > 300) {
            logout(mc, "起飞失败（头顶有方块或在水里？）");
            return;
        }
        ensureRocketSlot(p);
        ensureOffhandTotem(p);
        p.setXRot(-75f);
        if (idx < route.size()) {
            int[] wp = route.get(idx);
            p.setYRot(yawTo(wp[0] + 0.5 - p.getX(), wp[1] + 0.5 - p.getZ()));
        }
        switch (tk) {
            case 0 -> {
                if (p.onGround()) {
                    jump(mc, true);
                    tk = 1;
                }
            }
            case 1 -> {
                jump(mc, false);
                tk = 2;
                tkWait = 0;
            }
            case 2 -> {
                if (!p.onGround()) {
                    jump(mc, true);
                    tk = 3;
                } else if (++tkWait > 30) {
                    tk = 0;
                }
            }
            case 3 -> {
                jump(mc, false);
                tk = 4;
                tkWait = 0;
            }
            default -> {
                if (++tkWait > 8) tk = 0;
            }
        }
    }

    /* ========== 巡航 ========== */
    private void cruise(Minecraft mc, LocalPlayer p) {
        if (!p.isFallFlying()) {
            if (p.onGround()) {
                mode = Mode.PREP;
                return;
            }
            jump(mc, (tick & 1) == 0); // 意外脱离滑翔：交替按跳键重新展开
            return;
        }
        jump(mc, false);

        int[] wp = route.get(Math.min(idx, route.size() - 1));
        double dx = wp[0] + 0.5 - p.getX();
        double dz = wp[1] + 0.5 - p.getZ();
        if (dx * dx + dz * dz <= (double) ARRIVE_DIST * ARRIVE_DIST) {
            idx++;
            if (idx >= route.size()) {
                finishing = true;
                beginLand("航线完成，降落");
                return;
            }
            wp = route.get(idx);
            dx = wp[0] + 0.5 - p.getX();
            dz = wp[1] + 0.5 - p.getZ();
            say(String.format(Locale.ROOT, "前往航点 %d/%d：X %d / Z %d", idx + 1, route.size(), wp[0], wp[1]));
        }

        if (tick % 20 == 0 && checkSupplies(p)) return;
        if (tick % 10 == 0) {
            ensureRocketSlot(p);
            ensureOffhandTotem(p);
        }

        double v = p.getDeltaMovement().length();
        double err = lowFlight ? terrainTarget(mc.level, p) - p.getY() : altitude - p.getY();
        float pitch;
        if (lowFlight) {
            // 低飞模式：需要爬升时优先给足角度，宁可掉速也不要撞地形；err 越小才慢慢拉平
            if (err > 25) pitch = -60f;
            else if (err > 8) pitch = (float) Mth.clamp(-err * 1.6, -60.0, 10.0);
            else pitch = (float) Mth.clamp(-err * 0.9, -20.0, 25.0);
        } else if (err > 40) {
            pitch = (p.getY() < takeoffY + 45) ? -75f : -50f;
        } else {
            pitch = (float) Mth.clamp(-err * 0.8, -30.0, 25.0);
        }
        if (v < 0.45 && pitch < 8f) pitch = 8f; // 防失速

        p.setXRot(pitch);
        p.setYRot(yawTo(dx, dz));

        // 陡爬升很吃速度，容易越爬越慢最后失速下坠：爬升中放烟花的间隔比平时短
        boolean urgentClimb = lowFlight && err > 15 && v < 1.1;
        if ((v < minSpeed || urgentClimb) && tick - lastRocket >= (urgentClimb ? 6 : ROCKET_GAP)) {
            fireRocket(mc, p);
        }
    }

    private boolean checkSupplies(LocalPlayer p) {
        ItemStack el = p.getItemBySlot(EquipmentSlot.CHEST);
        int left = el.is(Items.ELYTRA) ? el.getMaxDamage() - el.getDamageValue() : 0;
        if (left <= landBelow) {
            repairing = true;
            beginLand("鞘翅耐久只剩 " + left + "，降落修补");
            return true;
        }
        int r = count(p, Items.FIREWORK_ROCKET);
        if (r < minRockets) {
            beginLand("火箭只剩 " + r + " 个，降落补给");
            return true;
        }
        return false;
    }

    private void beginLand(String reason) {
        mode = Mode.LAND;
        say(reason);
        if (!finishing) qq.accept(reason);
    }

    /* ========== 降落：尽量避开水面，接近地面时拉平 ========== */
    private void land(Minecraft mc, LocalPlayer p) {
        if (p.onGround() || p.isInWater()) {
            jump(mc, false);
            mode = Mode.PREP;
            prepWait = 0;
            return;
        }
        if (!p.isFallFlying()) {
            jump(mc, (tick & 1) == 0);
            return;
        }
        jump(mc, false);

        Level w = mc.level;
        int x = p.getBlockX(), z = p.getBlockZ();
        double yaw = Math.toRadians(p.getYRot());
        int ax = (int) Math.floor(p.getX() - Math.sin(yaw) * 8);
        int az = (int) Math.floor(p.getZ() + Math.cos(yaw) * 8);
        boolean ok = landable(w, x, z) && landable(w, ax, az);

        int gy = w.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        double h = p.getY() - gy - (ok ? 2 : 30);
        double vy = p.getDeltaMovement().y;
        double v = p.getDeltaMovement().length();

        float pitch;
        if (ok && h < 4) pitch = vy < -0.6 ? -15f : 8f;
        else pitch = (float) Mth.clamp(h * 0.7, ok ? 8 : -10, 40);
        if (v < 0.45 && pitch < 10f) pitch = 10f;
        p.setXRot(pitch);
    }

    private static boolean landable(Level w, int x, int z) {
        int y = w.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        return w.getFluidState(new BlockPos(x, y, z)).isEmpty();
    }

    // 低飞模式：在 climbLookahead（默认 30）格范围内检测前方障碍物（含树冠）。
    // 沿实际飞行方向（优先用速度向量，几乎静止时退回用朝向）扫描，
    // 从 2 格外开始、每 4 格采一次，并在飞行路径左右各偏 1.5 格再各采一条线，
    // 防止单点射线从树冠旁边擦过而漏检单棵树。不设上限：遇到很高的山会一直爬升。
    //
    // 容错（climbTolerance）：前方最高点只有比脚下地面高出超过这个值才会被采用来抬升目标高度，
    // 否则仍按脚下地面算，避免小土坡、单棵树之类的小起伏让飞行高度反复抖动。
    private double terrainTarget(Level w, LocalPlayer p) {
        int baseGround = groundHeight(w, p.getBlockX(), p.getBlockZ());
        int maxAhead = baseGround;

        Vec3 v = p.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        double dirX, dirZ;
        if (speed > 0.15) {
            dirX = v.x / speed;
            dirZ = v.z / speed;
        } else {
            double yaw = Math.toRadians(p.getYRot());
            dirX = -Math.sin(yaw);
            dirZ = Math.cos(yaw);
        }
        double perpX = -dirZ, perpZ = dirX; // 与飞行方向垂直的单位向量，左右各探一条线

        for (int d = 2; d <= climbLookahead; d += 4) {
            for (double off : new double[]{-1.5, 0, 1.5}) {
                int x = (int) Math.floor(p.getX() + dirX * d + perpX * off);
                int z = (int) Math.floor(p.getZ() + dirZ * d + perpZ * off);
                if (!w.hasChunk(x >> 4, z >> 4)) continue;
                maxAhead = Math.max(maxAhead, groundHeight(w, x, z));
            }
        }

        int ground = (maxAhead - baseGround > climbTolerance) ? maxAhead : baseGround;
        return ground + hoverHeight;
    }

    private static int groundHeight(Level w, int x, int z) {
        return w.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /* ========== 站在地上扔经验瓶修补 ========== */
    private void repair(Minecraft mc, LocalPlayer p) {
        ItemStack el = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!el.is(Items.ELYTRA)) {
            mode = Mode.PREP;
            return;
        }
        int max = el.getMaxDamage();
        if (max - el.getDamageValue() >= max * repairPct / 100 || !p.onGround()) {
            mode = Mode.PREP;
            return;
        }
        if (++timer < 3) return;
        timer = 0;

        int slot = firstSlot(p, Items.EXPERIENCE_BOTTLE);
        if (slot < 0) {
            mode = Mode.PREP; // 交给 prep 决定去末影箱还是下线
            return;
        }
        if (slot >= 9) {
            InvUtils.move().from(slot).toHotbar(supplySlot);
            return;
        }
        InvUtils.swap(slot, false);
        p.setXRot(90f);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /* ========== 末影箱补给：放下 -> 打开 -> 拿火箭和经验瓶 -> 用精准采集镐收回 ========== */
    private void startChest() {
        triedChest = true;
        mode = Mode.CHEST;
        cs = 0;
        csWait = 0;
        csTries = 0;
        takePass = 0;
        mineTicks = 0;
        pickWait = 0;
        mining = false;
        chestPos = null;
        say("去末影箱补给");
        qq.accept("背包物资不足，尝试从末影箱补给");
    }

    private void chestFail(Minecraft mc, String why) {
        if (mc.player != null) mc.player.closeContainer();
        say("末影箱补给失败：" + why);
        mode = Mode.PREP;
    }

    private void chest(Minecraft mc, LocalPlayer p) {
        if (++csWait > 800) {
            chestFail(mc, "超时");
            return;
        }
        switch (cs) {
            case 0 -> {
                int s = firstSlot(p, Items.ENDER_CHEST);
                if (s < 0) {
                    chestFail(mc, "背包里没有末影箱");
                    return;
                }
                chestPos = pickSite(mc.level, p);
                if (chestPos == null) {
                    chestFail(mc, "周围没有能放末影箱的空位");
                    return;
                }
                if (s != supplySlot) InvUtils.move().from(s).toHotbar(supplySlot);
                cs = 1;
            }
            case 1 -> {
                InvUtils.swap(supplySlot, false);
                if (!p.getMainHandItem().is(Items.ENDER_CHEST)) {
                    if (++csTries > 10) chestFail(mc, "末影箱没能拿到手上");
                    return;
                }
                BlockPos base = chestPos.below();
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5),
                        Direction.UP, base, false));
                p.swing(InteractionHand.MAIN_HAND);
                cs = 2;
                pickWait = 0;
            }
            case 2 -> {
                if (!mc.level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) {
                    if (++pickWait > 20) {
                        if (++csTries > 3) chestFail(mc, "放不下末影箱");
                        else cs = 1;
                    }
                    return;
                }
                if (p.containerMenu instanceof ChestMenu) {
                    takePass = 0;
                    cs = 3;
                    return;
                }
                if (++pickWait % 5 == 0) {
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(chestPos), Direction.UP, chestPos, false));
                }
                if (pickWait > 100) chestFail(mc, "打不开末影箱");
            }
            case 3 -> {
                if (!(p.containerMenu instanceof ChestMenu menu)) {
                    cs = 2;
                    return;
                }
                int total = menu.getRowCount() * 9;
                int clicks = 0;
                for (int i = 0; i < total && clicks < 6; i++) {
                    ItemStack s = menu.getSlot(i).getItem();
                    if (s.is(Items.FIREWORK_ROCKET) || s.is(Items.EXPERIENCE_BOTTLE)) {
                        InvUtils.shiftClick().slotId(i);
                        clicks++;
                    }
                }
                if (clicks == 0 || ++takePass > 20) { // 拿完了，或者背包满了拿不动
                    p.closeContainer();
                    cs = 4;
                }
            }
            case 4 -> {
                int pick = findSilkPickaxe(p);
                if (pick < 0) {
                    say("没有精准采集镐，末影箱留在原地：" + chestPos.toShortString());
                    mode = Mode.PREP;
                    return;
                }
                if (pick >= 9) {
                    InvUtils.move().from(pick).toHotbar(supplySlot);
                    return;
                }
                InvUtils.swap(pick, false);
                mining = false;
                cs = 5;
            }
            case 5 -> {
                if (mc.level.getBlockState(chestPos).isAir()) {
                    pickWait = 0;
                    cs = 6;
                    return;
                }
                if (!mining) {
                    mc.gameMode.startDestroyBlock(chestPos, Direction.UP);
                    mining = true;
                } else {
                    mc.gameMode.continueDestroyBlock(chestPos, Direction.UP);
                }
                p.swing(InteractionHand.MAIN_HAND);
                if (++mineTicks > 400) chestFail(mc, "挖不掉末影箱");
            }
            default -> {
                if (++pickWait >= 15) { // 等掉落物被吸进背包
                    say("末影箱补给完成");
                    mode = Mode.PREP;
                }
            }
        }
    }

    private static BlockPos pickSite(Level w, LocalPlayer p) {
        BlockPos b = p.blockPosition();
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] o : d) {
            BlockPos c = b.offset(o[0], 0, o[1]);
            if (w.getBlockState(c).isAir() && w.getBlockState(c.above()).isAir()
                && w.getBlockState(c.below()).isFaceSturdy(w, c.below(), Direction.UP)) return c;
        }
        return null;
    }

    private static int findSilkPickaxe(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(ItemTags.PICKAXES) && hasEnchant(s, false)) return i;
        }
        return -1;
    }

    /* ========== 通用小工具 ========== */

    // 保证火箭在固定的快捷栏格子里（不再占用副手）
    private void ensureRocketSlot(LocalPlayer p) {
        ItemStack cur = p.getInventory().getItem(rocketSlot);
        if (cur.is(Items.FIREWORK_ROCKET) && cur.getCount() >= 2) return;
        int s = firstSlot(p, Items.FIREWORK_ROCKET);
        if (s >= 0 && s != rocketSlot) InvUtils.move().from(s).toHotbar(rocketSlot);
    }

    // 背包里有图腾就放进副手；副手已有图腾则不动；脚本从不会把副手的东西换走
    private void ensureOffhandTotem(LocalPlayer p) {
        ItemStack off = p.getOffhandItem();
        if (off.is(Items.TOTEM_OF_UNDYING)) return;
        int s = firstSlot(p, Items.TOTEM_OF_UNDYING);
        if (s >= 0) InvUtils.move().from(s).toOffhand();
    }

    // 从固定快捷栏格子放火箭：临时切到该格子、使用、再切回原来选中的格子
    // 26.2 起 Inventory.selected 变为私有字段，改用 getSelectedSlot()/setSelectedSlot() 访问
    private void fireRocket(Minecraft mc, LocalPlayer p) {
        ItemStack r = p.getInventory().getItem(rocketSlot);
        if (!r.is(Items.FIREWORK_ROCKET)) return;
        int prevSelected = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(rocketSlot);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
        p.getInventory().setSelectedSlot(prevSelected);
        lastRocket = tick;
    }

    private static boolean hasEnchant(ItemStack s, boolean mending) {
        return s.getEnchantments().keySet().stream()
            .anyMatch(h -> h.is(mending ? Enchantments.MENDING : Enchantments.SILK_TOUCH));
    }

    private static int count(LocalPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        ItemStack off = p.getOffhandItem();
        if (off.is(item)) n += off.getCount();
        return n;
    }

    /** 0~35 里第一个（快捷栏优先），不含副手。 */
    private static int firstSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static void jump(Minecraft mc, boolean down) {
        mc.options.keyJump.setDown(down);
    }

    private void say(String s) {
        say.accept(s);
    }

    // 26.2 起改用 mc.disconnect(Screen, boolean) 主动断开客户端连接，不再依赖
    // net.minecraft.network.protocol.game.ClientboundDisconnectPacket（该类已从 mapping 中移除/改名）
    private void logout(Minecraft mc, String reason) {
        mode = Mode.IDLE;
        jump(mc, false);
        say("自动下线：" + reason);
        qqUrgent.accept("自动下线：" + reason);
        onEnd.accept(false);
        mc.disconnect(null, false);
    }
}
}
