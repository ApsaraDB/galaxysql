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

package com.alibaba.polardbx.manager.response;

import com.alibaba.polardbx.CobarServer;
import com.alibaba.polardbx.manager.ManagerConnection;
import com.alibaba.polardbx.net.FrontendConnection;
import com.alibaba.polardbx.net.NIOConnection;
import com.alibaba.polardbx.net.NIOProcessor;
import com.alibaba.polardbx.net.compress.PacketOutputProxyFactory;
import com.alibaba.polardbx.net.packet.OkPacket;
import com.alibaba.polardbx.server.util.StringUtil;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xianmao.hexm 2011-5-18 下午05:59:02
 */
public final class KillConnection {

    private static final Logger logger = LoggerFactory.getLogger(KillConnection.class);

    public static void response(String stmt, int offset, ManagerConnection mc) {
        int count = 0;
        List<FrontendConnection> list = getList(stmt, offset, mc);
        if (list != null) {
            for (NIOConnection c : list) {
                StringBuilder s = new StringBuilder();
                logger.warn(s.append(c).append("killed by manager").toString());

                c.close();
                count++;
            }
        }
        OkPacket packet = new OkPacket();
        packet.packetId = 1;
        packet.affectedRows = count;
        packet.serverStatus = 2;
        packet.write(PacketOutputProxyFactory.getInstance().createProxy(mc));
    }

    private static List<FrontendConnection> getList(String stmt, int offset, ManagerConnection mc) {
        String ids = stmt.substring(offset).trim();
        if (ids.length() > 0) {
            String[] idList = StringUtil.split(ids, ',', true);
            List<FrontendConnection> fcList = new ArrayList<FrontendConnection>(idList.length);
            NIOProcessor[] processors = CobarServer.getInstance().getProcessors();
            for (String id : idList) {
                long value = 0;
                try {
                    value = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                FrontendConnection fc = null;
                for (NIOProcessor p : processors) {
                    if ((fc = p.getFrontends().get(value)) != null) {
                        fcList.add(fc);
                        break;
                    }
                }
            }
            return fcList;
        }
        return null;
    }

}
