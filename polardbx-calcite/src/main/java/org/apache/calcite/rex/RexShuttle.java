/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rex;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlPostfixOperator;
import org.apache.calcite.sql.SqlPrefixOperator;
import org.apache.calcite.sql.fun.SqlCaseOperator;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.calcite.sql.fun.SqlLikeOperator;

/**
 * Passes over a row-expression, calling a handler method for each node,
 * appropriate to the type of the node.
 *
 * <p>Like {@link RexVisitor}, this is an instance of the
 * {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN Visitor Pattern}. Use
 * <code> RexShuttle</code> if you would like your methods to return a
 * value.</p>
 */
public class RexShuttle implements RexVisitor<RexNode> {
  //~ Methods ----------------------------------------------------------------

  public RexNode visitOver(RexOver over) {
    boolean[] update = {false};
    List<RexNode> clonedOperands = visitList(over.operands, update);
    RexWindow window = visitWindow(over.getWindow());
    if (update[0] || (window != over.getWindow())) {
      // REVIEW jvs 8-Mar-2005:  This doesn't take into account
      // the fact that a rewrite may have changed the result type.
      // To do that, we would need to take a RexBuilder and
      // watch out for special operators like CAST and NEW where
      // the type is embedded in the original call.
      return new RexOver(
          over.getType(),
          over.getAggOperator(),
          clonedOperands,
          window,
          over.isDistinct());
    } else {
      return over;
    }
  }

  public RexWindow visitWindow(RexWindow window) {
    boolean[] update = {false};
    List<RexFieldCollation> clonedOrderKeys =
        visitFieldCollations(window.orderKeys, update);
    List<RexNode> clonedPartitionKeys =
        visitList(window.partitionKeys, update);
    RexWindowBound lowerBound = window.getLowerBound().accept(this);
    RexWindowBound upperBound = window.getUpperBound().accept(this);
    if (update[0]
        || (lowerBound != window.getLowerBound() && lowerBound != null)
        || (upperBound != window.getUpperBound() && upperBound != null)) {
      return new RexWindow(
          clonedPartitionKeys,
          clonedOrderKeys,
          lowerBound,
          upperBound,
          window.isRows());
    } else {
      return window;
    }
  }

  public RexNode visitSubQuery(RexSubQuery subQuery) {
    boolean[] update = {false};
    List<RexNode> clonedOperands = visitList(subQuery.operands, update);
    if (update[0]) {
      return subQuery.clone(subQuery.getType(), clonedOperands);
    } else {
      return subQuery;
    }
  }

  @Override public RexNode visitTableInputRef(RexTableInputRef ref) {
    return ref;
  }

  @Override public RexNode visitPatternFieldRef(RexPatternFieldRef fieldRef) {
    return fieldRef;
  }

  public RexNode visitCall(final RexCall call) {
    boolean[] update = {false};
    List<RexNode> clonedOperands = visitList(call.operands, update);
    if (update[0]) {
      // REVIEW jvs 8-Mar-2005:  This doesn't take into account
      // the fact that a rewrite may have changed the result type.
      // To do that, we would need to take a RexBuilder and
      // watch out for special operators like CAST and NEW where
      // the type is embedded in the original call.
      return new RexCall(
          call.getType(),
          call.getOperator(),
          clonedOperands);
    } else {
      return call;
    }
  }

  /**
   * Visits each of an array of expressions and returns an array of the
   * results.
   *
   * @param exprs  Array of expressions
   * @param update If not null, sets this to true if any of the expressions
   *               was modified
   * @return Array of visited expressions
   */
  protected RexNode[] visitArray(RexNode[] exprs, boolean[] update) {
    RexNode[] clonedOperands = new RexNode[exprs.length];
    for (int i = 0; i < exprs.length; i++) {
      RexNode operand = exprs[i];
      RexNode clonedOperand = operand.accept(this);
      if ((clonedOperand != operand) && (update != null)) {
        update[0] = true;
      }
      clonedOperands[i] = clonedOperand;
    }
    return clonedOperands;
  }

  /**
   * Visits each of a list of expressions and returns a list of the
   * results.
   *
   * @param exprs  List of expressions
   * @param update If not null, sets this to true if any of the expressions
   *               was modified
   * @return Array of visited expressions
   */
  protected List<RexNode> visitList(
      List<? extends RexNode> exprs, boolean[] update) {
    ImmutableList.Builder<RexNode> clonedOperands = ImmutableList.builder();
    for (RexNode operand : exprs) {
      RexNode clonedOperand = operand.accept(this);
      if ((clonedOperand != operand) && (update != null)) {
        update[0] = true;
      }
      clonedOperands.add(clonedOperand);
    }
    return clonedOperands.build();
  }

  /**
   * Visits a list and writes the results to another list.
   */
  public void visitList(
      List<? extends RexNode> exprs, List<RexNode> outExprs) {
    for (RexNode expr : exprs) {
      outExprs.add(expr.accept(this));
    }
  }

  /**
   * Visits each of a list of field collations and returns a list of the
   * results.
   *
   * @param collations List of field collations
   * @param update     If not null, sets this to true if any of the expressions
   *                   was modified
   * @return Array of visited field collations
   */
  protected List<RexFieldCollation> visitFieldCollations(
      List<RexFieldCollation> collations, boolean[] update) {
    ImmutableList.Builder<RexFieldCollation> clonedOperands =
        ImmutableList.builder();
    for (RexFieldCollation collation : collations) {
      RexNode clonedOperand = collation.left.accept(this);
      if ((clonedOperand != collation.left) && (update != null)) {
        update[0] = true;
        collation =
            new RexFieldCollation(clonedOperand, collation.right);
      }
      clonedOperands.add(collation);
    }
    return clonedOperands.build();
  }

  public RexNode visitCorrelVariable(RexCorrelVariable variable) {
    return variable;
  }

  public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
    RexNode before = fieldAccess.getReferenceExpr();
    RexNode after = before.accept(this);

    if (before == after) {
      return fieldAccess;
    } else {
      return new RexFieldAccess(
          after,
          fieldAccess.getField());
    }
  }

  public RexNode visitInputRef(RexInputRef inputRef) {
    return inputRef;
  }

  public RexNode visitLocalRef(RexLocalRef localRef) {
    return localRef;
  }

  public RexNode visitLiteral(RexLiteral literal) {
    return literal;
  }

  public RexNode visitDynamicParam(RexDynamicParam dynamicParam) {
    return dynamicParam;
  }

  public RexNode visitRangeRef(RexRangeRef rangeRef) {
    return rangeRef;
  }

  @Override
  public RexNode visitSystemVar(RexSystemVar systemVar) {
    return systemVar;
  }

  @Override
  public RexNode visitUserVar(RexUserVar userVar) {
    return userVar;
  }

  /**
   * Applies this shuttle to each expression in a list.
   *
   * @return whether any of the expressions changed
   */
  public final <T extends RexNode> boolean mutate(List<T> exprList) {
    int changeCount = 0;
    for (int i = 0; i < exprList.size(); i++) {
      T expr = exprList.get(i);
      T expr2 = (T) apply(expr); // Avoid NPE if expr is null
      if (expr != expr2) {
        ++changeCount;
        exprList.set(i, expr2);
      }
    }
    return changeCount > 0;
  }

  /**
   * Applies this shuttle to each expression in a list and returns the
   * resulting list. Does not modify the initial list.
   */
  public final <T extends RexNode> List<T> apply(List<T> exprList) {
    if (exprList == null) {
      return null;
    }
    final List<T> list2 = new ArrayList<>(exprList);
    if (mutate(list2)) {
      return list2;
    } else {
      return exprList;
    }
  }

  /**
   * Applies this shuttle to each expression in an iterable.
   */
  public final Iterable<RexNode> apply(Iterable<? extends RexNode> iterable) {
    return Iterables.transform(iterable, new Function<RexNode, RexNode>() {
      public RexNode apply(@Nullable RexNode t) {
        return t == null ? null : t.accept(RexShuttle.this);
      }
    });
  }

    /**
     * Applies this shuttle to an expression, or returns null if the expression
     * is null.
     */
    public final RexNode apply(RexNode expr) {
        return (expr == null) ? null : expr.accept(this);
    }

    @Override
    public void visit(SqlBinaryOperator operator, RexCall call) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(SqlOperator operator, RexCall call) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(AggregateCall aggregateCall) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(SqlFunction operator, RexCall call) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(SqlPrefixOperator operator, RexCall call) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(SqlCaseOperator operator, RexCall call) {
        // TODO Auto-generated method stub

    }

  @Override
  public void visit(SqlPostfixOperator operator, RexCall call) {

  }

  @Override
  public void visit(SqlLikeOperator operator, RexCall call) {

  }
}

// End RexShuttle.java
