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

package com.alibaba.polardbx.optimizer.sharding;

import com.alibaba.polardbx.common.exception.NotSupportException;
import com.alibaba.polardbx.common.jdbc.ParameterContext;
import com.alibaba.polardbx.common.model.Group;
import com.alibaba.polardbx.common.model.Matrix;
import com.alibaba.polardbx.common.model.sqljep.Comparative;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.LogicalView;
import com.alibaba.polardbx.optimizer.partition.pruning.PartPrunedResult;
import com.alibaba.polardbx.optimizer.partition.pruning.PartitionPruneStep;
import com.alibaba.polardbx.optimizer.partition.pruning.PartitionPruner;
import com.alibaba.polardbx.optimizer.partition.pruning.PartitionPrunerUtils;
import com.alibaba.polardbx.optimizer.rule.TddlRuleManager;
import com.alibaba.polardbx.optimizer.sharding.result.PlanShardInfo;
import com.alibaba.polardbx.optimizer.sharding.result.RelShardInfo;
import com.alibaba.polardbx.rule.TableRule;
import com.alibaba.polardbx.rule.model.Field;
import com.alibaba.polardbx.rule.model.TargetDB;
import com.alibaba.polardbx.rule.utils.CalcParamsAttribute;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.DDL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <pre>
 * 1. ??????Rule?????????????????????????????????????????????executeOn()
 * 2. ?????????????????????????????????????????????Merge?????????
 * </pre>
 *
 * @author Dreamond
 * @author <a href="jianghang.loujh@taobao.com">jianghang</a>
 * @since 5.0.0
 */
public class DataNodeChooser {

    public static List<List<TargetDB>> shard(RelNode dne,
                                             Map<String, Map<String, Comparative>> tableComparativeMap,
                                             boolean forceAllowFullTableScan,
                                             ExecutionContext executionContext) {

        Map<Integer, ParameterContext> param =
            executionContext.getParams() == null ? null : executionContext.getParams().getCurrentParameter();
        List<List<TargetDB>> result = new LinkedList<>();
        Map<String, Object> calcParams = new HashMap<>();
        calcParams.put(CalcParamsAttribute.CONN_TIME_ZONE, executionContext.getTimeZone());

        if (dne instanceof LogicalView) {
            final LogicalView logicalView = (LogicalView) dne;
            TddlRuleManager ruleManager =
                executionContext.getSchemaManager(logicalView.getSchemaName()).getTddlRuleManager();
            // JOIN query
            if (logicalView.isJoin()) {
                for (int tableIndex = 0; tableIndex < logicalView.getTableNames().size(); tableIndex++) {
                    String logicalTable = logicalView.getTableNames().get(tableIndex);
                    Map<String, Comparative> comparative = tableComparativeMap.get(logicalTable);
                    /**
                     * calculate target db and table
                     */
                    final List<TargetDB> current = ruleManager
                        .shard(
                            logicalTable,
                            true,
                            forceAllowFullTableScan,
                            comparative,
                            param,
                            calcParams, executionContext);

                    result.add(current);

                } // end of for
            } else {
                final String logicalTable = logicalView.getLogicalTableName();
                final List<TargetDB> current = ruleManager
                    .shard(
                        logicalTable,
                        true,
                        forceAllowFullTableScan,
                        tableComparativeMap.get(logicalTable),
                        param,
                        calcParams, executionContext);
                result.add(current);
            }
        }

        return result;
    }

    /**
     * DataNodeChooser.shard used by HintPlanner
     */
    public static List<List<TargetDB>> shard(List<String> tables, Map<Integer, ParameterContext> param,
                                             Map<String, Map<String, Comparative>> tableComparativeMap,
                                             String schemaName, ExecutionContext ec) {
        List<List<TargetDB>> result = new LinkedList<>();

        TddlRuleManager ruleManager = ec.getSchemaManager(schemaName).getTddlRuleManager();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            String logicalTable = tables.get(tableIndex);
            Map<String, Comparative> comparative = tableComparativeMap.get(logicalTable);

            /**
             * calculate target db and table
             */
            Map<String, Object> calcParams = new HashMap<>();
            calcParams.put(CalcParamsAttribute.SHARD_FOR_EXTRA_DB, false);
            final List<TargetDB> current = ruleManager
                .shard(logicalTable, true, true, comparative, param, calcParams, ec);
            result.add(current);
        } // end of for

        return result;
    }

    public static List<List<TargetDB>> shardByPruneStep(List<String> tables,
                                                        Map<String, PartitionPruneStep> pruneStepMap,
                                                        String schemaName, ExecutionContext ec) {
        List<List<TargetDB>> result = new LinkedList<>();

        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            String logicalTable = tables.get(tableIndex);
            PartitionPruneStep partitionPruneStep = pruneStepMap.get(logicalTable);

            /**
             * calculate target db and table
             */
            PartPrunedResult tbPrunedResult = PartitionPruner.doPruningByStepInfo(partitionPruneStep, ec);

            final List<TargetDB> current = PartitionPrunerUtils.buildTargetDbsByPartPrunedResults(tbPrunedResult);
            result.add(current);
        } // end of for

        return result;
    }

    public static List<List<TargetDB>> shard(RelNode dne, boolean forceAllowFullTableScan,
                                             ExecutionContext executionContext) {
        Map<Integer, ParameterContext> param =
            executionContext.getParams() == null ? null : executionContext.getParams().getCurrentParameter();
        Map<String, Object> extraCmd = executionContext.getExtraCmds();
        if (dne instanceof LogicalView) {
            LogicalView logicalView = (LogicalView) dne;
            TddlRuleManager ruleManager =
                executionContext.getSchemaManager(logicalView.getSchemaName()).getTddlRuleManager();
            if (logicalView.isJoin()) {
                // JOIN ??????
                List<List<TargetDB>> result = new LinkedList<>();
                for (int tableIndex = 0; tableIndex < logicalView.getTableNames().size(); tableIndex++) {
                    String logicalTable = logicalView.getTableNames().get(tableIndex);
                    RelShardInfo tableShardInfo = logicalView.getRelShardInfo(tableIndex);
                    if (!ruleManager.getPartitionInfoManager().isNewPartDbTable(logicalTable)) {
                        final Map<String, Comparative> comparative = tableShardInfo.getAllComps();
                        final Map<String, Comparative> fullComparative = tableShardInfo.getAllFullComps();
                        final Map<String, Map<String, Comparative>> stringMapMap = Maps.newHashMap();
                        stringMapMap.put(logicalTable, fullComparative);
                        Map<String, Object> calcParams = new HashMap<>();
                        calcParams.put(CalcParamsAttribute.SHARD_FOR_EXTRA_DB, false);
                        calcParams.put(CalcParamsAttribute.COM_DB_TB, stringMapMap);
                        calcParams.put(CalcParamsAttribute.CONN_TIME_ZONE, executionContext.getTimeZone());

                        /**
                         * ????????????
                         */
                        final List<TargetDB> current = ruleManager.shard(logicalTable,
                            true,
                            forceAllowFullTableScan,
                            comparative,
                            param,
                            calcParams,
                            executionContext);
                        result.addAll(ImmutableList.of(current));
                    } else {
                        /**
                         * Should not be here because a join cannot be push to lv if one lv of its input is a partitioned table
                         */
                        throw GeneralUtil.nestedException(new NotSupportException("shard partitioned table"));
                    }
                }
                return result;
            } else {
                Map<String, Map<String, Comparative>> fullCompInfo = new HashMap<>();
                PlanShardInfo planShardInfo =
                    logicalView.getPartitionConditionCache(
                        () -> ConditionExtractor.partitioningConditionFrom(logicalView).extract()
                    ).allShardInfo(executionContext);
                fullCompInfo = planShardInfo.getAllTableFullComparative(logicalView.getSchemaName());
                Map<String, Object> calcParams = new HashMap<>();
                calcParams.put(CalcParamsAttribute.COM_DB_TB, fullCompInfo);
                calcParams.put(CalcParamsAttribute.CONN_TIME_ZONE, executionContext.getTimeZone());

                Map<String, Comparative> comparativeOfLv = logicalView.getRelShardInfo().getAllComps();
                if (extraCmd != null) {
                    Boolean shardForExtraDb = (Boolean) extraCmd.get("shardForExtraDb");
                    if (shardForExtraDb != null) {
                        calcParams.put(CalcParamsAttribute.SHARD_FOR_EXTRA_DB, shardForExtraDb);
                    }
                }
                List<TargetDB> shard = ruleManager.shard(logicalView.getLogicalTableName(),
                    true,
                    forceAllowFullTableScan,
                    comparativeOfLv,
                    param,
                    calcParams, executionContext);

                return Collections.singletonList(shard);
            }
        }
        return new ArrayList<>();
    }

    public static List<List<TargetDB>> shardChangeTable(String schemaName, String tableName,
                                                        ExecutionContext executionContext) {
        Map<Integer, ParameterContext> param =
            executionContext.getParams() == null ? null : executionContext.getParams().getCurrentParameter();

        Map<String, Object> calcParams = new HashMap<>(1);
        calcParams.put(CalcParamsAttribute.CONN_TIME_ZONE, executionContext.getTimeZone());

        List<TargetDB> shards = OptimizerContext.getContext(schemaName).getRuleManager()
            .shard(tableName, true, true, null, param, calcParams, executionContext);

        return Collections.singletonList(shards);
    }

    public static List<List<TargetDB>> shardGsi(String schemaName, String tableName,
                                                ExecutionContext executionContext) {
        Map<Integer, ParameterContext> param =
            executionContext.getParams() == null ? null : executionContext.getParams().getCurrentParameter();

        Map<String, Object> calcParams = new HashMap<>(1);
        calcParams.put(CalcParamsAttribute.SHARD_FOR_EXTRA_DB, false);

        List<TargetDB> shards = OptimizerContext.getContext(schemaName).getRuleManager()
            .shard(tableName, true, true, null, param, calcParams, executionContext);

        return Collections.singletonList(shards);
    }

    public static List<List<TargetDB>> shardCreateTable(String schemaName, String tableName, DDL relNode,
                                                        TableRule tableRule) {
        String defaultDb = OptimizerContext.getContext(schemaName).getRuleManager().getDefaultDbIndex(null);
        if (tableRule == null) {
            /**
             * ????????????, ??????rule??????????????????defaultGroup
             */
            HashMap<String, Field> map = Maps.newHashMap();
            map.put(tableName, null);
            TargetDB targetDB = new TargetDB();
            //ddlRelNode.setPartition(false);
            if (null != relNode) {
                relNode.setPartition(false);
            }
            targetDB.setDbIndex(defaultDb);
            targetDB.setTableNames(map);
            return Arrays.asList(Arrays.asList(targetDB));
        }
        Map<String, Set<String>> commonTopology = getCommonTopology(tableName, schemaName);
        if (tableRule.getActualTopology().size() == 1 && tableRule.getActualTopology().containsKey(defaultDb)
            && tableRule.getActualTopology().get(defaultDb).size() == 1 && !tableRule.isBroadcast()
            && (tableRule.getPartitionType() == null || !tableRule.getPartitionType()
            .isNoloopTime())) { // noloop??????????????????0??????????????????,?????????

            /**
             * ???????????????????????????????????????rule?????????????????????????????????????????? ???????????????????????????shard???????????????????????????
             */
            if (null != relNode) {
                // ????????????
                relNode.setPartition(false);
            }

            String phyTableName = tableName;
            if (tableRule != null && tableRule.isRandomTableNamePatternEnabled()) {
                phyTableName = tableRule.getTbNamePattern();
            }

            final List<TargetDB> objects = Lists.newArrayList();
            TargetDB targetDBTemp = new TargetDB();
            targetDBTemp.setDbIndex(defaultDb);
            targetDBTemp.addOneTable(phyTableName);
            objects.add(targetDBTemp);

            return Arrays.asList(objects);
        }

        if (tableRule.isBroadcast()) {
        } else {

            /**
             * ?????????????????????/????????????????????????
             */
            if (commonTopology != null) {
                boolean singleDbSingleTable = (commonTopology.size() == 1 && commonTopology.values().size() == 1);

                if (!singleDbSingleTable && null != relNode && relNode.getQuerySelNode() != null) {
                    /* ?????????????????????????????????????????????select */
                    throw new IllegalArgumentException("not support select for partition target!");
                }
            }
        }

        /**
         * ?????????????????????CreateTable Node ???????????????????????????????????????????????????????????????group???????????????tableRule???
         * ?????????????????????????????????tableRule????????????????????????group????????????????????????
         * ?????????group?????????????????????????????????????????????0,1,3,4????????????????????????????????????
         * 0,1,2,3?????????????????????????????????????????????????????????????????????????????????????????????????????????
         * ?????????groupList??????????????????????????????????????????shard???????????????group???????????????????????? ????????????????????????????????????
         */
        Map<String, Set<String>> actualTopology = tableRule.getActualTopology();
        List<TargetDB> createTableNodeList = new ArrayList<TargetDB>();

        /**
         * ?????????????????????????????????????????????????????????????????????
         */
        if (null != relNode) {
            relNode.setPartition(false);
        }

        /* ???????????????????????????????????????defaultdb */
        // boolean containsDefaultDb = false;
        for (String dbName : actualTopology.keySet()) {
            TargetDB targetDB = new TargetDB();
            targetDB.setDbIndex(dbName);
            for (String actualTable : actualTopology.get(dbName)) {
                targetDB.addOneTable(actualTable);
            }
            createTableNodeList.add(targetDB);
        }

        if (createTableNodeList.size() == 0) {
            throw new IllegalArgumentException("Can't find proper actual target!");
        }

        if (createTableNodeList.size() == 1) {
            /* ????????????target?????????????????????CreateTableNode */
            if (null != relNode) {
                // ????????????
                relNode.setPartition(false);
            }
        }
        return Arrays.asList(createTableNodeList);
    }

    private static Map<String, Set<String>> getCommonTopology(String tableName, String schemaName) {
        Map<String, Set<String>> topology = new HashMap<String, Set<String>>();
        Matrix matrix = OptimizerContext.getContext(schemaName).getMatrix();
        for (Group group : matrix.getGroups()) {
            Set<String> bcastTables = new HashSet<String>(1);
            bcastTables.add(tableName);
            topology.put(group.getName(), bcastTables);
        }
        return topology;
    }

}
