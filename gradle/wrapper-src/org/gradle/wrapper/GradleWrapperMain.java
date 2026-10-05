package org.gradle.wrapper;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal Gradle wrapper bootstrap. Reads gradle/wrapper/gradle-wrapper.properties, downloads the
 * distribution with plain Java if it is not already present in GRADLE_USER_HOME, unpacks it and runs
 * Gradle's launcher in-process (same approach as the official wrapper). Uses the same cache layout
 * as the official wrapper, so a later official wrapper reuses the download.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path projectDir = Paths.get("").toAbsolutePath();
        Path props = findProps(projectDir);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(props)) { p.load(in); }

        String url = p.getProperty("distributionUrl");
        if (url == null) fail("distributionUrl missing in " + props);
        String sha = p.getProperty("distributionSha256Sum");

        Path userHome = userHome();
        String zipName = url.substring(url.lastIndexOf('/') + 1);
        String distName = zipName.endsWith(".zip") ? zipName.substring(0, zipName.length() - 4) : zipName;
        Path base = userHome.resolve("wrapper").resolve("dists").resolve(distName).resolve(hash(url));
        Path ok = base.resolve(zipName + ".ok");

        if (!Files.exists(ok)) {
            Files.createDirectories(base);
            Path zip = base.resolve(zipName);
            System.err.println("Downloading " + url);
            download(url, zip);
            if (sha != null && !sha.isEmpty()) {
                String actual = sha256(zip);
                if (!actual.equalsIgnoreCase(sha)) { Files.deleteIfExists(zip); fail("SHA-256 mismatch: expected " + sha + " got " + actual); }
            }
            System.err.println("Unpacking " + zipName);
            unzip(zip, base);
            Files.write(ok, new byte[0]);
        }

        Path home = findGradleHome(base);
        File libDir = home.resolve("lib").toFile();
        File[] launchers = libDir.listFiles((d, n) -> n.startsWith("gradle-launcher-") && n.endsWith(".jar"));
        if (launchers == null || launchers.length == 0) fail("gradle-launcher jar not found in " + libDir);

        System.setProperty("org.gradle.appname", System.getProperty("org.gradle.appname", "gradlew"));
        URLClassLoader cl = new URLClassLoader(new URL[]{launchers[0].toURI().toURL()},
                ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(cl);
        Class<?> main = cl.loadClass("org.gradle.launcher.GradleMain");
        main.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    private static Path findProps(Path start) {
        for (Path d = start; d != null; d = d.getParent()) {
            Path f = d.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
            if (Files.exists(f)) return f;
        }
        fail("gradle/wrapper/gradle-wrapper.properties not found");
        return null;
    }

    private static Path userHome() {
        String e = System.getenv("GRADLE_USER_HOME");
        if (e != null && !e.isEmpty()) return Paths.get(e);
        String s = System.getProperty("gradle.user.home");
        if (s != null && !s.isEmpty()) return Paths.get(s);
        return Paths.get(System.getProperty("user.home"), ".gradle");
    }

    private static String hash(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return new BigInteger(1, md.digest(s.getBytes("UTF-8"))).toString(36);
    }

    private static void download(String url, Path dest) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "gradlew-java-bootstrap");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " downloading " + url);
        long total = c.getContentLengthLong(), done = 0, lastPct = -1;
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    long pct = done * 100 / total;
                    if (pct / 10 != lastPct / 10) { System.err.print(pct + "% "); lastPct = pct; }
                }
            }
        }
        System.err.println();
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return String.format("%064x", new BigInteger(1, md.digest()));
    }

    private static void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("bad zip entry: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path findGradleHome(Path base) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(base, Files::isDirectory)) {
            for (Path d : s) if (Files.isDirectory(d.resolve("lib"))) return d;
        }
        fail("unpacked Gradle distribution not found in " + base);
        return null;
    }

    private static void fail(String m) {
        System.err.println("ERROR: " + m);
        System.exit(1);
    }
}
