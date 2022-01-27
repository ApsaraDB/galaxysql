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

package com.alibaba.polardbx.optimizer.core.rel.ddl;

import com.alibaba.polardbx.common.charset.CharsetName;
import com.alibaba.polardbx.common.charset.CollationName;
import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.common.properties.ConnectionParams;
import com.alibaba.polardbx.common.properties.ParamManager;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.common.utils.TStringUtil;
import com.alibaba.polardbx.gms.metadb.table.IndexStatus;
import com.alibaba.polardbx.gms.topology.DbInfoManager;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.PlannerContext;
import com.alibaba.polardbx.optimizer.config.table.ColumnMeta;
import com.alibaba.polardbx.optimizer.config.table.GsiMetaManager.GsiIndexMetaBean;
import com.alibaba.polardbx.optimizer.config.table.GsiMetaManager.GsiMetaBean;
import com.alibaba.polardbx.optimizer.config.table.GsiMetaManager.GsiTableMetaBean;
import com.alibaba.polardbx.optimizer.config.table.SchemaManager;
import com.alibaba.polardbx.optimizer.config.table.TableMeta;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.AlterTablePreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.CreateTablePreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.DropLocalIndexPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.PreparedDataUtil;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.RenameTablePreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.AlterTableWithGsiPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.CreateGlobalIndexPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.CreateIndexWithGsiPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.DropIndexWithGsiPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.RenameGlobalIndexPreparedData;
import com.alibaba.polardbx.optimizer.partition.PartitionInfo;
import com.alibaba.polardbx.optimizer.utils.MetaUtils;
import com.alibaba.polardbx.optimizer.utils.RelUtils;
import com.alibaba.polardbx.rule.TableRule;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.ddl.AlterTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAddColumn;
import org.apache.calcite.sql.SqlAddIndex;
import org.apache.calcite.sql.SqlAddPrimaryKey;
import org.apache.calcite.sql.SqlAddUniqueIndex;
import org.apache.calcite.sql.SqlAlterColumnDefaultVal;
import org.apache.calcite.sql.SqlAlterSpecification;
import org.apache.calcite.sql.SqlAlterTable;
import org.apache.calcite.sql.SqlAlterTableDropIndex;
import org.apache.calcite.sql.SqlAlterTablePartitionKey;
import org.apache.calcite.sql.SqlAlterTableRenameIndex;
import org.apache.calcite.sql.SqlAlterTableTruncatePartition;
import org.apache.calcite.sql.SqlChangeColumn;
import org.apache.calcite.sql.SqlColumnDeclaration;
import org.apache.calcite.sql.SqlConvertToCharacterSet;
import org.apache.calcite.sql.SqlDropColumn;
import org.apache.calcite.sql.SqlDropPrimaryKey;
import org.apache.calcite.sql.SqlEnableKeys;
import org.apache.calcite.sql.SqlIndexColumnName;
import org.apache.calcite.sql.SqlIndexDefinition;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlModifyColumn;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.commons.collections.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LogicalAlterTable extends LogicalTableOperation {

    public final static Collection<AlterColumnSpecification> ALTER_COLUMN_RENAME = ImmutableList.of(
        AlterColumnSpecification.AlterColumnName);
    public final static Collection<AlterColumnSpecification> ALTER_COLUMN_NAME_OR_TYPE = ImmutableList.of(
        AlterColumnSpecification.AlterColumnName,
        AlterColumnSpecification.AlterColumnType);
    public final static Collection<AlterColumnSpecification> ALTER_COLUMN_DEFAULT = ImmutableList.of(
        AlterColumnSpecification.AlterColumnDefault);
    public final static Collection<AlterColumnSpecification> ALTER_COLUMN_REORDER = ImmutableList.of(
        AlterColumnSpecification.AlterColumnDefault);
    private final SqlAlterTable sqlAlterTable;
    // Use list for multiple alters.
    private final List<Set<AlterColumnSpecification>> alterColumnSpecificationSets = new ArrayList<>();

    // TODO there are duplications over these two PrepareData
    private AlterTablePreparedData alterTablePreparedData;
    private AlterTableWithGsiPreparedData alterTableWithGsiPreparedData;

    public LogicalAlterTable(AlterTable alterTable) {
        super(alterTable);
        this.sqlAlterTable = (SqlAlterTable) relDdl.sqlNode;
    }

    public static LogicalAlterTable create(AlterTable alterTable) {
        return new LogicalAlterTable(alterTable);
    }

    public static List<String> getAlteredColumns(SqlAlterTable alterTable, SqlAlterTable.ColumnOpt columnOpt) {
        Map<SqlAlterTable.ColumnOpt, List<String>> columnOpts = alterTable.getColumnOpts();
        if (columnOpts != null && columnOpts.size() > 0) {
            return columnOpts.get(columnOpt);
        }
        return null;
    }

    public SqlAlterTable getSqlAlterTable() {
        return this.sqlAlterTable;
    }

    public boolean isRepartition() {
        return sqlAlterTable != null && sqlAlterTable instanceof SqlAlterTablePartitionKey;
    }

    public boolean isCreateGsi() {
        return sqlAlterTable.createGsi();
    }

    public boolean isCreateClusteredIndex() {
        return sqlAlterTable.createClusteredIndex();
    }

    public boolean isAddIndex() {
        return sqlAlterTable.addIndex();
    }

    public boolean isDropIndex() {
        return sqlAlterTable.dropIndex();
    }

    public boolean isDropGsi() {
        return alterTableWithGsiPreparedData != null &&
            alterTableWithGsiPreparedData.getDropIndexWithGsiPreparedData() != null &&
            alterTableWithGsiPreparedData.getDropIndexWithGsiPreparedData().getGlobalIndexPreparedData() != null;
    }

    public boolean isAlterTableRenameGsi() {
        return alterTableWithGsiPreparedData != null
            && alterTableWithGsiPreparedData.getRenameGlobalIndexPreparedData() != null;
    }

    public boolean isAutoPartitionTable() {
        final String tableName = sqlAlterTable.getOriginTableName().getLastName();
        final TableMeta tableMeta =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(tableName);
        return tableMeta.isAutoPartition();
    }

    public AlterTablePreparedData getAlterTablePreparedData() {
        return alterTablePreparedData;
    }

    public AlterTableWithGsiPreparedData getAlterTableWithGsiPreparedData() {
        return alterTableWithGsiPreparedData;
    }

    private AlterTableWithGsiPreparedData getOrNewAlterTableWithGsiPreparedData() {
        if (this.alterTableWithGsiPreparedData == null) {
            this.alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();
        }
        return this.alterTableWithGsiPreparedData;
    }

    public boolean isWithGsi() {
        return alterTableWithGsiPreparedData != null && alterTableWithGsiPreparedData.hasGsi();
    }

    public boolean isAlterColumnAlterDefault() {
        return !alterColumnSpecificationSets.isEmpty() &&
            alterColumnSpecificationSets.get(0).stream().anyMatch(ALTER_COLUMN_DEFAULT::contains);
    }

    public boolean isAlterColumnRename() {
        return !alterColumnSpecificationSets.isEmpty() &&
            alterColumnSpecificationSets.get(0).stream().anyMatch(ALTER_COLUMN_RENAME::contains);
    }

    public boolean isTruncatePartition() {
        return sqlAlterTable.isTruncatePartition();
    }

    public void prepareData() {
        // NOTE that there is only one GSI operation is allowed along with a ALTER TABLE specification,
        // i.e. if an ALTER TABLE with a specification that is an explicit or implicit GSI operation,
        // then no any other operation is allowed in this ALTER TABLE statement.

        if (sqlAlterTable.createGsi()) {
            prepareCreateData();
        } else {
            if (sqlAlterTable.dropIndex()) {
                prepareDropData();
            } else if (sqlAlterTable.renameIndex()) {
                prepareRenameData();
            } else {
                prepareAlterGsiData();
            }
            prepareAlterData();
        }
    }

    private void prepareCreateData() {
        final SqlAddIndex sqlAddIndex = (SqlAddIndex) sqlAlterTable.getAlters().get(0);
        final String indexName = sqlAddIndex.getIndexName().getLastName();

        CreateIndexWithGsiPreparedData createIndexWithGsiPreparedData = new CreateIndexWithGsiPreparedData();
        createIndexWithGsiPreparedData.setGlobalIndexPreparedData(prepareCreateGsiData(indexName, sqlAddIndex));

        if (isAutoPartitionTable()) {
            createIndexWithGsiPreparedData.addLocalIndexPreparedData(
                prepareCreateLocalIndexData(tableName, indexName, isCreateClusteredIndex(), true));
        }
        addLocalIndexOnClusteredTable(createIndexWithGsiPreparedData, indexName, true);

        alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();
        alterTableWithGsiPreparedData.setCreateIndexWithGsiPreparedData(createIndexWithGsiPreparedData);
    }

    private CreateGlobalIndexPreparedData prepareCreateGsiData(String indexTableName, SqlAddIndex sqlAddIndex) {
        final OptimizerContext optimizerContext = OptimizerContext.getContext(schemaName);

        final SqlIndexDefinition indexDef = sqlAddIndex.getIndexDef();

        final TableMeta primaryTableMeta = optimizerContext.getLatestSchemaManager().getTable(tableName);
        final TableRule primaryTableRule = optimizerContext.getRuleManager().getTableRule(tableName);

        boolean isNewPartDb = DbInfoManager.getInstance().isNewPartitionDb(schemaName);
        boolean isBroadCast;
        Map<SqlNode, RexNode> partBoundExprInfo = null;
        PartitionInfo primaryPartitionInfo = null;
        if (isNewPartDb) {
            primaryPartitionInfo = optimizerContext.getPartitionInfoManager().getPartitionInfo(tableName);
            isBroadCast = primaryPartitionInfo.isBroadcastTable();
            partBoundExprInfo = ((AlterTable) (this.relDdl)).getAllRexExprInfo();
        } else {
            isBroadCast = primaryTableRule.isBroadcast();
        }

        boolean isUnique = sqlAddIndex instanceof SqlAddUniqueIndex;
        boolean isClustered = indexDef.isClustered();

        CreateGlobalIndexPreparedData preparedData =
            prepareCreateGlobalIndexData(tableName, indexDef.getPrimaryTableDefinition(), indexTableName,
                primaryTableMeta, false, false, false, indexDef.getDbPartitionBy(),
                indexDef.getDbPartitions(), indexDef.getTbPartitionBy(), indexDef.getTbPartitions(),
                indexDef.getPartitioning(), isUnique, isClustered, null, partBoundExprInfo);
        if (isNewPartDb) {
            preparedData.setPrimaryPartitionInfo(primaryPartitionInfo);
        } else {
            preparedData.setPrimaryTableRule(primaryTableRule);
        }
        preparedData.setIndexDefinition(indexDef);

        CreateTablePreparedData indexTablePreparedData = preparedData.getIndexTablePreparedData();

        if (indexDef.isSingle()) {
            indexTablePreparedData.setSharding(false);
        } else if (indexDef.isBroadcast()) {
            indexTablePreparedData.setBroadcast(true);
        } else {
            indexTablePreparedData.setSharding(true);
        }

        if (sqlAddIndex instanceof SqlAddUniqueIndex) {
            preparedData.setUnique(true);
        }

        if (indexDef.getOptions() != null) {
            final String indexComment = indexDef.getOptions()
                .stream()
                .filter(option -> null != option.getComment())
                .findFirst()
                .map(option -> RelUtils.stringValue(option.getComment()))
                .orElse("");
            preparedData.setIndexComment(indexComment);
        }

        if (indexDef.getIndexType() != null) {
            preparedData.setIndexType(null == indexDef.getIndexType() ? null : indexDef.getIndexType().name());
        }

        if (indexDef != null) {
            preparedData.setSingle(indexDef.isSingle());
            preparedData.setBroadcast(indexDef.isBroadcast());
        }

        return preparedData;
    }

    private void prepareDropData() {
        final SqlAlterTableDropIndex dropIndex = (SqlAlterTableDropIndex) sqlAlterTable.getAlters().get(0);
        final String indexTableName = dropIndex.getIndexName().getLastName();

        final GsiMetaBean gsiMetaBean =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getGsi(tableName, IndexStatus.ALL);

        if (gsiMetaBean.isGsi(indexTableName)) {
            alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();

            DropIndexWithGsiPreparedData dropIndexWithGsiPreparedData = new DropIndexWithGsiPreparedData();
            dropIndexWithGsiPreparedData
                .setGlobalIndexPreparedData(prepareDropGlobalIndexData(tableName, indexTableName, false));

            if (isAutoPartitionTable()) {
                // drop implicit local index
                dropIndexWithGsiPreparedData.addLocalIndexPreparedData(
                    prepareDropLocalIndexData(tableName, indexTableName, isCreateClusteredIndex(), true));

                // drop local index on clustered table
                dropLocalIndexOnClusteredTable(dropIndexWithGsiPreparedData, gsiMetaBean, indexTableName, true);
            }

            alterTableWithGsiPreparedData.setDropIndexWithGsiPreparedData(dropIndexWithGsiPreparedData);
        } else {
            final TableMeta tableMeta =
                OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(tableName);
            if (tableMeta.withGsi()) {
                boolean clusteredExists = false;
                for (SqlAlterSpecification alterItem : sqlAlterTable.getAlters()) {
                    if (alterItem.getKind() == SqlKind.DROP_INDEX) {
                        clusteredExists = true;
                    }
                }
                if (clusteredExists) {
                    // clustered index need add column or index.
                    if (sqlAlterTable.getAlters().size() > 1) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support multi ALTER statements on table with clustered index");
                    }

                    alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();

                    final GsiTableMetaBean gsiTableMetaBean = tableMeta.getGsiTableMetaBean();
                    for (Map.Entry<String, GsiIndexMetaBean> indexEntry : gsiTableMetaBean.indexMap.entrySet()) {
                        if (indexEntry.getValue().clusteredIndex) {
                            final String clusteredIndexTableName = indexEntry.getKey();

                            if (null != sqlAlterTable.getTableOptions()
                                && GeneralUtil.isNotEmpty(sqlAlterTable.getTableOptions().getUnion())) {
                                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                    "Do not support set table option UNION to table with clustered index");
                            }

                            if (sqlAlterTable.getAlters().get(0).getKind() == SqlKind.DROP_INDEX) {
                                // Special dealing.
                                final SqlAlterTableDropIndex dropClusteredIndex =
                                    (SqlAlterTableDropIndex) sqlAlterTable.getAlters().get(0);
                                if (!PreparedDataUtil.indexExistence(schemaName, clusteredIndexTableName,
                                    dropClusteredIndex.getIndexName().getLastName())) {
                                    continue; // Ignore this clustered index.
                                }
                            }

                            alterTableWithGsiPreparedData.addAlterGlobalIndexPreparedData(
                                prepareAlterTableData(clusteredIndexTableName));
                        }
                    }
                }
            }
        }
    }

    private void addLocalIndexOnClusteredTable(CreateIndexWithGsiPreparedData createIndexWithGsiPreparedData,
                                               String indexName,
                                               boolean onGsi) {
        final GsiMetaBean gsiMetaBean =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getGsi(tableName, IndexStatus.ALL);

        if (gsiMetaBean.withGsi(tableName)) {
            // Local indexes on clustered GSIs.
            final GsiTableMetaBean gsiTableMeta = gsiMetaBean.getTableMeta().get(tableName);
            for (Map.Entry<String, GsiIndexMetaBean> gsiEntry : gsiTableMeta.indexMap.entrySet()) {
                if (gsiEntry.getValue().clusteredIndex) {
                    final String clusteredTableName = gsiEntry.getKey();
                    createIndexWithGsiPreparedData
                        .addLocalIndexPreparedData(
                            prepareCreateLocalIndexData(clusteredTableName, indexName, true, onGsi));
                }
            }
        }
    }

    private void dropLocalIndexOnClusteredTable(DropIndexWithGsiPreparedData dropIndexWithGsiPreparedData,
                                                GsiMetaBean gsiMetaBean,
                                                String indexTableName,
                                                boolean onGsi) {
        if (gsiMetaBean.withGsi(tableName)) {
            // Drop generated local index on clustered.
            final GsiTableMetaBean gsiTableMeta = gsiMetaBean.getTableMeta().get(tableName);
            for (Map.Entry<String, GsiIndexMetaBean> gsiEntry : gsiTableMeta.indexMap.entrySet()) {
                if (gsiEntry.getValue().clusteredIndex && !gsiEntry.getKey().equalsIgnoreCase(indexTableName)) {
                    // Add all clustered index except which is dropping.
                    final String clusteredTableName = gsiEntry.getKey();
                    if (!dropIndexWithGsiPreparedData.hasLocalIndexOnClustered(clusteredTableName)) {
                        DropLocalIndexPreparedData dropLocalIndexPreparedData =
                            prepareDropLocalIndexData(clusteredTableName, null, true, onGsi);
                        dropIndexWithGsiPreparedData.addLocalIndexPreparedData(dropLocalIndexPreparedData);
                    }
                }
            }
        }
    }

    private void modifyColumnOnClusteredTable(AlterTableWithGsiPreparedData preparedData,
                                              GsiMetaBean gsiMetaBean) {
        if (!gsiMetaBean.withGsi(tableName)) {
            return;
        }
        final GsiTableMetaBean gsiTableMeta = gsiMetaBean.getTableMeta().get(tableName);
        for (Map.Entry<String, GsiIndexMetaBean> gsiEntry : gsiTableMeta.indexMap.entrySet()) {
            if (gsiEntry.getValue().clusteredIndex) {
                preparedData.addAlterClusterIndex(prepareAlterTableData(gsiEntry.getKey()));
            }
        }
    }

    private void prepareRenameData() {
        final SqlAlterTableRenameIndex renameIndex = (SqlAlterTableRenameIndex) sqlAlterTable.getAlters().get(0);
        final String indexTableName = renameIndex.getIndexName().getLastName();
        final String newIndexName = renameIndex.getNewIndexNameStr();

        final GsiMetaBean gsiMetaBean =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getGsi(tableName, IndexStatus.ALL);

        if (gsiMetaBean.isGsi(indexTableName)) {
            alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();
            alterTableWithGsiPreparedData
                .setRenameGlobalIndexPreparedData(prepareRenameGsiData(indexTableName, newIndexName));
        }
    }

    private RenameGlobalIndexPreparedData prepareRenameGsiData(String indexTableName, String newIndexTableName) {
        RenameGlobalIndexPreparedData preparedData = new RenameGlobalIndexPreparedData();

        RenameTablePreparedData renameTablePreparedData = new RenameTablePreparedData();

        renameTablePreparedData.setSchemaName(schemaName);
        renameTablePreparedData.setTableName(indexTableName);
        renameTablePreparedData.setNewTableName(newIndexTableName);

        preparedData.setSchemaName(schemaName);
        preparedData.setTableName(indexTableName);
        preparedData.setIndexTablePreparedData(renameTablePreparedData);

        return preparedData;
    }

    private void prepareAlterData() {
        // generate basic alter table actions
        alterTablePreparedData = prepareAlterTableData(tableName);

        alterClusterIndexData();

        if (isTruncatePartition()) {
            Set<String> partitionNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < sqlAlterTable.getAlters().size(); i++) {
                SqlAlterTableTruncatePartition sqlAlterTableTruncatePartition =
                    (SqlAlterTableTruncatePartition) (sqlAlterTable.getAlters().get(i));
                partitionNames.add(sqlAlterTableTruncatePartition.getPartitionName().getLastName());
            }
            alterTablePreparedData.setTruncatePartitionNames(partitionNames);
        }
    }

    /**
     * Apply primary table alters to cluster-index
     */
    private void alterClusterIndexData() {
        final GsiMetaBean gsiMetaBean =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getGsi(tableName, IndexStatus.ALL);
        if (!alterTablePreparedData.getAddedIndexes().isEmpty()) {
            // FIXME(moyi) new CreateIndexWithGsiPreparedData in constructor instead of here
            CreateIndexWithGsiPreparedData addIndex =
                this.getOrNewAlterTableWithGsiPreparedData().getOrNewCreateIndexWithGsi();
            for (String indexName : alterTablePreparedData.getAddedIndexes()) {
                addLocalIndexOnClusteredTable(addIndex, indexName, false);
            }

        }

        if (!alterTablePreparedData.getDroppedIndexes().isEmpty()) {
            // FIXME(moyi) new DropIndexWithGsiPreparedData in constructor instead of here
            DropIndexWithGsiPreparedData dropIndex =
                this.getOrNewAlterTableWithGsiPreparedData().getOrNewDropIndexWithGsi();
            for (String indexName : alterTablePreparedData.getDroppedIndexes()) {
                dropLocalIndexOnClusteredTable(dropIndex, gsiMetaBean, indexName, false);
            }
        }

        if (alterTablePreparedData.hasColumnModify()) {
            AlterTableWithGsiPreparedData preparedData = this.getOrNewAlterTableWithGsiPreparedData();
            modifyColumnOnClusteredTable(preparedData, gsiMetaBean);
        }
    }

    private AlterTablePreparedData prepareAlterTableData(String tableName) {
        AlterTablePreparedData preparedData = new AlterTablePreparedData();

        preparedData.setSchemaName(schemaName);
        preparedData.setTableName(tableName);

        List<String> droppedColumns = getAlteredColumns(sqlAlterTable, SqlAlterTable.ColumnOpt.DROP);
        List<String> addedColumns = getAlteredColumns(sqlAlterTable, SqlAlterTable.ColumnOpt.ADD);
        List<String> modifiedColumns = getAlteredColumns(sqlAlterTable, SqlAlterTable.ColumnOpt.MODIFY);
        Map<String, String> changedColumns = new HashMap<>();
        List<String> updatedColumns = new ArrayList<>(GeneralUtil.emptyIfNull(modifiedColumns));
        List<String> alterDefaultColumns = new ArrayList<>();

        // Add column for gsi with current_timestamp needs backfill
        if (CollectionUtils.isNotEmpty(addedColumns)) {
            SchemaManager sm = OptimizerContext.getContext(schemaName).getLatestSchemaManager();
            TableMeta tableMeta = sm.getTable(this.tableName);
            GsiMetaBean gsiMeta = sm.getGsi(this.tableName, IndexStatus.ALL);
            if (gsiMeta != null && gsiMeta.isGsi(tableName)) {
                List<String> columns = PreparedDataUtil.findNeedBackfillColumns(tableMeta, sqlAlterTable);
                preparedData.setBackfillColumns(columns);
            }
        }

        boolean columnReorder = false;
        boolean primaryKeyDropped = false;

        // We have to use the first column name as reference to confirm the physical index name
        // because Alter Table allows to add an index without specifying an index name.
        List<String> addedIndexes = new ArrayList<>();
        List<String> addedIndexesWithoutNames = new ArrayList<>();

        List<String> droppedIndexes = new ArrayList<>();
        Map<String, String> renamedIndexes = new HashMap<>();
        List<String> addedPrimaryKeyColumns = new ArrayList<>();

        for (SqlAlterSpecification alterItem : GeneralUtil.emptyIfNull(sqlAlterTable.getAlters())) {
            if (alterItem instanceof SqlChangeColumn) {
                SqlChangeColumn changeColumn = (SqlChangeColumn) alterItem;
                changedColumns
                    .put(changeColumn.getNewName().getLastName(), changeColumn.getOldName().getLastName());

                // Refresh is needed after reordered.
                if (changeColumn.isFirst() || changeColumn.getAfterColumn() != null) {
                    columnReorder = true;
                }
            } else if (alterItem instanceof SqlAlterColumnDefaultVal) {
                SqlAlterColumnDefaultVal alterColumnDefaultVal = (SqlAlterColumnDefaultVal) alterItem;
                String columnName = alterColumnDefaultVal.getColumnName().getLastName();
                updatedColumns.add(columnName);
                alterDefaultColumns.add(columnName);
            } else if (alterItem instanceof SqlAddIndex) {
                SqlAddIndex addIndex = (SqlAddIndex) alterItem;
                String firstColumnName = addIndex.getIndexDef().getColumns().get(0).getColumnNameStr();
                if (addIndex.getIndexName() != null) {
                    addedIndexes.add(addIndex.getIndexName().getLastName());
                } else {
                    // If user doesn't specify an index name, we use the first column name as reference.
                    addedIndexesWithoutNames.add(firstColumnName);
                }
            } else if (alterItem instanceof SqlAlterTableDropIndex) {
                SqlAlterTableDropIndex dropIndex = (SqlAlterTableDropIndex) alterItem;
                droppedIndexes.add(dropIndex.getIndexName().getLastName());
            } else if (alterItem instanceof SqlAlterTableRenameIndex) {
                SqlAlterTableRenameIndex renameIndex = (SqlAlterTableRenameIndex) alterItem;
                renamedIndexes
                    .put(renameIndex.getNewIndexName().getLastName(), renameIndex.getIndexName().getLastName());
            } else if (alterItem instanceof SqlModifyColumn) {
                final SqlModifyColumn modifyColumn = (SqlModifyColumn) alterItem;
                updatedColumns.add(modifyColumn.getColName().getLastName());

                // Refresh is needed when reorder.
                if (modifyColumn.isFirst() || modifyColumn.getAfterColumn() != null) {
                    columnReorder = true;
                }
            } else if (alterItem instanceof SqlDropPrimaryKey) {
                primaryKeyDropped = true;
            } else if (alterItem instanceof SqlAddPrimaryKey) {
                SqlAddPrimaryKey addPrimaryKey = (SqlAddPrimaryKey) alterItem;
                for (SqlIndexColumnName indexColumnName : addPrimaryKey.getColumns()) {
                    addedPrimaryKeyColumns.add(indexColumnName.getColumnNameStr());
                }
            } else if (alterItem instanceof SqlAddColumn) {
                SqlAddColumn addColumn = (SqlAddColumn) alterItem;
                if (addColumn.isFirst() || addColumn.getAfterColumn() != null) {
                    columnReorder = true;
                }
            } else if (alterItem instanceof SqlDropColumn) {
                columnReorder = true;
            } else if (alterItem instanceof SqlConvertToCharacterSet) {
                SqlConvertToCharacterSet convertCharset = (SqlConvertToCharacterSet) alterItem;
                preparedData.setCharset(convertCharset.getCharset());
                preparedData.setCollate(convertCharset.getCollate());
            } else if (alterItem instanceof SqlEnableKeys) {
                SqlEnableKeys enableKeys = (SqlEnableKeys) alterItem;
                preparedData.setEnableKeys(enableKeys.getEnableType());
            } else if (alterItem instanceof SqlAlterTableTruncatePartition) {
                //do nothing
            } else {
                throw new TddlRuntimeException(ErrorCode.ERR_DDL_JOB_UNSUPPORTED, "alter type: " + alterItem);
            }
        }

        String tableComment = null;
        if (sqlAlterTable.getTableOptions() != null &&
            sqlAlterTable.getTableOptions().getComment() != null) {
            tableComment = sqlAlterTable.getTableOptions().getComment().toValue();
        }

        preparedData.setAlterDefaultColumns(alterDefaultColumns);
        preparedData.setDroppedColumns(droppedColumns);
        preparedData.setAddedColumns(addedColumns);
        preparedData.setUpdatedColumns(updatedColumns);
        preparedData.setChangedColumns(changedColumns);
        preparedData.setColumnReorder(columnReorder);
        preparedData.setDroppedIndexes(droppedIndexes);
        preparedData.setAddedIndexes(addedIndexes);
        preparedData.setAddedIndexesWithoutNames(addedIndexesWithoutNames);
        preparedData.setRenamedIndexes(renamedIndexes);
        preparedData.setPrimaryKeyDropped(primaryKeyDropped);
        preparedData.setAddedPrimaryKeyColumns(addedPrimaryKeyColumns);
        preparedData.setTableComment(tableComment);

        return preparedData;
    }

    private void validateAlters(final TableMeta tableMeta,
                                final MetaUtils.TableColumns tableColumns,
                                AtomicReference<String> columnNameOut,
                                AtomicReference<SqlKind> alterTypeOut,
                                AtomicBoolean gsiExistsOut,
                                AtomicBoolean clusterExistsOut) {
        String columnName = null;
        SqlKind alterType = null;
        boolean gsiExists = false;
        boolean clusteredExists = false;

        for (SqlAlterSpecification alterItem : sqlAlterTable.getAlters()) {
            if (!(alterItem.isA(SqlKind.CHECK_ALTER_WITH_GSI))) {
                continue;
            }

            final PlannerContext context = (PlannerContext) this.getCluster().getPlanner().getContext();
            final ParamManager paramManager = context.getParamManager();

            alterType = alterItem.getKind();
            switch (alterType) {
            case ADD_COLUMN:
                SqlAddColumn addColumn = (SqlAddColumn) alterItem;
                if (tableMeta.withClustered()) {
                    if (sqlAlterTable.getColumnOpts().size() > 1 ||
                        !sqlAlterTable.getColumnOpts().containsKey(SqlAlterTable.ColumnOpt.ADD) ||
                        sqlAlterTable.getColumnOpts().get(SqlAlterTable.ColumnOpt.ADD).size() > 1 ||
                        sqlAlterTable.getAlters().size() > 1) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support mix ADD COLUMN with other ALTER statements when table contains CLUSTERED INDEX");
                    }
                    // Check duplicated column name for clustered index, because this may generate a compound job.
                    final String colName = addColumn.getColName().getLastName();
                    if (tableMeta.getColumnIgnoreCase(colName) != null) {
                        throw new TddlRuntimeException(ErrorCode.ERR_VALIDATE,
                            "Duplicate column name '" + colName + "' on `" + tableName + "`");
                    }
                    // Check in GSI table. This should never happen.
                    if (tableColumns.existsInGsi(colName)) {
                        throw new TddlRuntimeException(ErrorCode.ERR_VALIDATE,
                            "Duplicate column name '" + colName + "' on GSI of `" + tableName + "`");
                    }
                    clusteredExists = true;
                }
                break;
            case ALTER_COLUMN_DEFAULT_VAL:
                SqlAlterColumnDefaultVal alterDefaultVal = (SqlAlterColumnDefaultVal) alterItem;
                columnName = alterDefaultVal.getColumnName().getLastName();
                if (tableColumns.existsInGsi(columnName)) {
                    gsiExists = true;
                }
                break;
            case CHANGE_COLUMN:
                SqlChangeColumn changeColumn = (SqlChangeColumn) alterItem;
                columnName = changeColumn.getOldName().getLastName();
                if (tableColumns.existsInGsi(columnName)) {
                    gsiExists = true;

                    // Allow some special case of modify column.
                    final Set<AlterColumnSpecification> specificationSet =
                        getAlterColumnSpecification(tableMeta, changeColumn);
                    alterColumnSpecificationSets.add(specificationSet);

                    if (specificationSet.stream().anyMatch(ALTER_COLUMN_NAME_OR_TYPE::contains)) {
                        if ((tableColumns.isPrimaryKey(columnName) ||
                            tableColumns.isShardingKey(columnName) ||
                            tableColumns.isGsiShardingKey(columnName))) {
                            if (!paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                    "Do not support change column name or type on primary key or sharding key on table with GSI");
                            }
                        } else if (tableColumns.existsInGsiUniqueKey(columnName, false)) {
                            if (!paramManager
                                .getBoolean(ConnectionParams.ALLOW_DROP_OR_MODIFY_PART_UNIQUE_WITH_GSI) &&
                                !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                    "Change column included in UGSI is extremely dangerous which may corrupt the unique constraint");
                            }
                        } else if (!paramManager.getBoolean(ConnectionParams.ALLOW_LOOSE_ALTER_COLUMN_WITH_GSI)
                            && !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "Change column name or type included in GSI is not recommended");
                        }
                    } // Alter default, comment and order, so just let it go.

                    // Change alter warning.
                    if (!paramManager.getBoolean(ConnectionParams.ALLOW_LOOSE_ALTER_COLUMN_WITH_GSI) &&
                        !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                        if (1 == specificationSet.size() &&
                            specificationSet.stream().anyMatch(ALTER_COLUMN_DEFAULT::contains)) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "It seems that you only alter column default, try ALTER COLUMN SET/DROP DEFAULT(partly rollback supported) instead for better practice");
                        }
                    }
                }
                break;
            case DROP_COLUMN:
                SqlDropColumn dropColumn = (SqlDropColumn) alterItem;
                columnName = dropColumn.getColName().getLastName();

                if (tableColumns.existsInGsi(columnName)) {
                    gsiExists = true;

                    // PK can never modified.
                    if (tableColumns.primaryKeys.contains(columnName)) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support drop column included in primary key of table which has global secondary index");
                    }

                    // Drop column in local unique key and also in GSI is allowed in PolarDB-X by hint.
                    // Note this is **DANGER** because this operation may partly success and can't recover or rollback.
                    if ((!paramManager.getBoolean(ConnectionParams.ALLOW_DROP_OR_MODIFY_PART_UNIQUE_WITH_GSI)) &&
                        tableColumns.existsInLocalUniqueKey(columnName, false)) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support drop column included in unique key of table which has global secondary index");
                    }
                }

                // Sharding key can never modified.
                if (tableColumns.isGsiShardingKey(columnName)) {
                    throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                        "Do not support drop sharding key of global secondary index");
                }

                // Drop column in GSI unique key is allowed in PolarDB-X by hint.
                // Note this is **DANGER** because this operation may partly success and can't recover or rollback.
                if ((!paramManager.getBoolean(ConnectionParams.ALLOW_DROP_OR_MODIFY_PART_UNIQUE_WITH_GSI)) &&
                    tableColumns.existsInGsiUniqueKey(columnName, false)) {
                    throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                        "Do not support drop column included in unique key of global secondary index");
                }
                break;
            case MODIFY_COLUMN:
                SqlModifyColumn modifyColumn = (SqlModifyColumn) alterItem;
                columnName = modifyColumn.getColName().getLastName();

                if (tableColumns.existsInGsi(columnName)) {
                    gsiExists = true;

                    // Allow some special case of modify column.
                    final Set<AlterColumnSpecification> specificationSet =
                        getAlterColumnSpecification(tableMeta, modifyColumn);
                    alterColumnSpecificationSets.add(specificationSet);

                    if (specificationSet.stream().anyMatch(ALTER_COLUMN_NAME_OR_TYPE::contains)) {
                        if ((tableColumns.isPrimaryKey(columnName) ||
                            tableColumns.isShardingKey(columnName) ||
                            tableColumns.isGsiShardingKey(columnName))) {
                            if (!paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                    "Do not support change column name or type on primary key or sharding key on table with GSI");
                            }
                        } else if (tableColumns.existsInGsiUniqueKey(columnName, false)) {
                            if (!paramManager
                                .getBoolean(ConnectionParams.ALLOW_DROP_OR_MODIFY_PART_UNIQUE_WITH_GSI) &&
                                !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                    "Change column included in UGSI is extremely dangerous which may corrupt the unique constraint");
                            }
                        } else if (!paramManager.getBoolean(ConnectionParams.ALLOW_LOOSE_ALTER_COLUMN_WITH_GSI)
                            && !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "Change column name or type included in GSI is not recommended");
                        }
                    } // Alter default, comment and order, so just let it go.

                    // Modify alter warning.
                    if (!paramManager.getBoolean(ConnectionParams.ALLOW_LOOSE_ALTER_COLUMN_WITH_GSI) &&
                        !paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                        if (1 == specificationSet.size() &&
                            specificationSet.stream().anyMatch(ALTER_COLUMN_DEFAULT::contains)) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "It seems that you only alter column default, try ALTER COLUMN SET/DROP DEFAULT(partly rollback supported) instead for better practice");
                        }
                    }
                }
                break;
            case ADD_INDEX:
            case ADD_UNIQUE_INDEX:
            case ADD_FULL_TEXT_INDEX:
            case ADD_SPATIAL_INDEX:
            case ADD_FOREIGN_KEY:
                final SqlAddIndex addIndex = (SqlAddIndex) alterItem;
                if (null != addIndex.getIndexName() && tableMeta.withGsi(addIndex.getIndexName().getLastName())) {
                    throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER, "Duplicated index name "
                        + addIndex.getIndexName()
                        .getLastName());
                }
                // Fall over.
            case DROP_INDEX:
                clusteredExists = true;
                break;
            case DROP_PRIMARY_KEY:
                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                    "Does not support drop primary key from table with global secondary index");
            case CONVERT_TO_CHARACTER_SET:
                gsiExists = true;
                if (!paramManager.getBoolean(ConnectionParams.ALLOW_ALTER_GSI_INDIRECTLY)) {
                    // Check correctness. Because this can not rollback.
                    final SqlConvertToCharacterSet convert = (SqlConvertToCharacterSet) alterItem;
                    final CharsetName charsetName = CharsetName.of(convert.getCharset());
                    if (null == charsetName || !charsetName.name().equalsIgnoreCase(convert.getCharset())) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Unknown charset name '" + convert.getCharset() + "'");
                    }
                    if (convert.getCollate() != null) {
                        final CollationName collationName = CollationName.of(convert.getCollate());
                        if (null == collationName || !collationName.name().equalsIgnoreCase(convert.getCollate())) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "Unknown collate name '" + convert.getCollate() + "'");
                        }
                        if (!charsetName.isSupported(collationName)) {
                            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                                "Collate name '" + convert.getCollate() + "' not support for '" + convert
                                    .getCharset() + "'");
                        }
                    }
                }
                break;
            default:
                break;
            }
        }

        columnNameOut.set(columnName);
        gsiExistsOut.set(gsiExists);
        clusterExistsOut.set(clusteredExists);
        alterTypeOut.set(alterType);
    }

    private void prepareAlterGsiData() {
        TableMeta tableMeta = OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(tableName);
        if (!tableMeta.withGsi()) {
            return;
        }

        MetaUtils.TableColumns tableColumns = MetaUtils.TableColumns.build(tableMeta);

        // pass as output parameters
        AtomicReference<String> columnNameOut = new AtomicReference<>();
        AtomicReference<SqlKind> alterTypeOut = new AtomicReference<>();
        AtomicBoolean gsiExistsOut = new AtomicBoolean(false);
        AtomicBoolean clusteredExistsOut = new AtomicBoolean();

        // Clear column specifications.
        alterColumnSpecificationSets.clear();

        // Validate alter
        validateAlters(tableMeta, tableColumns, columnNameOut, alterTypeOut, gsiExistsOut, clusteredExistsOut);

        String columnName = columnNameOut.get();
        SqlKind alterType = alterTypeOut.get();
        boolean gsiExists = gsiExistsOut.get();
        boolean clusteredExists = clusteredExistsOut.get();

        alterTableWithGsiPreparedData = new AlterTableWithGsiPreparedData();

        if ((gsiExists || clusteredExists) && sqlAlterTable.getAlters().size() > 1) {
            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                "Do not support multi ALTER statements on table with global secondary index");
        }

        if (gsiExists) {
            if (alterType.belongsTo(SqlKind.ALTER_ALTER_COLUMN) && TStringUtil.isNotBlank(columnName)) {
                // Alter gsi table column when alter primary table columns
                final Set<String> gsiNameByColumn = tableColumns.getGsiNameByColumn(columnName);

                final GsiTableMetaBean gsiTableMetaBean = tableMeta.getGsiTableMetaBean();
                for (Map.Entry<String, GsiIndexMetaBean> indexEntry : gsiTableMetaBean.indexMap.entrySet()) {
                    final String indexTableName = indexEntry.getKey();

                    if (!gsiNameByColumn.contains(indexTableName)) {
                        continue;
                    }

                    if (null != sqlAlterTable.getTableOptions() &&
                        GeneralUtil.isNotEmpty(sqlAlterTable.getTableOptions().getUnion())) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support set table option UNION to table with global secondary index");
                    }

                    alterTableWithGsiPreparedData
                        .addAlterGlobalIndexPreparedData(prepareAlterTableData(indexTableName));
                }
            } else if (alterType == SqlKind.CONVERT_TO_CHARACTER_SET) {
                // Alter charset of gsi if primary table's charset is changed
                final GsiTableMetaBean gsiTableMetaBean = tableMeta.getGsiTableMetaBean();
                for (Map.Entry<String, GsiIndexMetaBean> indexEntry : gsiTableMetaBean.indexMap.entrySet()) {
                    final String indexTableName = indexEntry.getKey();

                    if (null != sqlAlterTable.getTableOptions()
                        && GeneralUtil.isNotEmpty(sqlAlterTable.getTableOptions().getUnion())) {
                        throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                            "Do not support set table option UNION to table with global secondary index");
                    }

                    alterTableWithGsiPreparedData
                        .addAlterGlobalIndexPreparedData(prepareAlterTableData(indexTableName));
                }
            }
        } else if (null != sqlAlterTable.getTableOptions() && GeneralUtil.isEmpty(sqlAlterTable.getAlters())) {
            // Alter table options
            if (GeneralUtil.isEmpty(sqlAlterTable.getTableOptions().getUnion())) {
                final GsiTableMetaBean gsiTableMetaBean = tableMeta.getGsiTableMetaBean();
                for (Map.Entry<String, GsiIndexMetaBean> indexEntry : gsiTableMetaBean.indexMap.entrySet()) {
                    final String indexTableName = indexEntry.getKey();
                    alterTableWithGsiPreparedData
                        .addAlterGlobalIndexPreparedData(prepareAlterTableData(indexTableName));
                }
            }
        } else if (clusteredExists) {
            // NOTE: All modifications on clustered-index are processes at `alterClusterIndexData1
            if (null != sqlAlterTable.getTableOptions()
                && GeneralUtil.isNotEmpty(sqlAlterTable.getTableOptions().getUnion())) {
                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                    "Do not support set table option UNION to table with clustered index");
            }
        }
    }

    private Set<AlterColumnSpecification> getAlterColumnSpecification(TableMeta tableMeta,
                                                                      SqlAlterSpecification specification) {
        final Set<AlterColumnSpecification> specificationSet = new HashSet<>();

        switch (specification.getKind()) {
        case CHANGE_COLUMN: {
            final SqlChangeColumn changeColumn = (SqlChangeColumn) specification;

            // Check name.
            final String oldName = changeColumn.getOldName().getLastName();
            if (!changeColumn.getNewName().getLastName().equalsIgnoreCase(oldName)) {
                specificationSet.add(AlterColumnSpecification.AlterColumnName);
            }

            // Check definition.
            final ColumnMeta columnMeta = tableMeta.getColumnIgnoreCase(oldName);
            if (null == columnMeta) {
                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                    "Modify unknown column '" + oldName + "'");
            }
            updateAlterColumnSpecification(columnMeta, changeColumn.getColDef(), specificationSet);

            // Check reorder.
            if (changeColumn.isFirst()) {
                // Check whether first column.
                if (!tableMeta.getPhysicalColumns().get(0).getName().equalsIgnoreCase(oldName)) {
                    specificationSet.add(AlterColumnSpecification.AlterColumnOrder);
                }
            } else if (changeColumn.getAfterColumn() != null) {
                final String afterColName = changeColumn.getAfterColumn().getLastName();
                for (int colIdx = 0; colIdx < tableMeta.getPhysicalColumns().size(); ++colIdx) {
                    final ColumnMeta probCol = tableMeta.getPhysicalColumns().get(colIdx);
                    if (probCol.getName().equalsIgnoreCase(afterColName)) {
                        // Find the before col.
                        if (colIdx >= tableMeta.getPhysicalColumns().size() - 1 || !tableMeta.getPhysicalColumns()
                            .get(colIdx + 1).getName().equalsIgnoreCase(oldName)) {
                            specificationSet.add(AlterColumnSpecification.AlterColumnOrder);
                        }
                        break;
                    }
                }
            } // Or change in place.
        }
        break;

        case MODIFY_COLUMN: {
            final SqlModifyColumn modifyColumn = (SqlModifyColumn) specification;

            // Modify doesn't change the name.
            // Now check definition.
            final String colName = modifyColumn.getColName().getLastName();
            final ColumnMeta columnMeta = tableMeta.getColumnIgnoreCase(colName);
            if (null == columnMeta) {
                throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER,
                    "Modify unknown column '" + colName + "'");
            }
            updateAlterColumnSpecification(columnMeta, modifyColumn.getColDef(), specificationSet);

            // Check reorder.
            if (modifyColumn.isFirst()) {
                // Check whether first column.
                if (!tableMeta.getPhysicalColumns().get(0).getName().equalsIgnoreCase(colName)) {
                    specificationSet.add(AlterColumnSpecification.AlterColumnOrder);
                }
            } else if (modifyColumn.getAfterColumn() != null) {
                final String afterColName = modifyColumn.getAfterColumn().getLastName();
                for (int colIdx = 0; colIdx < tableMeta.getPhysicalColumns().size(); ++colIdx) {
                    final ColumnMeta probCol = tableMeta.getPhysicalColumns().get(colIdx);
                    if (probCol.getName().equalsIgnoreCase(afterColName)) {
                        // Find the before col.
                        if (colIdx >= tableMeta.getPhysicalColumns().size() - 1 || !tableMeta.getPhysicalColumns()
                            .get(colIdx + 1).getName().equalsIgnoreCase(colName)) {
                            specificationSet.add(AlterColumnSpecification.AlterColumnOrder);
                        }
                        break;
                    }
                }
            } // Or modify in place.
        }
        break;

        default:
            throw new TddlRuntimeException(ErrorCode.ERR_OPTIMIZER, "Unknown alter specification");
        }
        return specificationSet;
    }

    private void updateAlterColumnSpecification(ColumnMeta columnMeta,
                                                SqlColumnDeclaration columnDeclaration,
                                                Set<AlterColumnSpecification> specificationSet) {
        // Check basic type.
        final RelDataType targetDataType = columnDeclaration.getDataType().deriveType(getCluster().getTypeFactory());
        if (!SqlTypeUtil.equalSansNullability(getCluster().getTypeFactory(), targetDataType,
            columnMeta.getField().getRelType())) {
            specificationSet.add(AlterColumnSpecification.AlterColumnType);
        }

        // Check nullable.
        final boolean targetNullable = null == columnDeclaration.getNotNull() ||
            SqlColumnDeclaration.ColumnNull.NULL == columnDeclaration.getNotNull();
        if (columnMeta.getField().getRelType().isNullable() != targetNullable) {
            specificationSet.add(AlterColumnSpecification.AlterColumnType);
        }

        // Check default value.
        final String originalDefault = null == columnMeta.getField().getDefault() ?
            (columnMeta.getField().getRelType().isNullable() ? "NULL" : null) : columnMeta.getField().getDefault();
        final String targetDefault;
        if (columnDeclaration.getDefaultExpr() != null) {
            targetDefault = columnDeclaration.getDefaultExpr().getOperator().getName();
        } else if (columnDeclaration.getDefaultVal() != null) {
            targetDefault = columnDeclaration.getDefaultVal().toValue();
        } else if (targetNullable) {
            targetDefault = "NULL"; // Default null.
        } else {
            targetDefault = null;
        }
        if ((null == originalDefault && targetDefault != null) || (originalDefault != null && null == targetDefault) ||
            (originalDefault != null && targetDefault != null && !originalDefault.equals(targetDefault))) {
            specificationSet.add(AlterColumnSpecification.AlterColumnDefault);
        }

        // Check comment.
        if (columnDeclaration.getComment() != null) {
            specificationSet.add(AlterColumnSpecification.AlterColumnComment);
        }
    }

    /**
     * Rewrite a local index to global index, if the table is auto-partitioned
     */
    public boolean needRewriteToGsi(boolean rewrite) {
        final String logicalTableName = sqlAlterTable.getOriginTableName().getLastName();
        final TableMeta tableMeta =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(logicalTableName);

        if (tableMeta.isAutoPartition()) {
            for (int idx = 0; idx < sqlAlterTable.getAlters().size(); ++idx) {
                final SqlAlterSpecification specification = sqlAlterTable.getAlters().get(idx);
                if (specification instanceof SqlAddIndex) {
                    final SqlAddIndex addIndex = (SqlAddIndex) specification;
                    if (!addIndex.getIndexDef().isClustered() &&
                        !addIndex.getIndexDef().isGlobal() &&
                        !addIndex.getIndexDef().isLocal()) {
                        // Need rewrite.
                        if (rewrite) {
                            SqlAddIndex newIndex = new SqlAddIndex(
                                addIndex.getParserPosition(),
                                addIndex.getIndexName(),
                                addIndex.getIndexDef().rebuildToGsi(null, null, false)
                            );
                            sqlAlterTable.getAlters().set(idx, newIndex);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // For alter table column with GSI.
    public enum AlterColumnSpecification {
        AlterColumnName,
        AlterColumnType,
        AlterColumnDefault, // Should start auto fill.
        AlterColumnComment, // May set to null and this flag is not set. Just push down this alter.
        AlterColumnOrder // Should alter order in metaDB first.
    }
}
