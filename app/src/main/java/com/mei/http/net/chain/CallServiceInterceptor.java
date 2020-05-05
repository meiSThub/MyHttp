package com.mei.http.net.chain;

import com.mei.http.net.HttpCodec;
import com.mei.http.net.HttpConnection;
import com.mei.http.net.Response;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 正在执行网络请求到拦截器
 * @desired
 */
public class CallServiceInterceptor implements Interceptor {

    @Override
    public Response intercept(InterceptorChain chain) throws IOException {
        Log.e("interceptor", "通信拦截器");
        HttpConnection connection = chain.httpConnection();
        HttpCodec httpCodec = new HttpCodec();
        // 1.请求服务器连接，并把请求的数据传递给服务器
        InputStream is = connection.call(httpCodec);

        // 2.解析服务器返回的数据
        // 响应行: HTTP/1.1 200 OK\r\n

        // 2-1.读取响应行
        String statusLine = httpCodec.readLine(is);

        // 2-2.读取服务器返回的请求头，响应行下面就是请求头了
        Map<String, String> headers = httpCodec.readHeaders(is);

        //根据Content-Length 解析
        // 2-3.读取响应体，即服务器返回到数据
        int contentLength = -1;
        if (headers.containsKey("Content-Length")) {
            contentLength = Integer.valueOf(headers.get("Content-Length"));
        }

        // 如果服务器数据是根据分块编码 解析，即数据是分块返回的
        boolean isChunked = false;
        // 判断是否需要分块读取服务器返回的数据
        if (headers.containsKey("Transfer-Encoding")) {
            // 如果响应头中："Transfer-Encoding：chunked\r\n",说明服务器返回的数据是分块返回的
            isChunked = headers.get("Transfer-Encoding").equalsIgnoreCase("chunked");
        }

        // 服务器返回的数据
        String body = null;
        if (contentLength > 0) {// 如果后台返回了body长度，则直接按照指定长度读取
            byte[] bytes = httpCodec.readBytes(is, contentLength);
            body = new String(bytes);
        } else if (isChunked) {// 如果后台返回的数据分块了
            body = httpCodec.readChunked(is);
        }

        // 根据空格，分割响应行数据
        String[] status = statusLine.split(" ");
        // 是否保持长连接
        boolean keepAlive = false;
        if (headers.containsKey("Connection")) {
            keepAlive = headers.get("Connection").equalsIgnoreCase("keep-alive");
        }
        // 更新连接最后一次使用的时间
        connection.updateLastUserTime();

        return new Response(Integer.valueOf(status[1]), contentLength, headers, body, keepAlive);
    }
}
