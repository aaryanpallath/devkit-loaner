package dev.aaryan.loaner;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;

/** One real mongod process per test JVM (downloaded and cached by flapdoodle), so tests need no Docker. */
public final class EmbeddedMongo {

    private static TransitionWalker.ReachedState<RunningMongodProcess> running;

    private EmbeddedMongo() {}

    public static synchronized String uri(String database) {
        if (running == null) {
            running = Mongod.instance().start(Version.Main.V8_0);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> running.close()));
        }
        var addr = running.current().getServerAddress();
        return "mongodb://" + addr.getHost() + ":" + addr.getPort() + "/" + database;
    }
}
