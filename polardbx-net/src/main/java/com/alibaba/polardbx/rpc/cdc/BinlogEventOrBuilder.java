/*
 *
 * Copyright (c) 2013-2021, Alibaba Group Holding Limited;
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
 *
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: DumperServer.proto

package com.alibaba.polardbx.rpc.cdc;

public interface BinlogEventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:dumper.BinlogEvent)
    com.google.protobuf.MessageOrBuilder {

    /**
     * <code>string logName = 1;</code>
     *
     * @return The logName.
     */
    java.lang.String getLogName();

    /**
     * <code>string logName = 1;</code>
     *
     * @return The bytes for logName.
     */
    com.google.protobuf.ByteString
    getLogNameBytes();

    /**
     * <code>int64 pos = 2;</code>
     *
     * @return The pos.
     */
    long getPos();

    /**
     * <code>string eventType = 3;</code>
     *
     * @return The eventType.
     */
    java.lang.String getEventType();

    /**
     * <code>string eventType = 3;</code>
     *
     * @return The bytes for eventType.
     */
    com.google.protobuf.ByteString
    getEventTypeBytes();

    /**
     * <code>int64 serverId = 4;</code>
     *
     * @return The serverId.
     */
    long getServerId();

    /**
     * <code>int64 endLogPos = 5;</code>
     *
     * @return The endLogPos.
     */
    long getEndLogPos();

    /**
     * <code>string info = 6;</code>
     *
     * @return The info.
     */
    java.lang.String getInfo();

    /**
     * <code>string info = 6;</code>
     *
     * @return The bytes for info.
     */
    com.google.protobuf.ByteString
    getInfoBytes();
}
