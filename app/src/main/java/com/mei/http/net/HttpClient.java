package com.mei.http.net;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc http请求客户端
 * @desired
 */
public class HttpClient {

    // 重试次数
    private int retry;

    // 请求任务调度器
    private Dispatcher dispatcher;

    // 网络连接池
    private ConnectionPool connectionPool;


    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public Call newCall(Request request) {
        return new Call(request, this);
    }


    private HttpClient(Builder builder) {
        this.retry = builder.retry;
        this.dispatcher = builder.dispatcher;
        this.connectionPool = builder.connectionPool;
    }

    public int retry() {
        return retry;
    }

    public ConnectionPool connectionPool() {
        return connectionPool;
    }

    public static class Builder {

        int retry;// 重试次数

        Dispatcher dispatcher;//

        ConnectionPool connectionPool;

        public Builder retry(int retry) {
            this.retry = retry;
            return this;
        }

        public Builder dispatcher(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder connectionPool(ConnectionPool connectionPool) {
            this.connectionPool = connectionPool;
            return this;
        }

        public HttpClient build() {
            if (dispatcher == null) {
                dispatcher = new Dispatcher();
            }

            if (connectionPool == null) {
                connectionPool = new ConnectionPool();
            }
            return new HttpClient(this);
        }
    }
}
