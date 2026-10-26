package dev.chaoslab.physics;

/**
 * Immutable three-dimensional vector.
 *
 * <p>Used at API boundaries and for diagnostics (momentum, angular momentum, centre of mass).
 * The hot numerical loops work on flat {@code double[]} arrays instead, to avoid allocating
 * millions of short-lived objects; {@link #fromArray(double[], int)} and
 * {@link #intoArray(double[], int)} convert between the two layouts.
 *
 * <p>Layout convention for flat arrays: body {@code i} occupies indices {@code 3i, 3i+1, 3i+2}.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public Vec3 plus(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 minus(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 times(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /** Right-handed cross product {@code this x o}. */
    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public double normSquared() {
        return x * x + y * y + z * z;
    }

    public double norm() {
        return Math.sqrt(normSquared());
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** Reads body {@code body} from a flat array laid out as (x0,y0,z0,x1,y1,z1,...). */
    public static Vec3 fromArray(double[] flat, int body) {
        int k = 3 * body;
        return new Vec3(flat[k], flat[k + 1], flat[k + 2]);
    }

    /** Writes this vector into slot {@code body} of a flat array. */
    public void intoArray(double[] flat, int body) {
        int k = 3 * body;
        flat[k] = x;
        flat[k + 1] = y;
        flat[k + 2] = z;
    }
}
