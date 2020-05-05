package com.dongnao.dnhttp.net;


import com.dongnao.dnhttp.net.chain.Interceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Lance
 * @date 2018/4/14
 */

public class HttpClient {
    private Dispatcher dispatcher;
    private ConnectionPool connectionPool;
    private int retrys;
    private List<Interceptor> interceptors;

    public HttpClient(Builder builder) {
        dispatcher = builder.dispatcher;
        connectionPool = builder.connectionPool;
        retrys = builder.retrys;
        interceptors = builder.interceptors;
    }

    public Call newCall(Request request) {
        return new Call(this, request);
    }


    public int retrys() {
        return retrys;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public ConnectionPool connectionPool() {
        return connectionPool;
    }

    public List<Interceptor> interceptors() {
        return interceptors;
    }

    public static final class Builder {
        /**
         * 队列 任务分发
         */
        Dispatcher dispatcher;
        ConnectionPool connectionPool;
        //默认重试3次
        int retrys = 3;
        List<Interceptor> interceptors = new ArrayList<>();

        public Builder dispatcher(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder connectionPool(ConnectionPool connectionPool) {
            this.connectionPool = connectionPool;
            return this;
        }

        public Builder retrys(int retrys) {
            this.retrys = retrys;
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            interceptors.add(interceptor);
            return this;
        }


        public HttpClient build() {
            if (null == dispatcher) {
                dispatcher = new Dispatcher();
            }
            if (null == connectionPool) {
                connectionPool = new ConnectionPool();
            }
            return new HttpClient(this);
        }

    }
}
