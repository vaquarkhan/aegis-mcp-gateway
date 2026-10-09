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

import org.apache.spark.sql.Row;
import org.apache.spark.sql.connect.SparkSession;

/**
 * The tutorial's tiny dataset, created the way a data engineer would, directly with a Spark Connect
 * client and outside Aegis. Run it with Java's single-file launcher (seed-data.ps1 / seed-data.sh):
 *
 * <pre>java -cp &lt;Spark Connect client jars&gt; SeedLake.java [sc://127.0.0.1:15002]</pre>
 *
 * Re-running it resets both tables to the same rows.
 */
public class SeedLake {

    public static void main(String[] args) {
        String remote = args.length > 0 ? args[0] : "sc://127.0.0.1:15002";
        try (SparkSession spark = SparkSession.builder().remote(remote).create()) {
            String[] statements = {
                "CREATE NAMESPACE IF NOT EXISTS lake.sales",
                "CREATE NAMESPACE IF NOT EXISTS lake.finance",
                "CREATE OR REPLACE TABLE lake.sales.orders (id BIGINT, item STRING, price DOUBLE) USING iceberg",
                "INSERT INTO lake.sales.orders VALUES (1, 'widget', 9.5), (2, 'gadget', 12.0), (3, 'gizmo', 3.25)",
                "CREATE OR REPLACE TABLE lake.finance.ledger (id BIGINT, amount DOUBLE) USING iceberg",
                "INSERT INTO lake.finance.ledger VALUES (1, 100.0)"
            };
            for (String sql : statements) {
                System.out.println("spark> " + sql);
                spark.sql(sql).collectAsList();
            }
            for (Object row : spark.sql("SELECT * FROM lake.sales.orders ORDER BY id").collectAsList()) {
                System.out.println("  lake.sales.orders " + ((Row) row).mkString(", "));
            }
            System.out.println("Seeded lake.sales.orders (3 rows) and lake.finance.ledger (1 row).");
        }
    }
}
