package pro.darc.viruaisor.root;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real JVM subprocess; avoids mocking java.lang.Process lifecycle. */
public final class OverlayCleanupFixture {
    public static void main(String[] args) throws Exception {
        System.out.println("READY");
        System.out.flush();
        while (System.in.read() != -1) { /* Wait for the owner-death signal. */ }
        Thread.sleep(Long.parseLong(args[1]));
        Files.writeString(Path.of(args[0]), "CLEAN");
    }
}
