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

package com.alibaba.polardbx.optimizer.partition.pruning;

import com.alibaba.polardbx.common.exception.NotSupportException;
import com.alibaba.polardbx.common.properties.ConnectionParams;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.LogicalInsert;
import com.alibaba.polardbx.optimizer.core.rel.LogicalView;
import com.alibaba.polardbx.optimizer.partition.PartitionInfo;
import com.alibaba.polardbx.optimizer.sharding.result.RelShardInfo;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlNode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * @author chenghui.lch
 */
public class PartitionPruner {

    //=========== Build Phase ============

    /**
     * Methods for generating PartitionPruneStep
     */
    public static PartitionPruneStep generatePartitionPrueStepInfo(PartitionInfo partInfo,
                                                                   RelNode relPlan,
                                                                   RexNode partPredInfo,
                                                                   ExecutionContext ec) {
        return PartitionPruneStepBuilder.generatePartitionPruneStepInfo(partInfo, relPlan == null ? null : relPlan.getRowType(), partPredInfo, ec);
    }

    /**
     * Methods for generating TupleRouteInfo by LogicalInsert
     */
    public static PartitionTupleRouteInfo generatePartitionTupleRoutingInfo(LogicalInsert insert,
                                                                            PartitionInfo partitionInfo) {
        return PartitionTupleRouteInfoBuilder.generatePartitionTupleRoutingInfo(insert, partitionInfo);
    }

    /**
     * Methods for generating TupleRouteInfo by special row values
     */
    public static PartitionTupleRouteInfo generatePartitionTupleRoutingInfo(String schemaName,
                                                                            String logTbName,
                                                                            PartitionInfo specificPartInfo,
                                                                            RelDataType valueRowType,
                                                                            List<List<SqlNode>> astValues,
                                                                            ExecutionContext ec) {
        return PartitionTupleRouteInfoBuilder
            .generatePartitionTupleRoutingInfo(schemaName, logTbName, specificPartInfo, valueRowType, astValues, ec);
    }

    //========== Pruning Phase =============

    /**
     * Methods for pruning by stepInfo ( for query with condition )
     * 
     * @param stepInfo
     * @param context
     * @return
     */
    public static PartPrunedResult doPruningByStepInfo(PartitionPruneStep stepInfo, ExecutionContext context) {
        PartPruneStepPruningContext pruningCtx = PartPruneStepPruningContext.initPruningContext(context);
        boolean enablePartPruning = context.getParamManager().getBoolean(ConnectionParams.ENABLE_PARTITION_PRUNING);
        if (!enablePartPruning) {
            PartitionPruneStep fullScanStep =
                PartitionPruneStepBuilder.generateFullScanPruneStepInfo(stepInfo.getPartitionInfo());
            return fullScanStep.prunePartitions(context, pruningCtx);
        }
        return stepInfo.prunePartitions(context, pruningCtx);
    }

    /**
     * Methods for pruning by tupleRouteInfo ( for insert / replace )
     */
    public static PartPrunedResult doPruningByTupleRouteInfo(PartitionTupleRouteInfo tupleRouteInfo, int tupleIndex,
                                                             ExecutionContext context) {
        PartPruneStepPruningContext pruningCtx = PartPruneStepPruningContext.initPruningContext(context);
        /**
         * disable const expr eval cache
         */
        pruningCtx.setEnableConstExprEvalCache(false);
        PartPrunedResult rs = tupleRouteInfo.routeTuple(tupleIndex, context, pruningCtx);
        return rs;
    }

    /**
     * Do pruning by plan and context, only used by LogicalView
     */
    public static List<PartPrunedResult> prunePartitions(RelNode relPlan, ExecutionContext context) {

        if (relPlan instanceof LogicalView) {
            LogicalView logicalView = (LogicalView) relPlan;

            if (logicalView.isJoin()) {
                List<PartPrunedResult> allTbPrunedResults = new ArrayList<>();
                for (int i = 0; i < logicalView.getTableNames().size(); i++) {
                    RelShardInfo relShardInfo = logicalView.getRelShardInfo(i);
                    PartitionPruneStep pruneStepInfo = relShardInfo.getPartPruneStepInfo();

                    /**
                     * do pruning partitions by context
                     */
                    PartPrunedResult tbPrunedResult = PartitionPruner.doPruningByStepInfo(pruneStepInfo, context);
                    allTbPrunedResults.add(tbPrunedResult);

                }

                BitSet bitSet = null;
                boolean bitSetSame = true;
                for (PartPrunedResult partPrunedResult : allTbPrunedResults) {
                    PartitionInfo partitionInfo = partPrunedResult.getPartInfo();
                    if (partitionInfo.isBroadcastTable()) {
                        continue;
                    }

                    if (bitSet == null) {
                        bitSet = partPrunedResult.getPartBitSet();
                    } else if (!bitSet.equals(partPrunedResult.getPartBitSet())) {
                        bitSetSame = false;
                        break;
                    }
                }

                if (!bitSetSame) {
                    List<PartPrunedResult> fullScanResults = new ArrayList<>();
                    for (int i = 0; i < logicalView.getTableNames().size(); i++) {
                        PartitionPruneStep partitionPruneStep =
                            PartitionPruneStepBuilder.generateFullScanPruneStepInfo(logicalView.getSchemaName(),
                                logicalView.getTableNames().get(i), context);
                        PartPrunedResult tbPrunedResult =
                            PartitionPruner.doPruningByStepInfo(partitionPruneStep, context);
                        fullScanResults.add(tbPrunedResult);
                    }
                    return fullScanResults;
                }

                return allTbPrunedResults;
            } else {
                RelShardInfo relShardInfo = logicalView.getRelShardInfo(0);
                PartitionPruneStep pruneStepInfo = relShardInfo.getPartPruneStepInfo();
                /**
                 * do pruning partitions by context
                 */
                PartPrunedResult tbPrunedResult = PartitionPruner.doPruningByStepInfo(pruneStepInfo, context);
                List<PartPrunedResult> allTbPrunedResults = new ArrayList<>();
                allTbPrunedResults.add(tbPrunedResult);
                return allTbPrunedResults;
            }
        } else {
            throw GeneralUtil.nestedException(new NotSupportException("Not support to non logical view"));
        }
    }
}
