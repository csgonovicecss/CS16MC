package com.example.cs16minecraft.assets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses GoldSrc studio models (IDST v10): bones, textures, sub-models (triangulated meshes) and
 * sequences, and can evaluate any animation frame into bone matrices / posed vertices.
 * Output is a plain-Java representation the renderer can turn into Minecraft vertex data.
 */
public final class GoldSrcModelLoader {
    private GoldSrcModelLoader() {}

    public static final class Bone { public String name; public int parent; public final float[] value = new float[6], scale = new float[6]; }
    public static final class Texture { public String name; public int flags, width, height; public BufferedImage image; public int[] argb; }
    /** Triangle list: for every vertex, an index into the sub-model's vertex array and pixel-space UVs. */
    public static final class Mesh { public int skinRef; public int[] vert; public float[] s, t; }
    public static final class SubModel { public String name; public float[] verts; public int[] vertBone; public final List<Mesh> meshes = new ArrayList<>(); }
    public static final class Event { public int frame, event; public String options; }
    public static final class Attachment { public String name; public int bone; public float[] org = new float[3]; }
    public static final class Sequence {
        public String label; public float fps; public int numFrames, flags, activity, animIndex, seqGroup;
        public final List<Event> events = new ArrayList<>();
        public double duration() { return fps > 0 ? Math.max(0, numFrames - 1) / (double) fps : 0; }
    }

    public static final class ModelData {
        public String name;
        public float[] eyePosition;
        public final List<Bone> bones = new ArrayList<>();
        public final List<Texture> textures = new ArrayList<>();
        public final List<SubModel> subModels = new ArrayList<>(); // flattened across body parts
        public final List<List<SubModel>> bodyParts = new ArrayList<>();
        public final List<Attachment> attachments = new ArrayList<>();

        /** Model-space position of an attachment for the given bone matrices. */
        public float[] attachmentPoint(int idx, float[] bm) {
            if (idx < 0 || idx >= attachments.size()) return null;
            Attachment a = attachments.get(idx);
            int o = a.bone * 12;
            if (o + 11 >= bm.length) return null;
            float x = a.org[0], y = a.org[1], z = a.org[2];
            return new float[]{
                bm[o] * x + bm[o + 1] * y + bm[o + 2] * z + bm[o + 3],
                bm[o + 4] * x + bm[o + 5] * y + bm[o + 6] * z + bm[o + 7],
                bm[o + 8] * x + bm[o + 9] * y + bm[o + 10] * z + bm[o + 11]};
        }
        public final List<Sequence> sequences = new ArrayList<>();
        public int[] skinTable; // skin family 0: skinRef -> texture index
        ByteBuffer buf;

        public int textureForSkin(int skinRef) {
            return skinTable != null && skinRef < skinTable.length ? skinTable[skinRef] : skinRef;
        }

        /** Returns 3x4 row-major bone-to-model matrices, 12 floats per bone. */
        public float[] poseBones(Sequence seq, int frame) {
            int n = bones.size();
            float[] world = new float[n * 12];
            float[] local = new float[12];
            int f = seq.numFrames > 0 ? Math.floorMod(frame, seq.numFrames) : 0;
            for (int i = 0; i < n; i++) {
                Bone bone = bones.get(i);
                float[] v = new float[6];
                int animBase = seq.animIndex + i * 12;
                for (int c = 0; c < 6; c++) {
                    v[c] = bone.value[c];
                    int off = buf.getShort(animBase + c * 2) & 0xFFFF;
                    if (off != 0) v[c] += decode(animBase + off, f) * bone.scale[c];
                }
                angleMatrix(v[3], v[4], v[5], v[0], v[1], v[2], local);
                if (bone.parent < 0) System.arraycopy(local, 0, world, i * 12, 12);
                else concat(world, bone.parent * 12, local, world, i * 12);
            }
            return world;
        }

        private float decode(int p, int frame) {
            int k = frame;
            for (int guard = 0; guard < 4096; guard++) {
                int valid = buf.get(p) & 0xFF, total = buf.get(p + 1) & 0xFF;
                if (total == 0) return 0f;
                if (total > k) {
                    return buf.getShort(p + 2 + (valid > k ? k : valid - 1) * 2);
                }
                k -= total;
                p += (valid + 1) * 2;
            }
            return 0f;
        }

        /** Same as poseVertices but writes into a reusable buffer (must hold verts.length floats). */
        public void poseVerticesInto(SubModel m, float[] bm, float[] out) {
            int count = m.verts.length / 3;
            for (int i = 0; i < count; i++) {
                int bo = m.vertBone[i] * 12;
                float x = m.verts[i * 3], y = m.verts[i * 3 + 1], z = m.verts[i * 3 + 2];
                out[i * 3] = bm[bo] * x + bm[bo + 1] * y + bm[bo + 2] * z + bm[bo + 3];
                out[i * 3 + 1] = bm[bo + 4] * x + bm[bo + 5] * y + bm[bo + 6] * z + bm[bo + 7];
                out[i * 3 + 2] = bm[bo + 8] * x + bm[bo + 9] * y + bm[bo + 10] * z + bm[bo + 11];
            }
        }

        /** Applies bone matrices to one sub-model; returns x,y,z per vertex in GoldSrc model space. */
        public float[] poseVertices(SubModel m, float[] boneMats) {
            int count = m.verts.length / 3;
            float[] out = new float[m.verts.length];
            for (int i = 0; i < count; i++) {
                int bo = m.vertBone[i] * 12;
                float x = m.verts[i * 3], y = m.verts[i * 3 + 1], z = m.verts[i * 3 + 2];
                out[i * 3] = boneMats[bo] * x + boneMats[bo + 1] * y + boneMats[bo + 2] * z + boneMats[bo + 3];
                out[i * 3 + 1] = boneMats[bo + 4] * x + boneMats[bo + 5] * y + boneMats[bo + 6] * z + boneMats[bo + 7];
                out[i * 3 + 2] = boneMats[bo + 8] * x + boneMats[bo + 9] * y + boneMats[bo + 10] * z + boneMats[bo + 11];
            }
            return out;
        }

        public Sequence findSequence(String label) {
            for (Sequence s : sequences) if (s.label.equalsIgnoreCase(label)) return s;
            return null;
        }
    }

    // ---------------------------------------------------------------- parsing

    public static ModelData parse(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x54534449) throw new IOException("not an IDST model: " + file.getFileName());
        int version = b.getInt(4);
        if (version != 10) throw new IOException("unsupported studio version " + version);

        ModelData m = new ModelData();
        m.buf = b;
        m.name = GoldSrcTextureLoader.cstr(b, 8, 64);
        m.eyePosition = new float[]{b.getFloat(76), b.getFloat(80), b.getFloat(84)};

        int numBones = b.getInt(140), boneIdx = b.getInt(144);
        int numSeq = b.getInt(164), seqIdx = b.getInt(168);
        int numTex = b.getInt(180), texIdx = b.getInt(184);
        int numSkinRef = b.getInt(192), numSkinFam = b.getInt(196), skinIdx = b.getInt(200);
        int numBody = b.getInt(204), bodyIdx = b.getInt(208);

        for (int i = 0; i < numBones; i++) {
            int o = boneIdx + i * 112;
            Bone bone = new Bone();
            bone.name = GoldSrcTextureLoader.cstr(b, o, 32);
            bone.parent = b.getInt(o + 32);
            for (int c = 0; c < 6; c++) {
                bone.value[c] = b.getFloat(o + 64 + c * 4);
                bone.scale[c] = b.getFloat(o + 88 + c * 4);
            }
            m.bones.add(bone);
        }

        for (int i = 0; i < numTex; i++) {
            int o = texIdx + i * 80;
            Texture t = new Texture();
            t.name = GoldSrcTextureLoader.cstr(b, o, 64);
            t.flags = b.getInt(o + 64);
            t.width = b.getInt(o + 68);
            t.height = b.getInt(o + 72);
            int data = b.getInt(o + 76);
            boolean masked = (t.flags & 0x40) != 0;
            t.image = new BufferedImage(t.width, t.height, BufferedImage.TYPE_INT_ARGB);
            int pal = data + t.width * t.height;
            for (int p = 0; p < t.width * t.height; p++) {
                int idx = b.get(data + p) & 0xFF;
                int r = b.get(pal + idx * 3) & 0xFF, g = b.get(pal + idx * 3 + 1) & 0xFF, bl = b.get(pal + idx * 3 + 2) & 0xFF;
                int a = (masked && idx == 255) ? 0 : 255;
                t.image.setRGB(p % t.width, p / t.width, (a << 24) | (r << 16) | (g << 8) | bl);
            }
            t.argb = t.image.getRGB(0, 0, t.width, t.height, null, 0, t.width);
            m.textures.add(t);
        }

        if (numSkinRef > 0 && numSkinFam > 0) {
            m.skinTable = new int[numSkinRef];
            for (int i = 0; i < numSkinRef; i++) m.skinTable[i] = b.getShort(skinIdx + i * 2);
        }

        for (int i = 0; i < numSeq; i++) {
            int o = seqIdx + i * 176;
            Sequence s = new Sequence();
            s.label = GoldSrcTextureLoader.cstr(b, o, 32);
            s.fps = b.getFloat(o + 32);
            s.flags = b.getInt(o + 36);
            s.activity = b.getInt(o + 40);
            s.numFrames = b.getInt(o + 56);
            s.animIndex = b.getInt(o + 124);
            s.seqGroup = b.getInt(o + 156);
            int numEvents = b.getInt(o + 48), eventIdx = b.getInt(o + 52);
            for (int k = 0; k < numEvents; k++) {
                int eo = eventIdx + k * 76;
                Event ev = new Event();
                ev.frame = b.getInt(eo);
                ev.event = b.getInt(eo + 4);
                ev.options = GoldSrcTextureLoader.cstr(b, eo + 12, 64).trim();
                s.events.add(ev);
            }
            m.sequences.add(s);
        }

        for (int i = 0; i < numBody; i++) {
            int o = bodyIdx + i * 76;
            int numModels = b.getInt(o + 64), modelIdx = b.getInt(o + 72);
            List<SubModel> part = new ArrayList<>();
            for (int k = 0; k < numModels; k++) {
                SubModel sm = readSubModel(b, modelIdx + k * 112);
                part.add(sm);
                m.subModels.add(sm);
            }
            m.bodyParts.add(part);
        }
        int numAtt = b.getInt(212), attIdx = b.getInt(216);
        for (int i = 0; i < numAtt; i++) {
            int ao = attIdx + i * 88;
            Attachment a = new Attachment();
            a.name = GoldSrcTextureLoader.cstr(b, ao, 32);
            a.bone = b.getInt(ao + 36);
            a.org = new float[]{b.getFloat(ao + 40), b.getFloat(ao + 44), b.getFloat(ao + 48)};
            m.attachments.add(a);
        }
        return m;
    }

    private static SubModel readSubModel(ByteBuffer b, int o) {
        SubModel sm = new SubModel();
        sm.name = GoldSrcTextureLoader.cstr(b, o, 64);
        int numMesh = b.getInt(o + 72), meshIdx = b.getInt(o + 76);
        int numVerts = b.getInt(o + 80), vertInfoIdx = b.getInt(o + 84), vertIdx = b.getInt(o + 88);
        sm.verts = new float[numVerts * 3];
        sm.vertBone = new int[numVerts];
        for (int i = 0; i < numVerts; i++) {
            sm.verts[i * 3] = b.getFloat(vertIdx + i * 12);
            sm.verts[i * 3 + 1] = b.getFloat(vertIdx + i * 12 + 4);
            sm.verts[i * 3 + 2] = b.getFloat(vertIdx + i * 12 + 8);
            sm.vertBone[i] = b.get(vertInfoIdx + i) & 0xFF;
        }
        for (int i = 0; i < numMesh; i++) {
            int mo = meshIdx + i * 20;
            int triIdx = b.getInt(mo + 4);
            Mesh mesh = new Mesh();
            mesh.skinRef = b.getInt(mo + 8);
            List<int[]> verts = new ArrayList<>(); // {vertex, s, t}
            int p = triIdx;
            while (true) {
                int n = b.getShort(p);
                p += 2;
                if (n == 0) break;
                boolean fan = n < 0;
                int count = Math.abs(n);
                int[][] strip = new int[count][];
                for (int v = 0; v < count; v++) {
                    strip[v] = new int[]{b.getShort(p) & 0xFFFF, b.getShort(p + 4), b.getShort(p + 6)};
                    p += 8;
                }
                for (int v = 0; v + 2 < count; v++) {
                    if (fan) { verts.add(strip[0]); verts.add(strip[v + 1]); verts.add(strip[v + 2]); }
                    else if ((v & 1) == 0) { verts.add(strip[v]); verts.add(strip[v + 1]); verts.add(strip[v + 2]); }
                    else { verts.add(strip[v + 1]); verts.add(strip[v]); verts.add(strip[v + 2]); }
                }
            }
            mesh.vert = new int[verts.size()];
            mesh.s = new float[verts.size()];
            mesh.t = new float[verts.size()];
            for (int k = 0; k < verts.size(); k++) {
                mesh.vert[k] = verts.get(k)[0];
                mesh.s[k] = verts.get(k)[1];
                mesh.t[k] = verts.get(k)[2];
            }
            sm.meshes.add(mesh);
        }
        return sm;
    }

    // ---------------------------------------------------------------- math

    /** GoldSrc AngleMatrix + position, 3x4 row-major. */
    private static void angleMatrix(float ax, float ay, float az, float px, float py, float pz, float[] m) {
        float sy = (float) Math.sin(az), cy = (float) Math.cos(az);
        float sp = (float) Math.sin(ay), cp = (float) Math.cos(ay);
        float sr = (float) Math.sin(ax), cr = (float) Math.cos(ax);
        m[0] = cp * cy;  m[4] = cp * sy;  m[8] = -sp;
        m[1] = sr * sp * cy + cr * -sy;  m[5] = sr * sp * sy + cr * cy;  m[9] = sr * cp;
        m[2] = cr * sp * cy + -sr * -sy; m[6] = cr * sp * sy + -sr * cy; m[10] = cr * cp;
        m[3] = px; m[7] = py; m[11] = pz;
    }

    private static void concat(float[] a, int ao, float[] b, float[] out, int oo) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[oo + r * 4 + c] = a[ao + r * 4] * b[c] + a[ao + r * 4 + 1] * b[4 + c] + a[ao + r * 4 + 2] * b[8 + c];
            }
            out[oo + r * 4 + 3] = a[ao + r * 4] * b[3] + a[ao + r * 4 + 1] * b[7] + a[ao + r * 4 + 2] * b[11] + a[ao + r * 4 + 3];
        }
    }
}
