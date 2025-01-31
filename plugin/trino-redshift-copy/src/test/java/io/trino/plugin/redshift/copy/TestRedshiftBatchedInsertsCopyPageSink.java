/*
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
package io.trino.plugin.redshift.copy;

import io.trino.Session;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.trino.plugin.redshift.copy.RedshiftQueryRunner.IAM_ROLE;
import static io.trino.plugin.redshift.copy.RedshiftQueryRunner.TEST_CATALOG;
import static io.trino.plugin.redshift.copy.TestingRedshiftServer.TEST_SCHEMA;
import static io.trino.plugin.redshift.copy.TestingRedshiftServer.executeInRedshift;
import static io.trino.testing.TestingProperties.requiredNonEmptySystemProperty;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.tpch.TpchTable.NATION;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
final class TestRedshiftBatchedInsertsCopyPageSink
        extends AbstractTestQueryFramework
{
    private static final String S3_COPY_ROOT = requiredNonEmptySystemProperty("test.redshift.s3.copy.root");
    private static final String AWS_REGION = requiredNonEmptySystemProperty("test.redshift.aws.region");
    private static final String AWS_ACCESS_KEY = requiredNonEmptySystemProperty("test.redshift.aws.access-key");
    private static final String AWS_SECRET_KEY = requiredNonEmptySystemProperty("test.redshift.aws.secret-key");

    private List<String> tableNames;

    @BeforeAll
    public void setup()
    {
        tableNames = new ArrayList<>();
    }

    @AfterAll
    public void cleanup()
    {
        tableNames.forEach(tableName -> executeInRedshift(format("DROP TABLE IF EXISTS %s.%s", TEST_SCHEMA, tableName)));
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return RedshiftQueryRunner.builder()
                .setConnectorProperties(
                        Map.of(
                                "redshift.batched-inserts-copy-location", S3_COPY_ROOT,
                                "redshift.batched-inserts-copy-iam-role", IAM_ROLE,
                                "s3.region", AWS_REGION,
                                "s3.aws-access-key", AWS_ACCESS_KEY,
                                "s3.aws-secret-key", AWS_SECRET_KEY))
                .setInitialTables(List.of(NATION))
                .build();
    }

    @Test
    void testCopyEnabled()
    {
        assertQuery(
                "SHOW SESSION LIKE 'redshift.batched_inserts_copy_enabled'",
                "VALUES ('redshift.batched_inserts_copy_enabled', 'true', 'true', 'boolean', 'Use COPY statements for batched inserts')");
    }

    @Test
    void testCopyFromPageSink()
    {
        String tableName = getTableName();
        assertQueryStats(
                getSession(),
                format("CREATE TABLE %s.%s.%s AS SELECT * FROM tpch.sf1.lineitem LIMIT 10", TEST_CATALOG, TEST_SCHEMA, tableName),
                queryStats -> {
                    // From what I can tell, there is no succinct way to check if the query was executed using the copy command
                },
                results -> assertThat(results.getRowCount()).isEqualTo(1));
        assertQueryStats(
                getSession(),
                format("SELECT * FROM %s.%s.%s", TEST_CATALOG, TEST_SCHEMA, tableName),
                queryStats -> {},
                results -> assertThat(results.getRowCount()).isEqualTo(10));
    }

    @Test
    void testQueryWithNoResults()
    {
        String tableName = getTableName();
        assertQuerySucceeds(
                getSession(),
                format("CREATE TABLE %s.%s.%s AS SELECT * FROM tpch.sf1.lineitem LIMIT 0", TEST_CATALOG, TEST_SCHEMA, tableName));
        assertQueryStats(
                getSession(),
                format("SELECT * FROM %s.%s.%s", TEST_CATALOG, TEST_SCHEMA, tableName),
                queryStats -> {},
                results -> assertThat(results.getRowCount()).isEqualTo(0));
    }

    @Test
    void testCopyFromDisabled()
    {
        String tableName = getTableName();
        Session copyDisabledSession = testSessionBuilder(getSession())
                .setCatalogSessionProperty("redshift", "batched_inserts_copy_enabled", "false")
                .build();
        assertQuerySucceeds(
                copyDisabledSession,
                format("CREATE TABLE %s.%s.%s AS SELECT * FROM tpch.sf1.lineitem LIMIT 10", TEST_CATALOG, TEST_SCHEMA, tableName));
        assertQueryStats(
                copyDisabledSession,
                format("SELECT * FROM %s.%s.%s", TEST_CATALOG, TEST_SCHEMA, tableName),
                queryStats -> {},
                results -> assertThat(results.getRowCount()).isEqualTo(10));
    }

    @Test
    void testVariousColumnTypes()
    {
        String tableName = getTableName();
        assertQuerySucceeds(
                getSession(),
                format("""
        CREATE TABLE %s.%s.%s AS
                        SELECT
                            CAST('Sample Text' AS VARCHAR) AS varchar_column,
                            CAST(123 AS INT) AS int_column,
                            CAST(123.45 AS DOUBLE) AS double_column,
                            CAST(123.45 AS DECIMAL(10,2)) AS decimal_column,
                            CAST('2025-02-16' AS DATE) AS date_column,
                            CAST('2025-02-16 10:30:00' AS TIMESTAMP) AS timestamp_column,
                            CAST('true' AS BOOLEAN) AS boolean_column,
                            CAST(1234567890123456789 AS BIGINT) AS bigint_column,
                            CAST('A' AS CHAR(1)) AS char_column,
                            CAST(CAST(CAST(0xCAFEBABE AS BIGINT) AS VARCHAR) AS VARBINARY) AS varbinary_column
        """, TEST_CATALOG, TEST_SCHEMA, tableName));
        assertQueryStats(
                getSession(),
                format("SELECT * FROM %s.%s.%s", TEST_CATALOG, TEST_SCHEMA, tableName),
                queryStats -> {},
                results -> {
                    assertThat(results.getRowCount()).isEqualTo(1);
                    assertThat(results.getColumnNames()).isEqualTo(List.of(
                            "varchar_column",
                            "int_column",
                            "double_column",
                            "decimal_column",
                            "date_column",
                            "timestamp_column",
                            "boolean_column",
                            "bigint_column",
                            "char_column",
                            "varbinary_column"));
                    List<MaterializedRow> rows = results.getMaterializedRows();
                    assertThat(rows.getFirst().getField(0)).isEqualTo("Sample Text");
                    assertThat(rows.getFirst().getField(1)).isEqualTo(123);
                    assertThat(rows.getFirst().getField(2)).isEqualTo(123.45);
                    assertThat(rows.getFirst().getField(3)).isEqualTo(BigDecimal.valueOf(123.45));
                    assertThat(rows.getFirst().getField(4)).isEqualTo(LocalDate.of(2025, 2, 16));
                    assertThat(rows.getFirst().getField(5)).isEqualTo(LocalDateTime.of(2025, 2, 16, 10, 30));
                    assertThat(rows.getFirst().getField(6)).isEqualTo(true);
                    assertThat(rows.getFirst().getField(7)).isEqualTo(1234567890123456789L);
                    assertThat(rows.getFirst().getField(8)).isEqualTo("A");
                    assertThat(Long.toHexString(Long.parseLong(new String((byte[]) rows.getFirst().getField(9), StandardCharsets.UTF_8))).toUpperCase()).isEqualTo("CAFEBABE");
                });
    }

    String getTableName()
    {
        String tableName = format("t_%s", UUID.randomUUID().toString().replace("-", "_"));
        tableNames.add(tableName);
        return tableName;
    }
}
