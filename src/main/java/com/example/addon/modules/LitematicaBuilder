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
import java.util.function.Consumer;

/**
 * 读一个 .litematic 文件，核对背包材料够不够，不够就报缺多少然后不开工；
 * 材料够的话就按从下到上的顺序自动摆方块。
 *
 * 这不是全自动路径规划：装了 Baritone 就会用 Baritone 走到每个目标点附近再放；
 * 没装 Baritone 的话，只会摆你站的位置够得着的方块，摆不到的会一直留在队列里，
 * 需要你自己飞/走过去。
 *
 * litematica 的 NBT 结构、BlockStates 的变长整数打包方式是我照着公开的格式文档写的，
 * 没有真机测试过，如果读出来的方块数量/位置不对，大概率是 decodePalette() 或
 * readBlockStates() 这两个方法的细节需要调。
 */
public class LitematicaBuilder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

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

    /* ===== 状态 ===== */
    private final BaritoneBridge bridge = new BaritoneBridge();
    private boolean cmdRegistered = false;
    private int cmdRetryTimer = 0;

    private record Plan(BlockPos pos, BlockState state) {}

    private final ArrayDeque<Plan> queue = new ArrayDeque<>();
    private final HashMap<Item, Integer> needed = new HashMap<>();
    private boolean loaded = false;
    private boolean building = false;
    private int placeCd = 0;
    private int stuckTicks = 0;
    private BlockPos walkTarget = null;

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
        building = false;
        if (useBaritoneWalk.get() && bridge.available()) bridge.stop();
    }

    private void resetPlan() {
        queue.clear();
        needed.clear();
        loaded = false;
        building = false;
        walkTarget = null;
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
            Item item = p.state().getBlock().asItem();
            if (item != Blocks.AIR.asItem()) needed.merge(item, 1, Integer::sum);
        }
        loaded = true;

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
        if (!loaded || queue.isEmpty()) {
            info("还没读取文件，先 #lbload <路径>");
            return;
        }
        building = true;
        stuckTicks = 0;
        info("开始建造，剩余 %d 个方块", queue.size());
    }

    private void cmdStop() {
        building = false;
        if (useBaritoneWalk.get() && bridge.available()) bridge.stop();
        info("已停止建造，剩余 %d 个方块（#lbstart 可以接着建）", queue.size());
    }

    private void cmdStatus() {
        if (!loaded) {
            info("还没读取任何文件");
            return;
        }
        info("建造%s中：还剩 %d 个方块", building ? "" : "已暂停", queue.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!cmdRegistered && ++cmdRetryTimer >= 200) {
            cmdRetryTimer = 0;
            tryRegisterCommands();
        }

        if (!building || queue.isEmpty()) {
            if (building && queue.isEmpty()) {
                building = false;
                info("建造完成！");
            }
            return;
        }

        if (placeCd > 0) {
            placeCd--;
            return;
        }

        buildStep(mc);
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
            // 周围 6 个方向都还是空的，这个位置暂时没法放（比如悬空结构，等邻居先放）
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

        int slot = findItemSlot(p, needItem);
        if (slot < 0) {
            if (isCreative(p) && creativeAutoSupply.get()) {
                giveCreative(mc, p, needItem);
                placeCd = 2; // 等服务器把创造栏给的物品同步回来，下个 tick 再找
                return;
            }
            building = false;
            info("材料用完了：%s，已停止建造，剩 %d 个方块没放", needItem.getDescription().getString(), queue.size());
            return;
        }
        if (slot >= 9) {
            InvUtils.move().from(slot).toHotbar(8);
            placeCd = 2;
            return;
        }
        InvUtils.swap(slot, false);

        float[] look = lookAt(p, hit.getLocation());
        p.setYRot(look[0]);
        p.setXRot(look[1]);

        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);

        queue.poll();
        placeCd = placeDelay.get();
    }

    // 在目标位置周围 6 个方向找一个已经是实心方块的邻居，对着那个面右键
    private static BlockHitResult findPlacementFace(Level w, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = target.relative(d);
            BlockState ns = w.getBlockState(neighbor);
            if (ns.isAir() || !ns.isSolidRender(w, neighbor)) continue;
            Vec3 hitPoint = Vec3.atCenterOf(neighbor).relative(d.getOpposite(), 0.5);
            return new BlockHitResult(hitPoint, d.getOpposite(), neighbor, false);
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

    // 固定用快捷栏最后一格（下标 8）临时放要建的方块，跟 buildStep 里换到满组材料时用的格子保持一致
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
     *  这部分我没有真机验证过，读出来不对大概率是这两个方法要调。
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
