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
package io.trino.plugin.deltalake.transactionlog;

import io.trino.plugin.deltalake.transactionlog.checkpoint.TransactionLogEntries;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.airlift.slice.SizeOf.instanceSize;
import static java.util.Objects.requireNonNull;

public record Transaction(long transactionId, TransactionLogEntries transactionEntries)
{
    private static final int INSTANCE_SIZE = instanceSize(Transaction.class);

    public Transaction
    {
        checkArgument(transactionId >= 0, "transactionId must be >= 0");
        requireNonNull(transactionEntries, "transactionEntries is null");
    }

    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + SIZE_OF_LONG
                + transactionEntries.getRetainedSizeInBytes();
    }
}
