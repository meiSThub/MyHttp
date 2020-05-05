package com.mei.http.net;

import android.text.TextUtils;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc http 链接封装
 * @desired
 */
public class HttpUrl {

    // 协议
    private String protocol;

    // 主机地址
    private String host;

    // 如链接：http://www.kuaidi100.com/query?type=yuantong&postid=222222222
    // file是：query?type=yuantong&postid=222222222
    private String file;

    // 端口号
    private int port;

    public HttpUrl(String url) throws MalformedURLException {
        URL url1 = new URL(url);
        this.host = url1.getHost();
        this.protocol = url1.getProtocol();
        this.file = url1.getFile();
        file = TextUtils.isEmpty(file) ? "/" : file;
        this.port = url1.getPort();
        port = port == -1 ? url1.getDefaultPort() : port;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return host;
    }

    public String getFile() {
        return file;
    }

    public int getPort() {
        return port;
    }
}
