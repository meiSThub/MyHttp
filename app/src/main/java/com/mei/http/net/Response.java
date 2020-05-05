package com.mei.http.net;

import java.util.HashMap;
import java.util.Map;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 请求返回数据
 * @desired
 */
public class Response {

    int code;// 请求返回的状态码

    int contentLength;// 返回数据长度

    // 返回的请求头
    Map<String, String> headers = new HashMap<>();

    // 返回的请求体，即返回的数据
    String body;

    // 是否保持连接
    boolean isKeepAlive;

    public Response(int code, int contentLength, Map<String, String> headers, String body,
            boolean isKeepAlive) {
        this.code = code;
        this.contentLength = contentLength;
        this.headers = headers;
        this.body = body;
        this.isKeepAlive = isKeepAlive;
    }

    public int getCode() {
        return code;
    }

    public int getContentLength() {
        return contentLength;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public boolean isKeepAlive() {
        return isKeepAlive;
    }
}
