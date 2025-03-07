/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.common.guava;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class FutureUtils
{
  /**
   * Waits for a given future and returns its value, like {@code future.get()}.
   *
   * On InterruptedException, cancels the provided future if {@code cancelIfInterrupted}, then re-throws the
   * original InterruptedException.
   *
   * Passes through CancellationExceptions and ExecutionExceptions as-is.
   */
  public static <T> T get(final ListenableFuture<T> future, final boolean cancelIfInterrupted)
      throws InterruptedException, ExecutionException
  {
    try {
      return future.get();
    }
    catch (InterruptedException e) {
      if (cancelIfInterrupted) {
        future.cancel(true);
      }

      throw e;
    }
  }

  /**
   * Waits for a given future and returns its value, like {@code future.get()}.
   *
   * On InterruptException, cancels the provided future if {@code cancelIfInterrupted}, and in either case, throws
   * a RuntimeException that wraps the original InterruptException.
   *
   * Passes through CancellationExceptions as-is.
   *
   * Re-wraps the causes of ExecutionExceptions using RuntimeException.
   */
  public static <T> T getUnchecked(final ListenableFuture<T> future, final boolean cancelIfInterrupted)
  {
    try {
      return FutureUtils.get(future, cancelIfInterrupted);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  /**
   * Like {@link Futures#transform}, but works better with lambdas due to not having overloads.
   *
   * One can write {@code FutureUtils.transform(future, v -> ...)} instead of
   * {@code Futures.transform(future, (Function<? super T, ?>) v -> ...)}
   */
  public static <T, R> ListenableFuture<R> transform(final ListenableFuture<T> future, final Function<T, R> fn)
  {
    return Futures.transform(future, fn::apply);
  }
}
