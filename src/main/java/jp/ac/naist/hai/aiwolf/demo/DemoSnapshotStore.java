package jp.ac.naist.hai.aiwolf.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import jp.ac.naist.hai.aiwolf.mc.WolfAiController;
import jp.ac.naist.hai.aiwolf.persistence.WolfAiNbt;

/**
 * デモ撮影用のスナップショット（Before / After の保存と復元）。
 *
 * <p>中身は通常のワールド保存と同じ {@link WolfAiNbt} の形式をそのまま使う。
 * 内部状態・エピソード記憶・空間記憶・学習済み選好・現在行動・決定タイミング
 * （行動継続の判定に使う）・乱数の決定インデックス・観測器のタイマー
 * （撫でのクールダウン、訪問済みバイオーム、分離判定、戦闘 ID の採番）が含まれる。
 * 進行中の戦闘エピソードは保存しない（ワールド保存と同じ扱い）。</p>
 *
 * <p><strong>通常のワールド保存とは完全に別物である。</strong>スナップショットは
 * {@code aiwolf-research/demo/snapshots/} に置かれ、{@code WolfAiPersistentState} には
 * 何も書かない。各ファイルには {@code demoSnapshot=true} が入る。</p>
 *
 * <p>スナップショットは個体ごとに名前空間が分かれる。別の個体のスナップショットを
 * 復元することはできない（個体 ID と個体シードが入れ替わり、個体の同一性が壊れるため）。</p>
 */
public final class DemoSnapshotStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("AIWolfResearch");

    /** スナップショット名に使える文字。ファイル名へそのまま使うので制限する。 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final String KEY_DEMO_MARKER = "demoSnapshot";
    private static final String KEY_SAVED_AT = "demoSnapshotSavedAt";
    private static final String KEY_NAME = "demoSnapshotName";

    private final Map<String, Map<String, NbtCompound>> snapshots = new HashMap<>();
    private final Path directory;

    /**
     * ストアを作る。
     *
     * @param directory 書き出し先。{@code null} ならメモリ上だけで保持する
     */
    public DemoSnapshotStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 名前が使えるかを判定する。
     *
     * @param name スナップショット名
     * @return 使えるなら true
     */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 個体の現在の研究状態を保存する。同名があれば上書きする。個体は変更しない。
     *
     * @param controller 対象のランタイム
     * @param name       スナップショット名
     */
    public void save(WolfAiController controller, String name) {
        Objects.requireNonNull(controller, "controller");
        requireValidName(name);
        NbtCompound nbt = WolfAiNbt.write(controller);
        nbt.putBoolean(KEY_DEMO_MARKER, true);
        nbt.putLong(KEY_SAVED_AT, System.currentTimeMillis());
        nbt.putString(KEY_NAME, name);
        String wolfId = controller.agent().wolfId();
        snapshots.computeIfAbsent(wolfId, key -> new HashMap<>()).put(name, nbt.copy());
        if (directory != null) {
            try {
                Files.createDirectories(directory);
                NbtIo.writeCompressed(nbt, fileFor(wolfId, name));
            } catch (IOException exception) {
                LOGGER.error("Failed to write demo snapshot {} to disk; it is kept in memory only", name, exception);
            }
        }
    }

    /**
     * 保存したスナップショットから新しいランタイムを作る。登録は呼び出し側が行う。
     *
     * @param wolfId 対象の個体 ID
     * @param name   スナップショット名
     * @return 復元したランタイム。なければ空
     */
    public Optional<WolfAiController> restore(String wolfId, String name) {
        Objects.requireNonNull(wolfId, "wolfId");
        if (!isValidName(name)) {
            return Optional.empty();
        }
        NbtCompound nbt = Optional.ofNullable(snapshots.get(wolfId))
                .map(byName -> byName.get(name))
                .orElseGet(() -> readFromDisk(wolfId, name));
        if (nbt == null) {
            return Optional.empty();
        }
        WolfAiController controller = WolfAiNbt.read(nbt.copy());
        if (controller == null || !wolfId.equals(controller.agent().wolfId())) {
            return Optional.empty();
        }
        return Optional.of(controller);
    }

    /**
     * 個体のスナップショット名を一覧する（メモリ上とディスク上の両方）。
     *
     * @param wolfId 個体 ID
     * @return 名前 → 保存時刻（epoch ミリ秒）
     */
    public Map<String, Long> list(String wolfId) {
        Map<String, Long> names = new TreeMap<>();
        Optional.ofNullable(snapshots.get(wolfId)).ifPresent(byName ->
                byName.forEach((name, nbt) -> names.put(name, nbt.getLong(KEY_SAVED_AT))));
        if (directory != null && Files.isDirectory(directory)) {
            String prefix = filePrefix(wolfId);
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String fileName = file.getFileName().toString();
                    if (fileName.startsWith(prefix) && fileName.endsWith(".nbt")) {
                        String name = fileName.substring(prefix.length(), fileName.length() - ".nbt".length());
                        names.putIfAbsent(name, file.toFile().lastModified());
                    }
                }
            } catch (IOException exception) {
                LOGGER.warn("Could not list demo snapshots in {}", directory, exception);
            }
        }
        return names;
    }

    /** @return メモリ上に保持しているスナップショット数（テスト用） */
    public int size() {
        int total = 0;
        for (Map<String, NbtCompound> byName : snapshots.values()) {
            total += byName.size();
        }
        return total;
    }

    private NbtCompound readFromDisk(String wolfId, String name) {
        if (directory == null) {
            return null;
        }
        Path file = fileFor(wolfId, name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            NbtCompound nbt = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            if (!nbt.getBoolean(KEY_DEMO_MARKER)) {
                return null;
            }
            snapshots.computeIfAbsent(wolfId, key -> new HashMap<>()).put(name, nbt.copy());
            return nbt;
        } catch (IOException exception) {
            LOGGER.error("Failed to read demo snapshot {}", file, exception);
            return null;
        }
    }

    private Path fileFor(String wolfId, String name) {
        return directory.resolve(filePrefix(wolfId) + name + ".nbt");
    }

    private static String filePrefix(String wolfId) {
        return wolfId.toLowerCase(Locale.ROOT) + "__";
    }

    private static void requireValidName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("snapshot name must match " + NAME.pattern() + " but was " + name);
        }
    }
}
