/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.Address;
import okhttp3.Route;
import okhttp3.internal.Util;
import okhttp3.internal.connection.Transmitter.TransmitterReference;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

public final class RealConnectionPool {
  /**
   * Background threads are used to cleanup expired connections. There will be at most a single
   * thread running per connection pool. The thread pool executor permits the pool itself to be
   * garbage collected.
   */
  private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
      Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new SynchronousQueue<>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** The maximum number of idle connections for each address. */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;// 这是一个final类型的变量，只在初始化连接池的时候进行初始化一次，默认是5分钟
  private final Runnable cleanupRunnable = () -> {
    while (true) {
      long waitNanos = cleanup(System.nanoTime());
      if (waitNanos == -1) return;
      if (waitNanos > 0) {
        long waitMillis = waitNanos / 1000000L;
        waitNanos -= (waitMillis * 1000000L);
        synchronized (RealConnectionPool.this) {
          try {
              // 下一次清理之前的等待
            RealConnectionPool.this.wait(waitMillis, (int) waitNanos);
          } catch (InterruptedException ignored) {
          }
        }
      }
    }
  };

  private final Deque<RealConnection> connections = new ArrayDeque<>();
  final RouteDatabase routeDatabase = new RouteDatabase();
  boolean cleanupRunning;

  public RealConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    if (keepAliveDuration <= 0) {
      throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
    }
  }

  public synchronized int idleConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.transmitters.isEmpty()) total++;
    }
    return total;
  }

  public synchronized int connectionCount() {
    return connections.size();
  }

  /**
   * 试图得到一个被回收的连接，如果有被回收的连接，则返回true
   * Attempts to acquire a recycled connection to {@code address} for {@code transmitter}. Returns
   * true if a connection was acquired.
   *
   * <p>If {@code routes} is non-null these are the resolved routes (ie. IP addresses) for the
   * connection. This is used to coalesce related domains to the same HTTP/2 connection, such as
   * {@code square.com} and {@code square.ca}.
   */
  boolean transmitterAcquirePooledConnection(Address address, Transmitter transmitter,
      @Nullable List<Route> routes, boolean requireMultiplexed) {
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      // 多路复用，即是否是http2.0
      if (requireMultiplexed && !connection.isMultiplexed()) continue;
      // 连接是否可以承载指定地址的数据流
      if (!connection.isEligible(address, routes)) continue;
      // 从连接池中获取一个可用的连接，把可用的连接设置给发射器
      transmitter.acquireConnectionNoEvents(connection);
      return true;
    }
    return false;
  }

  void put(RealConnection connection) {
    assert (Thread.holdsLock(this));
      // 看到在add之前 使用线程池executor执行了cleanupRunnable，意思是清理连接，为啥要清理呢？
      // 连接池有 最大空闲连接数、最大空闲时间的限制，所以不满足时是要进行清理的
    if (!cleanupRunning) {
      cleanupRunning = true;
      executor.execute(cleanupRunnable);
    }
    connections.add(connection);
  }

  /**
   * Notify this pool that {@code connection} has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  boolean connectionBecameIdle(RealConnection connection) {
    assert (Thread.holdsLock(this));
    if (connection.noNewExchanges || maxIdleConnections == 0) {
      connections.remove(connection);
      return true;
    } else {
      notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
      return false;
    }
  }

  public void evictAll() {
    List<RealConnection> evictedConnections = new ArrayList<>();
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();
        if (connection.transmitters.isEmpty()) {
          connection.noNewExchanges = true;
          evictedConnections.add(connection);
          i.remove();
        }
      }
    }

    for (RealConnection connection : evictedConnections) {
      closeQuietly(connection.socket());
    }
  }

  /**
   * 找到闲置时间最长的连接，并清理掉，即每次清理最多只会清理掉一个连接。
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
   * -1 if no further cleanups are required.
   */
  long cleanup(long now) {
    int inUseConnectionCount = 0;//正在使用的连接数
    int idleConnectionCount = 0;//空闲连接数
    RealConnection longestIdleConnection = null;//空闲时间最长的连接
    long longestIdleDurationNs = Long.MIN_VALUE;// 连接池中，连接闲置的最长时间

    // 遍历连接：找到待清理的连接, 找到下一次要清理的时间（还未到最大空闲时间）
    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();

        // 若连接正在使用，continue，正在使用连接数+1
        // If the connection is in use, keep searching.
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++;
          continue;
        }
        // 空闲连接数+1
        idleConnectionCount++;
        // 如果连接可以被清理
        // If the connection is ready to be evicted, we're done.
        long idleDurationNs = now - connection.idleAtNanos;
        // 如果连接的闲置时间，超过最大的闲置时间，则必须被清理
        if (idleDurationNs > longestIdleDurationNs) {
          longestIdleDurationNs = idleDurationNs;//
          longestIdleConnection = connection;// 找到闲置时间最长的连接
        }
      }// for 循环结束//

      // 若连接的闲置时间大于5分钟 或 空闲连接数 大于5，就移除并关闭这个连接
      if (longestIdleDurationNs >= this.keepAliveDurationNs
          || idleConnectionCount > this.maxIdleConnections) {
        // We've found a connection to evict. Remove it from the list, then close it below (outside
        // of the synchronized block).
        connections.remove(longestIdleConnection);// 从集合中移除连接
      } else if (idleConnectionCount > 0) {
        // A connection will be ready to evict soon.
        // 下次清理连接的等待时间，还剩多久到达5分钟，然后wait这个时间再来清理
        return keepAliveDurationNs - longestIdleDurationNs;
      } else if (inUseConnectionCount > 0) {
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        // 如果没有空闲连接，就5分钟后再尝试清理.
        return keepAliveDurationNs;
      } else {
        // No connections, idle or in use.
        // 连接池中没有连接，则不清理
        cleanupRunning = false;
        return -1;
      }
    }

    closeQuietly(longestIdleConnection.socket());

    // Cleanup again immediately.
    return 0;
  }

  /**
   * 砍掉泄漏的发射器并且返回依然存活的发射器的数量，一个连接，可以对应多个发射器。
   * 如果一个连接追踪他们但应用程序已经抛弃了，则这个发射器是泄漏的。
   * 泄漏探测是不精确的且依赖与垃圾回收
   * Prunes any leaked transmitters and then returns the number of remaining live transmitters on
   * {@code connection}. Transmitters are leaked if the connection is tracking them but the
   * application code has abandoned them. Leak detection is imprecise and relies on garbage
   * collection.
   */
  private int pruneAndGetAllocationCount(RealConnection connection, long now) {
    List<Reference<Transmitter>> references = connection.transmitters;
    for (int i = 0; i < references.size(); ) {
      Reference<Transmitter> reference = references.get(i);

      if (reference.get() != null) {// 弱引用的对象还存在，说明没有被回收
        i++;
        continue;
      }

      // We've discovered a leaked transmitter. This is an application bug.
      TransmitterReference transmitterRef = (TransmitterReference) reference;
      // 连接泄漏，由于Response的body对象没有关闭
      String message = "A connection to " + connection.route().address().url()
          + " was leaked. Did you forget to close a response body?";
      Platform.get().logCloseableLeak(message, transmitterRef.callStackTrace);

      references.remove(i);
      connection.noNewExchanges = true;// 没有新的交换机了，则表示连接不可用

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNanos = now - keepAliveDurationNs;
        return 0;
      }
    }
    // transmitters size大于1即表示多个请求复用此连接，说明次连接还在使用中
    return references.size();
  }

  /** Track a bad route in the route database. Other routes will be attempted first. */
  public void connectFailed(Route failedRoute, IOException failure) {
    // Tell the proxy selector when we fail to connect on a fresh connection.
    if (failedRoute.proxy().type() != Proxy.Type.DIRECT) {
      Address address = failedRoute.address();
      address.proxySelector().connectFailed(
          address.url().uri(), failedRoute.proxy().address(), failure);
    }

    routeDatabase.failed(failedRoute);
  }
}
