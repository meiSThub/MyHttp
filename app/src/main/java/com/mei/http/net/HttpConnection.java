package com.mei.http.net;

import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.ssl.SSLSocketFactory;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc http请求连接对象
 * @desired
 */
public class HttpConnection {

    // 与服务器连接的socket对象
    private Socket socket;

    // 最后一次使用的时间
    long lastUseTime;

    private Request request;

    // 服务器返回到数据流对象
    private InputStream in;

    // 客户端传递数据给服务器到输出流对象
    private OutputStream out;

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * 当前连接的socket是否与对应的host ：port一致
     */
    public boolean isSameAddress(String host, int port) {
        if (socket == null) {
            return false;
        }

        return TextUtils.equals(socket.getInetAddress().getHostName(), host)
                && socket.getPort() == port;
    }

    /**
     * 关闭连接
     */
    public void close() {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 与服务器建立连接，并通信
     */
    public InputStream call(HttpCodec httpCodec) throws IOException {
        // 创建Socket连接对象
        createSocket();
        // 传递数据给服务器
        httpCodec.writeRequest(out, request);
        return in;
    }

    /**
     * 创建Socket连接
     */
    private void createSocket() throws IOException {
        if (socket == null || socket.isClosed()) {
            // 获取url
            HttpUrl url = request.url();
            // 判断协议是否是https
            if (url.getProtocol().equalsIgnoreCase("https")) {
                // 如果是https，则使用SSLSocketFactory工厂创建特殊的为https使用的Socket
                socket = SSLSocketFactory.getDefault().createSocket();
            } else {
                // 创建普通的Socket对象
                socket = new Socket();
            }
            // 与服务器建立连接
            socket.connect(new InetSocketAddress(url.getHost(), url.getPort()));
            // 获取输入流
            in = socket.getInputStream();
            // 获取输出流
            out = socket.getOutputStream();
        }

    }

    /**
     * 更新连接最后一次使用的时间
     */
    public void updateLastUserTime() {
        lastUseTime = System.currentTimeMillis();
    }
}
