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

package com.alibaba.polardbx.group.config;

import com.alibaba.polardbx.atom.TAtomDataSource;
import com.alibaba.polardbx.common.jdbc.MasterSlave;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.common.utils.Pair;
import com.alibaba.polardbx.gms.node.StorageStatus;
import com.alibaba.polardbx.gms.node.StorageStatusManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MasterSlaveGroupDataSourceHolder implements GroupDataSourceHolder {

    private final TAtomDataSource masterDataSource;
    private final List<TAtomDataSource> slaveDataSources;
    private final List<String> slaveStorageIds;
    private Random random;

    public MasterSlaveGroupDataSourceHolder(
        TAtomDataSource masterDataSource, List<TAtomDataSource> slaveDataSources, List<String> slaveStorageIds) {
        this.masterDataSource = masterDataSource;
        this.slaveDataSources = slaveDataSources;
        this.slaveStorageIds = slaveStorageIds;
        this.random = new Random(System.currentTimeMillis());
    }

    @Override
    public TAtomDataSource getDataSource(MasterSlave masterSlave) {
        switch (masterSlave) {
        case MASTER_ONLY:
        case READ_WEIGHT:
            return masterDataSource;
        case SLAVE_FIRST:
            if (GeneralUtil.isEmpty(slaveDataSources)) {
                return masterDataSource;
            }
            return selectLowDelaySlaveDataSource(true);
        case SLAVE_ONLY:
            //FIXME ?????????????????????????????????????????????(???????????????????????????DN?????????????????????)
            if (slaveDataSources.size() == 1) {
                return slaveDataSources.get(0);
            }
            return selectSlaveDataSource();
        case LOW_DELAY_SLAVE_ONLY:
            return selectLowDelaySlaveDataSource(false);
        }
        return masterDataSource;
    }

    private TAtomDataSource selectSlaveDataSource() {
        Map<String, StorageStatus> statusMap = StorageStatusManager.getInstance().getAllowReadLearnerStorageMap();
        int startIndex = random.nextInt(slaveDataSources.size());
        //?????????????????????????????????????????????????????????????????????
        for (int i = 0; i < slaveStorageIds.size(); i++) {
            String id = slaveStorageIds.get(startIndex);
            StorageStatus storageStatus = statusMap.get(id);
            if (storageStatus != null && (!storageStatus.isBusy() && !storageStatus.isDelay())) {
                return slaveDataSources.get(startIndex);
            }
            startIndex++;
            if (startIndex >= slaveStorageIds.size()) {
                startIndex = 0;
            }
        }

        //????????????????????????????????????????????????DN??????
        for (int i = 0; i < slaveStorageIds.size(); i++) {
            String id = slaveStorageIds.get(startIndex);
            StorageStatus storageStatus = statusMap.get(id);
            if (storageStatus != null) {
                return slaveDataSources.get(startIndex);
            }
            startIndex++;
            if (startIndex >= slaveStorageIds.size()) {
                startIndex = 0;
            }
        }

        //FIXME ????????????????????????????????????????????????(???????????????????????????DN?????????????????????)
        return slaveDataSources.get(startIndex);
    }

    private TAtomDataSource selectLowDelaySlaveDataSource(boolean forceMaster) {
        Map<String, StorageStatus> statusMap = StorageStatusManager.getInstance().getAllowReadLearnerStorageMap();

        int startIndex = random.nextInt(slaveDataSources.size());
        List<Pair<String, Integer>> lowDelayIds = new ArrayList<>();
        //????????????????????????
        for (int i = 0; i < slaveStorageIds.size(); i++) {
            String id = slaveStorageIds.get(startIndex);
            StorageStatus storageStatus = statusMap.get(id);
            if (storageStatus != null && !storageStatus.isDelay()) {
                lowDelayIds.add(new Pair<>(id, startIndex));
            }
            startIndex++;
            if (startIndex >= slaveStorageIds.size()) {
                startIndex = 0;
            }
        }
        if (lowDelayIds.isEmpty()) {
            if (forceMaster) {
                //????????????????????????????????????????????????
                return masterDataSource;
            } else {
                throw new RuntimeException("all slave is delay, so can't continue use slave connection!");
            }

        } else {
            //???????????????????????????????????????????????????????????????
            for (Pair<String, Integer> pair : lowDelayIds) {
                StorageStatus storageStatus = statusMap.get(pair.getKey());
                if (storageStatus != null && !storageStatus.isBusy()) {
                    return slaveDataSources.get(pair.getValue());
                }
            }
            //???????????????????????????????????????????????????
            return slaveDataSources.get(lowDelayIds.get(0).getValue());
        }
    }
}
