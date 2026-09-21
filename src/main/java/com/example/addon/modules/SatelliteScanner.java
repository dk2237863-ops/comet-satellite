package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SatelliteScanner extends Module {
    private static final int MAX_MESSAGES_PER_TICK = 10;

    /* =========================
       Qmsg
       ========================= */

    private static final int QM_QUEUE_LIMIT = 5000;
    private static final int QM_SENT_LIMIT = 50000;
    private static final int QM_MAX_REJECTS = 3;

    private static final Pattern LONG_DIGITS =
        Pattern.compile("\\d{4,}");

    /* =========================
       性能
       ========================= */

    /*
     * 最大雷达范围：
     * 512 格
     */
    private static final int MAX_RANGE = 512;

    private static final int CHUNKS_PER_TICK = 32;
    private static final int SWEEP_GAP_TICKS = 10;

    private static final int PROBES_PER_TICK = 12;
    private static final int DECISIONS_PER_TICK = 500;

    private static final int CACHE_RESET_SWEEPS = 60;

    /*
     * 铜方块检测：
     * 每次只处理一定数量的 section。
     *
     * 避免第一次扫描512格时卡死客户端。
     */
    private static final int COPPER_SECTIONS_PER_TICK = 8;

    /* =========================
       天然结构半径
       ========================= */

    /*
     * 地牢：
     * 刷怪笼中心半径10
     */
    private static final int R_DUNGEON = 10;

    /*
     * 试炼场：
     * 任意铜方块中心半径10
     */
    private static final int R_TRIAL = 10;

    private static final int R_CITY = 30;
    private static final int R_VILLAGE = 80;
    private static final int R_JUNGLE = 16;
    private static final int R_DESERT = 24;
    private static final int R_MANSION = 24;
    private static final int R_SHIP = 14;

    /*
     * 天然结构附近出现：
     * 末影箱 / 潜影盒 / 末影珍珠
     *
     * 任意一个都让天然结构重新显示箱子。
     */
    private static final int PLAYER_MARK_RADIUS = 100;

    /* =========================
       Phase
       ========================= */

    private static final int PHASE_WAIT = 0;
    private static final int PHASE_SWEEP = 1;
    private static final int PHASE_RESOLVE = 2;

    /* =========================
       Kind
       ========================= */

    private enum Kind {
        OTHER,
        SHULKER,
        ENDER,
        HOPPER,
        DISPENSER,
        DROPPER,
        CHEST,
        BARREL,
        TRAPPED,
        SPAWNER,
        TRIAL,
        VAULT,
        SCULK,
        BELL
    }

    private record Msg(String fmt, Object[] args) {}

    private record Zone(int x, int y, int z, int r2) {}

    private record Cand(BlockPos pos, Kind kind) {}

    /*
     * 铜扫描任务。
     *
     * 一个 section = 16×16×16
     */
    private record CopperSection(LevelChunk chunk, int sectionIndex) {}

    /* =========================
       Settings
       ========================= */

    private final SettingGroup sgGeneral =
        settings.getDefaultGroup();

    private final SettingGroup sgQm =
        settings.createGroup("QQ推送");

    private final Setting<Double> range =
        sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("扫描半径，0 或超过 512 都按 512 算")
            .defaultValue(0.0)
            .min(0.0)
            .build());

    private final Setting<Boolean> lockNatural =
        sgGeneral.add(new BoolSetting.Builder()
            .name("lock-natural-structures")
            .description(
                "开：天然结构里的方块也锁定通知；关：跳过它们（末影箱/潜影盒/末影珍珠100格内除外）"
            )
            .defaultValue(false)
            .build());

    private final Setting<Boolean> pearls =
        sgGeneral.add(new BoolSetting.Builder()
            .name("pearls")
            .description("扫描末影珍珠")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> shulkers =
        sgGeneral.add(new BoolSetting.Builder()
            .name("shulkers")
            .description("扫描潜影盒")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> chests =
        sgGeneral.add(new BoolSetting.Builder()
            .name("chests")
            .description("扫描箱子")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> barrels =
        sgGeneral.add(new BoolSetting.Builder()
            .name("barrels")
            .description("扫描木桶")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> trappedChests =
        sgGeneral.add(new BoolSetting.Builder()
            .name("trapped-chests")
            .description("扫描陷阱箱")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> enderChests =
        sgGeneral.add(new BoolSetting.Builder()
            .name("ender-chests")
            .description("扫描末影箱")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> hoppers =
        sgGeneral.add(new BoolSetting.Builder()
            .name("hoppers")
            .description("扫描漏斗")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> dispensers =
        sgGeneral.add(new BoolSetting.Builder()
            .name("dispensers")
            .description("扫描发射器/投掷器")
            .defaultValue(true)
            .build());

    /* =========================
       Qmsg设置
       ========================= */

    private final Setting<Boolean> qmEnabled =
        sgQm.add(new BoolSetting.Builder()
            .name("qmsg-enabled")
            .description(
                "把扫描结果通过 Qmsg酱 推送到 QQ（一条一条发，发过的不再发）"
            )
            .defaultValue(false)
            .build());

    private final Setting<String> qmKey =
        sgQm.add(new StringSetting.Builder()
            .name("qmsg-key")
            .description("Qmsg酱控制台里的 API Key（不要泄露）")
            .defaultValue("")
            .build());

    private final Setting<Integer> qmInterval =
        sgQm.add(new IntSetting.Builder()
            .name("qmsg-interval-seconds")
            .description(
                "两条消息之间的间隔（秒）。最少5秒"
            )
            .defaultValue(15)
            .min(5)
            .max(300)
            .build());

    private final Setting<String> qmGroup =
        sgQm.add(new StringSetting.Builder()
            .name("qmsg-group")
            .description(
                "目标QQ群号，留空则发到你的QQ单聊"
            )
            .defaultValue("")
            .build());

    private final Setting<String> qmHost =
        sgQm.add(new StringSetting.Builder()
            .name("qmsg-host")
            .description("接口域名")
            .defaultValue("https://qmsg.zendee.cn")
            .build());

    /* =========================
       状态
       ========================= */

    private final Set<Integer> seenPearls =
        new HashSet<>();

    private final HashSet<BlockPos> seenBlocks =
        new HashSet<>();

    private final HashSet<BlockPos> suppressed =
        new HashSet<>();

    private final HashSet<BlockPos> queued =
        new HashSet<>();

    private final HashSet<BlockPos> markerSeen =
        new HashSet<>();

    /*
     * marks =
     * 末影箱
     * 潜影盒
     * 末影珍珠
     */
    private final HashSet<BlockPos> markSeen =
        new HashSet<>();

    private final HashSet<Long> negCells =
        new HashSet<>();

    private final HashMap<Long, Integer> chunkCount =
        new HashMap<>();

    private final HashMap<Class<?>, Kind> kindCache =
        new HashMap<>();

    private final HashMap<Class<?>, Boolean> pearlClassCache =
        new HashMap<>();

    private final ArrayList<Zone> zones =
        new ArrayList<>();

    private final ArrayList<BlockPos> marks =
        new ArrayList<>();

    private final ArrayList<Cand> unresolved =
        new ArrayList<>();

    private final ArrayDeque<BlockPos> dispQueue =
        new ArrayDeque<>();

    private final ArrayDeque<Msg> pending =
        new ArrayDeque<>();

    /* =========================
       铜扫描
       ========================= */

    private final ArrayDeque<CopperSection> copperQueue =
        new ArrayDeque<>();

    private final HashSet<Long> copperQueued =
        new HashSet<>();

    private final HashSet<Long> copperScanned =
        new HashSet<>();

    /* =========================
       Qmsg
       ========================= */

    private final ArrayDeque<String> qmLines =
        new ArrayDeque<>();

    private final HashSet<String> qmQueued =
        new HashSet<>();

    private final Set<String> qmSent =
        ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<String> qmRetry =
        new ConcurrentLinkedQueue<>();

    private final ConcurrentHashMap<String, Integer> qmRejects =
        new ConcurrentHashMap<>();

    private int qmCooldown = 0;

    /* =========================
       Scanner状态
       ========================= */

    private int pearlTimer = 0;

    private int phase = PHASE_WAIT;

    private int waitTicks = 0;

    private int lastCfg = -1;

    private int sweepCount = 0;

    private int sweepCx0;
    private int sweepCz0;
    private int sweepW;
    private int sweepIdx;
    private int sweepTotal;

    /* =========================
       Qmsg线程
       ========================= */

    private ExecutorService qmExecutor;

    private volatile boolean qmFailing = false;

    private volatile String qmError = null;

    /* =========================
       Constructor
       ========================= */

    public SatelliteScanner() {
        super(
            AddonTemplate.CATEGORY,
            "satellite-scanner",
            "首次入雷达即锁定：珍珠静默，天然结构识别，标记点解除天然结构屏蔽"
        );
    }

    /* =========================
       Activate
       ========================= */

    @Override
    public void onActivate() {
        seenPearls.clear();
        seenBlocks.clear();

        resetBlockState();

        pending.clear();

        qmLines.clear();
        qmQueued.clear();
        qmRetry.clear();
        qmRejects.clear();

        qmCooldown = 0;

        pearlTimer = 0;

        lastCfg = -1;

        qmFailing = false;
        qmError = null;

        if (qmEnabled.get()) {
            qmLines.add(
                "已启动 " +
                LocalTime.now().format(
                    DateTimeFormatter.ofPattern("HH:mm:ss")
                ) +
                "，推送正常时会收到这条消息"
            );
        }
    }

    /* =========================
       Deactivate
       ========================= */

    @Override
    public void onDeactivate() {
        qmLines.clear();
        qmQueued.clear();

        if (qmExecutor != null) {
            qmExecutor.shutdown();
            qmExecutor = null;
        }
    }

    /* =========================
       Reset
       ========================= */

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

        copperQueue.clear();
        copperQueued.clear();
        copperScanned.clear();

        phase = PHASE_WAIT;

        waitTicks = 0;

        sweepCount = 0;
    }

    /* =========================
       Tick
       ========================= */

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) {
            return;
        }

        flushMessages();
        flushQmsg();

        String err = qmError;

        if (err != null) {
            qmError = null;
            info("[Qmsg推送] 发送失败：%s", err);
        }

        /*
         * 每10 Tick扫描一次末影珍珠
         */
        if (++pearlTimer >= 10) {
            pearlTimer = 0;

            if (pearls.get()) {
                scanPearls(mc);
            }
        }

        scanBlocksStep(mc);
    }

    /* =========================
       末影珍珠
       ========================= */

    private void scanPearls(Minecraft mc) {
        double r = range.get();

        double rr =
            r <= 0
                ? MAX_RANGE
                : Math.min(r, MAX_RANGE);

        AABB box = new AABB(
            mc.player.getX() - rr,
            -64,
            mc.player.getZ() - rr,

            mc.player.getX() + rr,
            320,
            mc.player.getZ() + rr
        );

        Level world = mc.level;

        for (
            Entity e :
            world.getEntitiesOfClass(
                Entity.class,
                box,
                ent -> isPearlClass(ent.getClass())
            )
        ) {
            int id = e.getId();

            if (seenPearls.add(id)) {
                BlockPos pearlPos =
                    BlockPos.containing(
                        e.getX(),
                        e.getY(),
                        e.getZ()
                    );

                /*
                 * 末影珍珠作为天然结构标记
                 */
                if (markSeen.add(pearlPos)) {
                    marks.add(pearlPos);
                }

                report(
                    "[雷达锁定-末影珍珠] X %.2f / Y %.2f / Z %.2f",
                    e.getX(),
                    e.getY(),
                    e.getZ()
                );
            }
        }
    }

    private boolean isPearlClass(Class<?> c) {
        Boolean b = pearlClassCache.get(c);

        if (b == null) {
            b =
                c.getSimpleName()
                    .toLowerCase(Locale.ROOT)
                    .contains("enderpearl");

            pearlClassCache.put(c, b);
        }

        return b;
    }

    /* =========================
       方块扫描状态机
       ========================= */

    private void scanBlocksStep(Minecraft mc) {
        if (!(
            shulkers.get()
            || chests.get()
            || barrels.get()
            || trappedChests.get()
            || enderChests.get()
            || hoppers.get()
            || dispensers.get()
        )) {
            return;
        }

        Level world = mc.level;

        int cfg =
            (shulkers.get() ? 1 : 0)
            | (chests.get() ? 2 : 0)
            | (barrels.get() ? 4 : 0)
            | (trappedChests.get() ? 8 : 0)
            | (enderChests.get() ? 16 : 0)
            | (hoppers.get() ? 32 : 0)
            | (dispensers.get() ? 64 : 0)
            | (lockNatural.get() ? 128 : 0);

        if (cfg != lastCfg) {
            resetBlockState();
            lastCfg = cfg;
        }

        switch (phase) {
            case PHASE_WAIT -> {
                if (++waitTicks >= SWEEP_GAP_TICKS) {
                    startSweep(mc);
                }
            }

            case PHASE_SWEEP -> {
                sweepStep(world);
            }

            default -> {
                resolveStep(world);
            }
        }
    }

    /* =========================
       开始扫描
       ========================= */

    private void startSweep(Minecraft mc) {
        double r = range.get();

        int radius =
            r <= 0
                ? MAX_RANGE
                : (int) Math.min(r, MAX_RANGE);

        int rc = (radius + 15) >> 4;

        BlockPos c =
            mc.player.blockPosition();

        sweepCx0 =
            (c.getX() >> 4) - rc;

        sweepCz0 =
            (c.getZ() >> 4) - rc;

        sweepW =
            2 * rc + 1;

        sweepTotal =
            sweepW * sweepW;

        sweepIdx = 0;

        if (++sweepCount >= CACHE_RESET_SWEEPS) {
            sweepCount = 0;

            chunkCount.clear();
            negCells.clear();

            /*
             * 铜区块重新检查。
             *
             * 防止玩家挖掉/放置铜方块后永远不更新。
             */
            copperScanned.clear();
        }

        phase = PHASE_SWEEP;
    }

    /* =========================
       分帧扫描Chunk
       ========================= */

    private void sweepStep(Level world) {
        int n = 0;

        while (
            n < CHUNKS_PER_TICK
            && sweepIdx < sweepTotal
        ) {
            int cx =
                sweepCx0
                + sweepIdx % sweepW;

            int cz =
                sweepCz0
                + sweepIdx / sweepW;

            sweepIdx++;
            n++;

            scanChunk(world, cx, cz);
        }

        if (sweepIdx >= sweepTotal) {
            phase = PHASE_RESOLVE;
        }
    }

    /* =========================
       Chunk扫描
       ========================= */

    private void scanChunk(
        Level world,
        int cx,
        int cz
    ) {
        LevelChunk chunk =
            world.getChunk(cx, cz);

        /*
         * =========================
         * BlockEntity
         * =========================
         */

        var map =
            chunk.getBlockEntities();

        int size = map.size();

        long key =
            ((long) cx << 32)
            ^ (cz & 0xFFFFFFFFL);

        Integer prev =
            chunkCount.get(key);

        /*
         * BlockEntity数量没变化时，
         * 普通BlockEntity可以不重复处理。
         *
         * 但铜方块仍然需要独立扫描。
         */
        boolean scanBlockEntities =
            prev == null || prev != size;

        chunkCount.put(key, size);

        if (scanBlockEntities && size > 0) {
            for (BlockEntity be : map.values()) {
                Kind k = kindOf(be);

                if (k == Kind.OTHER) {
                    continue;
                }

                BlockPos pos =
                    be.getBlockPos();

                switch (k) {
                    /*
                     * 潜影盒 / 末影箱
                     *
                     * 两者都是天然结构解除屏蔽标记
                     */
                    case SHULKER, ENDER -> {
                        if (markSeen.add(pos)) {
                            marks.add(pos);
                        }

                        addCand(pos, k);
                    }

                    case HOPPER,
                         DROPPER,
                         CHEST,
                         BARREL,
                         TRAPPED -> {
                        addCand(pos, k);
                    }

                    case DISPENSER -> {
                        if (
                            !lockNatural.get()
                            && !markerSeen.contains(pos)
                        ) {
                            dispQueue.add(pos);
                        }

                        addCand(pos, k);
                    }

                    /*
                     * =========================
                     * 地牢
                     *
                     * 刷怪笼
                     * 半径10
                     * =========================
                     */
                    case SPAWNER -> {
                        if (
                            !lockNatural.get()
                            && markerSeen.add(pos)
                        ) {
                            addZone(
                                pos,
                                R_DUNGEON
                            );
                        }
                    }

                    /*
                     * TrialSpawner / Vault
                     *
                     * 不再用它们识别试炼场。
                     *
                     * 试炼场现在由铜方块识别。
                     */
                    case TRIAL, VAULT -> {
                        // 不处理
                    }

                    case SCULK -> {
                        if (
                            !lockNatural.get()
                            && markerSeen.add(pos)
                        ) {
                            addZone(
                                pos,
                                R_CITY
                            );
                        }
                    }

                    case BELL -> {
                        if (
                            !lockNatural.get()
                            && markerSeen.add(pos)
                        ) {
                            addZone(
                                pos,
                                R_VILLAGE
                            );
                        }
                    }

                    default -> {
                    }
                }
            }
        }

        /*
         * =========================
         * 铜方块扫描
         * =========================
         *
         * 只负责天然结构识别。
         */
        if (!lockNatural.get()) {
            queueCopperSections(chunk, cx, cz);
        }
    }

    /* =========================
       铜 Section 入队
       ========================= */

    private void queueCopperSections(
        LevelChunk chunk,
        int cx,
        int cz
    ) {
        LevelChunkSection[] sections =
            chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            long key =
                copperSectionKey(cx, cz, i);

            if (copperScanned.contains(key)) {
                continue;
            }

            if (copperQueued.add(key)) {
                copperQueue.add(
                    new CopperSection(
                        chunk,
                        i
                    )
                );
            }
        }
    }

    /* =========================
       铜扫描
       ========================= */

    private void processCopperSections() {
        int processed = 0;

        while (
            processed < COPPER_SECTIONS_PER_TICK
            && !copperQueue.isEmpty()
        ) {
            CopperSection task =
                copperQueue.poll();

            LevelChunk chunk =
                task.chunk();

            int sectionIndex =
                task.sectionIndex();

            int cx =
                chunk.getPos().x;

            int cz =
                chunk.getPos().z;

            long key =
                copperSectionKey(
                    cx,
                    cz,
                    sectionIndex
                );

            copperQueued.remove(key);

            if (copperScanned.contains(key)) {
                continue;
            }

            copperScanned.add(key);

            LevelChunkSection section =
                chunk.getSections()[sectionIndex];

            /*
             * 空section直接跳过。
             */
            if (section == null || section.hasOnlyAir()) {
                processed++;
                continue;
            }

            /*
             * 先使用 palette 的 maybeHas。
             *
             * 如果这一整个section的方块状态里
             * 根本没有铜，就不用扫描4096个位置。
             */
            if (!section.maybeHas(
                this::isCopperBlockState
            )) {
                processed++;
                continue;
            }

            /*
             * 确认存在铜后，再定位具体位置。
             */
            int baseX = cx << 4;
            int baseZ = cz << 4;

            int baseY =
                sectionIndex * 16
                + -64;

            /*
             * Minecraft世界高度可能变化，
             * 所以使用实际section的方块坐标。
             */
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {

                        BlockState state =
                            section.getBlockState(
                                x,
                                y,
                                z
                            );

                        if (!isCopperBlockState(state)) {
                            continue;
                        }

                        BlockPos pos =
                            new BlockPos(
                                baseX + x,
                                baseY + y,
                                baseZ + z
                            );

                        /*
                         * 同一个铜方块只建立一次区域。
                         */
                        if (markerSeen.add(pos)) {
                            addZone(
                                pos,
                                R_TRIAL
                            );
                        }
                    }
                }
            }

            processed++;
        }
    }

    /* =========================
       铜方块判断
       ========================= */

    private boolean isCopperBlockState(
        BlockState state
    ) {
        return isCopperBlock(
            state.getBlock()
        );
    }

    private boolean isCopperBlock(
        Block block
    ) {
        var id =
            BuiltInRegistries.BLOCK.getKey(block);

        if (id == null) {
            return false;
        }

        /*
         * 所有 minecraft ID 中包含 copper 的
         * 方块都视为铜类方块。
         *
         * 包括：
         *
         * copper_block
         * exposed_copper
         * weathered_copper
         * oxidized_copper
         * waxed_copper
         * waxed_exposed_copper
         * 等铜类变体。
         */
        String path =
            id.getPath()
                .toLowerCase(Locale.ROOT);

        return path.contains("copper");
    }

    /* =========================
       铜section Key
       ========================= */

    private static long copperSectionKey(
        int cx,
        int cz,
        int section
    ) {
        long a =
            ((long) cx & 0x3FFFFFFL)
            << 38;

        long b =
            ((long) cz & 0x3FFFFFFL)
            << 12;

        long c =
            section & 0xFFFL;

        return a | b | c;
    }

    /* =========================
       候选方块
       ========================= */

    private void addCand(
        BlockPos pos,
        Kind k
    ) {
        if (labelOf(k) == null) {
            return;
        }

        if (
            seenBlocks.contains(pos)
            || suppressed.contains(pos)
        ) {
            return;
        }

        if (queued.add(pos)) {
            unresolved.add(
                new Cand(pos, k)
            );
        }
    }

    /* =========================
       Resolve
       ========================= */

    private void resolveStep(Level world) {
        /*
         * 先继续处理铜section。
         */
        processCopperSections();

        final boolean filterOn =
            !lockNatural.get();

        int probes =
            PROBES_PER_TICK;

        /*
         * =========================
         * 丛林神庙
         * =========================
         */

        while (
            !dispQueue.isEmpty()
            && probes > 0
        ) {
            BlockPos p =
                dispQueue.poll();

            if (!markerSeen.add(p)) {
                continue;
            }

            probes--;

            if (
                countNear(
                    world,
                    p,
                    5,
                    3,
                    6,
                    b ->
                        b == Blocks.MOSSY_COBBLESTONE
                ) >= 6
            ) {
                addZone(
                    p,
                    R_JUNGLE
                );
            }
        }

        if (!dispQueue.isEmpty()) {
            return;
        }

        int decisions = 0;
        int done = 0;

        int n =
            unresolved.size();

        for (; done < n; done++) {
            if (
                decisions++
                >= DECISIONS_PER_TICK
            ) {
                break;
            }

            Cand c =
                unresolved.get(done);

            BlockPos p =
                c.pos();

            String label =
                labelOf(c.kind());

            if (label != null) {
                boolean natural = false;

                /*
                 * 潜影盒和末影箱本身不被天然结构过滤。
                 */
                if (
                    filterOn
                    && c.kind() != Kind.SHULKER
                    && c.kind() != Kind.ENDER
                ) {
                    natural =
                        insideZone(p);

                    if (!natural) {
                        long cell =
                            cellKey(p);

                        if (!negCells.contains(cell)) {
                            if (probes <= 0) {
                                break;
                            }

                            probes--;

                            int rad =
                                probeNatural(
                                    world,
                                    p
                                );

                            if (rad > 0) {
                                addZone(
                                    p,
                                    rad
                                );

                                natural = true;
                            } else {
                                negCells.add(cell);
                            }
                        }
                    }
                }

                /*
                 * 天然结构内：
                 *
                 * 如果100格内没有
                 * 末影箱 / 潜影盒 / 末影珍珠
                 *
                 * 则隐藏。
                 *
                 * 有任意一个则报告。
                 */
                if (
                    natural
                    && !markNear(p)
                ) {
                    suppressed.add(p);
                } else {
                    seenBlocks.add(p);

                    report(
                        "[雷达锁定-%s] [%d, %d, %d]",
                        label,
                        p.getX(),
                        p.getY(),
                        p.getZ()
                    );
                }
            }

            queued.remove(p);
        }

        if (done > 0) {
            unresolved.subList(
                0,
                done
            ).clear();
        }

        /*
         * 铜扫描也全部完成以后，
         * 才进入等待状态。
         */
        if (
            unresolved.isEmpty()
            && dispQueue.isEmpty()
            && copperQueue.isEmpty()
        ) {
            phase = PHASE_WAIT;
            waitTicks = 0;
        }
    }

    /* =========================
       天然结构探测
       ========================= */

    private int probeNatural(
        Level world,
        BlockPos c
    ) {
        int tnt = 0;
        int oak = 0;
        int planks = 0;

        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {

                    Block b =
                        world
                            .getBlockState(
                                c.offset(
                                    dx,
                                    dy,
                                    dz
                                )
                            )
                            .getBlock();

                    if (b == Blocks.TNT) {
                        tnt++;
                    } else if (
                        b == Blocks.DARK_OAK_PLANKS
                    ) {
                        oak++;
                        planks++;
                    } else if (
                        isPlanks(b)
                    ) {
                        planks++;
                    }
                }
            }
        }

        /*
         * 沙漠神殿
         */
        if (tnt >= 4) {
            return R_DESERT;
        }

        /*
         * 林地府邸
         */
        if (oak >= 12) {
            return R_MANSION;
        }

        /*
         * 沉船
         */
        if (
            planks >= 6
            && !world
                .getFluidState(c)
                .isEmpty()
        ) {
            return R_SHIP;
        }

        return 0;
    }

    private static boolean isPlanks(
        Block b
    ) {
        return
            b == Blocks.OAK_PLANKS
            || b == Blocks.SPRUCE_PLANKS
            || b == Blocks.BIRCH_PLANKS
            || b == Blocks.JUNGLE_PLANKS
            || b == Blocks.ACACIA_PLANKS
            || b == Blocks.DARK_OAK_PLANKS;
    }

    /* =========================
       附近方块计数
       ========================= */

    private static int countNear(
        Level world,
        BlockPos c,
        int rh,
        int rv,
        int needed,
        Predicate<Block> match
    ) {
        int count = 0;

        for (
            int dx = -rh;
            dx <= rh;
            dx++
        ) {
            for (
                int dy = -rv;
                dy <= rv;
                dy++
            ) {
                for (
                    int dz = -rh;
                    dz <= rh;
                    dz++
                ) {
                    if (
                        match.test(
                            world
                                .getBlockState(
                                    c.offset(
                                        dx,
                                        dy,
                                        dz
                                    )
                                )
                                .getBlock()
                        )
                    ) {
                        if (++count >= needed) {
                            return count;
                        }
                    }
                }
            }
        }

        return count;
    }

    /* =========================
       Zone
       ========================= */

    private void addZone(
        BlockPos p,
        int rad
    ) {
        int r2 =
            rad * rad;

        for (Zone z : zones) {
            if (z.r2() != r2) {
                continue;
            }

            long dx =
                z.x() - p.getX();

            long dy =
                z.y() - p.getY();

            long dz =
                z.z() - p.getZ();

            if (
                dx * dx
                + dy * dy
                + dz * dz
                <= 64
            ) {
                return;
            }
        }

        zones.add(
            new Zone(
                p.getX(),
                p.getY(),
                p.getZ(),
                r2
            )
        );
    }

    private boolean insideZone(
        BlockPos pos
    ) {
        for (Zone z : zones) {
            long dx =
                z.x() - pos.getX();

            long dy =
                z.y() - pos.getY();

            long dz =
                z.z() - pos.getZ();

            if (
                dx * dx
                + dy * dy
                + dz * dz
                <= z.r2()
            ) {
                return true;
            }
        }

        return false;
    }

    /* =========================
       标记点判断
       ========================= */

    private boolean markNear(
        BlockPos pos
    ) {
        long lim =
            (long) PLAYER_MARK_RADIUS
            * PLAYER_MARK_RADIUS;

        for (BlockPos m : marks) {
            long dx =
                m.getX()
                - pos.getX();

            long dy =
                m.getY()
                - pos.getY();

            long dz =
                m.getZ()
                - pos.getZ();

            if (
                dx * dx
                + dy * dy
                + dz * dz
                <= lim
            ) {
                return true;
            }
        }

        return false;
    }

    /* =========================
       Cell
       ========================= */

    private static long cellKey(
        BlockPos p
    ) {
        long x =
            p.getX() >> 3;

        long y =
            p.getY() >> 3;

        long z =
            p.getZ() >> 3;

        return
            ((x & 0x3FFFFFFL) << 38)
            | ((z & 0x3FFFFFFL) << 12)
            | (y & 0xFFFL);
    }

    /* =========================
       BlockEntity分类
       ========================= */

    private Kind kindOf(
        BlockEntity be
    ) {
        Class<?> c =
            be.getClass();

        Kind k =
            kindCache.get(c);

        if (k == null) {
            k = classify(be);
            kindCache.put(c, k);
        }

        return k;
    }

    private Kind classify(
        BlockEntity be
    ) {
        if (
            be instanceof
            ShulkerBoxBlockEntity
        ) {
            return Kind.SHULKER;
        }

        if (
            be instanceof
            EnderChestBlockEntity
        ) {
            return Kind.ENDER;
        }

        if (
            be instanceof
            HopperBlockEntity
        ) {
            return Kind.HOPPER;
        }

        if (
            be instanceof
            DropperBlockEntity
        ) {
            return Kind.DROPPER;
        }

        if (
            be instanceof
            DispenserBlockEntity
        ) {
            return Kind.DISPENSER;
        }

        if (
            be instanceof
            ChestBlockEntity
        ) {
            return Kind.CHEST;
        }

        if (
            be instanceof
            BarrelBlockEntity
        ) {
            return Kind.BARREL;
        }

        if (
            be instanceof
            TrappedChestBlockEntity
        ) {
            return Kind.TRAPPED;
        }

        String n =
            be.getClass()
                .getSimpleName()
                .toLowerCase(Locale.ROOT);

        /*
         * 刷怪笼
         */
        if (
            n.endsWith(
                "spawnerblockentity"
            )
        ) {
            return Kind.SPAWNER;
        }

        /*
         * 试炼刷怪笼
         *
         * 现在不再作为试炼场识别依据。
         */
        if (
            n.contains(
                "trialspawner"
            )
        ) {
            return Kind.TRIAL;
        }

        if (
            n.contains("vault")
        ) {
            return Kind.VAULT;
        }

        if (
            n.contains("sculksensor")
            || n.contains("sculkshrieker")
            || n.contains("sculkcatalyst")
        ) {
            return Kind.SCULK;
        }

        if (
            n.contains("bell")
        ) {
            return Kind.BELL;
        }

        return Kind.OTHER;
    }

    /* =========================
       Label
       ========================= */

    private String labelOf(
        Kind k
    ) {
        return switch (k) {
            case SHULKER ->
                shulkers.get()
                    ? "潜影盒"
                    : null;

            case ENDER ->
                enderChests.get()
                    ? "末影箱"
                    : null;

            case HOPPER ->
                hoppers.get()
                    ? "漏斗"
                    : null;

            case DISPENSER, DROPPER ->
                dispensers.get()
                    ? "发射器/投掷器"
                    : null;

            case CHEST ->
                chests.get()
                    ? "箱子"
                    : null;

            case BARREL ->
                barrels.get()
                    ? "木桶"
                    : null;

            case TRAPPED ->
                trappedChests.get()
                    ? "陷阱箱"
                    : null;

            default ->
                null;
        };
    }

    /* =========================
       Report
       ========================= */

    private void report(
        String fmt,
        Object... args
    ) {
        pending.add(
            new Msg(fmt, args)
        );

        if (qmEnabled.get()) {
            String text =
                softenDigits(
                    String.format(
                        Locale.ROOT,
                        fmt,
                        args
                    )
                );

            if (
                qmLines.size()
                    < QM_QUEUE_LIMIT
                && !qmSent.contains(text)
                && qmQueued.add(text)
            ) {
                qmLines.add(text);
            }
        }
    }

    /* =========================
       Chat
       ========================= */

    private void flushMessages() {
        for (
            int i = 0;
            i < MAX_MESSAGES_PER_TICK
            && !pending.isEmpty();
            i++
        ) {
            Msg m =
                pending.poll();

            info(
                m.fmt(),
                m.args()
            );
        }
    }

    /* =========================
       数字处理
       ========================= */

    private static String softenDigits(
        String s
    ) {
        Matcher m =
            LONG_DIGITS.matcher(s);

        StringBuilder sb =
            new StringBuilder();

        while (m.find()) {
            String d =
                m.group();

            StringBuilder g =
                new StringBuilder();

            int len =
                d.length();

            for (
                int i = 0;
                i < len;
                i++
            ) {
                if (
                    i > 0
                    && (len - i) % 3 == 0
                ) {
                    g.append(',');
                }

                g.append(
                    d.charAt(i)
                );
            }

            m.appendReplacement(
                sb,
                Matcher.quoteReplacement(
                    g.toString()
                )
            );
        }

        m.appendTail(sb);

        return sb.toString();
    }

    /* =========================
       Qmsg
       ========================= */

    private void flushQmsg() {
        String failed;

        while (
            (failed = qmRetry.poll())
            != null
        ) {
            if (qmQueued.add(failed)) {
                qmLines.addFirst(failed);
            }
        }

        if (qmCooldown > 0) {
            qmCooldown--;
            return;
        }

        if (qmLines.isEmpty()) {
            return;
        }

        if (!qmEnabled.get()) {
            qmLines.clear();
            qmQueued.clear();
            return;
        }

        String text = null;

        while (!qmLines.isEmpty()) {
            String t =
                qmLines.poll();

            qmQueued.remove(t);

            if (!qmSent.contains(t)) {
                text = t;
                break;
            }
        }

        if (text == null) {
            return;
        }

        qmCooldown =
            qmInterval.get() * 20;

        if (!postToQmsg(text)) {
            qmLines.addFirst(text);
            qmQueued.add(text);
        }
    }

    /* =========================
       Qmsg POST
       ========================= */

    private boolean postToQmsg(
        String text
    ) {
        String host =
            qmHost.get().trim();

        String key =
            qmKey.get().trim();

        String group =
            qmGroup.get().trim();

        if (
            !(
                host.startsWith("http://")
                || host.startsWith("https://")
            )
        ) {
            qmFail(
                "qmsg-host 必须以 http:// 或 https:// 开头"
            );

            return false;
        }

        if (
            !key.matches(
                "[A-Za-z0-9_-]{8,64}"
            )
        ) {
            qmFail(
                "qmsg-key 格式不对，请复制正确的 API Key"
            );

            return false;
        }

        if (
            !group.isEmpty()
            && !group.matches("\\d{5,12}")
        ) {
            qmFail(
                "qmsg-group 应为QQ群号数字，或者留空"
            );

            return false;
        }

        while (
            host.endsWith("/")
        ) {
            host =
                host.substring(
                    0,
                    host.length() - 1
                );
        }

        final String endpoint =
            host
            + "/v3/jsend/"
            + key;

        StringBuilder json =
            new StringBuilder(
                "{\"msg\":\""
            );

        json.append(
            jsonEscape(
                "[卫星雷达]\n"
                + text
            )
        );

        json.append("\"");

        if (!group.isEmpty()) {
            json.append(
                ",\"group\":\""
            );

            json.append(group);

            json.append("\"");
        }

        json.append("}");

        final String body =
            json.toString();

        if (
            qmExecutor == null
            || qmExecutor.isShutdown()
        ) {
            qmExecutor =
                Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread t =
                            new Thread(
                                runnable,
                                "satellite-qmsg"
                            );

                        t.setDaemon(true);

                        return t;
                    }
                );
        }

        qmExecutor.execute(
            () ->
                sendHttp(
                    endpoint,
                    body,
                    text
                )
        );

        return true;
    }

    /* =========================
       HTTP
       ========================= */

    private void sendHttp(
        String endpoint,
        String body,
        String text
    ) {
        HttpURLConnection conn =
            null;

        try {
            conn =
                (HttpURLConnection)
                    URI.create(endpoint)
                        .toURL()
                        .openConnection();

            conn.setRequestMethod("POST");

            conn.setConnectTimeout(5000);

            conn.setReadTimeout(8000);

            conn.setDoOutput(true);

            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            );

            try (
                OutputStream os =
                    conn.getOutputStream()
            ) {
                os.write(
                    body.getBytes(
                        StandardCharsets.UTF_8
                    )
                );
            }

            int code =
                conn.getResponseCode();

            var stream =
                code >= 400
                    ? conn.getErrorStream()
                    : conn.getInputStream();

            String resp =
                stream == null
                    ? ""
                    : new String(
                        stream.readAllBytes(),
                        StandardCharsets.UTF_8
                    );

            if (
                code == 200
                && resp
                    .replace(" ", "")
                    .contains(
                        "\"success\":true"
                    )
            ) {
                qmFailing = false;

                qmRejects.remove(text);

                markSent(text);

                return;
            }

            boolean violation =
                resp.contains("违规")
                || resp.contains("敏感")
                || resp.contains("禁止");

            if (violation) {
                int n =
                    qmRejects.merge(
                        text,
                        1,
                        Integer::sum
                    );

                if (
                    n >= QM_MAX_REJECTS
                ) {
                    qmRejects.remove(text);

                    markSent(text);

                    qmFail(
                        "这条消息被判违规，已跳过："
                        + shorten(resp)
                    );

                    return;
                }
            }

            qmFail(
                "HTTP "
                + code
                + " "
                + shorten(resp)
            );

            qmRetry.add(text);

        } catch (Exception e) {
            qmFail(
                e.getClass()
                    .getSimpleName()
                + ": "
                + shorten(
                    String.valueOf(
                        e.getMessage()
                    )
                )
            );

            qmRetry.add(text);

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* =========================
       Sent
       ========================= */

    private void markSent(
        String text
    ) {
        if (
            qmSent.size()
            > QM_SENT_LIMIT
        ) {
            qmSent.clear();
        }

        qmSent.add(text);
    }

    /* =========================
       Qmsg Error
       ========================= */

    private void qmFail(
        String msg
    ) {
        if (!qmFailing) {
            qmFailing = true;
            qmError = msg;
        }
    }

    private static String shorten(
        String s
    ) {
        return s.length() > 120
            ? s.substring(0, 120) + "..."
            : s;
    }

    /* =========================
       JSON
       ========================= */

    private static String jsonEscape(
        String s
    ) {
        StringBuilder sb =
            new StringBuilder(
                s.length() + 16
            );

        for (
            int i = 0;
            i < s.length();
            i++
        ) {
            char c =
                s.charAt(i);

            switch (c) {
                case '"' ->
                    sb.append("\\\"");

                case '\\' ->
                    sb.append("\\\\");

                case '\n' ->
                    sb.append("\\n");

                case '\r' ->
                    sb.append("\\r");

                case '\t' ->
                    sb.append("\\t");

                default -> {
                    if (c < 0x20) {
                        sb.append(
                            String.format(
                                Locale.ROOT,
                                "\\u%04x",
                                (int) c
                            )
                        );
                    } else {
                        sb.append(c);
                    }
                }
            }
        }

        return sb.toString();
    }
}
