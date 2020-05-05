package com.mei.http.net.chain;

import com.mei.http.net.Call;
import com.mei.http.net.HttpConnection;
import com.mei.http.net.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 拦截器责任链封装，统一管理所有的拦截器
 * @desired
 */
public class InterceptorChain {

    // 拦截器集合
    private List<Interceptor> interceptors = new ArrayList<>();

    // 当前执行的拦截器下标
    private final int index;

    // 请求对象
    Call call;

    // http请求连接
    private HttpConnection httpConnection;

    public InterceptorChain(List<Interceptor> interceptors, int index, Call call,
            HttpConnection httpConnection) {
        this.interceptors = interceptors;
        this.index = index;
        this.call = call;
        this.httpConnection = httpConnection;
    }

    public HttpConnection httpConnection() {
        return httpConnection;
    }

    public Response process(HttpConnection connection) throws IOException {
        this.httpConnection = connection;
        return process();
    }

    public Response process() throws IOException {
        if (index >= interceptors.size()) {
            throw new IOException("Interceptor Chain Error index >" + interceptors.size());
        }

        // 获取当前拦截器,并执行
        Interceptor interceptor = interceptors.get(index);
        InterceptorChain chain = new InterceptorChain(interceptors, index + 1, call,
                httpConnection);
        return interceptor.intercept(chain);
    }
}
