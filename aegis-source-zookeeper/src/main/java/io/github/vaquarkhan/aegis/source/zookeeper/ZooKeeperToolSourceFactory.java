/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.source.zookeeper;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

/**
 * {@code type: zookeeper} source. Settings:
 * <pre>
 * settings:
 *   connectString: zk1:2181,zk2:2181,zk3:2181   # required
 *   sessionTimeoutMs: 15000
 *   connectTimeoutMs: 10000                     # how long a call waits for SyncConnected
 *   auth:
 *     digestEnv: AEGIS_ZOOKEEPER_DIGEST          # required, user:password held outside config
 *   maxDataBytes: 65536                         # cap on znode data returned to the model
 *   maxChildren: 500                            # cap on children listed in one call
 * </pre>
 *
 * <p>The connection is opened on the first tool call, not at startup, so a gateway can start while
 * the ensemble is down; that call then fails as a backend availability error, which the circuit
 * breaker counts. The connection is reused, and a session that dies is replaced on the next call.
 * The connect string is configuration, never an argument, so an agent cannot point this at another
 * ensemble. Digest authentication is mandatory because this source can create znodes; newly created
 * nodes use ZooKeeper's {@code CREATOR_ALL_ACL} instead of a world-writable ACL.
 */
public final class ZooKeeperToolSourceFactory implements ToolSourceFactory {

    @Override
    public String type() {
        return "zookeeper";
    }

    @Override
    public ToolSource create(String id, ConfigNode settings) {
        return create(id, settings, System::getenv);
    }

    @Override
    public ToolSource create(String id, ConfigNode settings, BuildContext context) {
        return create(id, settings, context.env());
    }

    private ToolSource create(String id, ConfigNode settings, Function<String, String> environment) {
        String connectString = settings.requiredString("connectString");
        int sessionTimeout = (int) settings.integer("sessionTimeoutMs", 15_000);
        int connectTimeout = (int) settings.integer("connectTimeoutMs", 10_000);
        int maxDataBytes = (int) settings.integer("maxDataBytes", 65_536);
        int maxChildren = (int) settings.integer("maxChildren", 500);
        ConfigNode auth = settings.child("auth");
        String digest = resolve(auth, "digestEnv", environment);
        if (maxDataBytes < 1 || maxDataBytes > 1_048_576) {
            throw settings.error("maxDataBytes", "must be between 1 and 1048576 (ZooKeeper's own node limit)");
        }
        if (maxChildren < 1 || maxChildren > 100_000) {
            throw settings.error("maxChildren", "must be between 1 and 100000");
        }
        return new ZooKeeperToolSource(id, maxDataBytes, maxChildren,
                () -> connect(connectString, sessionTimeout, connectTimeout, "digest", digest));
    }

    private static String resolve(ConfigNode settings, String key, Function<String, String> environment) {
        String name = settings.requiredString(key);
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(settings.path() + "." + key + " names " + name + " which is not set");
        }
        return value;
    }

    /**
     * Opens an authenticated client and waits for SyncConnected. The ZooKeeper constructor returns
     * before the session is established, so without this wait the first tool call fails with
     * CONNECTIONLOSS and looks like an outage rather than a connection race. A client that cannot be
     * created or does not connect in time is reported as {@link ZooKeeperToolSource.Unavailable}.
     */
    static ZooKeeper connect(String connectString, int sessionTimeoutMs, int connectTimeoutMs,
                             String authScheme, String authValue) {
        if (authScheme == null || authValue == null) {
            throw new IllegalArgumentException("a ZooKeeper connection needs an auth scheme and value");
        }
        CountDownLatch connected = new CountDownLatch(1);
        Watcher watcher = (WatchedEvent event) -> {
            if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                connected.countDown();
            }
        };
        ZooKeeper zk;
        try {
            zk = new ZooKeeper(connectString, sessionTimeoutMs, watcher);
        } catch (IOException e) {
            throw new ZooKeeperToolSource.Unavailable("the ZooKeeper client could not be created", e);
        }
        try {
            if (!connected.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                closeQuietly(zk);
                throw new ZooKeeperToolSource.Unavailable("the ensemble did not connect within " + connectTimeoutMs + "ms", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(zk);
            throw new ZooKeeperToolSource.Unavailable("interrupted while connecting", e);
        }
        zk.addAuthInfo(authScheme, authValue.getBytes(StandardCharsets.UTF_8));
        return zk;
    }

    private static void closeQuietly(ZooKeeper zk) {
        try {
            zk.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
