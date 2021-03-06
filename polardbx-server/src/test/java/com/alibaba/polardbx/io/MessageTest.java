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

package com.alibaba.polardbx.io;

import com.alibaba.polardbx.net.util.MySQLMessage;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author xianmao.hexm
 */
public class MessageTest {

    @Test
    public void testReadBytesWithNull() {
        byte[] bytes = new byte[] {1, 2, 3, 0, 5};
        MySQLMessage message = new MySQLMessage(bytes);
        byte[] ab = message.readBytesWithNull();
        Assert.assertEquals(3, ab.length);
        Assert.assertEquals(4, message.position());
    }

    @Test
    public void testReadBytesWithNull2() {
        byte[] bytes = new byte[] {0, 1, 2, 3, 0, 5};
        MySQLMessage message = new MySQLMessage(bytes);
        byte[] ab = message.readBytesWithNull();
        Assert.assertEquals(0, ab.length);
        Assert.assertEquals(1, message.position());
    }

    @Test
    public void testReadBytesWithNull3() {
        byte[] bytes = new byte[] {};
        MySQLMessage message = new MySQLMessage(bytes);
        byte[] ab = message.readBytesWithNull();
        Assert.assertEquals(0, ab.length);
        Assert.assertEquals(0, message.position());
    }

}
