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
package io.github.vaquarkhan.aegis.gateway;

import io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest;
import io.github.vaquarkhan.aegis.engine.catalog.SignedSerial;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The signed catalog manifest workflow from the gateway jar, so no other tool is required:
 * <pre>
 *   --manifest-keygen &lt;private.pem&gt; &lt;public.pem&gt;                 new Ed25519 key pair (never overwrites)
 *   --manifest-create &lt;catalog.json&gt; &lt;manifest.json&gt; &lt;gateway&gt; [--serial &lt;n&gt;]
 *        pins from a reviewed GET /aegis/v1/catalog reply; a serial makes older manifests unacceptable
 *        once this one was accepted
 *   --manifest-sign   &lt;manifest.json&gt; &lt;private.pem&gt;                writes &lt;manifest.json&gt;.sig
 *   --manifest-verify &lt;manifest.json&gt; &lt;public.pem&gt; &lt;gateway&gt;       checks as the gateway does at start
 * </pre>
 * Keep the private key off the gateway host; the gateway needs only the public key.
 */
final class CatalogManifestCommands {

    private CatalogManifestCommands() {}

    static int run(String command, String[] a, PrintStream out, PrintStream err) {
        try {
            switch (command) {
                case "--manifest-keygen" -> {
                    if (a.length != 2) {
                        return usage(err, "--manifest-keygen <private.pem> <public.pem>");
                    }
                    String[] pair = SignedCatalogManifest.generateKeyPair();
                    writePrivate(Path.of(a[0]), pair[0]);
                    Files.writeString(Path.of(a[1]), pair[1], StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
                    out.println("wrote " + a[0] + " (keep it off the gateway host) and " + a[1]);
                    return Main.OK;
                }
                case "--manifest-create" -> {
                    SerialArgs sa = serialOption(a);
                    if (sa == null) {
                        return usage(err, SERIAL_USAGE);
                    }
                    a = sa.args();
                    if (a.length != 3) {
                        return usage(err, "--manifest-create <catalog.json> <manifest.json> <gateway-name> [--serial <n>]");
                    }
                    Map<String, String> pins = pinsFromCatalogReply(Path.of(a[0]));
                    Files.write(Path.of(a[1]), SignedCatalogManifest.create(pins, a[2], sa.serial()), StandardOpenOption.CREATE_NEW);
                    out.println("wrote " + a[1] + " with " + pins.size() + " pins; review it, then --manifest-sign it");
                    return Main.OK;
                }
                case "--manifest-sign" -> {
                    if (a.length != 2) {
                        return usage(err, "--manifest-sign <manifest.json> <private.pem>");
                    }
                    Path manifest = Path.of(a[0]);
                    Path sig = manifest.resolveSibling(manifest.getFileName() + ".sig");
                    String s = SignedCatalogManifest.sign(Files.readAllBytes(manifest), SignedCatalogManifest.readPrivateKey(Path.of(a[1])));
                    Files.writeString(sig, s + "\n", StandardCharsets.US_ASCII);
                    out.println("wrote " + sig);
                    return Main.OK;
                }
                default -> {
                    if (a.length != 3) {
                        return usage(err, "--manifest-verify <manifest.json> <public.pem> <gateway-name>");
                    }
                    Path manifest = Path.of(a[0]);
                    SignedCatalogManifest.Loaded loaded = SignedCatalogManifest.loadWithSerial(manifest,
                            manifest.resolveSibling(manifest.getFileName() + ".sig"), Path.of(a[1]), a[2]);
                    out.println("OK pins=" + loaded.pins().size() + (loaded.serial() > 0 ? " serial=" + loaded.serial() : ""));
                    return Main.OK;
                }
            }
        } catch (FileAlreadyExistsException e) {
            err.println("refusing to overwrite " + e.getFile());
            return Main.USAGE;
        } catch (IOException | RuntimeException e) {
            err.println("BROKEN " + e.getMessage());
            return Main.BROKEN;
        }
    }

    /** Reads {@code {"tools":[{"name","digest","status"}]}} as returned by {@code GET /aegis/v1/catalog}. */
    static Map<String, String> pinsFromCatalogReply(Path file) throws IOException {
        Map<String, Object> reply = Json.parseObject(Files.readAllBytes(file), JsonLimits.CONFIG);
        if (!(reply.get("tools") instanceof List<?> tools) || tools.isEmpty()) {
            throw new IOException(file + " is not a GET /aegis/v1/catalog reply with tools");
        }
        Map<String, String> pins = new TreeMap<>();
        for (Object t : tools) {
            if (!(t instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || !(m.get("digest") instanceof String d)) {
                throw new IOException(file + " has a tool without a name and digest");
            }
            pins.put(name, d);
        }
        return pins;
    }

    /** Create-command arguments with an optional {@code --serial <n>} pair taken out. */
    record SerialArgs(String[] args, long serial) {}

    /**
     * Takes an optional {@code --serial <n>} pair out of create-command arguments.
     *
     * @return the remaining arguments and the serial (0 when absent), or null when the value is invalid
     */
    static SerialArgs serialOption(String[] a) {
        List<String> rest = new java.util.ArrayList<>();
        long serial = 0;
        for (int i = 0; i < a.length; i++) {
            if (!"--serial".equals(a[i])) {
                rest.add(a[i]);
                continue;
            }
            if (serial != 0 || i + 1 >= a.length) {
                return null;
            }
            try {
                serial = Long.parseLong(a[++i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (serial < 1 || serial > SignedSerial.MAX) {
                return null;
            }
        }
        return new SerialArgs(rest.toArray(String[]::new), serial);
    }

    static final String SERIAL_USAGE = "--serial must be followed by a whole number from 1 to " + SignedSerial.MAX;

    private static void writePrivate(Path file, String pem) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file); // Windows: the file inherits the directory ACL
        }
        Files.writeString(file, pem, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static int usage(PrintStream err, String text) {
        err.println("usage: " + text);
        return Main.USAGE;
    }
}
