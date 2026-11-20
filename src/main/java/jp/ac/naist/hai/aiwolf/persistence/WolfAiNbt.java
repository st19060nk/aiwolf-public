package jp.ac.naist.hai.aiwolf.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfEventObserver;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;

/**
 * 個体 1 体分の AI 状態と NBT の相互変換。
 *
 * <p>永続化の詳細をここへ閉じ込め、{@link WolfAiAgent} 側は Minecraft を知らないままにしている。</p>
 *
 * <p>読み込み側は<strong>未知のキーを無視し、欠損したキーには初期値を使う</strong>。
 * 状態量や行動を追加・削除しても、既存のワールドが壊れないようにするためである。</p>
 *
 * <p>決定インデックスと記憶 ID の連番も保存する。これらを復元しないと、
 * セーブ／ロードのたびに乱数列と記憶 ID が巻き戻り、再現性と研究ログの整合性が失われる。</p>
 */
public final class WolfAiNbt {

    /**
     * 保存形式のバージョン。
     *
     * <p>2 で currentBehavior・決定タイミング・Observer の長期状態を追加した。
     * 3 で combat の採番カウンタとメンテナンスの端数を追加した。
     * 古いワールドも読めるよう、追加キーはすべて任意扱いにしている。</p>
     */
    public static final int FORMAT_VERSION = 3;

    private static final String KEY_VERSION = "formatVersion";
    private static final String KEY_UUID = "wolfUuid";
    private static final String KEY_CONDITION = "condition";
    private static final String KEY_OWNER = "ownerResearchId";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_SEED = "randomSeed";
    private static final String KEY_DECISION_INDEX = "decisionIndex";
    private static final String KEY_MEMORY_SEQUENCE = "memorySequence";
    private static final String KEY_STATE = "state";
    private static final String KEY_PREFERENCES = "preferences";
    private static final String KEY_MEMORIES = "memories";
    private static final String KEY_REGIONS = "regions";
    private static final String KEY_CURRENT_BEHAVIOR = "currentBehavior";
    private static final String KEY_LAST_DECISION_TICK = "lastDecisionTick";
    private static final String KEY_BEHAVIOUR_START_TICK = "behaviourStartTick";
    private static final String KEY_OBSERVER = "observer";
    private static final String KEY_VISITED_BIOMES = "visitedBiomes";
    private static final String KEY_LAST_PET_TICK = "lastPetTick";
    private static final String KEY_LAST_SLEEP_TICK = "lastSleepEventTick";
    private static final String KEY_SEPARATION_REPORTED = "separationReported";
    private static final String KEY_COMBAT_SEQUENCE = "combatSequence";
    private static final String KEY_MAINTENANCE_CARRY = "maintenanceCarryTicks";

    private WolfAiNbt() {
        throw new AssertionError("utility class");
    }

    /**
     * 個体を NBT へ書き出す。
     *
     * @param controller 個体のランタイム
     * @return NBT
     */
    public static NbtCompound write(WolfAiController controller) {
        WolfAiAgent agent = controller.agent();
        NbtCompound nbt = new NbtCompound();
        nbt.putInt(KEY_VERSION, FORMAT_VERSION);
        nbt.putString(KEY_UUID, agent.wolfId());
        nbt.putString(KEY_CONDITION, agent.condition().id());
        if (agent.ownerResearchId() != null) {
            nbt.putString(KEY_OWNER, agent.ownerResearchId());
        }
        nbt.putLong(KEY_CREATED_AT, agent.createdAtEpochMillis());
        nbt.putLong(KEY_UPDATED_AT, agent.lastUpdatedEpochMillis());
        nbt.putLong(KEY_SEED, agent.random().seed());
        nbt.putLong(KEY_DECISION_INDEX, agent.random().decisionIndex());
        nbt.putLong(KEY_MEMORY_SEQUENCE, agent.memorySequence());
        // メンテナンスの端数。保存しないとセーブのたびに切り捨てられ、
        // 長期実験で感情のベースライン回帰が実際より遅くなる。
        nbt.putLong(KEY_MAINTENANCE_CARRY, agent.maintenanceCarryTicks());

        NbtCompound state = new NbtCompound();
        for (StateParameter parameter : StateParameter.ALL) {
            state.putDouble(parameter.key(), agent.state().get(parameter));
        }
        nbt.put(KEY_STATE, state);

        NbtCompound preferences = new NbtCompound();
        for (BehaviorType behavior : BehaviorType.ALL) {
            preferences.putDouble(behavior.id(), agent.preferences().get(behavior));
        }
        nbt.put(KEY_PREFERENCES, preferences);

        NbtList memories = new NbtList();
        for (WolfMemory memory : agent.memoryStore().memories()) {
            memories.add(writeMemory(memory));
        }
        nbt.put(KEY_MEMORIES, memories);

        NbtList regions = new NbtList();
        for (Map.Entry<SpatialRegion, RegionMemory> entry : agent.spatialMemory().regions().entrySet()) {
            regions.add(writeRegion(entry.getKey(), entry.getValue()));
        }
        nbt.put(KEY_REGIONS, regions);

        // 現在行動を保存する。BehaviorScorer が現在行動へ継続ボーナスを加えるため、
        // これがないと再起動前後で次の行動スコアが変わり、再現性が壊れる。
        if (agent.currentBehavior() != null) {
            nbt.putString(KEY_CURRENT_BEHAVIOR, agent.currentBehavior().id());
        }
        nbt.putLong(KEY_LAST_DECISION_TICK, controller.lastDecisionTick());
        nbt.putLong(KEY_BEHAVIOUR_START_TICK, controller.behaviourStartTick());
        nbt.put(KEY_OBSERVER, writeObserver(controller.observer().exportState()));

        return nbt;
    }

    /**
     * NBT から個体のランタイムを復元する。
     *
     * @param nbt NBT
     * @return 復元したランタイム。UUID が壊れていれば {@code null}
     */
    public static WolfAiController read(NbtCompound nbt) {
        WolfAiAgent agent = readAgent(nbt);
        if (agent == null) {
            return null;
        }
        WolfAiController controller = new WolfAiController(agent);

        // 旧セーブ（FORMAT_VERSION 1）にはこれらのキーがない。
        // 存在しない場合は未決定として扱い、次の意思決定で自然に埋まる。
        if (nbt.contains(KEY_CURRENT_BEHAVIOR)) {
            // 未知の行動 ID（行動を削除したあとの旧セーブなど）は無視する。
            // 例外を投げるとワールドが読めなくなるため、安全側に倒す。
            BehaviorType.byId(nbt.getString(KEY_CURRENT_BEHAVIOR))
                    .ifPresent(agent::restoreCurrentBehavior);
        }
        controller.restoreDecisionTiming(
                nbt.contains(KEY_LAST_DECISION_TICK) ? nbt.getLong(KEY_LAST_DECISION_TICK) : Long.MIN_VALUE,
                nbt.contains(KEY_BEHAVIOUR_START_TICK)
                        ? nbt.getLong(KEY_BEHAVIOUR_START_TICK) : Long.MIN_VALUE);
        if (nbt.contains(KEY_OBSERVER)) {
            controller.observer().importState(readObserver(nbt.getCompound(KEY_OBSERVER)));
        }
        return controller;
    }

    /**
     * NBT から個体そのものを復元する。
     *
     * @param nbt NBT
     * @return 復元した個体。UUID が壊れていれば {@code null}
     */
    private static WolfAiAgent readAgent(NbtCompound nbt) {
        String wolfId = nbt.getString(KEY_UUID);
        if (wolfId.isEmpty() || !isUuid(wolfId)) {
            return null;
        }
        ExperimentalCondition condition = ExperimentalCondition.byId(nbt.getString(KEY_CONDITION))
                .orElse(ExperimentalCondition.ADAPTIVE);

        WolfAiAgent agent = WolfAiAgent.restore(wolfId, condition,
                nbt.getLong(KEY_SEED), Math.max(0L, nbt.getLong(KEY_DECISION_INDEX)));
        agent.restoreMemorySequence(Math.max(0L, nbt.getLong(KEY_MEMORY_SEQUENCE)));
        agent.restoreMaintenanceCarryTicks(
                nbt.contains(KEY_MAINTENANCE_CARRY) ? nbt.getLong(KEY_MAINTENANCE_CARRY) : 0L);
        agent.restoreTimestamps(nbt.getLong(KEY_CREATED_AT), nbt.getLong(KEY_UPDATED_AT));
        if (nbt.contains(KEY_OWNER)) {
            agent.setOwnerResearchId(nbt.getString(KEY_OWNER));
        }

        NbtCompound state = nbt.getCompound(KEY_STATE);
        for (StateParameter parameter : StateParameter.ALL) {
            if (state.contains(parameter.key())) {
                agent.state().set(parameter, state.getDouble(parameter.key()));
            }
        }

        NbtCompound preferences = nbt.getCompound(KEY_PREFERENCES);
        for (BehaviorType behavior : BehaviorType.ALL) {
            if (preferences.contains(behavior.id())) {
                agent.preferences().set(behavior, preferences.getDouble(behavior.id()));
            }
        }

        NbtList memories = nbt.getList(KEY_MEMORIES, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < memories.size(); i++) {
            WolfMemory memory = readMemory(memories.getCompound(i));
            if (memory != null) {
                agent.memoryStore().add(memory, memory.worldTime());
            }
        }

        NbtList regions = nbt.getList(KEY_REGIONS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < regions.size(); i++) {
            readRegionInto(regions.getCompound(i), agent);
        }

        return agent;
    }

    /**
     * Observer の長期状態を NBT へ書き出す。何を保存し何を保存しないかは
     * {@link WolfEventObserver} の Javadoc に分類理由がある。
     */
    private static NbtCompound writeObserver(WolfEventObserver.PersistedState state) {
        NbtCompound nbt = new NbtCompound();
        NbtList biomes = new NbtList();
        for (String biome : state.visitedBiomes()) {
            biomes.add(NbtString.of(biome));
        }
        nbt.put(KEY_VISITED_BIOMES, biomes);
        nbt.putLong(KEY_LAST_PET_TICK, state.lastPetTick());
        nbt.putLong(KEY_LAST_SLEEP_TICK, state.lastSleepEventTick());
        nbt.putBoolean(KEY_SEPARATION_REPORTED, state.separationReported());
        // 戦闘の採番カウンタ。0 へ戻すと同じ個体の別セッションの戦闘が
        // 同じ combatId を持ち、解析で無関係な 2 件が 1 件へ結合される。
        nbt.putLong(KEY_COMBAT_SEQUENCE, state.combatSequence());
        return nbt;
    }

    private static WolfEventObserver.PersistedState readObserver(NbtCompound nbt) {
        NbtList biomes = nbt.getList(KEY_VISITED_BIOMES, NbtElement.STRING_TYPE);
        List<String> visited = new ArrayList<>(biomes.size());
        for (int i = 0; i < biomes.size(); i++) {
            visited.add(biomes.getString(i));
        }
        return new WolfEventObserver.PersistedState(visited,
                nbt.contains(KEY_LAST_PET_TICK) ? nbt.getLong(KEY_LAST_PET_TICK) : Long.MIN_VALUE,
                nbt.contains(KEY_LAST_SLEEP_TICK) ? nbt.getLong(KEY_LAST_SLEEP_TICK) : Long.MIN_VALUE,
                nbt.getBoolean(KEY_SEPARATION_REPORTED),
                nbt.contains(KEY_COMBAT_SEQUENCE) ? nbt.getLong(KEY_COMBAT_SEQUENCE) : 0L);
    }

    private static NbtCompound writeMemory(WolfMemory memory) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", memory.memoryId());
        nbt.putLong("worldTime", memory.worldTime());
        nbt.putString("type", memory.type().id());
        nbt.putString("dimension", memory.dimension());
        nbt.putString("biome", memory.biome());
        nbt.putInt("regionX", memory.region().regionX());
        nbt.putInt("regionY", memory.region().regionY());
        nbt.putInt("regionZ", memory.region().regionZ());
        nbt.putDouble("x", memory.x());
        nbt.putDouble("y", memory.y());
        nbt.putDouble("z", memory.z());
        if (memory.actorId() != null) {
            nbt.putString("actor", memory.actorId());
        }
        nbt.putDouble("wolfHealth", memory.wolfHealthRatio());
        nbt.putDouble("playerHealth", memory.playerHealthRatio());
        nbt.putDouble("valence", memory.valence());
        nbt.putDouble("arousal", memory.arousal());
        return nbt;
    }

    private static WolfMemory readMemory(NbtCompound nbt) {
        WolfEventType type = WolfEventType.byId(nbt.getString("type")).orElse(null);
        if (type == null) {
            // 既に削除された種別の記憶は読み飛ばす。
            return null;
        }
        String dimension = nbt.getString("dimension");
        SpatialRegion region = new SpatialRegion(dimension,
                nbt.getInt("regionX"), nbt.getInt("regionY"), nbt.getInt("regionZ"));
        double valence = nbt.getDouble("valence");
        double arousal = nbt.getDouble("arousal");
        return new WolfMemory(nbt.getString("id"), nbt.getLong("worldTime"), type,
                dimension, nbt.getString("biome"), region,
                nbt.getDouble("x"), nbt.getDouble("y"), nbt.getDouble("z"),
                nbt.contains("actor") ? nbt.getString("actor") : null,
                nbt.getDouble("wolfHealth"), nbt.getDouble("playerHealth"),
                valence, arousal, type.salienceOf(valence, arousal), Map.of());
    }

    private static NbtCompound writeRegion(SpatialRegion region, RegionMemory memory) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("dimension", region.dimension());
        nbt.putInt("regionX", region.regionX());
        nbt.putInt("regionY", region.regionY());
        nbt.putInt("regionZ", region.regionZ());
        nbt.putDouble("fear", memory.fearValue());
        nbt.putDouble("positive", memory.positiveValue());
        nbt.putInt("visits", memory.visitCount());
        nbt.putLong("lastVisited", memory.lastVisitedWorldTime());
        return nbt;
    }

    private static void readRegionInto(NbtCompound nbt, WolfAiAgent agent) {
        SpatialRegion region = new SpatialRegion(nbt.getString("dimension"),
                nbt.getInt("regionX"), nbt.getInt("regionY"), nbt.getInt("regionZ"));
        agent.spatialMemory().restoreRegion(region, new RegionMemory(
                nbt.getDouble("fear"), nbt.getDouble("positive"),
                nbt.getInt("visits"), nbt.getLong("lastVisited")));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
