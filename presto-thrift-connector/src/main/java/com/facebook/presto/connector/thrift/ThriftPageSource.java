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
package com.facebook.presto.connector.thrift;

import com.facebook.presto.connector.thrift.api.PrestoThriftId;
import com.facebook.presto.connector.thrift.api.PrestoThriftNullableToken;
import com.facebook.presto.connector.thrift.api.PrestoThriftPageResult;
import com.facebook.presto.connector.thrift.api.PrestoThriftService;
import com.facebook.presto.connector.thrift.clientproviders.PrestoThriftServiceProvider;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.MoreFutures.toCompletableFuture;
import static java.util.Objects.requireNonNull;

public class ThriftPageSource
        implements ConnectorPageSource
{
    private final PrestoThriftId splitId;
    private final PrestoThriftService client;
    private final List<String> columnNames;
    private final List<Type> columnTypes;
    private final long maxBytesPerResponse;
    private final AtomicLong readTimeNanos = new AtomicLong(0);

    private PrestoThriftId nextToken;
    private boolean firstCall = true;
    private CompletableFuture<PrestoThriftPageResult> future;
    private long completedBytes;

    public ThriftPageSource(
            PrestoThriftServiceProvider clientProvider,
            ThriftConnectorSplit split,
            List<ColumnHandle> columns,
            long maxBytesPerResponse)
    {
        // init columns
        requireNonNull(columns, "columns is null");
        ImmutableList.Builder<String> columnNames = new ImmutableList.Builder<>();
        ImmutableList.Builder<Type> columnTypes = new ImmutableList.Builder<>();
        for (ColumnHandle columnHandle : columns) {
            ThriftColumnHandle thriftColumnHandle = (ThriftColumnHandle) columnHandle;
            columnNames.add(thriftColumnHandle.getColumnName());
            columnTypes.add(thriftColumnHandle.getColumnType());
        }
        this.columnNames = columnNames.build();
        this.columnTypes = columnTypes.build();

        // this parameter is read from config, so it should be checked by config validation
        // however, here it's a raw constructor parameter, so adding this safety check
        checkArgument(maxBytesPerResponse > 0, "maxBytesPerResponse is zero or negative");
        this.maxBytesPerResponse = maxBytesPerResponse;

        // init split
        requireNonNull(split, "split is null");
        this.splitId = split.getSplitId();

        // init client
        requireNonNull(clientProvider, "clientProvider is null");
        if (split.getAddresses().isEmpty()) {
            this.client = clientProvider.anyHostClient();
        }
        else {
            this.client = clientProvider.selectedHostClient(split.getAddresses());
        }
    }

    @Override
    public long getTotalBytes()
    {
        return 0;
    }

    @Override
    public long getCompletedBytes()
    {
        return completedBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return readTimeNanos.get();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return 0;
    }

    @Override
    public boolean isFinished()
    {
        return !firstCall && !canGetMoreData(nextToken);
    }

    @Override
    public Page getNextPage()
    {
        if (future == null) {
            // no data request in progress
            if (firstCall || canGetMoreData(nextToken)) {
                // no data in the current batch, but can request more; will send a request
                future = sendDataRequestInternal();
            }
            return null;
        }

        if (!future.isDone()) {
            // data request is in progress
            return null;
        }

        // response for data request is ready
        Page result = processBatch(getFutureValue(future));

        // immediately try sending a new request
        if (canGetMoreData(nextToken)) {
            future = sendDataRequestInternal();
        }
        else {
            future = null;
        }

        return result;
    }

    private static boolean canGetMoreData(PrestoThriftId nextToken)
    {
        return nextToken != null;
    }

    private CompletableFuture<PrestoThriftPageResult> sendDataRequestInternal()
    {
        long start = System.nanoTime();
        ListenableFuture<PrestoThriftPageResult> rowsBatchFuture = client.getRows(
                splitId,
                columnNames,
                maxBytesPerResponse,
                new PrestoThriftNullableToken(nextToken));
        rowsBatchFuture.addListener(() -> readTimeNanos.addAndGet(System.nanoTime() - start), directExecutor());
        return toCompletableFuture(nonCancellationPropagating(rowsBatchFuture));
    }

    private Page processBatch(PrestoThriftPageResult rowsBatch)
    {
        firstCall = false;
        nextToken = rowsBatch.getNextToken();
        Page page = rowsBatch.toPage(columnTypes);
        if (page != null) {
            completedBytes += page.getSizeInBytes();
        }
        return page;
    }

    @Override
    public CompletableFuture<?> isBlocked()
    {
        return future == null || future.isDone() ? NOT_BLOCKED : future;
    }

    @Override
    public void close()
            throws IOException
    {
        if (future != null) {
            future.cancel(true);
        }
        client.close();
    }
}
