package com.mei.http.net;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc
 * @desired
 */
public class RequestBody {

    /**
     * 表单提交 使用urlencoded编码
     */
    private final static String CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final static String CHARSET = "utf-8";

    // 请求体数据
    private Map<String, String> encodedBody = new HashMap<>();

    public String contentType() {
        return CONTENT_TYPE;
    }

    public int contentLength() {
        return body().getBytes().length;
    }

    /**
     * 拼接请求体
     */
    public String body() {
        StringBuffer sb = new StringBuffer();
        for (String key : encodedBody.keySet()) {
            String value = encodedBody.get(key);
            sb.append(key)
                    .append("=")
                    .append(value)
                    .append("&");
        }

        if (sb.length() != 0) {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * 添加请求参数
     */
    public RequestBody add(String key, String value) {
        try {
            encodedBody.put(URLEncoder.encode(key, CHARSET), URLEncoder.encode(value, CHARSET));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return this;
    }

}
