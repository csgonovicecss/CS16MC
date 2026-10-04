package com.example.cs16minecraft.audio;

import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the user's own CS 1.6 .wav files straight from the Half-Life install (decoded once, cached in memory).
 * Uses javax.sound instead of Minecraft's sound engine so no sound assets or sounds.json need to be bundled.
 */
public final class CS16SoundManager {
    public static final CS16SoundManager INSTANCE = new CS16SoundManager();

    /** Where sounds come from; implemented by the asset manager. */
    public interface Source {
        Path find(String relPath);                // e.g. "sound/player/die1.wav"
        List<String> list(String relPrefix);      // e.g. "sound/player/die"
        void error(String message);
    }

    private record Sample(AudioFormat format, byte[] data) {}

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

    public void init(Source s) { this.source = s; }

    /** Plays "weapons/ak47-1.wav" or "sound/weapons/ak47-1.wav". Returns false if the file doesn't exist. */
    public boolean play(String name, double volume) {
        Source s = source;
        if (s == null || audioBroken.get()) return false;
        String rel = normalize(name);
        Path p = s.find(rel);
        if (p == null) {
            if (reported.add(rel)) s.error("missing sound: " + rel);
            return false;
        }
        exec.execute(() -> playNow(rel, p, volume));
        return true;
    }

    public boolean exists(String name) {
        Source s = source;
        return s != null && s.find(normalize(name)) != null;
    }

    /** Plays the first of the given names that exists (CS often has -1 / -2 variants per weapon). */
    public boolean playFirst(double volume, String... names) {
        for (String n : names) if (exists(n)) return play(n, volume);
        return false;
    }

    /** Picks a random existing name from the list. */
    public boolean playAnyOf(double volume, String... names) {
        java.util.List<String> ok = new java.util.ArrayList<>();
        for (String n : names) if (exists(n)) ok.add(n);
        if (ok.isEmpty()) return false;
        return play(ok.get(ThreadLocalRandom.current().nextInt(ok.size())), volume);
    }

    /** Tries each prefix group in order; plays a random file from the first group that has any. */
    public boolean playRandomGroup(double volume, String... prefixes) {
        Source s = source;
        if (s == null) return false;
        for (String prefix : prefixes) {
            List<String> matches = s.list(prefix.toLowerCase(Locale.ROOT));
            matches.removeIf(m -> !m.endsWith(".wav"));
            if (!matches.isEmpty()) {
                return play(matches.get(ThreadLocalRandom.current().nextInt(matches.size())), volume);
            }
        }
        if (reported.add("group:" + String.join("|", prefixes))) s.error("no sounds found for " + String.join(", ", prefixes));
        return false;
    }

    private static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!n.startsWith("sound/")) n = "sound/" + n;
        if (!n.endsWith(".wav")) n += ".wav";
        return n;
    }

    private void playNow(String key, Path file, double volume) {
        Source s = source;
        try {
            Sample sample = cache.get(key);
            if (sample == null) {
                sample = decode(file);
                cache.put(key, sample);
            }
            if (!voices.tryAcquire()) return;
            try {
                Clip clip = AudioSystem.getClip();
                clip.open(sample.format(), sample.data(), 0, sample.data().length);
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    double db = 20.0 * Math.log10(Math.max(0.0001, Math.min(1.0, volume)));
                    gain.setValue((float) Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
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
            if (audioBroken.compareAndSet(false, true) && s != null) s.error("audio output unavailable: " + e.getMessage());
        } catch (Exception e) {
            if (s != null && reported.add("fail:" + key)) s.error("could not play " + key + ": " + e);
        }
    }

    private static Sample decode(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat f = in.getFormat();
            AudioInputStream use = in;
            if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED && f.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16,
                        f.getChannels(), f.getChannels() * 2, f.getSampleRate(), false);
                use = AudioSystem.getAudioInputStream(target, in);
                f = target;
            }
            byte[] data = use.readAllBytes();
            int frame = Math.max(1, f.getFrameSize());
            if (data.length % frame != 0) data = java.util.Arrays.copyOf(data, data.length - data.length % frame);
            return new Sample(f, data);
        }
    }
}
