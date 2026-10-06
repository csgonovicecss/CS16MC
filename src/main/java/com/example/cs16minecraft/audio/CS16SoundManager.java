package com.example.cs16minecraft.audio;

import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CS16SoundManager {
    public static final CS16SoundManager INSTANCE = new CS16SoundManager();

    public interface Source {
        Path find(String relPath);
        List<String> list(String relPrefix);
        void error(String message);
    }

    record Sample(AudioFormat format, byte[] data) {}

    private volatile Source source;
    private final Map<String, Sample> cache = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final Semaphore voices = new Semaphore(24);
    private final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CS16-Audio");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean audioBroken = new AtomicBoolean();

    private CS16SoundManager() {}

    public void init(Source s) {
        this.source = s;
    }

    public boolean play(String name, double volume) {
        return play(name, volume, false);
    }

    public boolean playMuffled(String name, double volume) {
        return play(name, volume * 0.8, true);
    }

    private boolean play(String name, double volume, boolean muffled) {
        return play(name, volume, muffled, 1.0);
    }

    public boolean playFirstPitched(double volume, double pitch, String... names) {
        for (String n : names) {
            if (exists(n)) {
                return play(n, volume, false, pitch);
            }
        }
        return false;
    }

    public boolean playChannel(String channel, double volume, double pitch, String... names) {
        return playFirstPitched(volume, pitch, names);
    }

    private boolean play(String name, double volume, boolean muffled, double pitch) {
        Source s = source;

        if (s == null || audioBroken.get()) {
            return false;
        }

        String rel = normalize(name);
        Path p = s.find(rel);

        if (p == null) {
            if (reported.add(rel)) {
                s.error("missing sound: " + rel);
            }
            return false;
        }

        exec.execute(() -> playNow(rel, p, volume, muffled, pitch));
        return true;
    }

    public boolean exists(String name) {
        Source s = source;
        return s != null && s.find(normalize(name)) != null;
    }

    public boolean playFirst(double volume, String... names) {
        for (String n : names) {
            if (exists(n)) {
                return play(n, volume);
            }
        }
        return false;
    }

    public boolean playAnyOfMuffled(double volume, String... names) {
        java.util.List<String> ok = new java.util.ArrayList<>();

        for (String n : names) {
            if (exists(n)) {
                ok.add(n);
            }
        }

        if (ok.isEmpty()) {
            return false;
        }

        return playMuffled(
                ok.get(ThreadLocalRandom.current().nextInt(ok.size())),
                volume
        );
    }

    public boolean playAnyOf(double volume, String... names) {
        java.util.List<String> ok = new java.util.ArrayList<>();

        for (String n : names) {
            if (exists(n)) {
                ok.add(n);
            }
        }

        if (ok.isEmpty()) {
            return false;
        }

        return play(
                ok.get(ThreadLocalRandom.current().nextInt(ok.size())),
                volume
        );
    }

    public boolean playRandomGroup(double volume, String... prefixes) {
        Source s = source;

        if (s == null) {
            return false;
        }

        for (String prefix : prefixes) {
            List<String> matches = s.list(prefix.toLowerCase(Locale.ROOT));
            matches.removeIf(m -> !m.endsWith(".wav"));

            if (!matches.isEmpty()) {
                return play(
                        matches.get(ThreadLocalRandom.current().nextInt(matches.size())),
                        volume
                );
            }
        }

        if (reported.add("group:" + String.join("|", prefixes))) {
            s.error("no sounds found for " + String.join(", ", prefixes));
        }

        return false;
    }

    private static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('\\', '/');

        if (!n.startsWith("sound/")) {
            n = "sound/" + n;
        }

        if (!n.endsWith(".wav")) {
            n += ".wav";
        }

        return n;
    }

    private void playNow(
            String key,
            Path file,
            double volume,
            boolean muffled,
            double pitch
    ) {
        Source s = source;

        try {
            String ck = muffled ? key + "#muffled" : key;

            Sample sample = cache.get(ck);

            if (sample == null) {
                sample = decode(file);

                if (muffled) {
                    sample = muffle(sample);
                }

                cache.put(ck, sample);
            }

            if (!voices.tryAcquire()) {
                return;
            }

            try {
                Clip clip = AudioSystem.getClip();

                AudioFormat sf = sample.format();
                AudioFormat of = sf;

                if (Math.abs(pitch - 1.0) > 0.01) {
                    of = new AudioFormat(
                            sf.getEncoding(),
                            (float) (sf.getSampleRate() * pitch),
                            sf.getSampleSizeInBits(),
                            sf.getChannels(),
                            sf.getFrameSize(),
                            (float) (sf.getFrameRate() * pitch),
                            sf.isBigEndian()
                    );
                }

                clip.open(
                        of,
                        sample.data(),
                        0,
                        sample.data().length
                );

                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain =
                            (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);

                    double db = 20.0 * Math.log10(
                            Math.max(
                                    0.0001,
                                    Math.min(1.0, volume)
                            )
                    );

                    gain.setValue(
                            (float) Math.max(
                                    gain.getMinimum(),
                                    Math.min(gain.getMaximum(), db)
                            )
                    );
                }

                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) {
                        clip.close();
                        voices.release();
                    }
                });

                clip.start();

            } catch (Exception e) {
                voices.release();
                throw e;
            }

        } catch (IllegalArgumentException | LineUnavailableException e) {
            if (audioBroken.compareAndSet(false, true) && s != null) {
                s.error("audio output unavailable: " + e.getMessage());
            }

        } catch (Exception e) {
            if (s != null && reported.add("fail:" + key)) {
                s.error("could not play " + key + ": " + e);
            }
        }
    }

    static Sample decode(Path file) throws Exception {
        try (AudioInputStream in =
                     AudioSystem.getAudioInputStream(file.toFile())) {

            AudioFormat f = in.getFormat();
            AudioInputStream use = in;

            if (
                    f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                            || f.getSampleSizeInBits() != 16
                            || f.isBigEndian()
            ) {
                AudioFormat target = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        f.getSampleRate(),
                        16,
                        f.getChannels(),
                        f.getChannels() * 2,
                        f.getSampleRate(),
                        false
                );

                use = AudioSystem.getAudioInputStream(target, in);
                f = target;
            }

            byte[] data = use.readAllBytes();

            int frame = Math.max(1, f.getFrameSize());

            if (data.length % frame != 0) {
                data = java.util.Arrays.copyOf(
                        data,
                        data.length - data.length % frame
                );
            }

            return new Sample(f, data);
        }
    }

    static Sample muffle(Sample s) {
        byte[] d = s.data().clone();

        int ch = Math.max(
                1,
                s.format().getChannels()
        );

        double a = 0.09;

        double[] y1 = new double[ch];
        double[] y2 = new double[ch];

        for (int i = 0; i + 1 < d.length; i += 2) {
            int c = (i / 2) % ch;

            double x = (short) (
                    (d[i] & 0xFF)
                            | (d[i + 1] << 8)
            );

            y1[c] += a * (x - y1[c]);
            y2[c] += a * (y1[c] - y2[c]);

            int o = (int) Math.max(
                    -32768,
                    Math.min(
                            32767,
                            Math.round(y2[c] * 1.6)
                    )
            );

            d[i] = (byte) o;
            d[i + 1] = (byte) (o >> 8);
        }

        return new Sample(
                s.format(),
                d
        );
    }
}