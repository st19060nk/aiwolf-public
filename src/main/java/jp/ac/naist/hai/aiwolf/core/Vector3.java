package jp.ac.naist.hai.aiwolf.core;

/**
 * 最小限の 3 次元ベクトル。Minecraft の {@code Vec3d} に依存せず幾何計算を書くためにある。
 *
 * <p>回避行動の方向決定のように「研究上の意図が数式で決まる」処理を、
 * Minecraft を起動せずに単体テストできるようにするのが目的である。</p>
 *
 * @param x X 成分
 * @param y Y 成分
 * @param z Z 成分
 */
public record Vector3(double x, double y, double z) {

    /** 原点。方向が定まらないときのフォールバック判定に使う。 */
    public static final Vector3 ZERO = new Vector3(0.0, 0.0, 0.0);

    /**
     * 差ベクトル {@code this - other} を返す。
     *
     * @param other 引くベクトル
     * @return 差
     */
    public Vector3 minus(Vector3 other) {
        return new Vector3(x - other.x, y - other.y, z - other.z);
    }

    /**
     * 和ベクトルを返す。
     *
     * @param other 足すベクトル
     * @return 和
     */
    public Vector3 plus(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    /**
     * スカラ倍したベクトルを返す。
     *
     * @param factor 倍率
     * @return スカラ倍
     */
    public Vector3 scale(double factor) {
        return new Vector3(x * factor, y * factor, z * factor);
    }

    /**
     * 水平面（XZ）での長さを返す。オオカミの移動は基本的に水平方向なので、
     * 方向計算では Y を無視する。
     *
     * @return 水平長さ
     */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    /**
     * 水平方向の単位ベクトルを返す。Y 成分は 0 になる。
     *
     * <p>長さが 0 のときは {@link #ZERO} を返す。<strong>NaN を返さない。</strong>
     * region の中心とオオカミの位置がちょうど一致する場合に呼ばれうるため、
     * ここで 0 除算を起こすと navigation へ NaN が流れて挙動が壊れる。</p>
     *
     * @return 単位ベクトル、または {@link #ZERO}
     */
    public Vector3 horizontalNormalized() {
        double length = horizontalLength();
        if (length < 1.0e-9) {
            return ZERO;
        }
        return new Vector3(x / length, 0.0, z / length);
    }

    /**
     * Y 軸まわりに回転したベクトルを返す。候補方向を扇状に散らすために使う。
     *
     * @param radians 回転角 [rad]
     * @return 回転後のベクトル
     */
    public Vector3 rotatedAroundY(double radians) {
        double cos = StrictMath.cos(radians);
        double sin = StrictMath.sin(radians);
        return new Vector3(x * cos - z * sin, y, x * sin + z * cos);
    }

    /**
     * すべての成分が有限かを返す。
     *
     * @return 有限なら true
     */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
