package com.dongnao.dnhttp.net2;

import android.text.TextUtils;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocketFactory;

/**
 * Created by Administrator on 2018/5/2.
 */
public class HttpConnection {

    Socket socket;

    //最后使用的时间
    long lastUseTime;
    private Request request;
    private InputStream is;
    private OutputStream os;

    /**
     * 当前连接的socket是否与对应的host ：port一致
     *
     * @param host
     * @param port
     * @return
     */
    public boolean isSameAddress(String host, int port) {
        if (null == socket) {
            return false;
        }
        return TextUtils.equals(socket.getInetAddress().getHostName(), host) && port == socket.getPort();
    }


    /**
     * 与服务器通信
     *
     * @return
     */
    public InputStream call(HttpCodec httpCodec) throws IOException {
        createSocket();
        //发送请求
        httpCodec.writeRequest(os, request);
        //返回服务器响应 (InputStream)
        return is;
    }

    /**
     * 创建socket连接
     */
    private void createSocket() throws IOException {
        if (null == socket || socket.isClosed()) {
            HttpUrl url = request.url();
            //
            if (url.protocol.equalsIgnoreCase("https")) {
                socket = SSLSocketFactory.getDefault().createSocket();
            } else {
                socket = new Socket();
            }
            socket.connect(new InetSocketAddress(url.host, url.port));
            is = socket.getInputStream();
            os = socket.getOutputStream();
        }
    }

    public void close() {
        if (null != socket) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public void updateLastUserTime() {
        lastUseTime = System.currentTimeMillis();
    }
}
