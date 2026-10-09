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

import java.nio.charset.StandardCharsets;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;

/**
 * Creates ledgers with BookKeeper's own client, so the bookie's HTTP admin API has real ledgers to list and
 * delete. Run with the JDK source launcher and the BookKeeper jars, outside Aegis:
 *
 * <pre>java -cp &lt;bookkeeper jars&gt; CreateLedgers.java zk+hierarchical://127.0.0.1:2181/ledgers 3</pre>
 *
 * Prints one {@code LEDGER <id>} line per ledger. bin/bookkeeper shell simpletest is not used because it
 * deletes the ledger it wrote.
 */
public class CreateLedgers {
    public static void main(String[] args) throws Exception {
        ClientConfiguration conf = new ClientConfiguration().setMetadataServiceUri(args[0]);
        int count = Integer.parseInt(args[1]);
        try (BookKeeper bk = BookKeeper.forConfig(conf).build()) {
            for (int i = 0; i < count; i++) {
                LedgerHandle lh = bk.createLedger(1, 1, 1, BookKeeper.DigestType.CRC32C, new byte[0]);
                for (int e = 0; e < 5; e++) {
                    lh.addEntry(("event-" + e).getBytes(StandardCharsets.UTF_8));
                }
                System.out.println("LEDGER " + lh.getId());
                lh.close();
            }
        }
    }
}
