package com.mei.http.net.chain;

import com.mei.http.net.ConnectionPool;
import com.mei.http.net.HttpClient;
import com.mei.http.net.HttpConnection;
import com.mei.http.net.HttpUrl;
import com.mei.http.net.Request;
import com.mei.http.net.Response;

import android.util.Log;

import java.io.IOException;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 连接拦截器，获得有效连接(Socket)的拦截器
 * @desired
 */
public class ConnectionInterceptor implements Interceptor {

    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("interceptor", "获取连接拦截器");

        Request request = chain.call.request();
        HttpClient httpClient = chain.call.httpClient();
        HttpUrl url = request.url();
        //从连接池中获得连接
        ConnectionPool connectionPool = httpClient.connectionPool();
        HttpConnection connection = connectionPool.get(url.getHost(), url.getPort());
        if (connection == null) {
            connection = new HttpConnection();
        } else {
            Log.e("interceptor", "从连接池中获得连接");
        }
        // 设置请求信息
        connection.setRequest(request);

        // 执行下一个拦截器
        try {
            Response response = chain.process(connection);
            // 判断返回数据，是否可保持连接
            if (response.isKeepAlive()) {
                // 如果服务器允许保持长连接，则把连接加入到连接池
                httpClient.connectionPool().put(connection);
            } else {
                // 否则，关闭连接
                connection.close();
            }
            return response;
        } catch (IOException e) {
            e.printStackTrace();
            connection.close();
            throw e;
        }
    }
}
