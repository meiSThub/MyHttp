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
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.ConnectionSpec;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.ExchangeCodec;
import okhttp3.internal.http1.Http1ExchangeCodec;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2ExchangeCodec;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.http2.StreamResetException;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.tls.OkHostnameVerifier;
import okhttp3.internal.ws.RealWebSocket;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;

public final class RealConnection extends Http2Connection.Listener implements Connection {
  private static final String NPE_THROW_WITH_NULL = "throw with null exception";
  private static final int MAX_TUNNEL_ATTEMPTS = 21;

  public final RealConnectionPool connectionPool;
  private final Route route;

  // The fields below are initialized by connect() and never reassigned.

  /** The low-level TCP socket. */
  private Socket rawSocket;

  /**
   * The application layer socket. Either an {@link SSLSocket} layered over {@link #rawSocket}, or
   * {@link #rawSocket} itself if this connection does not use SSL.
   */
  private Socket socket;
  private Handshake handshake;
  private Protocol protocol;
  private Http2Connection http2Connection;
  private BufferedSource source;
  private BufferedSink sink;

  // The fields below track connection state and are guarded by connectionPool.

  /**
   * 标记当前连接不可用
   * If true, no new exchanges can be created on this connection. Once true this is always true.
   * Guarded by {@link #connectionPool}.
   */
  boolean noNewExchanges;

  /**
   * The number of times there was a problem establishing a stream that could be due to route
   * chosen. Guarded by {@link #connectionPool}.
   */
  int routeFailureCount;

  int successCount;
  private int refusedStreamCount;

  /**
   * 当前连接可以承载的数据流的最大数量。
   * The maximum number of concurrent streams that can be carried by this connection. If {@code
   * allocations.size() < allocationLimit} then new streams can be created on this connection.
   */
  private int allocationLimit = 1;

  /** Current calls carried by this connection. */
  final List<Reference<Transmitter>> transmitters = new ArrayList<>();

  /** Nanotime timestamp when {@code allocations.size()} reached zero. */
  long idleAtNanos = Long.MAX_VALUE;

  public RealConnection(RealConnectionPool connectionPool, Route route) {
    this.connectionPool = connectionPool;
    this.route = route;
  }

  /** Prevent further exchanges from being created on this connection. */
  public void noNewExchanges() {
    assert (!Thread.holdsLock(connectionPool));
    synchronized (connectionPool) {
      noNewExchanges = true;
    }
  }

  static RealConnection testConnection(
      RealConnectionPool connectionPool, Route route, Socket socket, long idleAtNanos) {
    RealConnection result = new RealConnection(connectionPool, route);
    result.socket = socket;
    result.idleAtNanos = idleAtNanos;
    return result;
  }

    /**
     * 执行 TCP和TLS 握手，即连接服务器
     * @param connectTimeout 连接超时时间
     * @param readTimeout   读超时时间
     * @param writeTimeout  写超时时间
     * @param pingIntervalMillis ping间隔时间
     * @param connectionRetryEnabled 是否允许连接重试
     * @param call
     * @param eventListener 事件回调
     */
  public void connect(int connectTimeout, int readTimeout, int writeTimeout,
      int pingIntervalMillis, boolean connectionRetryEnabled, Call call,
      EventListener eventListener) {
    if (protocol != null) throw new IllegalStateException("already connected");

    RouteException routeException = null;
    // 连接参数列表
    List<ConnectionSpec> connectionSpecs = route.address().connectionSpecs();
    // 连接参数选择器
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);

    // ssl socket 工厂
    if (route.address().sslSocketFactory() == null) {
      if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
        throw new RouteException(new UnknownServiceException(
            "CLEARTEXT communication not enabled for client"));
      }
      String host = route.address().url().host();
      if (!Platform.get().isCleartextTrafficPermitted(host)) {
          // 域名不合法
        throw new RouteException(new UnknownServiceException(
            "CLEARTEXT communication to " + host + " not permitted by network security policy"));
      }
    } else {
      if (route.address().protocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)) {
        throw new RouteException(new UnknownServiceException(
            "H2_PRIOR_KNOWLEDGE cannot be used with HTTPS"));
      }
    }

    while (true) {
      try {
        if (route.requiresTunnel()) {// 允许连接代理
          // 这里进入的条件是，通过http代理了https请求，有一个特殊的协议交换过程
          connectTunnel(connectTimeout, readTimeout, writeTimeout, call, eventListener);
          if (rawSocket == null) {
            // We were unable to connect the tunnel but properly closed down our resources.
            break;
          }
        } else {
          // 建立 Socket 连接，并把输入流保存到source对象中，输出流保存到sink对象中
          connectSocket(connectTimeout, readTimeout, call, eventListener);
        }
        // 如果前面判定是https请求，这里就是https的tls建立过程
        establishProtocol(connectionSpecSelector, pingIntervalMillis, call, eventListener);
        // 断开连接
        eventListener.connectEnd(call, route.socketAddress(), route.proxy(), protocol);
        break;
      } catch (IOException e) {
        closeQuietly(socket);
        closeQuietly(rawSocket);
        socket = null;
        rawSocket = null;
        source = null;
        sink = null;
        handshake = null;
        protocol = null;
        http2Connection = null;
        // 连接失败回调
        eventListener.connectFailed(call, route.socketAddress(), route.proxy(), null, e);

        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }

        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException;
        }
      }
    }

    if (route.requiresTunnel() && rawSocket == null) {
      ProtocolException exception = new ProtocolException("Too many tunnel connections attempted: "
          + MAX_TUNNEL_ATTEMPTS);
      throw new RouteException(exception);
    }

    if (http2Connection != null) {
      synchronized (connectionPool) {
        allocationLimit = http2Connection.maxConcurrentStreams();
      }
    }
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   */
  private void connectTunnel(int connectTimeout, int readTimeout, int writeTimeout, Call call,
      EventListener eventListener) throws IOException {
    Request tunnelRequest = createTunnelRequest();
    HttpUrl url = tunnelRequest.url();
    for (int i = 0; i < MAX_TUNNEL_ATTEMPTS; i++) {
      connectSocket(connectTimeout, readTimeout, call, eventListener);
      tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

      if (tunnelRequest == null) break; // Tunnel successfully created.

      // The proxy decided to close the connection after an auth challenge. We need to create a new
      // connection, but this time with the auth credentials.
      closeQuietly(rawSocket);
      rawSocket = null;
      sink = null;
      source = null;
      eventListener.connectEnd(call, route.socketAddress(), route.proxy(), null);
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  private void connectSocket(int connectTimeout, int readTimeout, Call call,
      EventListener eventListener) throws IOException {
    Proxy proxy = route.proxy();// 路由代理
    Address address = route.address();// 路由地址
    // 通过socket工厂，创建一个socket对象
    rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
        ? address.socketFactory().createSocket()
        : new Socket(proxy);
    // 开始建立连接回调
    eventListener.connectStart(call, route.socketAddress(), proxy);
    // 设置读超时时间
    rawSocket.setSoTimeout(readTimeout);
    try {
      //  执行Socket连接
      Platform.get().connectSocket(rawSocket, route.socketAddress(), connectTimeout);
    } catch (ConnectException e) {
      ConnectException ce = new ConnectException("Failed to connect to " + route.socketAddress());
      ce.initCause(e);
      throw ce;
    }

    // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
    // More details:
    // https://github.com/square/okhttp/issues/3245
    // https://android-review.googlesource.com/#/c/271775/
    try {
        // 输入流资源
      source = Okio.buffer(Okio.source(rawSocket));
      // 输出流资源
      sink = Okio.buffer(Okio.sink(rawSocket));
    } catch (NullPointerException npe) {
      if (NPE_THROW_WITH_NULL.equals(npe.getMessage())) {
        throw new IOException(npe);
      }
    }
  }

    /**
     * 连接建立
     * @throws IOException
     */
  private void establishProtocol(ConnectionSpecSelector connectionSpecSelector,
      int pingIntervalMillis, Call call, EventListener eventListener) throws IOException {
    if (route.address().sslSocketFactory() == null) {
        // 如果是Http2.0
      if (route.address().protocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)) {
        socket = rawSocket;// 建立连接的Socket对象
        protocol = Protocol.H2_PRIOR_KNOWLEDGE;// http2.0
        startHttp2(pingIntervalMillis);
        return;
      }

      socket = rawSocket;// 建立连接的Socket对象
      protocol = Protocol.HTTP_1_1;// http1.1
      return;
    }

    eventListener.secureConnectStart(call);
    // TLS握手流程
    connectTls(connectionSpecSelector);
    eventListener.secureConnectEnd(call, handshake);

    if (protocol == Protocol.HTTP_2) {// 如果是Http2.0
      startHttp2(pingIntervalMillis);
    }
  }

  private void startHttp2(int pingIntervalMillis) throws IOException {
    // 设置连接超时时间
    socket.setSoTimeout(0); // HTTP/2 connection timeouts are set per-stream.
    //  构建Http2.0连接
    http2Connection = new Http2Connection.Builder(true)
        .socket(socket, route.address().url().host(), source, sink)
        .listener(this)
        .pingIntervalMillis(pingIntervalMillis)
        .build();
    // 启动http2.0
    http2Connection.start();
  }

    /**
     * TLS 握手流程
     * @param connectionSpecSelector 连接参数选择器
     * @throws IOException
     */
  private void connectTls(ConnectionSpecSelector connectionSpecSelector) throws IOException {
    Address address = route.address();
    SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
    boolean success = false;
    SSLSocket sslSocket = null;
    try {
      //  1.创建一个SSL Socket连接，对Socket的包装
      // Create the wrapper over the connected socket.
      sslSocket = (SSLSocket) sslSocketFactory.createSocket(
          rawSocket, address.url().host(), address.url().port(), true /* autoClose */);

      // Configure the socket's ciphers, TLS versions, and extensions.
      ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
      if (connectionSpec.supportsTlsExtensions()) {// 是否支持TLS扩展
          // 补充TLS扩展信息
        Platform.get().configureTlsExtensions(
            sslSocket, address.url().host(), address.protocols());
      }
      // 2.开始TLS握手
      // Force handshake. This can throw!
      sslSocket.startHandshake();
      // block for session establishment
      SSLSession sslSocketSession = sslSocket.getSession();// 获取连接Session
      // 未验证的握手连接
      Handshake unverifiedHandshake = Handshake.get(sslSocketSession);

      // Verify that the socket's certificates are acceptable for the target host.
      // 3.域名校验：请求的域名与连接返回的证书中的域名比较，如果比较不通过，则报异常
      // 即证书接收的域名与用户请求的域名是否一致
      if (!address.hostnameVerifier().verify(address.url().host(), sslSocketSession)) {
        //  获取未校验通过证书集合
        List<Certificate> peerCertificates = unverifiedHandshake.peerCertificates();
        if (!peerCertificates.isEmpty()) {
          X509Certificate cert = (X509Certificate) peerCertificates.get(0);
          throw new SSLPeerUnverifiedException(
              "Hostname " + address.url().host() + " not verified:"
                  + "\n    certificate: " + CertificatePinner.pin(cert)
                  + "\n    DN: " + cert.getSubjectDN().getName()
                  + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
        } else {
          throw new SSLPeerUnverifiedException(
              "Hostname " + address.url().host() + " not verified (no certificates)");
        }
      }

      // 4.证书校验：校验证书锁定,如果校验不通过，则报异常
      // Check that the certificate pinner is satisfied by the certificates presented.
      address.certificatePinner().check(address.url().host(),
          unverifiedHandshake.peerCertificates());
      //
      // Success! Save the handshake and the ALPN protocol.
      String maybeProtocol = connectionSpec.supportsTlsExtensions()
          ? Platform.get().getSelectedProtocol(sslSocket)
          : null;
      socket = sslSocket;
      source = Okio.buffer(Okio.source(socket));// 输入流
      sink = Okio.buffer(Okio.sink(socket));// 输出流
      handshake = unverifiedHandshake;//
      protocol = maybeProtocol != null // Http版本
          ? Protocol.get(maybeProtocol)
          : Protocol.HTTP_1_1;
      success = true; // 连接成功
    } catch (AssertionError e) {
      if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
      throw e;
    } finally {
      if (sslSocket != null) {
        Platform.get().afterHandshake(sslSocket);
      }
      if (!success) {
        closeQuietly(sslSocket); // 释放连接
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  private Request createTunnel(int readTimeout, int writeTimeout, Request tunnelRequest,
      HttpUrl url) throws IOException {
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
    while (true) {
      Http1ExchangeCodec tunnelCodec = new Http1ExchangeCodec(null, null, source, sink);
      source.timeout().timeout(readTimeout, MILLISECONDS);
      sink.timeout().timeout(writeTimeout, MILLISECONDS);
      tunnelCodec.writeRequest(tunnelRequest.headers(), requestLine);
      tunnelCodec.finishRequest();
      Response response = tunnelCodec.readResponseHeaders(false)
          .request(tunnelRequest)
          .build();
      tunnelCodec.skipConnectBody(response);

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (!source.getBuffer().exhausted() || !sink.buffer().exhausted()) {
            throw new IOException("TLS tunnel buffered too many bytes!");
          }
          return null;

        case HTTP_PROXY_AUTH:
          tunnelRequest = route.address().proxyAuthenticator().authenticate(route, response);
          if (tunnelRequest == null) throw new IOException("Failed to authenticate with proxy");

          if ("close".equalsIgnoreCase(response.header("Connection"))) {
            return tunnelRequest;
          }
          break;

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   *
   * <p>In order to support preemptive authentication we pass a fake “Auth Failed” response to the
   * authenticator. This gives the authenticator the option to customize the CONNECT request. It can
   * decline to do so by returning null, in which case OkHttp will use it as-is
   */
  private Request createTunnelRequest() throws IOException {
    Request proxyConnectRequest = new Request.Builder()
        .url(route.address().url())
        .method("CONNECT", null)
        .header("Host", Util.hostHeader(route.address().url(), true))
        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
        .header("User-Agent", Version.userAgent())
        .build();

    Response fakeAuthChallengeResponse = new Response.Builder()
        .request(proxyConnectRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(HttpURLConnection.HTTP_PROXY_AUTH)
        .message("Preemptive Authenticate")
        .body(Util.EMPTY_RESPONSE)
        .sentRequestAtMillis(-1L)
        .receivedResponseAtMillis(-1L)
        .header("Proxy-Authenticate", "OkHttp-Preemptive")
        .build();

    Request authenticatedRequest = route.address().proxyAuthenticator()
        .authenticate(route, fakeAuthChallengeResponse);

    return authenticatedRequest != null
        ? authenticatedRequest
        : proxyConnectRequest;
  }

  /**
   * 连接是否可以承载指定地址的数据流，如果可以，返回true。非空的Router是一个连接的解析路由。
   * Returns true if this connection can carry a stream allocation to {@code address}. If non-null
   * {@code route} is the resolved route for a connection.
   */
  boolean isEligible(Address address, @Nullable List<Route> routes) {
    // If this connection is not accepting new exchanges, we're done.
    // 如果当前连接承载的数据流到达最大值或者当前连接不可用，则当前连接不可以承载新的数据流，返回false
    if (transmitters.size() >= allocationLimit || noNewExchanges) return false;

    // 匹配address中，非host部分，如果不匹配，则返回false
    // If the non-host fields of the address don't overlap, we're done.
    if (!Internal.instance.equalsNonHost(this.route.address(), address)) return false;

    // 如果host匹配，则当前连接可以承载该地址的数据流
    // If the host exactly matches, we're done: this connection can carry the address.
    if (address.url().host().equals(this.route().address().url().host())) {
      return true; // This connection is a perfect match.
    }

    // At this point we don't have a hostname match. But we still be able to carry the request if
    // our connection coalescing requirements are met. See also:
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

    //到这里hostname是没匹配的，但是还是有机会返回true：连接合并，具体来说需要满足如下条件：
    // 1. 必须是Http2.0
    // 2. IP地址必须匹配
    // 3. 请求域名必须与服务器证书中绑定的域名匹配
    // 4. 域名的证书校验必须通过


    // 1. This connection must be HTTP/2.
    if (http2Connection == null) return false;

    // 2. The routes must share an IP address.
    if (routes == null || !routeMatchesAny(routes)) return false;

    // 3. This connection's server certificate's must cover the new host.
    if (address.hostnameVerifier() != OkHostnameVerifier.INSTANCE) return false;
    if (!supportsUrl(address.url())) return false;

    // 4. Certificate pinning must match the host.
    try {
      address.certificatePinner().check(address.url().host(), handshake().peerCertificates());
    } catch (SSLPeerUnverifiedException e) {
      return false;
    }

    return true; // The caller's address can be carried by this connection.
  }

  /**
   * Returns true if this connection's route has the same address as any of {@code routes}. This
   * requires us to have a DNS address for both hosts, which only happens after route planning. We
   * can't coalesce connections that use a proxy, since proxies don't tell us the origin server's IP
   * address.
   */
  private boolean routeMatchesAny(List<Route> candidates) {
    for (int i = 0, size = candidates.size(); i < size; i++) {
      Route candidate = candidates.get(i);
      if (candidate.proxy().type() == Proxy.Type.DIRECT
          && route.proxy().type() == Proxy.Type.DIRECT
          && route.socketAddress().equals(candidate.socketAddress())) {
        return true;
      }
    }
    return false;
  }

  public boolean supportsUrl(HttpUrl url) {
    if (url.port() != route.address().url().port()) {
      return false; // Port mismatch.
    }

    if (!url.host().equals(route.address().url().host())) {
      // We have a host mismatch. But if the certificate matches, we're still good.
      return handshake != null && OkHostnameVerifier.INSTANCE.verify(
          url.host(), (X509Certificate) handshake.peerCertificates().get(0));
    }

    return true; // Success. The URL is supported.
  }

  /**
   * 数据交换解码器
   * @param client
   * @param chain
   * @return
   * @throws SocketException
   */
  ExchangeCodec newCodec(OkHttpClient client, Interceptor.Chain chain) throws SocketException {
    if (http2Connection != null) {// Http2.0 的解码器
      return new Http2ExchangeCodec(client, this, chain, http2Connection);
    } else {
      socket.setSoTimeout(chain.readTimeoutMillis());
      source.timeout().timeout(chain.readTimeoutMillis(), MILLISECONDS);
      sink.timeout().timeout(chain.writeTimeoutMillis(), MILLISECONDS);
      // http1.1的解码器，包含输入流和输出流
      return new Http1ExchangeCodec(client, this, source, sink);
    }
  }

  RealWebSocket.Streams newWebSocketStreams(Exchange exchange) throws SocketException {
    socket.setSoTimeout(0);
    noNewExchanges();
    return new RealWebSocket.Streams(true, source, sink) {
      @Override public void close() throws IOException {
        exchange.bodyComplete(-1L, true, true, null);
      }
    };
  }

  @Override public Route route() {
    return route;
  }

  public void cancel() {
    // Close the raw socket so we don't end up doing synchronous I/O.
    closeQuietly(rawSocket);
  }

  @Override public Socket socket() {
    return socket;
  }

  /** Returns true if this connection is ready to host new streams. */
  public boolean isHealthy(boolean doExtensiveChecks) {
    //  Socket 被关闭，输入流被中断，输出流被中断，则表示连接都是无效的
    if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
      return false;
    }

    if (http2Connection != null) {// 如果是Http2.0，则单独判断
      return http2Connection.isHealthy(System.nanoTime());
    }

    if (doExtensiveChecks) {
      try {
        int readTimeout = socket.getSoTimeout();
        try {
          socket.setSoTimeout(1);
          if (source.exhausted()) {// 连接卡死了
            return false; // Stream is exhausted; socket is closed.
          }
          return true;
        } finally {
          socket.setSoTimeout(readTimeout);
        }
      } catch (SocketTimeoutException ignored) {
        // Read timed out; socket is good.
      } catch (IOException e) {
        return false; // Couldn't read; socket is closed.
      }
    }

    return true;
  }

  /** Refuse incoming streams. */
  @Override public void onStream(Http2Stream stream) throws IOException {
    stream.close(ErrorCode.REFUSED_STREAM, null);
  }

  /** When settings are received, adjust the allocation limit. */
  @Override public void onSettings(Http2Connection connection) {
    synchronized (connectionPool) {
      allocationLimit = connection.maxConcurrentStreams();
    }
  }

  @Override public Handshake handshake() {
    return handshake;
  }

  /**
   * 如果是一个HTTP2.0的连接，返回true。这样的连接可以被多路复用
   * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
   * requests simultaneously.
   */
  public boolean isMultiplexed() {
    return http2Connection != null;
  }

  /**
   * Track a failure using this connection. This may prevent both the connection and its route from
   * being used for future exchanges.
   */
  void trackFailure(@Nullable IOException e) {
    assert (!Thread.holdsLock(connectionPool));
    synchronized (connectionPool) {
      if (e instanceof StreamResetException) {
        ErrorCode errorCode = ((StreamResetException) e).errorCode;
        if (errorCode == ErrorCode.REFUSED_STREAM) {
          // Retry REFUSED_STREAM errors once on the same connection.
          refusedStreamCount++;
          if (refusedStreamCount > 1) {
            noNewExchanges = true;
            routeFailureCount++;
          }
        } else if (errorCode != ErrorCode.CANCEL) {
          // Keep the connection for CANCEL errors. Everything else wants a fresh connection.
          noNewExchanges = true;
          routeFailureCount++;
        }
      } else if (!isMultiplexed() || e instanceof ConnectionShutdownException) {
        noNewExchanges = true;

        // If this route hasn't completed a call, avoid it for new connections.
        if (successCount == 0) {
          if (e != null) {
            connectionPool.connectFailed(route, e);
          }
          routeFailureCount++;
        }
      }
    }
  }

  @Override public Protocol protocol() {
    return protocol;
  }

  @Override public String toString() {
    return "Connection{"
        + route.address().url().host() + ":" + route.address().url().port()
        + ", proxy="
        + route.proxy()
        + " hostAddress="
        + route.socketAddress()
        + " cipherSuite="
        + (handshake != null ? handshake.cipherSuite() : "none")
        + " protocol="
        + protocol
        + '}';
  }
}
