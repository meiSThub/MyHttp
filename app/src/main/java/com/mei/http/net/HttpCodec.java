package com.mei.http.net;

import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mxb
 * @date 2020/5/4
 * @desc 网络请求数据到写入写出封装类
 * @desired
 */
public class HttpCodec {


    //回车和换行
    static final String CRLF = "\r\n";

    static final int CR = 13;// \r

    static final int LF = 10;// \n

    static final String SPACE = " ";// 空格

    static final String VERSION = "HTTP/1.1";// 协议版本

    static final String COLON = ":";// 冒号

    // 请求头信息
    public static final String HEAD_HOST = "Host";// 服务器地址

    public static final String HEAD_CONNECTION = "Connection";// 是否保持长连接

    public static final String HEAD_CONTENT_TYPE = "Content-Type";// 提交到内容类型

    public static final String HEAD_CONTENT_LENGTH = "Content-Length";// 提交的数据长度

    public static final String HEAD_TRANSFER_ENCODING = "Transfer-Encoding";//

    public static final String HEAD_VALUE_KEEP_ALIVE = "Keep-Alive";

    public static final String HEAD_VALUE_CHUNKED = "chunked";

    private final ByteBuffer byteBuffer;// 字节缓存

    public HttpCodec() {
        byteBuffer = ByteBuffer.allocate(10 * 1024);
    }

    /**
     * 把客户端需要传递给服务器的数据，写入到输出流中
     *
     * @param out     输出流
     * @param request 网络请求对象
     */
    public void writeRequest(OutputStream out, Request request) throws IOException {

        StringBuffer sb = new StringBuffer();
        // 1.写入请求行
        //GET /v3/weather/weatherInfo?city=%E9%95%BF%E6%B2%99&key=13cb58f5884f9749287abbead9c658f2 HTTP/1.1\r\n
        sb.append(request.method());
        sb.append(SPACE);
        sb.append(request.url().getFile());
        sb.append(SPACE);
        sb.append(VERSION);
        sb.append(CRLF);

        // 2.写入请求头
        Map<String, String> headers = request.headers();
        for (String key : headers.keySet()) {
            String value = headers.get(key);
            //结构： "key: value"，冒号后面有空格
            sb.append(key).append(COLON).append(SPACE).append(value);
            sb.append(CRLF);
        }
        // 请求头和请求体通过空行隔开
        sb.append(CRLF);

        // 3.写入请求体
        RequestBody body = request.requestBody();
        if (body != null) {
            sb.append(body.body());
        }

        // 4.把所有的数据，通过输出流传递给服务器
        out.write(sb.toString().getBytes());
        out.flush();
    }

    /**
     * 读取一行数据
     *
     * @param is 服务器返回的输入流
     */
    public String readLine(InputStream is) throws IOException {
        // 清理bytebuffer
        byteBuffer.clear();
        // 标记
        byteBuffer.mark();
        // 是否可能换行
        boolean maybeEofLine = false;
        byte b;
        // 一次读一个字节
        while ((b = (byte) is.read()) != -1) {
            byteBuffer.put(b);
            // 如果当前读到一个"\r"
            if (b == CR) {
                maybeEofLine = true;
            } else if (maybeEofLine) {
                // 读到 "\n"
                if (b == LF) {
                    //一行数据
                    byte[] lineBytes = new byte[byteBuffer.position()];
                    //将
                    byteBuffer.reset();
                    //从bytebuffer获得数据
                    byteBuffer.get(lineBytes);
                    byteBuffer.clear();
                    byteBuffer.mark();
                    return new String(lineBytes);
                }

                // 否则还原
                maybeEofLine = false;
            }
        }
        throw new IOException("Response read Line");
    }

    /**
     * 读取服务器返回的请求头
     */
    public Map<String, String> readHeaders(InputStream is) throws IOException {
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(is);
            // 如果读到空行 "\r\n" 响应头读完了，响应头和body通过空行隔开
            if (TextUtils.equals(line, CRLF)) {
                break;
            }

            // 找到分隔符到位置
            int index = line.indexOf(":");
            //结构： "key: value\r\n"
            String key = line.substring(0, index);
            String value = line.substring(index + 2, line.length() - 2);
            headers.put(key, value);
        }
        return headers;
    }

    /**
     * 根据指定的长度，读取字节
     *
     * @param is     输入流
     * @param length 读取的内容长度
     */
    public byte[] readBytes(InputStream is, int length) throws IOException {
        byte[] bytes = new byte[length];
        int readNum = 0;
        while (true) {
            readNum += is.read(bytes, readNum, length - readNum);
            //读取完毕
            if (readNum == length) {
                return bytes;
            }
        }
    }

    /**
     * 读取后台返回的分块数据
     */
    public String readChunked(InputStream is) throws IOException {
        int len = -1;
        boolean isEmptyData = false;
        StringBuffer chunked = new StringBuffer();
        while (true) {
            /**
             * 数据分块的格式：
             * 指定这一块数据的长度： 10\r\n
             * 这一块数据的具体内容： 10字节长度的内容\r\n
             */
            if (len < 0) {
                String line = readLine(is);
                // 不取换行符
                line = line.substring(0, line.length() - 2);
                // 获取数据块的长度，16进制字符串转成10进制整型
                len = Integer.valueOf(line, 16);
                //如果长度是0 再读一个/r/n 响应结束
                isEmptyData = len == 0;
            } else {
                // 读取内容
                byte[] bytes = readBytes(is, len);
                chunked.append(new String(bytes));
                len = -1;
                // 没有下一个数据块了，即读取完毕
                if (isEmptyData) {
                    return chunked.toString();
                }
            }
        }
    }
}
