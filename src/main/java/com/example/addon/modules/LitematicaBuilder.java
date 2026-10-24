package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 读一个 .litematic 文件，核对背包材料够不够，不够就报缺多少然后不开工；
 * 材料够的话就按从下到上的顺序自动摆方块。
 *
 * 新增：遇到悬空（周围 6 个方向都没有实心方块可以贴靠）的位置，会自己从最近的地面
 * 或者已经搭好的部分往上搭临时脚手架，垫到能贴着放目标方块为止；整个建筑放完之后，
 * 再回头把那些脚手架挖掉（“修复地形”），只留下原本该有的结构。
 *
 * 这不是全自动路径规划：装了 Baritone 就会用 Baritone 走到每个目标点附近再放；
 * 没装 Baritone 的话，只会摆你站的位置够得着的方块，摆不到的会一直留在队列里，
 * 需要你自己飞/走过去。
 *
 * litematica 的 NBT 结构、BlockStates 的变长整数打包方式是我照着公开的格式文档写的，
 * 没有真机测试过。#lbload 读完之后会把前 10 个方块的坐标打印出来，建议先对照游戏里
 * 实际的位置确认一下坐标对不对，再 #lbstart。
 */
public class LitematicaBuilder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgScaffold = settings.createGroup("自动搭架/修复地形");

    private final Setting<String> filePath = sg.add(new StringSetting.Builder()
        .name("schematic-path")
        .description("要建造的 .litematic 文件的绝对路径")
        .defaultValue("")
        .build());

    private final Setting<Integer> placeDelay = sg.add(new IntSetting.Builder()
        .name("place-delay-ticks")
        .description("每放一个方块之间间隔多少 tick，别设成 0，太快容易被服务器判定异常")
        .defaultValue(3).min(1).max(40).build());

    private final Setting<Boolean> useBaritoneWalk = sg.add(new BoolSetting.Builder()
        .name("use-baritone-walk")
        .description("装了 Baritone 的话，自动走到每个目标点附近再放；没装就只放你够得着的")
        .defaultValue(true).build());

    private final Setting<Double> reach = sg.add(new DoubleSetting.Builder()
        .name("reach")
        .description("放方块的最大距离（格），跟服务器的交互距离限制保持一致，别调太大")
        .defaultValue(4.3).min(2.0).max(6.0).build());

    private final Setting<Boolean> creativeAutoSupply = sg.add(new BoolSetting.Builder()
        .name("creative-auto-supply")
        .description("创造模式下，背包里没有需要的方块就自动从创造栏拿一组放进快捷栏，不用自己找、也跳过材料核对那一步")
        .defaultValue(true).build());

    private final Setting<Boolean> scaffoldEnabled = sgScaffold.add(new BoolSetting.Builder()
        .name("auto-scaffold")
        .description("开：遇到悬空的位置（周围没有实心方块可以贴靠）自动往上搭临时脚手架；关：悬空的位置会一直卡住等你自己先垫东西")
        .defaultValue(true).build());

    private final Setting<String> scaffoldBlockId = sgScaffold.add(new StringSetting.Builder()
        .name("scaffold-block")
        .description("用来搭脚手架的方块（方块 id，比如 minecraft:cobblestone）。背包里没有的话会在这几种里自动试：圆石/泥土/下界岩/石头")
        .defaultValue("minecraft:cobblestone")
        .build());

    private final Setting<Integer> maxScaffoldDepth = sgScaffold.add(new IntSetting.Builder()
        .name("max-scaffold-depth")
        .description("往下找地面/已搭部分最多找多少格，超过这个还没找到实心方块就放弃这个位置（避免在天空之城下面搭几百格高的柱子）")
        .defaultValue(60).min(5).max(300).build());

    private final Setting<Boolean> cleanupScaffold = sgScaffold.add(new BoolSetting.Builder()
        .name("cleanup-scaffold")
        .description("开：建筑放完之后自动把脚手架挖掉（“修复地形”）；关：脚手架留在原地不管")
        .defaultValue(true).build());

    /* ===== 状态 ===== */
    private final BaritoneBridge bridge = new BaritoneBridge();
    private boolean cmdRegistered = false;
    private int cmdRetryTimer = 0;

    private enum Phase { IDLE, BUILDING, CLEANUP }

    private record Plan(BlockPos pos, BlockState state) {}

    private final ArrayDeque<Plan> queue = new ArrayDeque<>();
    private final HashMap<BlockPos, BlockState> planMap = new HashMap<>(); // 坐标 -> 原理图里该放什么，脚手架要避让这些位置
    private final HashMap<Item, Integer> needed = new HashMap<>();
    private final LinkedHashSet<BlockPos> scaffoldPositions = new LinkedHashSet<>(); // 搭了哪些临时脚手架，收尾要挖掉
    private boolean loaded = false;
    private Phase phase = Phase.IDLE;
    private int placeCd = 0;
    private int stuckTicks = 0;
    private int noFaceWarnTimer = 0;
    private BlockPos walkTarget = null;
    private boolean mining = false;

    public LitematicaBuilder() {
        super(AddonTemplate.CATEGORY, "litematica-builder", "导入 .litematic 文件，核对材料后自动建造");
        tryRegisterCommands();
    }

    @Override
    public void onActivate() {
        resetPlan();
        tryRegisterCommands();
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        if (useBaritoneWalk.get() && bridge.available()) bridge.stop();
    }

    private void resetPlan() {
        queue.clear();
        planMap.clear();
        needed.clear();
        scaffoldPositions.clear();
        loaded = false;
        phase = Phase.IDLE;
        walkTarget = null;
        mining = false;
    }

    /* ========== 指令：#lbload <path> / #lbstart / #lbstop / #lbstatus ========== */
    private void tryRegisterCommands() {
        if (cmdRegistered || !bridge.available()) return;
        cmdRegistered = bridge.registerCommand(List.of("lbload"), "读取 .litematic 文件并核对材料",
            List.of("用法：#lbload <文件路径>（不填路径就用设置里的 schematic-path）"),
            this::cmdLoad);
        bridge.registerCommand(List.of("lbstart"), "开始建造（要先 #lbload 并且材料够）",
            List.of("用法：#lbstart"), raw -> cmdStart());
        bridge.registerCommand(List.of("lbstop"), "停止建造",
            List.of("用法：#lbstop"), raw -> cmdStop());
        bridge.registerCommand(List.of("lbstatus"), "查看建造进度",
            List.of("用法：#lbstatus"), raw -> cmdStatus());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSendMessage(SendMessageEvent event) {
        String m = event.message;
        if (m == null) return;
        String t = m.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("#lbload") || low.startsWith("#lbload ")) {
            event.cancel();
            cmdLoad(t.length() > 7 ? t.substring(7).trim() : "");
            return;
        }
        if (low.equals("#lbstart")) { event.cancel(); cmdStart(); return; }
        if (low.equals("#lbstop")) { event.cancel(); cmdStop(); return; }
        if (low.equals("#lbstatus")) { event.cancel(); cmdStatus(); }
    }

    private void cmdLoad(String rawPath) {
        String path = rawPath == null || rawPath.isBlank() ? filePath.get() : rawPath.trim();
        if (path.isBlank()) {
            info("没有指定文件路径，用法：#lbload <.litematic 文件的绝对路径>");
            return;
        }
        if (!isActive()) toggle();
        resetPlan();

        List<Plan> plan;
        try {
            plan = readLitematic(Path.of(path));
        } catch (Exception e) {
            info("读取失败：%s: %s", e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (plan.isEmpty()) {
            info("这个文件里一个要放的方块都没有（可能全是空气，或者解析出错了）");
            return;
        }

        for (Plan p : plan) {
            queue.add(p);
            planMap.put(p.pos(), p.state());
            Item item = p.state().getBlock().asItem();
            if (item != Blocks.AIR.asItem()) needed.merge(item, 1, Integer::sum);
        }
        loaded = true;

        // 打印前 10 个方块的坐标，方便你对照游戏里实际的位置，确认解析出来的坐标对不对
        info("坐标预览（前 %d 个，按从下到上排序）：", Math.min(10, plan.size()));
        int shown = 0;
        for (Plan p : plan) {
            if (shown++ >= 10) break;
            info("  [%d] %s -> %s", shown, p.pos().toShortString(), p.state().getBlock().getDescriptionId());
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null) {
            info("读到了 %d 个方块，但现在不在游戏里，没法核对背包材料", queue.size());
            return;
        }

        boolean creative = isCreative(pl);
        if (creative && creativeAutoSupply.get()) {
            info("读到 %d 个方块，需要 %d 种材料。创造模式，缺的会自动从创造栏拿，直接 #lbstart 开始建",
                queue.size(), needed.size());
            return;
        }

        StringBuilder missing = new StringBuilder();
        int haveAll = 0, needAll = 0;
        for (var e : needed.entrySet()) {
            int have = countItem(pl, e.getKey());
            needAll += e.getValue();
            haveAll += Math.min(have, e.getValue());
            if (have < e.getValue()) {
                if (missing.length() > 0) missing.append("，");
                missing.append(e.getKey().getDescription().getString())
                    .append(" 还差 ").append(e.getValue() - have);
            }
        }

        info("读到 %d 个方块，需要 %d 种材料（共 %d 个，背包已有 %d 个）",
            queue.size(), needed.size(), needAll, haveAll);

        if (missing.length() > 0) {
            info("材料不够，还差：%s", missing);
            info("材料备齐之后重新 #lbload 一次，或者直接 #lbstart 也会在真正缺材料时自动停下");
        } else {
            info("材料都够，输入 #lbstart 开始建造");
        }
    }

    private void cmdStart() {
        if (!loaded || (queue.isEmpty() && scaffoldPositions.isEmpty())) {
            info("还没读取文件，先 #lbload <路径>");
            return;
        }
        phase = queue.isEmpty() ? Phase.CLEANUP : Phase.BUILDING;
        stuckTicks = 0;
        info("开始建造，剩余 %d 个方块", queue.size());
    }

    private void cmdStop() {
        phase = Phase.IDLE;
        if (useBaritoneWalk.get() && bridge.available()) bridge.stop();
        info("已停止建造，剩余 %d 个方块、%d 个脚手架（#lbstart 可以接着建）", queue.size(), scaffoldPositions.size());
    }

    private void cmdStatus() {
        if (!loaded) {
            info("还没读取任何文件");
            return;
        }
        info("建造%s：阶段=%s，还剩 %d 个方块，脚手架 %d 个",
            phase == Phase.IDLE ? "已暂停" : "进行中", phase, queue.size(), scaffoldPositions.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!cmdRegistered && ++cmdRetryTimer >= 200) {
            cmdRetryTimer = 0;
            tryRegisterCommands();
        }

        if (phase == Phase.IDLE) return;

        if (placeCd > 0) {
            placeCd--;
            return;
        }

        if (phase == Phase.BUILDING) {
            if (queue.isEmpty()) {
                if (cleanupScaffold.get() && !scaffoldPositions.isEmpty()) {
                    info("主体建造完成，开始清理 %d 个脚手架方块", scaffoldPositions.size());
                    phase = Phase.CLEANUP;
                } else {
                    phase = Phase.IDLE;
                    info("建造完成！");
                }
                return;
            }
            buildStep(mc);
        } else {
            cleanupStep(mc);
        }
    }

    private void buildStep(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level w = mc.level;

        Plan next = queue.peek();
        if (next == null) return;

        // 已经是目标方块了（之前放过/本来就有），直接跳过
        if (w.getBlockState(next.pos()).getBlock() == next.state().getBlock()) {
            queue.poll();
            placeCd = 1;
            return;
        }

        double dist = p.position().distanceTo(Vec3.atCenterOf(next.pos()));
        if (dist > reach.get()) {
            walkTarget = next.pos();
            if (useBaritoneWalk.get() && bridge.available()) {
                bridge.goToBlock(next.pos());
                if (++stuckTicks > 300) {
                    info("走了很久还没到 %s 附近，先跳过这个，继续后面的", next.pos().toShortString());
                    queue.poll();
                    queue.add(next); // 放到队尾，之后再试
                    stuckTicks = 0;
                }
            } else {
                // 没装 Baritone：等玩家自己飞/走过来，先处理队列里别的方块
                Plan deferred = queue.poll();
                queue.add(deferred);
                if (++stuckTicks > 100) {
                    stuckTicks = 0;
                    info("还有方块够不着，请飞到 %s 附近（剩 %d 个）", next.pos().toShortString(), queue.size());
                }
            }
            placeCd = 5;
            return;
        }
        stuckTicks = 0;

        // 找一个相邻的、已经是实心方块的面，朝那里右键放置
        BlockHitResult hit = findPlacementFace(w, next.pos());
        if (hit == null) {
            if (scaffoldEnabled.get()) {
                boolean progressed = scaffoldStep(mc, p, w, next.pos());
                if (progressed) {
                    placeCd = placeDelay.get();
                    return;
                }
                // 搭不了（找不到地面、或者方块不够），warn 一下，别无声无息地死循环
                if (++noFaceWarnTimer >= 60) {
                    noFaceWarnTimer = 0;
                    info("[卡住] %s 这个位置周围没有实心方块可以贴靠，自动搭脚手架也失败了（可能是坐标算错了、"
                        + "悬空太深超过 max-scaffold-depth，或者脚手架方块用完了）", next.pos().toShortString());
                }
            } else if (++noFaceWarnTimer >= 60) {
                noFaceWarnTimer = 0;
                info("[卡住] %s 这个位置周围没有实心方块可以贴靠（auto-scaffold 关着，不会自动垫）", next.pos().toShortString());
            }
            queue.poll();
            queue.add(next);
            placeCd = 5;
            return;
        }

        Item needItem = next.state().getBlock().asItem();
        if (needItem == Blocks.AIR.asItem()) {
            queue.poll(); // 目标就是空气，不用放
            placeCd = 1;
            return;
        }

        if (!placeItem(mc, p, needItem, hit)) return; // 材料不够之类的问题，placeItem 内部已经处理/报告

        queue.poll();
        placeCd = placeDelay.get();
    }

    /* ========== 自动搭脚手架：从目标位置正下方，往最近的实心方块（地面/已搭部分）之间一路垫上去 ==========
     * 每次调用只垫一格，垫到能贴着放目标方块为止（之后 buildStep 的 findPlacementFace 就能找到了）。
     * 返回 true 表示这次垫了一格（已经消耗了这个 tick），false 表示没能垫（会被上层记录为失败）。 */
    private boolean scaffoldStep(Minecraft mc, LocalPlayer p, Level w, BlockPos target) {
        BlockPos below = target.below();

        // 正下方已经是实心的，说明问题不在“悬空”，是别的原因（不该走到这里，保险起见直接放弃）
        if (!w.getBlockState(below).isAir()) return false;

        // 从正下方往下找最近的实心方块（地面、已经建好的部分，或者之前搭的脚手架）
        BlockPos probe = below;
        int depth = 0;
        while (w.getBlockState(probe).isAir() && depth < maxScaffoldDepth.get()) {
            probe = probe.below();
            depth++;
        }
        if (depth >= maxScaffoldDepth.get()) return false; // 太深了，可能是悬空在半空中或者坐标算错了

        BlockPos placeAt = probe.above();

        // 这个位置本来就是原理图里需要的方块，就直接放该放的东西（它自己也能当支撑），不用额外搭脚手架
        BlockState planned = planMap.get(placeAt);
        Item item;
        boolean isRealBlock;
        if (planned != null && !planned.isAir()) {
            item = planned.getBlock().asItem();
            isRealBlock = true;
        } else {
            item = findScaffoldItem(p);
            if (item == null) return false; // 没有能当脚手架用的方块了
            isRealBlock = false;
        }

        BlockHitResult hit = findPlacementFace(w, placeAt);
        if (hit == null) return false; // probe 应该是实心的，理论上找得到；找不到就放弃，避免死循环

        if (!placeItem(mc, p, item, hit)) return false;

        if (!isRealBlock) {
            scaffoldPositions.add(placeAt);
        } else {
            // 碰巧是原理图自己的方块：它已经放上去了，不用再在主队列里重复放一次
            queue.removeIf(pl -> pl.pos().equals(placeAt));
        }
        return true;
    }

    // 挑一个背包里有、能当脚手架用的方块：优先用设置里指定的，没有就在几种常见方块里试
    private Item findScaffoldItem(LocalPlayer p) {
        List<String> candidates = new ArrayList<>();
        candidates.add(scaffoldBlockId.get());
        candidates.add("minecraft:cobblestone");
        candidates.add("minecraft:dirt");
        candidates.add("minecraft:netherrack");
        candidates.add("minecraft:stone");
        for (String id : candidates) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(rl);
            if (b == Blocks.AIR) continue;
            Item item = b.asItem();
            if (isCreative(p) && creativeAutoSupply.get()) return item; // 创造模式反正能自己拿
            if (findItemSlot(p, item) >= 0) return item;
        }
        return null;
    }

    /* ========== 收尾：把脚手架挖掉（“修复地形”） ========== */
    private void cleanupStep(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level w = mc.level;

        if (scaffoldPositions.isEmpty()) {
            phase = Phase.IDLE;
            info("脚手架清理完成，建造全部结束！");
            return;
        }

        BlockPos pos = scaffoldPositions.iterator().next();
        if (w.getBlockState(pos).isAir()) {
            scaffoldPositions.remove(pos);
            mining = false;
            placeCd = 1;
            return;
        }

        double dist = p.position().distanceTo(Vec3.atCenterOf(pos));
        if (dist > reach.get()) {
            if (useBaritoneWalk.get() && bridge.available()) {
                bridge.goToBlock(pos);
            }
            placeCd = 5;
            return;
        }

        float[] look = lookAt(p, Vec3.atCenterOf(pos));
        p.setYRot(look[0]);
        p.setXRot(look[1]);

        if (isCreative(p)) {
            mc.gameMode.destroyBlock(pos);
            scaffoldPositions.remove(pos);
            placeCd = 1;
            return;
        }

        if (!mining) {
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            mining = true;
        } else {
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    /* ========== 放置一个方块：切到对应物品、转头看过去、右键放置 ==========
     * 返回 true 表示放置动作已经发出去了；false 表示材料不够（创造模式之外）或者物品正在换格子的中间状态。 */
    private boolean placeItem(Minecraft mc, LocalPlayer p, Item needItem, BlockHitResult hit) {
        int slot = findItemSlot(p, needItem);
        if (slot < 0) {
            if (isCreative(p) && creativeAutoSupply.get()) {
                giveCreative(mc, p, needItem);
                placeCd = 2; // 等服务器把创造栏给的物品同步回来，下个 tick 再找
                return false;
            }
            phase = Phase.IDLE;
            info("材料用完了：%s，已停止建造，剩 %d 个方块没放", needItem.getDescription().getString(), queue.size());
            return false;
        }
        if (slot >= 9) {
            InvUtils.move().from(slot).toHotbar(8);
            placeCd = 2;
            return false;
        }
        InvUtils.swap(slot, false);

        float[] look = lookAt(p, hit.getLocation());
        p.setYRot(look[0]);
        p.setXRot(look[1]);

        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // 在目标位置周围 6 个方向找一个已经是实心方块的邻居，对着那个面右键。
    // 注意：Vec3 没有 relative(Direction, double) 这个方法（那是 BlockPos/Vec3i 才有的），
    // 这里改成手动按方向的单位向量加偏移。
    private static BlockHitResult findPlacementFace(Level w, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = target.relative(d);
            BlockState ns = w.getBlockState(neighbor);
            if (ns.isAir() || !ns.isSolidRender(w, neighbor)) continue;
            Direction opp = d.getOpposite();
            Vec3 hitPoint = Vec3.atCenterOf(neighbor).add(
                opp.getStepX() * 0.5, opp.getStepY() * 0.5, opp.getStepZ() * 0.5);
            return new BlockHitResult(hitPoint, opp, neighbor, false);
        }
        return null;
    }

    private static float[] lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eyes = p.getEyePosition();
        double dx = target.x - eyes.x, dy = target.y - eyes.y, dz = target.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        return new float[]{yaw, pitch};
    }

    private static int countItem(LocalPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static int findItemSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    // instabuild 就是创造模式那个"随便拿东西建"的能力标志；生存/冒险模式下是 false
    private static boolean isCreative(LocalPlayer p) {
        return p.getAbilities().instabuild;
    }

    // 固定用快捷栏最后一格（下标 8）临时放要建的方块，跟 placeItem 里换到满组材料时用的格子保持一致
    private static final int CREATIVE_SLOT = 8;

    private void giveCreative(Minecraft mc, LocalPlayer p, Item item) {
        ItemStack stack = new ItemStack(item, 64);
        p.getInventory().setItem(CREATIVE_SLOT, stack);
        mc.gameMode.handleCreativeModeItemAdd(stack, 36 + CREATIVE_SLOT); // 36+格子号 = 这个槽位在玩家容器里的编号
    }

    /* =====================================================================
     *  litematica 文件解析。标准结构（gzip 压缩的 NBT）：
     *  根节点
     *   └ Regions（复合标签，key 是区域名，随便取的）
     *       └ <区域名>
     *           ├ Position {x,y,z}   区域的起始角坐标
     *           ├ Size {x,y,z}       区域尺寸，可以是负数（表示往反方向延伸）
     *           ├ BlockStatePalette  方块状态调色板（列表，每项是 Name + 可选 Properties）
     *           └ BlockStates        long[]，按调色板下标打包存的方块数组
     *  这部分我没有真机验证过，读出来不对大概率是这两个方法要调——#lbload 之后会打印前 10
     *  个坐标，建议先核对一下。
     * ===================================================================== */
    private List<Plan> readLitematic(Path path) throws IOException {
        CompoundTag root;
        try (var in = java.nio.file.Files.newInputStream(path)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }

        CompoundTag regions = root.getCompound("Regions");
        List<Plan> result = new ArrayList<>();

        for (String regionName : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(regionName);

            CompoundTag posTag = region.getCompound("Position");
            int px = posTag.getInt("x"), py = posTag.getInt("y"), pz = posTag.getInt("z");

            CompoundTag sizeTag = region.getCompound("Size");
            int sx = sizeTag.getInt("x"), sy = sizeTag.getInt("y"), sz = sizeTag.getInt("z");

            int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
            int startX = sx >= 0 ? px : px + sx + 1;
            int startY = sy >= 0 ? py : py + sy + 1;
            int startZ = sz >= 0 ? pz : pz + sz + 1;

            List<BlockState> palette = decodePalette(region.getList("BlockStatePalette", Tag.TAG_COMPOUND));
            long[] packed = region.getLongArray("BlockStates");
            int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));

            long totalEntries = (long) absX * absY * absZ;
            for (long idx = 0; idx < totalEntries; idx++) {
                int paletteIdx = readPackedEntry(packed, bitsPerEntry, idx);
                if (paletteIdx < 0 || paletteIdx >= palette.size()) continue;
                BlockState state = palette.get(paletteIdx);
                if (state.isAir()) continue;

                // 线性下标转局部坐标：litematica 是 y-major，然后 z，然后 x
                long rem = idx;
                int lx = (int) (rem % absX); rem /= absX;
                int lz = (int) (rem % absZ); rem /= absZ;
                int ly = (int) rem;

                BlockPos world = new BlockPos(startX + lx, startY + ly, startZ + lz);
                result.add(new Plan(world, state));
            }
        }

        // 从下到上排序，保证下面先放好、上面的才有依靠
        result.sort(Comparator.comparingInt(a -> a.pos().getY()));
        return result;
    }

    private List<BlockState> decodePalette(ListTag paletteList) {
        List<BlockState> out = new ArrayList<>(paletteList.size());
        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag entry = paletteList.getCompound(i);
            String name = entry.getString("Name");
            ResourceLocation id = ResourceLocation.tryParse(name);
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            BlockState state = block.defaultBlockState();

            if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag props = entry.getCompound("Properties");
                for (String key : props.getAllKeys()) {
                    Property<?> prop = state.getBlock().getStateDefinition().getProperty(key);
                    if (prop == null) continue;
                    state = applyProperty(state, prop, props.getString(key));
                }
            }
            out.add(state);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> prop, String value) {
        Optional<T> parsed = prop.getValue(value);
        return parsed.map(v -> state.setValue(prop, v)).orElse(state);
    }

    // litematica 的变长位打包：每项占 bitsPerEntry 位，LSB 在前，可以跨 long 边界（不像区块数据那样对齐补位）
    private static int readPackedEntry(long[] array, int bitsPerEntry, long index) {
        long bitIndex = index * bitsPerEntry;
        int startArrIdx = (int) (bitIndex >> 6);
        int endArrIdx = (int) (((index + 1) * bitsPerEntry - 1) >> 6);
        int startBitOffset = (int) (bitIndex & 0x3F);

        if (startArrIdx >= array.length) return -1;

        long value;
        if (startArrIdx == endArrIdx) {
            value = array[startArrIdx] >>> startBitOffset;
        } else {
            int endOffset = 64 - startBitOffset;
            value = (array[startArrIdx] >>> startBitOffset);
            if (endArrIdx < array.length) value |= array[endArrIdx] << endOffset;
        }
        long mask = (1L << bitsPerEntry) - 1;
        return (int) (value & mask);
    }
}
