package jp.ac.naist.hai.aiwolf.persistence;

import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.mc.WolfAiRegistry;

/**
 * ワールドのセーブデータへ AI 状態を書き出す入れ物。
 *
 * <p>ディメンションではなくワールド全体に 1 つだけ持つ（オーバーワールドへ保存する）。
 * オオカミがネザーへ行っても、チャンクがアンロードされても状態が失われないようにするためである。
 * 個体の同一性はエンティティ UUID のみで決まる。</p>
 *
 * <p>実体のデータは {@link WolfAiRegistry} が保持し、このクラスはその内容を
 * 保存・復元するだけの薄い層である。</p>
 */
public final class WolfAiPersistentState extends PersistentState {

    /** セーブデータ上の名前。{@code data/aiwolf_research.dat} として保存される。 */
    public static final String STATE_ID = "aiwolf_research";

    private static final String KEY_VERSION = "formatVersion";
    private static final String KEY_EXPERIMENT_SEED = "experimentSeed";
    private static final String KEY_DEFAULT_CONDITION = "defaultCondition";
    private static final String KEY_ANONYMITY_SALT = "anonymitySalt";
    private static final String KEY_WOLVES = "wolves";

    /** {@link PersistentState} の生成・読み込み定義。 */
    public static final PersistentState.Type<WolfAiPersistentState> TYPE =
            new PersistentState.Type<>(WolfAiPersistentState::new, WolfAiPersistentState::fromNbt, null);

    /**
     * サーバのセーブデータから読み込む。存在しなければ新規に作る。
     *
     * @param server サーバ
     * @return 永続化状態
     */
    public static WolfAiPersistentState get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("overworld is not available");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, STATE_ID);
    }

    /**
     * NBT から読み込み、レジストリへ流し込む。
     *
     * @param nbt            NBT
     * @param registryLookup レジストリ参照（本実装では未使用）
     * @return 永続化状態
     */
    private static WolfAiPersistentState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        WolfAiPersistentState state = new WolfAiPersistentState();

        if (nbt.contains(KEY_EXPERIMENT_SEED)) {
            WolfAiRegistry.setExperimentSeed(nbt.getLong(KEY_EXPERIMENT_SEED));
        }
        if (nbt.contains(KEY_ANONYMITY_SALT)) {
            // salt はワールドと一生を共にする。変わると同じ参加者が別 ID になる。
            WolfAiRegistry.setAnonymitySalt(nbt.getLong(KEY_ANONYMITY_SALT));
        }
        ExperimentalCondition.byId(nbt.getString(KEY_DEFAULT_CONDITION))
                .ifPresent(WolfAiRegistry::setDefaultCondition);

        NbtList wolves = nbt.getList(KEY_WOLVES, NbtElement.COMPOUND_TYPE);
        int restored = 0;
        for (int i = 0; i < wolves.size(); i++) {
            WolfAiController controller = WolfAiNbt.read(wolves.getCompound(i));
            if (controller == null) {
                continue;
            }
            WolfAiRegistry.restore(UUID.fromString(controller.agent().wolfId()), controller);
            restored++;
        }
        WolfAiRegistry.onStateLoaded(state, restored);
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        nbt.putInt(KEY_VERSION, WolfAiNbt.FORMAT_VERSION);
        nbt.putLong(KEY_EXPERIMENT_SEED, WolfAiRegistry.experimentSeed());
        nbt.putString(KEY_DEFAULT_CONDITION, WolfAiRegistry.defaultCondition().id());
        nbt.putLong(KEY_ANONYMITY_SALT, WolfAiRegistry.anonymitySalt());

        NbtList wolves = new NbtList();
        WolfAiRegistry.all().values().forEach(controller -> wolves.add(WolfAiNbt.write(controller)));
        nbt.put(KEY_WOLVES, wolves);
        return nbt;
    }

    /**
     * 既定値で空の状態を作る。新規ワールドで呼ばれる。
     */
    public WolfAiPersistentState() {
        WolfAiRegistry.setExperimentSeed(AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
        WolfAiRegistry.setDefaultCondition(AiWolfParameters.DEFAULT_EXPERIMENTAL_CONDITION);
    }
}
