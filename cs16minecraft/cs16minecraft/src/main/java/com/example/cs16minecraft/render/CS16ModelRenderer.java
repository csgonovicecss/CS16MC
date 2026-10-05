package com.example.cs16minecraft.render;

import com.example.cs16minecraft.assets.GoldSrcModelLoader.Mesh;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.ModelData;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.SubModel;
import com.example.cs16minecraft.assets.GoldSrcModelLoader.Texture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Software rasteriser for GoldSrc models: perspective-correct textured triangles, bilinear filtering, z-buffer,
 * masked / additive textures, near-plane clipping, dirty-rectangle tracking.
 *
 * Performance design (output is identical to a simple per-pixel version):
 *  - triangles are clipped + projected into a batch, then rasterised in parallel horizontal bands (each thread owns
 *    its rows, so there are no races and draw order inside a band is preserved);
 *  - each row only visits the pixels that are actually inside the triangle (analytic span), no per-pixel inside test;
 *  - fixed-point bilinear filtering with packed channels, bit-masks instead of floorMod for power-of-two textures.
 * View space is GoldSrc's: +X forward, +Y left, +Z up.
 */
public final class CS16ModelRenderer {
    private static final float NEAR = 1.0f;
    private static final int FLOATS_PER_TRI = 15;

    private int stride, maxH;
    private int[] color;
    private float[] depth;
    private int w, h;
    private double focal, cx, cy;
    private int dMinX = 1, dMinY = 1, dMaxX = 0, dMaxY = 0; // empty when min > max
    private boolean mirror;
    private boolean parallel = true;

    // triangle batch
    private float[] tdata = new float[FLOATS_PER_TRI * 4096];
    private int[] ttex = new int[4096];
    private float[] tlight = new float[4096];
    private byte[] tflags = new byte[4096];
    private int tcount;
    private final List<Texture> texList = new ArrayList<>();
    private final IdentityHashMap<Texture, Integer> texIndex = new IdentityHashMap<>();

    private float[] scratch = new float[0];
    private final float[][] tri = new float[3][5];
    private final float[][] clipA = new float[8][5];

    public CS16ModelRenderer(int maxW, int maxH) {
        allocate(maxW, maxH);
    }

    private void allocate(int sw, int sh) {
        stride = sw;
        maxH = sh;
        color = new int[sw * sh];
        depth = new float[sw * sh]; // stores 1/depth, 0 = empty
        dMinX = 1; dMinY = 1; dMaxX = 0; dMaxY = 0;
    }

    public int[] color() { return color; }
    public int stride() { return stride; }
    public void setMirror(boolean m) { this.mirror = m; }
    public void setParallel(boolean p) { this.parallel = p; }

    /** @return {minX, minY, maxX, maxY} of everything drawn this frame, or null if nothing was drawn. */
    public int[] dirty() {
        return dMinX > dMaxX ? null : new int[]{dMinX, dMinY, dMaxX, dMaxY};
    }

    /** Clears what was drawn last frame and sets the camera. verticalFov in radians. */
    public void begin(int width, int height, double verticalFov) {
        if (width > stride || height > maxH) {
            allocate(Math.max(width, stride), Math.max(height, maxH));
        } else if (dMinX <= dMaxX) {
            for (int y = dMinY; y <= dMaxY; y++) {
                int a = y * stride + dMinX, b = y * stride + dMaxX + 1;
                Arrays.fill(color, a, b, 0);
                Arrays.fill(depth, a, b, 0f);
            }
        }
        dMinX = 1; dMinY = 1; dMaxX = 0; dMaxY = 0;
        tcount = 0;
        texList.clear();
        texIndex.clear();
        mirror = false;
        this.w = width;
        this.h = height;
        focal = (h / 2.0) / Math.tan(verticalFov / 2.0);
        cx = w / 2.0;
        cy = h / 2.0;
    }

    private void mark(int x0, int y0, int x1, int y1) {
        if (dMinX > dMaxX) { dMinX = x0; dMinY = y0; dMaxX = x1; dMaxY = y1; return; }
        dMinX = Math.min(dMinX, x0); dMinY = Math.min(dMinY, y0);
        dMaxX = Math.max(dMaxX, x1); dMaxY = Math.max(dMaxY, y1);
    }

    /** Queues one posed sub-model; (ox,oy,oz) is an origin offset in GoldSrc units, light is 0..1. Call flush() when done. */
    public void drawSubModel(ModelData m, SubModel sm, float[] boneMats, float ox, float oy, float oz, float light) {
        if (scratch.length < sm.verts.length) scratch = new float[sm.verts.length];
        m.poseVerticesInto(sm, boneMats, scratch);
        float[] pv = scratch;
        float ys = mirror ? -1f : 1f;
        for (Mesh mesh : sm.meshes) {
            int ti = m.textureForSkin(mesh.skinRef);
            if (ti < 0 || ti >= m.textures.size()) continue;
            Texture tex = m.textures.get(ti);
            if (tex.argb == null) continue;
            int texId = texIndex.computeIfAbsent(tex, t -> { texList.add(t); return texList.size() - 1; });
            boolean additive = (tex.flags & 0x20) != 0;
            boolean smooth = !additive && (tex.flags & 0x40) == 0; // masked textures keep hard edges
            byte flags = (byte) ((additive ? 1 : 0) | (smooth ? 2 : 0));
            for (int i = 0; i + 2 < mesh.vert.length; i += 3) {
                for (int k = 0; k < 3; k++) {
                    int v = mesh.vert[i + k] * 3;
                    tri[k][0] = pv[v] + ox;
                    tri[k][1] = (pv[v + 1] + oy) * ys;
                    tri[k][2] = pv[v + 2] + oz;
                    tri[k][3] = mesh.s[i + k];
                    tri[k][4] = mesh.t[i + k];
                }
                clipAndPush(texId, flags, light);
            }
        }
    }

    private void clipAndPush(int texId, byte flags, float light) {
        int n = 0;
        for (int i = 0; i < 3; i++) {
            float[] a = tri[i], b = tri[(i + 1) % 3];
            boolean ain = a[0] >= NEAR, bin = b[0] >= NEAR;
            if (ain) System.arraycopy(a, 0, clipA[n++], 0, 5);
            if (ain != bin) {
                float t = (NEAR - a[0]) / (b[0] - a[0]);
                float[] o = clipA[n++];
                for (int k = 0; k < 5; k++) o[k] = a[k] + (b[k] - a[k]) * t;
            }
        }
        if (n < 3) return;
        for (int i = 1; i + 1 < n; i++) push(clipA[0], clipA[i], clipA[i + 1], texId, flags, light);
    }

    private void push(float[] a, float[] b, float[] c, int texId, byte flags, float light) {
        double ax = cx - (a[1] / a[0]) * focal, ay = cy - (a[2] / a[0]) * focal;
        double bx = cx - (b[1] / b[0]) * focal, by = cy - (b[2] / b[0]) * focal;
        double ccx = cx - (c[1] / c[0]) * focal, ccy = cy - (c[2] / c[0]) * focal;
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, ccx))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, ccx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, ccy))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, ccy))));
        if (minX > maxX || minY > maxY) return;
        double area = (bx - ax) * (ccy - ay) - (by - ay) * (ccx - ax);
        if (Math.abs(area) < 1e-6) return;
        mark(minX, minY, maxX, maxY);

        if (tcount == ttex.length) {
            int n = tcount * 2;
            tdata = Arrays.copyOf(tdata, n * FLOATS_PER_TRI);
            ttex = Arrays.copyOf(ttex, n);
            tlight = Arrays.copyOf(tlight, n);
            tflags = Arrays.copyOf(tflags, n);
        }
        int o = tcount * FLOATS_PER_TRI;
        float iza = 1f / a[0], izb = 1f / b[0], izc = 1f / c[0];
        tdata[o] = (float) ax;   tdata[o + 1] = (float) ay;
        tdata[o + 2] = (float) bx;   tdata[o + 3] = (float) by;
        tdata[o + 4] = (float) ccx;  tdata[o + 5] = (float) ccy;
        tdata[o + 6] = iza;  tdata[o + 7] = izb;  tdata[o + 8] = izc;
        tdata[o + 9] = a[3] * iza;  tdata[o + 10] = b[3] * izb;  tdata[o + 11] = c[3] * izc;
        tdata[o + 12] = a[4] * iza; tdata[o + 13] = b[4] * izb; tdata[o + 14] = c[4] * izc;
        ttex[tcount] = texId;
        tlight[tcount] = light;
        tflags[tcount] = flags;
        tcount++;
    }

    /** Rasterises everything queued since begin(), using all cores. Must be called before reading color(). */
    public void flush() {
        if (tcount == 0 || dMinX > dMaxX) return;
        int rows = dMaxY - dMinY + 1;
        int bands = parallel ? Math.max(1, Math.min(Math.min(Runtime.getRuntime().availableProcessors(), 8), rows / 24)) : 1;
        if (bands == 1 || tcount < 48) {
            rasterBand(dMinY, dMaxY, null, tcount);
        } else {
            // bin triangles into the bands they touch (once), so each thread only walks its own list, in draw order
            final int b = bands, y0 = dMinY;
            final int[] edge = new int[b + 1];
            for (int i = 0; i <= b; i++) edge[i] = y0 + (int) ((long) rows * i / b);
            final int[] counts = new int[b];
            int[][] lists = new int[b][];
            int[] fill = new int[b];
            for (int t = 0; t < tcount; t++) {
                int o = t * FLOATS_PER_TRI;
                float ay = tdata[o + 1], by = tdata[o + 3], cy = tdata[o + 5];
                int lo = (int) Math.floor(Math.min(ay, Math.min(by, cy))), hi = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
                for (int i = 0; i < b; i++) if (hi >= edge[i] && lo <= edge[i + 1] - 1) counts[i]++;
            }
            for (int i = 0; i < b; i++) lists[i] = new int[counts[i]];
            for (int t = 0; t < tcount; t++) {
                int o = t * FLOATS_PER_TRI;
                float ay = tdata[o + 1], by = tdata[o + 3], cy = tdata[o + 5];
                int lo = (int) Math.floor(Math.min(ay, Math.min(by, cy))), hi = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
                for (int i = 0; i < b; i++) if (hi >= edge[i] && lo <= edge[i + 1] - 1) lists[i][fill[i]++] = t;
            }
            IntStream.range(0, b).parallel().forEach(i -> rasterBand(edge[i], edge[i + 1] - 1, lists[i], counts[i]));
        }
        tcount = 0;
    }

    private void rasterBand(int bandY0, int bandY1, int[] list, int n) {
        for (int ti = 0; ti < n; ti++) {
            int t = list == null ? ti : list[ti];
            int o = t * FLOATS_PER_TRI;
            double ax = tdata[o], ay = tdata[o + 1], bx = tdata[o + 2], by = tdata[o + 3], cx2 = tdata[o + 4], cy2 = tdata[o + 5];
            int minY = Math.max(bandY0, Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy2)))));
            int maxY = Math.min(bandY1, Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy2)))));
            if (minY > maxY) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx2))));
            int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx2))));
            if (minX > maxX) continue;

            double area = (bx - ax) * (cy2 - ay) - (by - ay) * (cx2 - ax);
            double inv = 1.0 / area;
            double dw0dx = -(cy2 - by) * inv, dw0dy = (cx2 - bx) * inv;
            double dw1dx = -(ay - cy2) * inv, dw1dy = (ax - cx2) * inv;
            double dw2dx = -(dw0dx + dw1dx);
            double px0 = minX + 0.5;
            double w0row = ((cx2 - bx) * (minY + 0.5 - by) - (cy2 - by) * (px0 - bx)) * inv;
            double w1row = ((ax - cx2) * (minY + 0.5 - cy2) - (ay - cy2) * (px0 - cx2)) * inv;

            // 1/z, u/z and v/z are linear in screen space: F = Fc + (Fa-Fc)*w0 + (Fb-Fc)*w1
            double iza = tdata[o + 6], izb = tdata[o + 7], izc = tdata[o + 8];
            double ua = tdata[o + 9], ub = tdata[o + 10], uc = tdata[o + 11];
            double va = tdata[o + 12], vb = tdata[o + 13], vc = tdata[o + 14];
            double izA = iza - izc, izB = izb - izc, uA = ua - uc, uB = ub - uc, vA = va - vc, vB = vb - vc;
            double izdx = izA * dw0dx + izB * dw1dx, izdy = izA * dw0dy + izB * dw1dy;
            double udx = uA * dw0dx + uB * dw1dx, udy = uA * dw0dy + uB * dw1dy;
            double vdx = vA * dw0dx + vB * dw1dx, vdy = vA * dw0dy + vB * dw1dy;
            double izRow = izc + izA * w0row + izB * w1row;
            double uRow = uc + uA * w0row + uB * w1row;
            double vRow = vc + vA * w0row + vB * w1row;

            Texture tex = texList.get(ttex[t]);
            int[] px = tex.argb;
            int tw = tex.width, th = tex.height;
            boolean pow2 = (tw & (tw - 1)) == 0 && (th & (th - 1)) == 0;
            int mx = tw - 1, my = th - 1;
            boolean additive = (tflags[t] & 1) != 0, smooth = (tflags[t] & 2) != 0;
            int lightI = (int) (tlight[t] * 256f);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - minY;
                double w0 = w0row + dw0dy * dy, w1 = w1row + dw1dy * dy, w2 = 1.0 - w0 - w1;
                // analytic span: all three barycentrics must be >= 0
                double lo = 0, hi = maxX - minX;
                if (dw0dx > 1e-12) lo = Math.max(lo, -w0 / dw0dx); else if (dw0dx < -1e-12) hi = Math.min(hi, -w0 / dw0dx); else if (w0 < 0) continue;
                if (dw1dx > 1e-12) lo = Math.max(lo, -w1 / dw1dx); else if (dw1dx < -1e-12) hi = Math.min(hi, -w1 / dw1dx); else if (w1 < 0) continue;
                if (dw2dx > 1e-12) lo = Math.max(lo, -w2 / dw2dx); else if (dw2dx < -1e-12) hi = Math.min(hi, -w2 / dw2dx); else if (w2 < 0) continue;
                int k0 = (int) Math.ceil(lo - 1e-9), k1 = (int) Math.floor(hi + 1e-9);
                if (k0 > k1) continue;
                int idx = y * stride + minX + k0;
                double iz = izRow + izdy * dy + izdx * k0;
                double uz = uRow + udy * dy + udx * k0;
                double vz = vRow + vdy * dy + vdx * k0;
                for (int k = k0; k <= k1; k++, idx++, iz += izdx, uz += udx, vz += vdx) {
                    if (iz <= depth[idx]) continue;          // nearer pixels have larger 1/z: no division for hidden pixels
                    double rz = 1.0 / iz;
                    double u = uz * rz, v = vz * rz;
                    int src;
                    if (smooth) {
                        src = bilinear(px, tw, th, mx, my, pow2, u, v);
                    } else {
                        int ix = fastFloor(u), iy = fastFloor(v);
                        if (pow2) { ix &= mx; iy &= my; } else { ix = Math.floorMod(ix, tw); iy = Math.floorMod(iy, th); }
                        src = px[iy * tw + ix];
                        if ((src >>> 24) == 0) continue;
                    }
                    int r = (((src >> 16) & 0xFF) * lightI) >> 8, g = (((src >> 8) & 0xFF) * lightI) >> 8, bl = ((src & 0xFF) * lightI) >> 8;
                    if (additive) {
                        int dst = color[idx];
                        int nr = Math.min(255, ((dst >> 16) & 0xFF) + r);
                        int ng = Math.min(255, ((dst >> 8) & 0xFF) + g);
                        int nb = Math.min(255, (dst & 0xFF) + bl);
                        int na = Math.max(dst >>> 24, Math.max(r, Math.max(g, bl)));
                        color[idx] = (na << 24) | (nr << 16) | (ng << 8) | nb;
                    } else {
                        color[idx] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
                        depth[idx] = (float) iz;
                    }
                }
            }
        }
    }

    private static int fastFloor(double x) {
        int i = (int) x;
        return x < i ? i - 1 : i;
    }

    /** Fixed-point (8-bit weights) bilinear sample with packed red/blue and green lerps. */
    private static int bilinear(int[] px, int tw, int th, int mx, int my, boolean pow2, double u, double v) {
        u -= 0.5;
        v -= 0.5;
        int x0 = fastFloor(u), y0 = fastFloor(v);
        int fx = (int) ((u - x0) * 256.0), fy = (int) ((v - y0) * 256.0);
        int xa, xb, ya, yb;
        if (pow2) { xa = x0 & mx; xb = (x0 + 1) & mx; ya = y0 & my; yb = (y0 + 1) & my; }
        else { xa = Math.floorMod(x0, tw); xb = Math.floorMod(x0 + 1, tw); ya = Math.floorMod(y0, th); yb = Math.floorMod(y0 + 1, th); }
        int top = lerp(px[ya * tw + xa], px[ya * tw + xb], fx);
        int bot = lerp(px[yb * tw + xa], px[yb * tw + xb], fx);
        return lerp(top, bot, fy) | 0xFF000000;
    }

    private static int lerp(int c0, int c1, int t) {
        int s = 256 - t;
        int rb = (((c0 & 0x00FF00FF) * s + (c1 & 0x00FF00FF) * t) >> 8) & 0x00FF00FF;
        int g = (((c0 & 0x0000FF00) * s + (c1 & 0x0000FF00) * t) >> 8) & 0x0000FF00;
        return rb | g;
    }

    /** Additive camera-facing sprite (muzzle flash). Position in view space, size in GoldSrc units. Call after flush(). */
    public void drawSprite(int[] argb, int sw, int sh, float vx, float vy, float vz, float size, float intensity) {
        if (vx < NEAR) return;
        double sx = cx - (vy / vx) * focal, sy = cy - (vz / vx) * focal;
        double half = size * 0.5 * focal / vx;
        int x0 = (int) Math.floor(sx - half), x1 = (int) Math.ceil(sx + half);
        int y0 = (int) Math.floor(sy - half), y1 = (int) Math.ceil(sy + half);
        int cx0 = Math.max(0, x0), cx1 = Math.min(w - 1, x1), cy0 = Math.max(0, y0), cy1 = Math.min(h - 1, y1);
        if (cx0 > cx1 || cy0 > cy1) return;
        mark(cx0, cy0, cx1, cy1);
        for (int y = cy0; y <= cy1; y++) {
            int ty = (int) Math.min(sh - 1, Math.max(0, (y - y0) / (2 * half) * sh));
            for (int x = cx0; x <= cx1; x++) {
                int tx = (int) Math.min(sw - 1, Math.max(0, (x - x0) / (2 * half) * sw));
                int s = argb[ty * sw + tx];
                int r = Math.min(255, (int) (((s >> 16) & 0xFF) * intensity));
                int g = Math.min(255, (int) (((s >> 8) & 0xFF) * intensity));
                int b = Math.min(255, (int) ((s & 0xFF) * intensity));
                int a = Math.max(r, Math.max(g, b));
                if (a == 0) continue;
                int idx = y * stride + x;
                int dst = color[idx];
                int nr = Math.min(255, ((dst >> 16) & 0xFF) + r);
                int ng = Math.min(255, ((dst >> 8) & 0xFF) + g);
                int nb = Math.min(255, (dst & 0xFF) + b);
                color[idx] = (Math.max(dst >>> 24, a) << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }
    }
}
