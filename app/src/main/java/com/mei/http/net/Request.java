package com.mei.http.net;

import android.text.TextUtils;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc http请求对象
 * @desired
 */
public class Request {

    // 请求头
    private Map<String, String> headers;


    // 请求链接
    private HttpUrl url;

    private String method;// 请求方式

    private RequestBody requestBody;


    public Request(Builder builder) {
        this.url = builder.url;
        this.requestBody = builder.mRequestBody;
        this.method = builder.method;
        this.headers = builder.headers;
    }


    public HttpUrl url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public RequestBody requestBody() {
        return requestBody;
    }

    public String method() {
        return method;
    }

    public static class Builder {

        HttpUrl url;

        String method;//请求方法

        // 请求头
        Map<String, String> headers = new HashMap<>();

        RequestBody mRequestBody;

        public Builder url(String url) {
            try {
                this.url = new HttpUrl(url);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
            return this;
        }

        public Builder get() {
            method = "GET";
            return this;
        }


        public Builder post(RequestBody body) {
            this.mRequestBody = body;
            method = "POST";
            return this;
        }

        public Builder addHeader(String name, String value) {
            headers.put(name, value);
            return this;
        }


        public Builder removeHeader(String name) {
            headers.remove(name);
            return this;
        }


        public Request build() {
            if (url == null) {
                throw new IllegalStateException("url == null");
            }
            if (TextUtils.isEmpty(method)) {
                method = "GET";
            }
            return new Request(this);
        }
    }
}
