/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.druid.bvt.sql.mysql.select;

import com.alibaba.polardbx.druid.DbType;
import com.alibaba.polardbx.druid.sql.SQLUtils;
import com.alibaba.polardbx.druid.sql.ast.SQLExpr;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.polardbx.druid.sql.repository.SchemaRepository;
import com.alibaba.polardbx.druid.sql.visitor.SQLASTVisitorAdapter;
import junit.framework.TestCase;

public class CTERewriteTest extends TestCase {
    public void test_for_rewrite() throws Exception {
        String sql = "WITH x AS (\n" +
                "        SELECT o.open_id AS open_id\n" +
                "        FROM purchases_order o\n" +
                "        WHERE DATE(o.order_start_date) >= IFNULL(NULL, current_date())\n" +
                "            AND DATE(o.order_start_date) <= IFNULL(NULL, current_date())\n" +
                "            AND o.order_status <= 10\n" +
                "        ORDER BY 1\n" +
                "    ),\n" +
                "    c AS (\n" +
                "        SELECT COUNT(*) AS orders\n" +
                "        FROM x\n" +
                "        GROUP BY x.open_id\n" +
                "        ORDER BY 1\n" +
                "    ),\n" +
                "    y AS (\n" +
                "        SELECT SUM(c.orders) AS total\n" +
                "        FROM c\n" +
                "        ORDER BY 1\n" +
                "    ),\n" +
                "    z AS (\n" +
                "        SELECT COUNT(*) AS single\n" +
                "        FROM x\n" +
                "        WHERE NOT EXISTS (\n" +
                "            SELECT 1\n" +
                "            FROM purchases_order o, c\n" +
                "            WHERE c.orders <= 1\n" +
                "                AND o.open_id = x.open_id\n" +
                "                AND DATE(o.order_start_date) < IFNULL(NULL, current_date())\n" +
                "            ORDER BY 1\n" +
                "        )\n" +
                "        ORDER BY 1\n" +
                "    )\n" +
                "SELECT ROUND((y.total - z.single) / y.total, 2) AS value\n" +
                "FROM y, z\n" +
                "ORDER BY 1";

        SQLSelectStatement stmt = (SQLSelectStatement) SQLUtils.parseSingleMysqlStatement(sql);
        SchemaRepository repository = new SchemaRepository(DbType.mysql);
        repository.resolve(stmt);

        MyVisitor v = new MyVisitor();
        stmt.accept(v);
        stmt.getSelect().setWithSubQuery(null);

        assertEquals("SELECT ROUND((y.total - z.single) / y.total, 2) AS value\n" +
                "FROM (\n" +
                "\tSELECT SUM(c.orders) AS total\n" +
                "\tFROM (\n" +
                "\t\tSELECT COUNT(*) AS orders\n" +
                "\t\tFROM (\n" +
                "\t\t\tSELECT o.open_id AS open_id\n" +
                "\t\t\tFROM purchases_order o\n" +
                "\t\t\tWHERE DATE(o.order_start_date) >= IFNULL(NULL, current_date())\n" +
                "\t\t\t\tAND DATE(o.order_start_date) <= IFNULL(NULL, current_date())\n" +
                "\t\t\t\tAND o.order_status <= 10\n" +
                "\t\t\tORDER BY 1\n" +
                "\t\t) x\n" +
                "\t\tGROUP BY x.open_id\n" +
                "\t\tORDER BY 1\n" +
                "\t) c\n" +
                "\tORDER BY 1\n" +
                ") y, (\n" +
                "\t\tSELECT COUNT(*) AS single\n" +
                "\t\tFROM (\n" +
                "\t\t\tSELECT o.open_id AS open_id\n" +
                "\t\t\tFROM purchases_order o\n" +
                "\t\t\tWHERE DATE(o.order_start_date) >= IFNULL(NULL, current_date())\n" +
                "\t\t\t\tAND DATE(o.order_start_date) <= IFNULL(NULL, current_date())\n" +
                "\t\t\t\tAND o.order_status <= 10\n" +
                "\t\t\tORDER BY 1\n" +
                "\t\t) x\n" +
                "\t\tWHERE NOT EXISTS (\n" +
                "\t\t\tSELECT 1\n" +
                "\t\t\tFROM purchases_order o, (\n" +
                "\t\t\t\t\tSELECT COUNT(*) AS orders\n" +
                "\t\t\t\t\tFROM (\n" +
                "\t\t\t\t\t\tSELECT o.open_id AS open_id\n" +
                "\t\t\t\t\t\tFROM purchases_order o\n" +
                "\t\t\t\t\t\tWHERE DATE(o.order_start_date) >= IFNULL(NULL, current_date())\n" +
                "\t\t\t\t\t\t\tAND DATE(o.order_start_date) <= IFNULL(NULL, current_date())\n" +
                "\t\t\t\t\t\t\tAND o.order_status <= 10\n" +
                "\t\t\t\t\t\tORDER BY 1\n" +
                "\t\t\t\t\t) x\n" +
                "\t\t\t\t\tGROUP BY x.open_id\n" +
                "\t\t\t\t\tORDER BY 1\n" +
                "\t\t\t\t) c\n" +
                "\t\t\tWHERE c.orders <= 1\n" +
                "\t\t\t\tAND o.open_id = x.open_id\n" +
                "\t\t\t\tAND DATE(o.order_start_date) < IFNULL(NULL, current_date())\n" +
                "\t\t\tORDER BY 1\n" +
                "\t\t)\n" +
                "\t\tORDER BY 1\n" +
                "\t) z\n" +
                "ORDER BY 1", stmt.toString());
    }

    public static class MyVisitor extends SQLASTVisitorAdapter {
        public boolean visit(SQLExprTableSource x) {
            SQLExpr expr = x.getExpr();
            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr tableExpr = (SQLIdentifierExpr) expr;
                SQLTableSource resolvedTableSource = tableExpr.getResolvedTableSource();
                if (resolvedTableSource instanceof SQLWithSubqueryClause.Entry) {
                    SQLWithSubqueryClause.Entry cteEntry = (SQLWithSubqueryClause.Entry) resolvedTableSource;
                    SQLSubqueryTableSource subqueryTableSource
                            = new SQLSubqueryTableSource(cteEntry.getSubQuery().clone(), cteEntry.getAlias());

                    SQLUtils.replaceInParent(x, subqueryTableSource);
                    return false;
                }
            }
            return true;
        }
    }
}
