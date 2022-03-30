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

package com.alibaba.polardbx.transaction.async;

import com.alibaba.polardbx.common.eventlogger.EventLogger;
import com.alibaba.polardbx.common.eventlogger.EventType;
import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.common.jdbc.IConnection;
import com.alibaba.polardbx.common.utils.Pair;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.executor.common.StorageInfoManager;
import com.alibaba.polardbx.executor.sync.ISyncAction;
import com.alibaba.polardbx.executor.sync.SyncManagerHelper;
import com.alibaba.polardbx.executor.utils.ExecUtils;
import com.alibaba.polardbx.executor.utils.transaction.DeadlockParser;
import com.alibaba.polardbx.executor.utils.transaction.GroupConnPair;
import com.alibaba.polardbx.executor.utils.transaction.LocalTransaction;
import com.alibaba.polardbx.executor.utils.transaction.TrxLock;
import com.alibaba.polardbx.executor.utils.transaction.TrxLookupSet;
import com.alibaba.polardbx.group.jdbc.TGroupDataSource;
import com.alibaba.polardbx.transaction.TransactionExecutor;
import com.alibaba.polardbx.transaction.TransactionLogger;
import com.alibaba.polardbx.transaction.sync.FetchTransForDeadlockDetectionSyncAction;
import com.alibaba.polardbx.transaction.utils.DiGraph;
import org.apache.commons.collections.CollectionUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Math.min;

/**
 * Deadlock detection task.
 *
 * @author TennyZhuang
 */
public class DeadlockDetectionTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DeadlockDetectionTask.class);

    private final String SQL_QUERY_DEADLOCKS =
        "SELECT "
            + "trx_a.TRX_MYSQL_THREAD_ID AS waiting_conn_id, "
            + "trx_a.TRX_STATE AS waiting_state, "
            + "trx_a.TRX_QUERY AS waiting_physical_sql, "
            + "trx_a.TRX_OPERATION_STATE AS waiting_operation_state, "
            + "trx_a.TRX_TABLES_IN_USE AS waiting_tables_in_use, "
            + "trx_a.TRX_TABLES_LOCKED AS waiting_tables_locked, "
            + "trx_a.TRX_LOCK_STRUCTS AS waiting_lock_structs, "
            + "trx_a.TRX_LOCK_MEMORY_BYTES AS waiting_heap_size, "
            + "trx_a.TRX_ROWS_LOCKED AS waiting_row_locks, "
            + "locks_a.LOCK_ID AS waiting_lock_id, "
            + "locks_a.LOCK_MODE AS waiting_lock_mode, "
            + "locks_a.LOCK_TYPE AS waiting_lock_type, "
            + "locks_a.LOCK_TABLE AS waiting_lock_physical_table, "
            + "locks_a.LOCK_INDEX AS waiting_lock_index, "
            + "locks_a.LOCK_SPACE AS waiting_lock_space, "
            + "locks_a.LOCK_PAGE AS waiting_lock_page, "
            + "locks_a.LOCK_REC AS waiting_lock_rec, "
            + "locks_a.LOCK_DATA AS waiting_lock_data, "
            + "trx_b.TRX_MYSQL_THREAD_ID AS blocking_conn_id, "
            + "trx_b.TRX_STATE AS blocking_state, "
            + "trx_b.TRX_QUERY AS blocking_physical_sql, "
            + "trx_b.TRX_OPERATION_STATE AS blocking_operation_state, "
            + "trx_b.TRX_TABLES_IN_USE AS blocking_tables_in_use, "
            + "trx_b.TRX_TABLES_LOCKED AS blocking_tables_locked, "
            + "trx_b.TRX_LOCK_STRUCTS AS blocking_lock_structs, "
            + "trx_b.TRX_LOCK_MEMORY_BYTES AS blocking_heap_size, "
            + "trx_b.TRX_ROWS_LOCKED AS blocking_row_locks, "
            + "locks_b.LOCK_ID AS blocking_lock_id, "
            + "locks_b.LOCK_MODE AS blocking_lock_mode, "
            + "locks_b.LOCK_TYPE AS blocking_lock_type, "
            + "locks_b.LOCK_TABLE AS blocking_lock_physical_table, "
            + "locks_b.LOCK_INDEX AS blocking_lock_index, "
            + "locks_b.LOCK_SPACE AS blocking_lock_space, "
            + "locks_b.LOCK_PAGE AS blocking_lock_page, "
            + "locks_b.LOCK_REC AS blocking_lock_rec, "
            + "locks_b.LOCK_DATA AS blocking_lock_data "
            + "FROM "
            + "information_schema.INNODB_LOCK_WAITS AS lock_waits, "
            + "information_schema.INNODB_LOCKS AS locks_a, information_schema.INNODB_LOCKS AS locks_b, "
            + "information_schema.INNODB_TRX AS trx_a, information_schema.INNODB_TRX AS trx_b "
            + "WHERE "
            + "trx_a.trx_id = lock_waits.requesting_trx_id AND trx_b.trx_id = lock_waits.blocking_trx_id "
            + "AND locks_a.lock_id = lock_waits.requested_lock_id AND locks_b.lock_id = lock_waits.blocking_lock_id";

    private final String db;

    private final TransactionExecutor executor;

    private static Class killSyncActionClass;

    static {
        // 只有server支持
        try {
            killSyncActionClass =
                Class.forName("com.alibaba.polardbx.server.response.KillSyncAction");
        } catch (ClassNotFoundException e) {
            throw new TddlRuntimeException(ErrorCode.ERR_CONFIG, e, e.getMessage());
        }
    }

    public DeadlockDetectionTask(String db, TransactionExecutor executor) {
        this.db = db;
        this.executor = executor;
    }

    /**
     * fetch lock-wait information to update the wait-for graph and the lookup set
     *
     * @param dataSource a DN's data source
     * @param groupNames all group on that DN
     * @param lookupSet a transaction lookup set which contains all transactions
     * @param graph a wait-for graph containing "trx a is waiting for trx b"-like information
     */
    public void fetchLockWaits(TGroupDataSource dataSource,
                               Collection<String> groupNames,
                               TrxLookupSet lookupSet,
                               DiGraph<TrxLookupSet.Transaction> graph) {
        try (final IConnection conn = dataSource.getConnection(); final Statement stmt = conn.createStatement()) {
            final ResultSet rs = stmt.executeQuery(SQL_QUERY_DEADLOCKS);
            while (rs.next()) {
                // Get the waiting and blocking connection id of DN
                final long waiting = rs.getLong("waiting_conn_id");
                final long blocking = rs.getLong("blocking_conn_id");

                // Get the waiting and blocking transaction
                final Pair<Pair<TrxLookupSet.Transaction, TrxLookupSet.Transaction>, String> waitingAndBlockingTrx =
                    lookupSet.getWaitingAndBlockingTrx(groupNames, waiting, blocking);

                final TrxLookupSet.Transaction waitingTrx = waitingAndBlockingTrx.getKey().getKey();
                final TrxLookupSet.Transaction blockingTrx = waitingAndBlockingTrx.getKey().getValue();

                if (null != waitingTrx && null != blockingTrx) {
                    // Update the wait-for graph and the lookup set
                    graph.addDiEdge(waitingTrx, blockingTrx);

                    // Get which group the waiting and blocking thread id are in
                    final String groupName = waitingAndBlockingTrx.getValue();

                    // Get the waiting local transaction of this group
                    final LocalTransaction waitingLocalTrx = waitingTrx.getLocalTransaction(groupName);
                    if (!waitingLocalTrx.isUpdated()) {
                        waitingLocalTrx.setState(rs.getString("waiting_state"));
                        final String physicalSql = rs.getString("waiting_physical_sql");
                        // Record a truncated SQL
                        waitingLocalTrx.setPhysicalSql(
                            physicalSql == null ? null : physicalSql.substring(0, min(4096, physicalSql.length())));
                        waitingLocalTrx.setOperationState(rs.getString("waiting_operation_state"));
                        waitingLocalTrx.setTablesInUse(rs.getInt("waiting_tables_in_use"));
                        waitingLocalTrx.setTablesLocked(rs.getInt("waiting_tables_locked"));
                        waitingLocalTrx.setLockStructs(rs.getInt("waiting_lock_structs"));
                        waitingLocalTrx.setHeapSize(rs.getInt("waiting_heap_size"));
                        waitingLocalTrx.setRowLocks(rs.getInt("waiting_row_locks"));

                        waitingLocalTrx.setUpdated(true);
                    }

                    if (null == waitingLocalTrx.getWaitingTrxLock()) {
                        // Update waiting-lock information of this local transaction
                        waitingLocalTrx.setWaitingTrxLock(new TrxLock(
                            rs.getString("waiting_lock_id"),
                            rs.getString("waiting_lock_mode"),
                            rs.getString("waiting_lock_type"),
                            rs.getString("waiting_lock_physical_table"),
                            rs.getString("waiting_lock_index"),
                            rs.getInt("waiting_lock_space"),
                            rs.getInt("waiting_lock_page"),
                            rs.getInt("waiting_lock_rec"),
                            rs.getString("waiting_lock_data")
                        ));
                    }

                    // Get the blocking local transaction of this group
                    final LocalTransaction blockingLocalTrx = blockingTrx.getLocalTransaction(groupName);
                    if (!blockingLocalTrx.isUpdated()) {
                        blockingLocalTrx.setState(rs.getString("blocking_state"));
                        final String physicalSql = rs.getString("blocking_physical_sql");
                        // Record a truncated SQL
                        blockingLocalTrx.setPhysicalSql(
                            physicalSql == null ? null : physicalSql.substring(0, min(4096, physicalSql.length())));
                        blockingLocalTrx.setOperationState(rs.getString("blocking_operation_state"));
                        blockingLocalTrx.setTablesInUse(rs.getInt("blocking_tables_in_use"));
                        blockingLocalTrx.setTablesLocked(rs.getInt("blocking_tables_locked"));
                        blockingLocalTrx.setLockStructs(rs.getInt("blocking_lock_structs"));
                        blockingLocalTrx.setHeapSize(rs.getInt("blocking_heap_size"));
                        blockingLocalTrx.setRowLocks(rs.getInt("blocking_row_locks"));

                        blockingLocalTrx.setUpdated(true);
                    }
                    // Update holding-lock information of blocking transaction
                    blockingLocalTrx.addHoldingTrxLock(new TrxLock(
                        rs.getString("blocking_lock_id"),
                        rs.getString("blocking_lock_mode"),
                        rs.getString("blocking_lock_type"),
                        rs.getString("blocking_lock_physical_table"),
                        rs.getString("blocking_lock_index"),
                        rs.getInt("blocking_lock_space"),
                        rs.getInt("blocking_lock_page"),
                        rs.getInt("blocking_lock_rec"),
                        rs.getString("blocking_lock_data")
                    ));
                }
            }
        } catch (SQLException ex) {
            final String dnId = dataSource.getMasterSourceAddress();
            throw new RuntimeException("Failed to fetch lock waits on data source " + dnId, ex);
        }

    }

    /**
     * Fetch all transactions on the given db
     */
    public TrxLookupSet fetchTransInfo(String db) {
        final TrxLookupSet lookupSet = new TrxLookupSet();
        final List<List<Map<String, Object>>> results =
            SyncManagerHelper.sync(new FetchTransForDeadlockDetectionSyncAction(db), db);
        for (final List<Map<String, Object>> result : results) {
            if (result == null) {
                continue;
            }
            for (final Map<String, Object> row : result) {
                final Long transId = (Long) row.get("TRANS_ID");
                final String group = (String) row.get("GROUP");
                final long connId = (Long) row.get("CONN_ID");
                final long frontendConnId = (Long) row.get("FRONTEND_CONN_ID");
                final Long startTime = (Long) row.get("START_TIME");
                final String sql = (String) row.get("SQL");
                final GroupConnPair entry = new GroupConnPair(group, connId);
                lookupSet.addNewTransaction(entry, transId);
                lookupSet.updateTransaction(transId, frontendConnId, sql, startTime);
            }
        }
        return lookupSet;
    }

    private void killByFrontendConnId(long frontendConnId) {
        ISyncAction killSyncAction;
        try {
            killSyncAction =
                (ISyncAction) killSyncActionClass.getConstructor(String.class, Long.TYPE, Boolean.TYPE, ErrorCode.class)
                    .newInstance(db, frontendConnId, true, ErrorCode.ERR_TRANS_DEADLOCK);
        } catch (Exception e) {
            throw new TddlRuntimeException(ErrorCode.ERR_CONFIG, e, e.getMessage());
        }
        SyncManagerHelper.sync(killSyncAction);
    }

    @Override
    public void run() {

        boolean hasLeadership = ExecUtils.hasLeadership(db);

        if (!hasLeadership) {
            TransactionLogger.getLogger().debug("Skip deadlock detection task since I am not the leader");
            return;
        }
        logger.debug("Deadlock detection task starts.");
        try {
            // Get all global transaction information
            final TrxLookupSet lookupSet = fetchTransInfo(db);

            // Get all group data sources, and group by DN's ID (host:port)
            final Map<String, List<TGroupDataSource>> instId2GroupList = ExecUtils.getInstId2GroupList(db);

            final DiGraph<TrxLookupSet.Transaction> graph = new DiGraph<>();

            // For each DN, find the lock-wait information and add it to the graph
            for (List<TGroupDataSource> groupDataSources : instId2GroupList.values()) {
                if (CollectionUtils.isNotEmpty(groupDataSources)) {
                    // Since all data sources are in the same DN, any data source is ok
                    final TGroupDataSource groupDataSource = groupDataSources.get(0);

                    // Get all group names in this DN
                    final List<String> groupNames =
                        groupDataSources.stream().map(TGroupDataSource::getDbGroupKey).collect(Collectors.toList());

                    // Fetch lock-wait information for this DN,
                    // and update the lookup set and the graph with the information
                    fetchLockWaits(groupDataSource, groupNames, lookupSet, graph);
                }
            }

            graph.detect().ifPresent((cycle) -> {
                assert cycle.size() >= 2;
                final Pair<StringBuilder, StringBuilder> deadlockLog = DeadlockParser.parseGlobalDeadlock(cycle);
                final StringBuilder simpleDeadlockLog = deadlockLog.getKey();
                final StringBuilder fullDeadlockLog = deadlockLog.getValue();

                // TODO: kill transaction by some priority, such as create time, or prefer to kill internal transaction.
                // The index of the transaction to be killed in the cycle
                final int indexOfToKillTrx = 0;
                final TrxLookupSet.Transaction toKillTrx = cycle.get(indexOfToKillTrx);
                simpleDeadlockLog
                    .append(String.format(" Will rollback %s", Long.toHexString(toKillTrx.getTransactionId())));
                fullDeadlockLog.append(String.format("*** WE ROLL BACK TRANSACTION (%s)\n", indexOfToKillTrx + 1));

                // Store deadlock log in StorageInfoManager so that executor can access it
                StorageInfoManager.updateDeadlockInfo(fullDeadlockLog.toString());

                TransactionLogger.getLogger().warn(simpleDeadlockLog.toString());
                logger.warn(simpleDeadlockLog.toString());
                EventLogger.log(EventType.DEAD_LOCK_DETECTION, simpleDeadlockLog.toString());

                final long toKillFrontendConnId = toKillTrx.getFrontendConnId();
                killByFrontendConnId(toKillFrontendConnId);
            });
        } catch (Throwable ex) {
            logger.error("Failed to do deadlock detection", ex);
        }
    }

}
