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

package com.alibaba.polardbx.sequence.impl;

import com.alibaba.polardbx.common.constants.SystemTables;
import com.alibaba.polardbx.common.logger.LoggerInit;
import com.alibaba.polardbx.common.model.lifecycle.AbstractLifecycle;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.common.utils.TStringUtil;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.common.utils.thread.NamedThreadFactory;
import com.alibaba.polardbx.gms.metadb.MetaDbDataSource;
import com.alibaba.polardbx.group.jdbc.TGroupDataSource;
import com.alibaba.polardbx.sequence.SequenceDao;
import com.alibaba.polardbx.sequence.SequenceRange;
import com.alibaba.polardbx.sequence.exception.SequenceException;
import com.alibaba.polardbx.sequence.util.RandomSequence;
import com.alibaba.polardbx.sequence.util.SequenceHelper;
import org.apache.commons.lang.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author JIECHEN 2013-10-31 ??????5:48:48
 * @since 5.0.0
 */
public class GroupSequenceDao extends AbstractLifecycle implements SequenceDao {

    protected static final Logger logger = LoggerFactory
        .getLogger(GroupSequenceDao.class);

    protected static final int DEFAULT_INNER_STEP = 1000;

    protected static final int DEFAULT_RETRY_TIMES = 2;

    public static final String DEFAULT_TABLE_NAME = SystemTables.SEQUENCE;

    protected static final String DEFAULT_NAME_COLUMN_NAME = "name";
    protected static final String DEFAULT_VALUE_COLUMN_NAME = "value";
    protected static final String DEFAULT_GMT_MODIFIED_COLUMN_NAME = "gmt_modified";

    protected static final int DEFAULT_DSCOUNT = 2;
    protected static final Boolean DEFAULT_ADJUST = false;

    protected static final long DELTA = 100000000L;

    /**
     * ?????????
     */
    protected String appName;

    protected String schemaName;

    protected String unitName;

    /**
     * group??????
     */
    protected List<String> dbGroupKeys;

    protected List<String> oriDbGroupKeys;

    /**
     * ?????????
     */
    protected Map<String, DataSource> dataSourceMap;

    /**
     * ???????????????
     */
    protected boolean adjust = DEFAULT_ADJUST;
    /**
     * ????????????
     */
    protected int retryTimes = DEFAULT_RETRY_TIMES;

    /**
     * ???????????????
     */
    protected int dscount = DEFAULT_DSCOUNT;

    /**
     * ?????????
     */
    protected int innerStep = DEFAULT_INNER_STEP;

    /**
     * ?????????
     */
    protected int outStep = DEFAULT_INNER_STEP;

    /**
     * ?????????????????????
     */
    protected String tableName = DEFAULT_TABLE_NAME;

    /**
     * ???????????????????????????
     */
    protected String nameColumnName = DEFAULT_NAME_COLUMN_NAME;

    /**
     * ????????????????????????
     */
    protected String valueColumnName = DEFAULT_VALUE_COLUMN_NAME;

    /**
     * ???????????????????????????????????????
     */
    protected String gmtModifiedColumnName = DEFAULT_GMT_MODIFIED_COLUMN_NAME;

    /* ds index, ???????????? */
    protected ConcurrentHashMap<Integer, AtomicInteger> excludedKeyCount =
        new ConcurrentHashMap<Integer, AtomicInteger>(
            dscount);
    // ???????????????????????????
    protected int maxSkipCount = 10;
    // ???????????????????????????
    protected boolean useSlowProtect = false;
    // ???????????????
    protected int protectMilliseconds = 50;

    protected ExecutorService exec =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            new NamedThreadFactory("Group-Seq-Slow-Protection", true), new ThreadPoolExecutor.AbortPolicy());

    protected Lock configLock = new ReentrantLock();

    // ?????????????????????: center/unit (?????????center??????????????????unit?????????????????????tddl)
    protected String writeMode = null;
    // ????????????????????????????????????Sequence?????????: true/false (?????????false?????????????????????????????????????????????tddl,?????????????????????)
    private boolean isColdStartMode = true;
    // ??????????????????????????????
    protected String configStr = "";

    protected boolean optimisticLockMode = false;

    public GroupSequenceDao() {
    }

    /**
     * ?????????
     */
    @Override
    public void doInit() {

        // ????????????????????????????????????
        if (StringUtils.isEmpty(appName)) {
            SequenceException sequenceException = new SequenceException("appName is Null ");
            logger.error("????????????appName", sequenceException);
            throw sequenceException;
        }
        if (dbGroupKeys == null || dbGroupKeys.size() == 0) {
            noDbGroupKeys();
        }

        dataSourceMap = new HashMap();
        dataSourceMap.put(dbGroupKeys.get(0), MetaDbDataSource.getInstance().getDataSource());

        if (dbGroupKeys.size() >= dscount) {
            dscount = dbGroupKeys.size();
        } else {
            for (int ii = dbGroupKeys.size(); ii < dscount; ii++) {
                dbGroupKeys.add(dscount + "-OFF");
            }
        }

        outStep = innerStep * dscount;// ???????????????
        outputInitResult();

        LoggerInit.TDDL_SEQUENCE_LOG.info(getConfigStr());
    }

    protected void noDbGroupKeys() {
        logger.error("????????????dbgroupKeys");
        throw new SequenceException("dbgroupKeys?????????");
    }

    @Override
    protected void doDestroy() {

        // do nothing
        super.doDestroy();
        for (DataSource dataSource : dataSourceMap.values()) {
            if (dataSource instanceof TGroupDataSource) {
                ((TGroupDataSource) dataSource).destroyDataSource();
                logger.info("destroy dataSource success");
            } else {
                logger.info("destroy dataSource fail, unknown datasource");
            }
        }
    }

    /**
     * ??????????????????????????????
     */
    protected void outputInitResult() {
        StringBuilder sb = new StringBuilder();
        sb.append("GroupSequenceDao??????????????????\r\n ");
        sb.append("appName:").append(appName).append("\r\n");
        sb.append("innerStep:").append(this.innerStep).append("\r\n");
        sb.append("dataSource:").append(dscount).append("???:");
        for (String str : dbGroupKeys) {
            sb.append("[").append(str).append("]???");
        }
        sb.append("\r\n");
        sb.append("adjust???").append(adjust).append("\r\n");
        sb.append("retryTimes:").append(retryTimes).append("\r\n");
        sb.append("tableName:").append(tableName).append("\r\n");
        sb.append("nameColumnName:").append(nameColumnName).append("\r\n");
        sb.append("valueColumnName:").append(valueColumnName).append("\r\n");
        sb.append("gmtModifiedColumnName:").append(gmtModifiedColumnName).append("\r\n");
        logger.info(sb.toString());

        String format =
            "[type:group] [appName:{0}] [dsCount:{1}] [dbGroupKeys:{2}] [innerStep:{3}] [outStep:{4}] [retryTimes:{5}] [adjust:{6}] [tableInfo:{7}({8},{9},{10})]";
        this.configStr = MessageFormat.format(format,
            appName,
            String.valueOf(dscount),
            StringUtils.join(dbGroupKeys, ','),
            String.valueOf(innerStep),
            String.valueOf(outStep),
            String.valueOf(retryTimes),
            String.valueOf(adjust),
            tableName,
            nameColumnName,
            valueColumnName,
            gmtModifiedColumnName);
    }

    /**
     * @param index gourp??????????????????0??????
     * @param value ???????????????
     */
    private boolean check(int index, long value) {
        return (value % outStep) == (index * innerStep);
    }

    /**
     * <pre>
     * ?????????????????????sequence???
     *
     * 1?????????sequece??????????????????????????????????????????
     * 2??????????????????????????????????????????????????????
     * 3???????????????????????????????????????
     *
     * &#64;throws SequenceException
     * </pre>
     */
    public void adjust(String name) throws SequenceException, SQLException {

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        for (int i = 0; i < dbGroupKeys.size(); i++) {
            if (dbGroupKeys.get(i).toUpperCase().endsWith("-OFF"))// ????????????????????????
            {
                continue;
            }
            DataSource dataSource = dataSourceMap.get(dbGroupKeys.get(i));
            try {
                conn = SequenceHelper.getConnection(dataSource);
                stmt = conn.prepareStatement(getSelectSql());

                stmt.setString(1, name);
                stmt.setString(2, schemaName);

                rs = stmt.executeQuery();
                int item = 0;
                while (rs.next()) {
                    item++;
                    long val = rs.getLong(this.getValueColumnName());
                    if (!check(i, val)) // ????????????
                    {
                        if (this.isAdjust()) {
                            this.adjustUpdate(i, val, name);
                        } else {
                            logger.error("???????????????????????????????????????????????????????????????????????????adjust??????");
                            throw new SequenceException("???????????????????????????????????????????????????????????????????????????adjust??????");
                        }
                    }
                }
                if (item == 0)// ?????????,??????????????????
                {
                    if (this.isAdjust()) {
                        this.adjustInsert(i, name);
                    } else {
                        logger.error("????????????????????????sequence???????????????????????????sequence?????????????????????adjust??????");
                        throw new SequenceException("????????????????????????sequence???????????????????????????sequence?????????????????????adjust??????");
                    }
                }
            } catch (SQLException e) {// ??????SQL??????????????????????????????????????????
                logger.error("???????????????????????????????????????.", e);
                throw e;
            } finally {
                SequenceHelper.closeDbResources(rs, stmt, conn);
            }
        }
    }

    protected Connection getSeqDsConnection(DataSource dataSource) throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * ??????
     */
    private void adjustUpdate(int index, long value, String name) throws SequenceException {
        long newValue = (value - value % outStep) + outStep + index * innerStep;// ????????????????????????
        DataSource dataSource = dataSourceMap.get(dbGroupKeys.get(index));
        Connection conn = null;
        PreparedStatement stmt = null;
        // ResultSet rs = null;
        try {

            conn = getSeqDsConnection(dataSource);
            stmt = conn.prepareStatement(getUpdateSql());

            stmt.setLong(1, newValue);
            stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            stmt.setString(3, name);
            stmt.setLong(4, value);

            stmt.setString(5, schemaName);

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SequenceException("Failed to auto adjust init value at  " + name + " update affectedRow =0");
            }
            logger.info(
                dbGroupKeys.get(index) + "??????????????????!" + "sequence Name???" + name + "???????????????" + value + "-->" + newValue);
        } catch (SQLException e) { // ??????SQL????????????Sequence??????
            logger.error("??????SQLException,??????????????????????????????dbGroupIndex:" + dbGroupKeys.get(index) + "???sequence Name???" + name
                    + "???????????????" + value + "-->" + newValue,
                e);
            throw new SequenceException(e,
                "??????SQLException,??????????????????????????????dbGroupIndex:" + dbGroupKeys.get(index) + "???sequence Name???" + name + "???????????????"
                    + value + "-->" + newValue);
        } finally {
            SequenceHelper.closeDbResources(null, stmt, conn);
        }
    }

    /**
     * ????????????
     */
    protected void adjustInsert(int index, String name) throws SequenceException, SQLException {
        DataSource dataSource = dataSourceMap.get(dbGroupKeys.get(index));
        long newValue = index * innerStep;
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = getSeqDsConnection(dataSource);
            stmt = conn.prepareStatement(getInsertSql());

            int i = 0;
            stmt.setString(++i, schemaName);
            stmt.setString(++i, name);
            stmt.setLong(++i, newValue);
            stmt.setTimestamp(++i, new Timestamp(System.currentTimeMillis()));

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SequenceException("faild to auto adjust init value at  " + name + " update affectedRow =0");
            }
            logger.info(dbGroupKeys.get(index) + "   name:" + name + "????????????:" + name + "value:" + newValue);

        } catch (SQLException e) {
            logger.error("??????SQLException,??????????????????????????????dbGroupIndex:" + dbGroupKeys.get(index) + "???sequence Name???" + name
                    + "   value:" + newValue,
                e);
            throw new SequenceException(e,
                "??????SQLException,??????????????????????????????dbGroupIndex:" + dbGroupKeys.get(index) + "???sequence Name???" + name
                    + "   value:" + newValue);
        } finally {
            SequenceHelper.closeDbResources(rs, stmt, conn);
        }
    }

    /**
     * ??????groupKey????????????????????????
     */
    protected boolean isOffState(String groupKey) {
        return groupKey.toUpperCase().endsWith("-OFF");
    }

    /**
     * ???????????????exclude,?????????????????????
     */
    protected boolean recoverFromExcludes(int index) {
        boolean result = true;
        if (excludedKeyCount.get(index) != null) {
            if (excludedKeyCount.get(index).incrementAndGet() > maxSkipCount) {
                excludedKeyCount.remove(index);
                logger.error(maxSkipCount + "???????????????index???" + index + "???????????????????????????????????????");
            } else {
                result = false;
            }
        }
        return result;
    }

    protected long queryOldValue(DataSource dataSource, String keyName) throws SQLException, SequenceException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = SequenceHelper.getConnection(dataSource);
            stmt = conn.prepareStatement(getSelectSql());

            stmt.setString(1, keyName);
            stmt.setString(2, schemaName);

            rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            } else {
                throw new SequenceException("??????????????????sequence??????????????????sequence : " + keyName);
            }
        } finally {
            SequenceHelper.closeDbResources(rs, stmt, conn);
        }
    }

    /**
     * CAS??????sequence???
     */
    protected int updateNewValue(DataSource dataSource, String keyName, long oldValue,
                                 long newValue) throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = SequenceHelper.getConnection(dataSource);
            stmt = conn.prepareStatement(getUpdateSql());

            stmt.setLong(1, newValue);
            stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            stmt.setString(3, keyName);
            stmt.setLong(4, oldValue);
            stmt.setString(5, schemaName);

            return stmt.executeUpdate();
        } finally {
            SequenceHelper.closeDbResources(rs, stmt, conn);
        }
    }

    /**
     * ??????????????????????????????sequence???
     */
    protected long getOldValue(final DataSource dataSource, final String keyName) throws SQLException,
        SequenceException {
        long result = 0;

        // ???????????????????????????????????????????????????1????????????????????????????????????
        if (!useSlowProtect || excludedKeyCount.size() >= (dscount - 1)) {
            result = queryOldValue(dataSource, keyName);
        } else {
            FutureTask<Long> future = new FutureTask<Long>(new Callable<Long>() {

                @Override
                public Long call() throws Exception {
                    return queryOldValue(dataSource, keyName);
                }
            });
            try {
                exec.submit(future);
                result = future.get(protectMilliseconds, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new SQLException("[SEQUENCE SLOW-PROTECTED MODE]:InterruptedException", e);
            } catch (ExecutionException e) {
                throw new SQLException("[SEQUENCE SLOW-PROTECTED MODE]:ExecutionException", e);
            } catch (TimeoutException e) {
                throw new SQLException(
                    "[SEQUENCE SLOW-PROTECTED MODE]:TimeoutException,???????????????????????????" + protectMilliseconds,
                    e);
            }
        }
        return result;
    }

    /**
     * ??????oldValue??????newValue
     */
    protected long generateNewValue(int index, long oldValue, String keyName) throws SequenceException {
        long newValue = oldValue + outStep;
        if (!check(index, newValue)) // ???????????????????????????
        {
            if (this.isAdjust()) {
                newValue = adjustNewValue(index, newValue);
            } else {
                throwErrorRangeException(index, keyName);
            }
        }
        return newValue;
    }

    protected long adjustNewValue(int index, long newValue) {
        return (newValue - newValue % outStep) + outStep + index * innerStep;// ????????????????????????
    }

    protected void throwErrorRangeException(int index, String keyName) throws SequenceException {
        String errorMsg = dbGroupKeys.get(index) + ":" + keyName + "?????????????????????????????????????????????????????????????????????????????????adjust?????????";
        throw new SequenceException(errorMsg);
    }

    protected DataSource getGroupDsByIndex(int index) {
        return dataSourceMap.get(dbGroupKeys.get(index));
    }

    /**
     * ?????????sequence???????????????????????????
     */
    protected boolean isOldValueFixed(long oldValue) {
        boolean result = true;
        StringBuilder message = new StringBuilder();
        if (oldValue < 0) {
            message.append("Sequence value cannot be less than zero.");
            result = false;
        } else if (oldValue > Long.MAX_VALUE - DELTA) {
            message.append("Sequence value overflow.");
            result = false;
        }
        if (!result) {
            message.append(" Sequence value  = ").append(oldValue);
            message.append(", please check table ").append(getTableName());
            logger.info(message.toString());
        }
        return result;
    }

    /**
     * ????????????????????????sequence?????????????????????
     */
    protected void excludeDataSource(int index) {
        // ????????????????????????????????????????????????????????????
        if (excludedKeyCount.size() < (dscount - 1)) {
            excludedKeyCount.put(index, new AtomicInteger(0));
            logger.error("????????????index???" + index + "???????????????" + maxSkipCount + "??????????????????");
        }
    }

    @Override
    public SequenceRange nextRange(final String name) throws SequenceException {
        if (name == null) {
            logger.error("??????????????????");
            throw new IllegalArgumentException("????????????????????????");
        }

        Throwable ex = null;
        configLock.lock();
        try {
            int[] randomIntSequence = RandomSequence.randomIntSequence(dscount);
            for (int i = 0; i < retryTimes; i++) {
                for (int j = 0; j < dscount; j++) {
                    int index = randomIntSequence[j];
                    if (isOffState(dbGroupKeys.get(index)) || !recoverFromExcludes(index)) {
                        continue;
                    }
                    final DataSource dataSource = getGroupDsByIndex(index);

                    long newValue = 0L;
                    if (optimisticLockMode) {
                        long oldValue;
                        // ?????????????????????????????????????????????????????????????????????
                        try {
                            oldValue = getOldValue(dataSource, name);
                            if (!isOldValueFixed(oldValue)) {
                                continue;
                            }
                        } catch (SQLException e) {
                            ex = e;
                            logger.warn("??????????????????--???????????????" + dbGroupKeys.get(index) + ":" + name, e);
                            excludeDataSource(index);
                            continue;
                        }

                        newValue = generateNewValue(index, oldValue, name);
                        try {
                            if (0 == updateNewValue(dataSource, name, oldValue, newValue)) {
                                logger.warn("??????????????????--???????????????" + dbGroupKeys.get(index) + ":" + name);
                                continue;
                            }
                        } catch (SQLException e) {
                            ex = e;
                            logger.warn("??????????????????--???????????????" + dbGroupKeys.get(index) + ":" + name, e);
                            continue;
                        }
                    } else {
                        try {
                            newValue = getNewValueForNextRange(dataSource, name, index);
                        } catch (SQLException e) {
                            ex = e;
                            logger.warn("Failed to get new value for group sequence '" + name + "' on group '"
                                    + dbGroupKeys.get(index) + "'.",
                                e);
                            excludeDataSource(index);
                            continue;
                        }
                    }

                    SequenceRange range = new SequenceRange(newValue + 1, newValue + innerStep);

                    String infoMsg = "Got a new range for group sequence '" + name + "'. Range Info: "
                        + range.toString();
                    LoggerInit.TDDL_SEQUENCE_LOG.info(infoMsg);
                    if (logger.isDebugEnabled()) {
                        logger.debug(infoMsg);
                    }

                    return range;
                }
                // ????????????????????????????????????,??????excludedMap,???????????????????????????
                if (i == (retryTimes - 2)) {
                    excludedKeyCount.clear();
                }
            }

            if (ex == null) {
                logger.error("???????????????????????????????????????" + this.retryTimes + "?????????????????????!????????????????????????????????????????????????");
                throw new SequenceException("All dataSource faild to get value!");
            } else {
                logger.error("???????????????????????????????????????" + this.retryTimes + "?????????????????????!", ex);
                throw new SequenceException(ex, ex.getMessage());
            }
        } finally {
            configLock.unlock();
        }
    }

    protected long getNewValueForNextRange(final DataSource dataSource, final String keyName,
                                           final int index) throws SQLException {
        long newValue = 0;

        // ???????????????????????????????????????????????????1????????????????????????????????????
        if (!useSlowProtect || excludedKeyCount.size() >= (dscount - 1)) {
            newValue = fetchNewValue(dataSource, keyName, index);
        } else {
            FutureTask<Long> future = new FutureTask<Long>(new Callable<Long>() {

                @Override
                public Long call() throws Exception {
                    return fetchNewValue(dataSource, keyName, index);
                }
            });
            try {
                exec.submit(future);
                newValue = future.get(protectMilliseconds, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new SQLException("[SEQUENCE SLOW-PROTECTED MODE]:InterruptedException", e);
            } catch (ExecutionException e) {
                throw new SQLException("[SEQUENCE SLOW-PROTECTED MODE]:ExecutionException", e);
            } catch (TimeoutException e) {
                throw new SQLException(
                    "[SEQUENCE SLOW-PROTECTED MODE]:TimeoutException,???????????????????????????" + protectMilliseconds,
                    e);
            }
        }
        return newValue;
    }

    protected long fetchNewValue(final DataSource dataSource, final String keyName,
                                 final int index) throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            long newValue = 0L;
            conn = SequenceHelper.getConnection(dataSource);
            stmt = conn.prepareStatement(getFetchSql(index), Statement.RETURN_GENERATED_KEYS);

            stmt.setString(1, keyName);
            stmt.setString(2, schemaName);

            int updateCount = stmt.executeUpdate();
            if (updateCount == 1) {
                rs = stmt.getGeneratedKeys();
                if (rs.next()) {
                    newValue = rs.getLong(1);
                } else {
                    throwErrorRangeException(index, keyName);
                }
            } else if (updateCount > 1) {
                String errMsg = "Found multiple sequences with the same name '" + keyName + "' on group '"
                    + dbGroupKeys.get(index)
                    + "'. Please double check them and delete the extra records to keep only one. "
                    + "Also add a unique index for the name column to avoid potential issues.";
                logger.warn(errMsg);
                throw new SequenceException(errMsg);
            } else {
                String errMsg = "Sequence '" + keyName + "' doesn't exist on group '" + dbGroupKeys.get(index) + "'.";
                logger.warn(errMsg);
                throw new SequenceException(errMsg);
            }
            return newValue;
        } finally {
            SequenceHelper.closeDbResources(rs, stmt, conn);
        }
    }

    public boolean updateExplicitValue(final String name, final long value) throws SequenceException {
        if (name == null) {
            logger.error("??????????????????");
            throw new IllegalArgumentException("????????????????????????");
        }
        if (value <= 0L) {
            return false;
        }

        try {
            // The method is only applied to DRDS, so we can get the only group directly.
            final DataSource dataSource = getGroupDsByIndex(0);
            // We just need innerStep since there is only one group in DRDS.
            long newValue = value - value % innerStep;
            // Update sequence with the new value
            return updateExplicitValueInternal(dataSource, name, newValue);
        } catch (SQLException e) {
            String errMsg = "Failed to update value " + value + " directly for sequence '" + name + "': "
                + e.getMessage();
            logger.error(errMsg, e);
            throw new SequenceException(e, errMsg);
        }
    }

    private boolean updateExplicitValueInternal(DataSource dataSource, String keyName, long value) throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = SequenceHelper.getConnection(dataSource);
            stmt = conn.prepareStatement(getDirectUpdateSql());

            stmt.setLong(1, value);
            stmt.setString(2, keyName);
            stmt.setLong(3, value);
            stmt.setString(4, schemaName);

            int updateCount = stmt.executeUpdate();
            return updateCount >= 1;
        } finally {
            SequenceHelper.closeDbResources(null, stmt, conn);
        }
    }

    public void setDscount(int dscount) {
        this.dscount = dscount;
    }

    protected String getInsertSql() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("insert into ").append(getTableName()).append("(");
        buffer.append("schema_name").append(",");
        buffer.append(getNameColumnName()).append(",");
        buffer.append(getValueColumnName()).append(",");
        buffer.append(getGmtModifiedColumnName());
        buffer.append(") values(?,?,?,?);");
        return buffer.toString();
    }

    protected String getSelectSql() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("select ").append(getValueColumnName());
        buffer.append(" from ").append(getTableName());
        buffer.append(" where ").append(getNameColumnName()).append(" = ?");
        buffer.append(" and schema_name = ?");
        return buffer.toString();
    }

    protected String getUpdateSql() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("update ").append(getTableName());
        buffer.append(" set ").append(getValueColumnName()).append(" = ?, ");
        buffer.append(getGmtModifiedColumnName()).append(" = ? where ");
        buffer.append(getNameColumnName()).append(" = ? and ");
        buffer.append(getValueColumnName()).append(" = ?");
        buffer.append(" and schema_name = ?");
        return buffer.toString();
    }

    protected String getFetchSql(final int index) {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(getTableName());
        sb.append(" SET ").append(getValueColumnName()).append(" = IF((");
        sb.append(getValueColumnName()).append(" + ").append(outStep).append(") % ").append(outStep);
        sb.append(" != ").append(index).append(" * ").append(innerStep).append(", ");
        sb.append("LAST_INSERT_ID(");
        if (isAdjust()) {
            sb.append(getValueColumnName()).append(" + ").append(outStep).append(" - (");
            sb.append(getValueColumnName()).append(" + ").append(outStep).append(") % ");
            sb.append(outStep).append(" + ").append(outStep).append(" + ");
            sb.append(index).append(" * ").append(innerStep);
        } else {
            sb.append(0);
        }
        sb.append("), LAST_INSERT_ID(").append(getValueColumnName()).append(" + ").append(outStep).append(")), ");
        sb.append(getGmtModifiedColumnName()).append(" = NOW()");
        sb.append(" WHERE ").append(getNameColumnName()).append(" = ?");
        sb.append(" AND schema_name = ?");
        return sb.toString();
    }

    private String getDirectUpdateSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(getTableName());
        sb.append(" SET ").append(getValueColumnName()).append(" = ?, ");
        sb.append(getGmtModifiedColumnName()).append(" = NOW()");
        sb.append(" WHERE ").append(getNameColumnName()).append(" = ? AND ");
        sb.append(getValueColumnName()).append(" < ?");
        sb.append(" AND schema_name = ?");
        return sb.toString();
    }

    @Override
    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public int getInnerStep() {
        return innerStep;
    }

    public void setInnerStep(int innerStep) {
        this.innerStep = innerStep;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        tableName = TStringUtil.trim(tableName);
        this.tableName = tableName;
    }

    public String getNameColumnName() {
        return nameColumnName;
    }

    public void setNameColumnName(String nameColumnName) {
        this.nameColumnName = TStringUtil.trim(nameColumnName);
    }

    public String getValueColumnName() {
        return valueColumnName;
    }

    public void setValueColumnName(String valueColumnName) {
        this.valueColumnName = TStringUtil.trim(valueColumnName);
    }

    public String getGmtModifiedColumnName() {
        return gmtModifiedColumnName;
    }

    public void setGmtModifiedColumnName(String gmtModifiedColumnName) {
        this.gmtModifiedColumnName = TStringUtil.trim(gmtModifiedColumnName);
    }

    public void setAppName(String appName) {
        this.appName = TStringUtil.trim(appName);
    }

    public void setDbGroupKeys(List<String> dbGroupKeys) {

        if (!GeneralUtil.isEmpty(dbGroupKeys)) {
            List<String> dbGroupKeysTrimed = new ArrayList(dbGroupKeys.size());

            for (String groupKey : dbGroupKeys) {
                dbGroupKeysTrimed.add(TStringUtil.trim(groupKey));
            }
            dbGroupKeys = dbGroupKeysTrimed;
        }

        this.oriDbGroupKeys = dbGroupKeys;
        this.dbGroupKeys = dbGroupKeys;
    }

    public boolean isAdjust() {
        return adjust;
    }

    public void setAdjust(boolean adjust) {
        this.adjust = adjust;
    }

    public int getMaxSkipCount() {
        return maxSkipCount;
    }

    public void setMaxSkipCount(int maxSkipCount) {
        this.maxSkipCount = maxSkipCount;
    }

    public boolean isUseSlowProtect() {
        return useSlowProtect;
    }

    public void setUseSlowProtect(boolean useSlowProtect) {
        this.useSlowProtect = useSlowProtect;
    }

    public int getProtectMilliseconds() {
        return protectMilliseconds;
    }

    public void setProtectMilliseconds(int protectMilliseconds) {
        this.protectMilliseconds = protectMilliseconds;
    }

    public String getAppName() {
        return appName;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {

        this.unitName = TStringUtil.trim(unitName);
    }

    public void setWriteMode(String writeMode) {
        this.writeMode = writeMode;
    }

    public String getWriteMode() {
        return writeMode;
    }

    @Override
    public int getStep() {
        return innerStep;
    }

    public String getConfigStr() {
        return configStr;
    }

    public void setColdStartMode(boolean isColdStartMode) {
        this.isColdStartMode = isColdStartMode;
    }

    public boolean isOptimisticLockMode() {
        return optimisticLockMode;
    }

    public void setOptimisticLockMode(boolean optimisticLockMode) {
        this.optimisticLockMode = optimisticLockMode;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

}
