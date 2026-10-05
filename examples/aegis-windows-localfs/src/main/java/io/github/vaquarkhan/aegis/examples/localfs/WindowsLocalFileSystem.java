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
package io.github.vaquarkhan.aegis.examples.localfs;

import java.io.IOException;
import java.util.Locale;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.permission.FsPermission;

/**
 * Hadoop's local file system, unchanged except that on Windows it does not try to apply POSIX
 * permissions or ownership. Hadoop does that through a native {@code winutils.exe} that Apache Hadoop
 * releases do not include, so without it every directory or file Iceberg creates fails with
 * "HADOOP_HOME and hadoop.home.dir are unset". Reads, writes, renames and deletes are Hadoop's own code,
 * on the real local disk.
 *
 * <p>Enable with {@code fs.file.impl=io.github.vaquarkhan.aegis.examples.localfs.WindowsLocalFileSystem}
 * (Spark: {@code spark.hadoop.fs.file.impl}; other Hadoop clients: {@code core-site.xml}). On Linux and
 * macOS it behaves exactly like {@link RawLocalFileSystem}. Tutorials only; not for production.
 */
public class WindowsLocalFileSystem extends RawLocalFileSystem {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** {@link RawLocalFileSystem} leaves this to its {@code LocalFileSystem} wrapper; readers ask for it. */
    @Override
    public String getScheme() {
        return "file";
    }

    @Override
    public void setPermission(Path p, FsPermission permission) throws IOException {
        if (!WINDOWS) {
            super.setPermission(p, permission);
        }
    }

    @Override
    public void setOwner(Path p, String username, String groupname) throws IOException {
        if (!WINDOWS) {
            super.setOwner(p, username, groupname);
        }
    }
}
