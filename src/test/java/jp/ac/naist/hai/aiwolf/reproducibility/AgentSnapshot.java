package jp.ac.naist.hai.aiwolf.reproducibility;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/**
 * 永続化のセーブ・ロードを Minecraft なしで再現するスナップショット。
 *
 * <p>保存する項目は {@code WolfAiNbt} と<strong>同一</strong>にしてある。
 * NBT 層は Minecraft の型に依存するためこのテスト環境では実行できないが、
 * 「この項目集合を保存・復元すれば行動列が途切れない」という要件は
 * ここで検証できる。逆に言えば、この一覧に載っている項目を NBT 側が
 * 書き忘れていればセーブ・ロードで行動列が変わる。</p>
 *
 * <p>とくに {@code decisionIndex} と {@code currentBehavior} は落としやすい。
 * 前者を落とすと乱数列が巻き戻り、後者を落とすと継続ボーナスが消えて
 * 再起動直後の 1 手が変わる。</p>
 */
final class AgentSnapshot {

    private final String wolfId;
    private final ExperimentalCondition condition;
    private final long seed;
    private final long decisionIndex;
    private final long memorySequence;
    private final Map<StateParameter, Double> state = new EnumMap<>(StateParameter.class);
    private final Map<BehaviorType, Double> preferences = new EnumMap<>(BehaviorType.class);
    private final List<WolfMemory> memories;
    private final Map<SpatialRegion, RegionMemory> regions = new LinkedHashMap<>();
    private final BehaviorType currentBehavior;

    private AgentSnapshot(WolfAiAgent agent) {
        this.wolfId = agent.wolfId();
        this.condition = agent.condition();
        this.seed = agent.random().seed();
        this.decisionIndex = agent.random().decisionIndex();
        this.memorySequence = agent.memorySequence();
        for (StateParameter parameter : StateParameter.values()) {
            state.put(parameter, agent.state().get(parameter));
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            preferences.put(behavior, agent.preferences().get(behavior));
        }
        this.memories = List.copyOf(agent.memoryStore().memories());
        for (Map.Entry<SpatialRegion, RegionMemory> entry : agent.spatialMemory().regions().entrySet()) {
            RegionMemory source = entry.getValue();
            regions.put(entry.getKey(), new RegionMemory(source.fearValue(), source.positiveValue(),
                    source.visitCount(), source.lastVisitedWorldTime()));
        }
        this.currentBehavior = agent.currentBehavior();
    }

    /**
     * 個体の現在の内容を保存する。個体は変更しない。
     *
     * @param agent 対象個体
     * @return スナップショット
     */
    static AgentSnapshot save(WolfAiAgent agent) {
        return new AgentSnapshot(agent);
    }

    /**
     * スナップショットから新しい個体を復元する。
     *
     * @return 復元した個体
     */
    WolfAiAgent restore() {
        WolfAiAgent agent = WolfAiAgent.restore(wolfId, condition, seed, decisionIndex);
        agent.restoreMemorySequence(memorySequence);
        state.forEach((parameter, value) -> agent.state().set(parameter, value));
        preferences.forEach((behavior, value) -> agent.preferences().set(behavior, value));
        for (WolfMemory memory : memories) {
            agent.memoryStore().add(memory, memory.worldTime());
        }
        regions.forEach(agent.spatialMemory()::restoreRegion);
        agent.restoreCurrentBehavior(currentBehavior);
        return agent;
    }

    /**
     * {@code currentBehavior} を意図的に落として復元する（旧形式セーブの模擬）。
     *
     * @return 復元した個体
     */
    WolfAiAgent restoreWithoutCurrentBehavior() {
        WolfAiAgent agent = restore();
        agent.restoreCurrentBehavior(null);
        return agent;
    }

    /**
     * {@code decisionIndex} を意図的に落として復元する（再現性が壊れる例）。
     *
     * @return 復元した個体
     */
    WolfAiAgent restoreWithoutDecisionIndex() {
        WolfAiAgent agent = WolfAiAgent.restore(wolfId, condition, seed, 0L);
        agent.restoreMemorySequence(memorySequence);
        state.forEach((parameter, value) -> agent.state().set(parameter, value));
        preferences.forEach((behavior, value) -> agent.preferences().set(behavior, value));
        for (WolfMemory memory : memories) {
            agent.memoryStore().add(memory, memory.worldTime());
        }
        regions.forEach(agent.spatialMemory()::restoreRegion);
        agent.restoreCurrentBehavior(currentBehavior);
        return agent;
    }

    /** @return 保存した decisionIndex */
    long decisionIndex() {
        return decisionIndex;
    }

    /** @return 保存した現在行動（未決定なら {@code null}） */
    BehaviorType currentBehavior() {
        return currentBehavior;
    }
}
