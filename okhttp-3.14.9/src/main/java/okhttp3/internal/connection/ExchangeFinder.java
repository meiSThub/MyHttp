/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Util;
import okhttp3.internal.http.ExchangeCodec;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Attempts to find the connections for a sequence of exchanges. This uses the following strategies:
 *
 * <ol>
 *   <li>If the current call already has a connection that can satisfy the request it is used.
 *       Using the same connection for an initial exchange and its follow-ups may improve locality.
 *
 *   <li>If there is a connection in the pool that can satisfy the request it is used. Note that
 *       it is possible for shared exchanges to make requests to different host names! See {@link
 *       RealConnection#isEligible} for details.
 *
 *   <li>If there's no existing connection, make a list of routes (which may require blocking DNS
 *       lookups) and attempt a new connection them. When failures occur, retries iterate the list
 *       of available routes.
 * </ol>
 *
 * <p>If the pool gains an eligible connection while DNS, TCP, or TLS work is in flight, this finder
 * will prefer pooled connections. Only pooled HTTP/2 connections are used for such de-duplication.
 *
 * <p>It is possible to cancel the finding process.
 */
final class ExchangeFinder {
  private final Transmitter transmitter;
  private final Address address;
  private final RealConnectionPool connectionPool;
  private final Call call;
  private final EventListener eventListener;

  private RouteSelector.Selection routeSelection;

  // State guarded by connectionPool.
  private final RouteSelector routeSelector;
  private RealConnection connectingConnection;
  private boolean hasStreamFailure;
  private Route nextRouteToTry;

  ExchangeFinder(Transmitter transmitter, RealConnectionPool connectionPool,
      Address address, Call call, EventListener eventListener) {
    this.transmitter = transmitter; //
    this.connectionPool = connectionPool;
    this.address = address;
    this.call = call;
    this.eventListener = eventListener;
    this.routeSelector = new RouteSelector(
        address, connectionPool.routeDatabase, call, eventListener);
  }

  public ExchangeCodec find(
      OkHttpClient client, Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
    int connectTimeout = chain.connectTimeoutMillis();// 连接超时时间
    int readTimeout = chain.readTimeoutMillis();// 读超时时间
    int writeTimeout = chain.writeTimeoutMillis();// 写超时时间
    int pingIntervalMillis = client.pingIntervalMillis();// ping超时时间
    boolean connectionRetryEnabled = client.retryOnConnectionFailure();// 连接失败，是否重试

    try {
        // 找到一个健康的连接
      RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
          writeTimeout, pingIntervalMillis, connectionRetryEnabled, doExtensiveHealthChecks);
        //利用连接实例化ExchangeCodec对象，如果是HTTP/2返回Http2ExchangeCodec，否则返回Http1ExchangeCodec
      return resultConnection.newCodec(client, chain);
    } catch (RouteException e) {
      trackFailure();
      throw e;
    } catch (IOException e) {
      trackFailure();
      throw new RouteException(e);
    }
  }

  /**
   * 查找有效的，健康的连接。如果没有找到健康的连接，则重复该过程，直到找到一个健康的连接
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   */
  private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
      int writeTimeout, int pingIntervalMillis, boolean connectionRetryEnabled,
      boolean doExtensiveHealthChecks) throws IOException {
    while (true) {// 循环查找，直到找到一个健康的连接
      RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
          pingIntervalMillis, connectionRetryEnabled);

      // If this is a brand new connection, we can skip the extensive health checks.
      synchronized (connectionPool) {
        if (candidate.successCount == 0 && !candidate.isMultiplexed()) {
          return candidate;// 如果是新创建的连接，则直接返回
        }
      }

      // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
      // isn't, take it out of the pool and start again.
      if (!candidate.isHealthy(doExtensiveHealthChecks)) {
        //  标记连接不可用
        candidate.noNewExchanges();
        continue;// 如果连接不可用，则继续循环查找下一个可用的连接
      }

      return candidate;
    }
  }

  /**
   * 为承载新的数据流 寻找 连接。如果现有连接存在，则优先使用该连接，否则使用连接池，如果都没有，则创建一个新的连接
   * 寻找顺序是：已分配的连接、连接池、新建连接
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      int pingIntervalMillis, boolean connectionRetryEnabled) throws IOException {
    boolean foundPooledConnection = false;// 找到连接池连接
    RealConnection result = null;// 找到的连接
    Route selectedRoute = null;
    RealConnection releasedConnection;// 被释放掉的连接
    Socket toClose;
    synchronized (connectionPool) {// 锁住连接池
        // 发射机被取消了
      if (transmitter.isCanceled()) throw new IOException("Canceled");
      hasStreamFailure = false; // This is a fresh attempt.

      // 一、寻找已分配的连接是否可用
      // 1.试图使用一个已经分配的连接。这里我们需要格外小心，因为已经分配的连接可能是受限制的。
      // 尝试使用 已给数据流分配的连接.（例如重定向请求时，可以复用上次请求的连接）
      // Attempt to use an already-allocated connection. We need to be careful here because our
      // already-allocated connection may have been restricted from creating new exchanges.
      releasedConnection = transmitter.connection;
      // 2.有已分配的连接，但已经被限制承载新的数据流，就尝试释放掉（如果连接上已没有数据流），并返回待关闭的socket。
      toClose = transmitter.connection != null && transmitter.connection.noNewExchanges
          ? transmitter.releaseConnectionNoEvents() // 释放连接
          : null;

      if (transmitter.connection != null) {
        // We had an already-allocated connection and it's good.
        // 3.找到一个正在使用的连接(即已分配的连接)
        // 说明上面没有释放掉，那么此连接可用
        result = transmitter.connection;
        releasedConnection = null;
      }
      // 二、从连接池中寻找可用的连接
      if (result == null) {
        // 1. 第一次尝试从缓冲池里面获取RealConnection(Socket的包装类)
        // Attempt to get a connection from the pool.
        if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, null, false)) {
          foundPooledConnection = true;// 从连接池找到可用连接
          result = transmitter.connection;// 可用的连接
        } else if (nextRouteToTry != null) {
          // 2.如果缓冲池中没有，则看看有没有下一个Route可以尝试，这里只有重试的情况会走进来
          selectedRoute = nextRouteToTry;
          nextRouteToTry = null;
        } else if (retryCurrentRoute()) {
          // 3.如果已经设置了使用当前Route重试，那么会继续使用当前的Route
          selectedRoute = transmitter.connection.route();
        }
      }
    }
    // 关闭Socket
    closeQuietly(toClose);

    if (releasedConnection != null) {// 被释放的连接不为空
        // 回调，连接被释放
      eventListener.connectionReleased(call, releasedConnection);
    }
    if (foundPooledConnection) {// 从连接池找到可用连接
      // 获得连接回调
      eventListener.connectionAcquired(call, result);
    }
    // 4.如果前面发现ConnectionPool或者Transmitter中有可以复用的Connection，这里就直接返回了
    if (result != null) {// 找到可用连接
      // 如果有已分配可用连接 或 从连接池获取到连接，结束！  没有 就走下面的新建连接过程。
      // If we found an already-allocated or pooled connection, we're done.
      return result;
    }
    // 三、创建一个新的连接，如果没有已分配的连接或者从连接池中也没有找到可以复用的连接时
    // 1.如果前面没有获取到连接，这里就需要通过routeSelector来获取到新的Route来进行Connection的建立
    // If we need a route selection, make one. This is a blocking operation.
    boolean newRouteSelection = false;// 新的路由选择器
    if (selectedRoute == null && (routeSelection == null || !routeSelection.hasNext())) {
      newRouteSelection = true;
      // 这里会根据域名，执行DNS的解析，获取真实的IP地址
      // 2.获取route的过程其实就是DNS获取到域名IP的过程，这是一个阻塞的过程，会等待DNS结果返回
      routeSelection = routeSelector.next();
    }

    List<Route> routes = null;
    synchronized (connectionPool) {
      if (transmitter.isCanceled()) throw new IOException("Canceled");

      if (newRouteSelection) {
        // 3.前面如果通过routeSelector拿到新的Route，其实就是相当于拿到一批新的IP，这里会再次尝试
        // 从ConnectionPool中检查是否有可以复用的Connection
        // Now that we have a set of IP addresses, make another attempt at getting a connection from
        // the pool. This could match due to connection coalescing.
        routes = routeSelection.getAll();
        if (connectionPool.transmitterAcquirePooledConnection(
            address, transmitter, routes, false)) {
          foundPooledConnection = true;
          result = transmitter.connection;
        }
      }

      // 如果第二次从连接池中没有找到一个可用的连接，则创建一个连接
      if (!foundPooledConnection) {
        // 4.前面我们拿到的是一批IP，这里通过routeSelection获取到其中一个IP，Route是proxy和InetAddress的包装类
        if (selectedRoute == null) {// 选择的路由
          selectedRoute = routeSelection.next();
        }
        // 5.创建一个连接：用新的route创建RealConnection，注意这里还没有尝试连接.
        // Create a connection and assign it to this allocation immediately. This makes it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        result = new RealConnection(connectionPool, selectedRoute);
        connectingConnection = result;
      }
    }
    // 6.如果第二次从connectionPool获取到Connection可以直接返回了，因为连接池中的连接是已经和服务器建立连接的
    // If we found a pooled connection on the 2nd time around, we're done.
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
      return result;
    }

    // 7.只有新创建的连接，才会执行握手操作，如果是复用的连接，则不用重复握手。
    // 执行连接：即TCP和TLS握手连接，域名校验，证书校验等。这是一个阻塞操作。在这个过程中进行证书校验。
    // Do TCP + TLS handshakes. This is a blocking operation.
    result.connect(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis,
        connectionRetryEnabled, call, eventListener);

    connectionPool.routeDatabase.connected(result.route());

    Socket socket = null;
    synchronized (connectionPool) {
      connectingConnection = null;
      // 8.最后一次尝试从连接池获取，注意最后一个参数为true，即要求 多路复用（http2.0）
      //意思是，如果本次是http2.0，那么为了保证 多路复用性，（因为上面的握手操作不是线程安全）会再次确认连接池中此时是否已有同样连接
      // Last attempt at connection coalescing, which only occurs if we attempted multiple
      // concurrent connections to the same host.
      if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, routes, true)) {
        // We lost the race! Close the connection we created and return the pooled connection.
        // 如果获取到，就把新创建的连接设置为不可用
        result.noNewExchanges = true;
        socket = result.socket();
        result = transmitter.connection;// 返回从连接池获取的连接
        // 那么这个刚刚连接成功的路由 就可以 用作下次 尝试的路由
        // It's possible for us to obtain a coalesced connection that is immediately unhealthy. In
        // that case we will retry the route we just successfully connected with.
        nextRouteToTry = selectedRoute;
      } else {
        // 9.使用新创建的连接并存入连接池中。最后一次尝试也没有获取可用的连接时，就把刚刚新建的连接存入连接池
        connectionPool.put(result);// 把新创建的连接，保存到连接池中
        transmitter.acquireConnectionNoEvents(result);
      }
    }
    closeQuietly(socket);// 关闭连接
    // 获得一个连接回调
    eventListener.connectionAcquired(call, result);
    return result;// 返回新创建的连接
  }

  RealConnection connectingConnection() {
    assert (Thread.holdsLock(connectionPool));
    return connectingConnection;
  }

  void trackFailure() {
    assert (!Thread.holdsLock(connectionPool));
    synchronized (connectionPool) {
      hasStreamFailure = true; // Permit retries.
    }
  }

  /** Returns true if there is a failure that retrying might fix. */
  boolean hasStreamFailure() {
    synchronized (connectionPool) {
      return hasStreamFailure;
    }
  }

  /** Returns true if a current route is still good or if there are routes we haven't tried yet. */
  boolean hasRouteToTry() {
    synchronized (connectionPool) {
      if (nextRouteToTry != null) {
        return true;
      }
      if (retryCurrentRoute()) {
        // Lock in the route because retryCurrentRoute() is racy and we don't want to call it twice.
        nextRouteToTry = transmitter.connection.route();
        return true;
      }
      return (routeSelection != null && routeSelection.hasNext())
          || routeSelector.hasNext();
    }
  }

  /**
   * Return true if the route used for the current connection should be retried, even if the
   * connection itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from
   * coalesced connections.
   */
  private boolean retryCurrentRoute() {
    return transmitter.connection != null
        && transmitter.connection.routeFailureCount == 0
        && Util.sameConnection(transmitter.connection.route().address().url(), address.url());
  }
}
