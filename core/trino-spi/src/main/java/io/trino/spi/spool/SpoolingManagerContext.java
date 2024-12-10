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
package io.trino.spi.spool;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.trino.spi.Experimental;

@Experimental(eta = "2025-05-31")
public interface SpoolingManagerContext
{
    default OpenTelemetry getOpenTelemetry()
    {
        throw new UnsupportedOperationException();
    }

    default Tracer getTracer()
    {
        throw new UnsupportedOperationException();
    }

    default boolean isCoordinator()
    {
        throw new UnsupportedOperationException();
    }

    default String getNodeVersion()
    {
        throw new UnsupportedOperationException();
    }
}
