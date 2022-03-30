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

package com.alibaba.polardbx.executor.balancer.action;

import com.alibaba.polardbx.executor.ddl.job.task.shared.EmptyTask;
import com.alibaba.polardbx.executor.ddl.newengine.job.ExecutableDdlJob;
import com.alibaba.polardbx.executor.scaleout.ScaleOutUtils;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Action that move multiple groups concurrently
 *
 * @author moyi
 * @since 2021/10
 */
public class ActionMoveGroups implements BalanceAction, Comparable<ActionMoveGroups> {

    private final String schema;
    private final List<ActionMoveGroup> actions;

    public ActionMoveGroups(String schema, List<ActionMoveGroup> actions) {
        this.schema = schema;
        this.actions = actions;
    }

    @Override
    public String getSchema() {
        return schema;
    }

    @Override
    public String getName() {
        return "MoveGroups";
    }

    @Override
    public String getStep() {
        return StringUtils.join(this.actions, ",");
    }

    /*
     * Convert it to concurrent move-database jobs like:
     *
     * Empty
     * /   \
     * MoveDatabase | MoveDatabase | MoveDatabase
     * \                   /           /
     *  Empty
     *
     * @param ec
     * @return
     */
    @Override
    public ExecutableDdlJob toDdlJob(ExecutionContext ec) {
        ExecutableDdlJob job = new ExecutableDdlJob();
        job.setMaxParallelism(ScaleOutUtils.getScaleoutTaskParallelism(ec));
        //todo guxu refactor this
        EmptyTask head = new EmptyTask(schema);
        job.addTask(head);
        job.labelAsHead(head);
        EmptyTask tail = new EmptyTask(schema);
        job.addTask(tail);
        job.labelAsTail(tail);

        for (ActionMoveGroup move : actions) {
            ExecutableDdlJob subJob = move.toDdlJob(ec);
            job.appendJobAfter(head, subJob);
            job.addTaskRelationship(subJob.getTail(), tail);
        }

        return job;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ActionMoveGroups)) {
            return false;
        }
        ActionMoveGroups that = (ActionMoveGroups) o;
        return Objects.equals(schema, that.schema) && Objects.equals(actions, that.actions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, actions);
    }

    @Override
    public String toString() {
        return "ActionMoveGroups{" +
            "schema='" + schema + '\'' +
            ", actions=" + actions +
            '}';
    }

    public List<ActionMoveGroup> getActions() {
        return actions;
    }

    @Override
    public int compareTo(ActionMoveGroups o) {
        for (int i = 0; i < Math.min(actions.size(), o.actions.size()); i++) {
            int res = actions.get(i).compareTo(o.actions.get(i));
            if (res != 0) {
                return res;
            }
        }
        return Integer.compare(actions.size(), o.actions.size());
    }
}
