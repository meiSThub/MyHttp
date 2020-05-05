package com.dongnao.dnhttp.net.chain;

import android.util.Log;

import com.dongnao.dnhttp.net.HttpClient;
import com.dongnao.dnhttp.net.HttpConnection;
import com.dongnao.dnhttp.net.HttpUrl;
import com.dongnao.dnhttp.net.Request;
import com.dongnao.dnhttp.net.Response;

import java.io.IOException;

/**
 * @author Lance
 * @date 2018/4/17
 */

public class ConnectionInterceptor implements Interceptor {
    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("interceprot","连接拦截器....");
        Request request = chain.call.request();
        HttpClient client = chain.call.client();
        HttpUrl url = request.url();
        String host = url.getHost();
        int port = url.getPort();
        HttpConnection connection = client.connectionPool().get(host, port);
        if (null == connection) {
            connection = new HttpConnection();
        } else {
            Log.e("call", "使用连接池......");
        }
        connection.setRequest(request);
        try {
            Response response = chain.proceed(connection);
            if (response.isKeepAlive()) {
                client.connectionPool().put(connection);
            }
            return response;
        } catch (IOException e) {
            throw e;
        }
    }
}
