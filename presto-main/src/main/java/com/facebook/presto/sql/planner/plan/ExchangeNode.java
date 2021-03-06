/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.plan;

import com.facebook.presto.sql.planner.PartitionFunctionBinding;
import com.facebook.presto.sql.planner.Symbol;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_BROADCAST_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Immutable
public class ExchangeNode
        extends PlanNode
{
    public enum Type
    {
        GATHER,
        REPARTITION,
        REPLICATE
    }

    private final Type type;

    private final List<PlanNode> sources;

    private final PartitionFunctionBinding partitionFunction;

    // for each source, the list of inputs corresponding to each output
    private final List<List<Symbol>> inputs;

    @JsonCreator
    public ExchangeNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("type") Type type,
            @JsonProperty("partitionFunction") PartitionFunctionBinding partitionFunction,
            @JsonProperty("sources") List<PlanNode> sources,
            @JsonProperty("inputs") List<List<Symbol>> inputs)
    {
        super(id);

        requireNonNull(type, "type is null");
        requireNonNull(sources, "sources is null");
        requireNonNull(partitionFunction, "partitionFunction is null");
        requireNonNull(inputs, "inputs is null");

        checkArgument(inputs.stream().allMatch(inputSymbols -> inputSymbols.size() == partitionFunction.getOutputLayout().size()), "Input symbols do not match output symbols");
        checkArgument(inputs.size() == sources.size(), "Must have same number of input lists as sources");
        for (int i = 0; i < inputs.size(); i++) {
            checkArgument(sources.get(i).getOutputSymbols().containsAll(inputs.get(i)), "Source does not supply all required input symbols");
        }

        this.type = type;
        this.sources = sources;
        this.partitionFunction = partitionFunction;
        this.inputs = ImmutableList.copyOf(inputs);
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, PlanNode child, List<Symbol> partitioningColumns, Optional<Symbol> hashColumns)
    {
        return partitionedExchange(id, child, new PartitionFunctionBinding(FIXED_HASH_DISTRIBUTION, child.getOutputSymbols(), partitioningColumns, hashColumns));
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, PlanNode child, PartitionFunctionBinding partitionFunction)
    {
        return new ExchangeNode(
                id,
                ExchangeNode.Type.REPARTITION,
                partitionFunction,
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()));
    }

    public static ExchangeNode replicatedExchange(PlanNodeId id, PlanNode child)
    {
        return new ExchangeNode(
                id,
                ExchangeNode.Type.REPLICATE,
                new PartitionFunctionBinding(FIXED_BROADCAST_DISTRIBUTION, child.getOutputSymbols(), ImmutableList.of()),
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()));
    }

    public static ExchangeNode gatheringExchange(PlanNodeId id, PlanNode child)
    {
        return new ExchangeNode(
                id,
                ExchangeNode.Type.GATHER,
                new PartitionFunctionBinding(SINGLE_DISTRIBUTION, child.getOutputSymbols(), ImmutableList.of()),
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()));
    }

    public static ExchangeNode gatheringExchange(PlanNodeId id, List<Symbol> outputLayout, List<PlanNode> children)
    {
        return new ExchangeNode(
                id,
                ExchangeNode.Type.GATHER,
                new PartitionFunctionBinding(SINGLE_DISTRIBUTION, outputLayout, ImmutableList.of()),
                children,
                children.stream()
                        .map(PlanNode::getOutputSymbols)
                        .collect(toImmutableList()));
    }

    @JsonProperty
    public Type getType()
    {
        return type;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return sources;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return partitionFunction.getOutputLayout();
    }

    @JsonProperty
    public PartitionFunctionBinding getPartitionFunction()
    {
        return partitionFunction;
    }

    @JsonProperty
    public List<List<Symbol>> getInputs()
    {
        return inputs;
    }

    @Override
    public <C, R> R accept(PlanVisitor<C, R> visitor, C context)
    {
        return visitor.visitExchange(this, context);
    }
}
