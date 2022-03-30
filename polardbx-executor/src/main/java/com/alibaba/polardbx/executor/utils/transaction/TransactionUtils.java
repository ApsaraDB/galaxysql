package com.alibaba.polardbx.executor.utils.transaction;

import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.executor.common.ExecutorContext;
import com.alibaba.polardbx.executor.sync.ISyncAction;
import com.alibaba.polardbx.executor.sync.SyncManagerHelper;
import com.alibaba.polardbx.group.jdbc.DataSourceWrapper;
import com.alibaba.polardbx.group.jdbc.TGroupDataSource;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.rule.TddlRuleManager;
import com.google.common.collect.ImmutableList;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * @author wuzhe
 */
public class TransactionUtils {

    /**
     * The codes below are moved from InformationSchemaInnodbTrxHandler to here
     */
    public static final Class fetchAllTransSyncActionClass;

    static {
        try {
            fetchAllTransSyncActionClass =
                Class.forName("com.alibaba.polardbx.transaction.sync.FetchAllTransSyncAction");
        } catch (ClassNotFoundException e) {
            throw new TddlRuntimeException(ErrorCode.ERR_CONFIG, e, e.getMessage());
        }
    }

    /**
     * @param schemaNames A list of schema names
     * @return A transaction lookup set containing all transactions on schema with {schemaNames}
     */
    public static TrxLookupSet getTrxLookupSet(Collection<String> schemaNames) {
        TrxLookupSet lookupSet = new TrxLookupSet();

        if (null == schemaNames || schemaNames.isEmpty()) {
            // Return empty lookup set
            return lookupSet;
        }

        for (final String schemaName : schemaNames) {
            ISyncAction fetchAllTransSyncAction;
            try {
                fetchAllTransSyncAction =
                    (ISyncAction) fetchAllTransSyncActionClass.getConstructor(String.class, boolean.class)
                        .newInstance(schemaName, true);
            } catch (Exception e) {
                throw new TddlRuntimeException(ErrorCode.ERR_CONFIG, e, e.getMessage());
            }

            final List<List<Map<String, Object>>> results =
                SyncManagerHelper.sync(fetchAllTransSyncAction, schemaName);

            for (final List<Map<String, Object>> result : results) {
                if (result == null) {
                    continue;
                }
                for (final Map<String, Object> row : result) {
                    final Long transId = (Long) row.get("TRANS_ID");
                    final String group = (String) row.get("GROUP");
                    final Long connId = (Long) row.get("CONN_ID");
                    final Long frontendConnId = (Long) row.get("FRONTEND_CONN_ID");
                    final Long startTime = (Long) row.get("START_TIME");
                    final String sql = (String) row.get("SQL");

                    final GroupConnPair groupConnPair = new GroupConnPair(group, connId);
                    lookupSet.addNewTransaction(groupConnPair, transId);
                    lookupSet.updateTransaction(transId, frontendConnId, sql, startTime);
                }
            }
        }

        return lookupSet;
    }

    /**
     * Find full logical table name (`logical schema`.`logical table`) given physical DB and physical tables
     *
     * @param physicalTableMap (input) Map: physical DB -> physical table list
     * @param physicalToLogical (output, updated) Map: `physical DB`.`physical table` -> `logical schema`.`logical table`
     */
    public static void convertPhysicalToLogical(Map<String, Set<String>> physicalTableMap,
                                                Map<String, String> physicalToLogical) {
        if (MapUtils.isEmpty(physicalTableMap)) {
            return;
        }
        final Set<String> allSchemaNames = OptimizerContext.getActiveSchemaNames();
        // A counter records the number of physical db we found
        int counter = 0;
        // For each schema
        for (final String schemaName : allSchemaNames) {
            final OptimizerContext optimizerContext = OptimizerContext.getContext(schemaName);

            if (null == optimizerContext) {
                continue;
            }

            final TddlRuleManager ruleManager = optimizerContext.getRuleManager();

            if (null == ruleManager) {
                continue;
            }

            // Get all groups in this schema
            final List<String> groupNames =
                ExecutorContext.getContext(schemaName).getTopologyHandler().getAllTransGroupList();

            for (final String groupName : groupNames) {
                final TGroupDataSource groupDataSource =
                    (TGroupDataSource) ExecutorContext.getContext(schemaName).getTopologyExecutor()
                        .getGroupExecutor(groupName).getDataSource();

                final Map<String, DataSourceWrapper> dataSourceWrapperMap =
                    groupDataSource.getConfigManager().getDataSourceWrapperMap();

                // For each physical db in this group
                for (final DataSourceWrapper dataSourceWrapper : dataSourceWrapperMap.values()) {
                    final String physicalDbName = dataSourceWrapper
                        .getWrappedDataSource()
                        .getDsConfHandle()
                        .getRunTimeConf()
                        .getDbName();

                    // Figure out whether this physical db is in the input map
                    final Set<String> physicalTables = physicalTableMap.get(physicalDbName);

                    if (null == physicalTables) {
                        // This physical db is not in the input map, move on to the next
                        continue;
                    }

                    for (final String physicalTable : physicalTables) {
                        final String fullyQualifiedPhysicalTableName =
                            (groupName + "." + physicalTable).toLowerCase();
                        final Set<String> logicalTableNames =
                            ruleManager.getLogicalTableNames(fullyQualifiedPhysicalTableName, schemaName);
                        if (CollectionUtils.isNotEmpty(logicalTableNames)) {
                            final String physical = String.format("`%s`.`%s`", physicalDbName, physicalTable);
                            final String logical =
                                String.format("`%s`.`%s`", schemaName, logicalTableNames.iterator().next());
                            physicalToLogical.put(physical, logical);
                        }
                    }

                    // Since we have found a physical db, increment this counter
                    counter++;
                    if (counter == physicalTableMap.size()) {
                        // Already find all physical DB, early terminate
                        return;
                    }
                }
            }
        }
    }
}
