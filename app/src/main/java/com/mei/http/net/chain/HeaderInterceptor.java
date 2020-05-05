package com.mei.http.net.chain;

import com.mei.http.net.Request;
import com.mei.http.net.RequestBody;
import com.mei.http.net.Response;

import android.util.Log;

import java.io.IOException;
import java.util.Map;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 请求头拦截器
 * @desired
 */
public class HeaderInterceptor implements Interceptor {

    /**
     * 补充缺失的请求头
     */
    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("interceptor", "请求头拦截器");
        Request request = chain.call.request();
        Map<String, String> headers = request.headers();
        // 保持连接
        // 如果使用者没有配置 Connection请求头
        if (!headers.containsKey("Connection")) {
            headers.put("Connection", "Keep-Alive");
        }

        // 添加服务器地址
        headers.put("Host", request.url().getHost());

        RequestBody body = request.requestBody();
        if (body != null) {
            //  //请求体长度
            int contentLength = body.contentLength();
            headers.put("Content-Length", String.valueOf(contentLength));

            String contentType = body.contentType();
            headers.put("Content-Type", contentType);
        }

        // 执行下一个拦截器
        return chain.process();
    }
}
