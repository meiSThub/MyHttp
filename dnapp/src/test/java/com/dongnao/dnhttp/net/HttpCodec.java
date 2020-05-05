package com.dongnao.dnhttp.net;

import com.dongnao.dnhttp.net.Request;
import com.dongnao.dnhttp.net.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * @author Lance
 * @date 2018/4/17
 */

public class HttpCodec {

    //回车和换行
    static final String CRLF = "\r\n";
    static final int CR = 13;
    static final int LF = 10;
    static final String SPACE = " ";
    static final String VERSION = "HTTP/1.1";
    static final String COLON = ":";


    static final String HEAD_HOST = "Host";
    static final String HEAD_CONNECTION = "Connection";
    static final String HEAD_CONTENT_TYPE = "Content-Type";
    static final String HEAD_CONTENT_LENGTH = "Content-Length";
    static final String HEAD_TRANSFER_ENCODING = "Transfer-Encoding";

    static final String HEAD_VALUE_KEEP_ALIVE = "Keep-Alive";


    public  String getRequest(Request request) {
        StringBuffer protocol = new StringBuffer();
        addVersion(protocol, request);
        addHeaders(protocol, request);
        addBody(protocol, request);
        return protocol.toString();
    }

    /**
     * 请求题
     *
     * @param protocol
     */
    private  void addBody(StringBuffer protocol, Request request) {
        if (null == request.body()) {
            return;
        }
        protocol.append(request.body().body());
    }

    /**
     * 请求方法和协议版本
     *
     * @param protocol
     */
    private  void addVersion(StringBuffer protocol, Request request) {
        protocol.append(request.method());
        protocol.append(SPACE);
        protocol.append(request.url().path);
        protocol.append(SPACE);
        protocol.append(VERSION);
        protocol.append(CRLF);
    }

    /**
     * 请求头
     *
     * @param protocol
     */
    private  void addHeaders(StringBuffer protocol, Request request) {
        Map<String, String> headers = request.headers();
        //写入http头
        headers.put(HEAD_HOST, request.url().host);
        headers.put(HEAD_CONNECTION, HEAD_VALUE_KEEP_ALIVE);
        if (null != request.body()) {
            String contentType = request.body().contentType();
            if (contentType != null) {
                headers.put(HEAD_CONTENT_TYPE, contentType);
            }
            long contentLength = request.body().contentLength();
            if (contentLength != -1) {
                headers.put(HEAD_CONTENT_LENGTH, Long.toString(contentLength));
            }
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            protocol.append(entry.getKey());
            protocol.append(COLON);
            protocol.append(SPACE);
            protocol.append(entry.getValue());
            protocol.append(CRLF);
        }
        protocol.append(CRLF);

    }

    public  Response readResponse(InputStream is) throws IOException {
        Response response = new Response();
        readHeaders(response, is);
        readBody(response, is);
        return response;
    }

    /**
     * @return
     * @throws IOException
     */
     void parseLine(Response response, String line) throws IOException {
        //读取header
        if (line.startsWith("HTTP/")) {
            String[] statusLine = line.split(" ");
            response.code = Integer.valueOf(statusLine[1]);
        } else {
            int index = line.indexOf(":");
            String name = line.substring(0, index);
            // ": "->len - 2("\r\n")
            String value = line.substring(index + 2, line.length() - 2);
            response.headers.put(name, value);
            if (HEAD_CONTENT_LENGTH.equalsIgnoreCase(name)) {
                response.contentLength = Integer.valueOf(value);
            }
            if (HEAD_TRANSFER_ENCODING.equalsIgnoreCase(name) && value.equalsIgnoreCase
                    ("chunked")) {
                response.isChunked = true;
            }
            if (HEAD_CONNECTION.equalsIgnoreCase(name) && value.equalsIgnoreCase
                    (HEAD_VALUE_KEEP_ALIVE)) {
                response.isKeepAlive = true;
            }
        }
    }

     String readLine(InputStream is) throws IOException {
        try {
            byte b;
            boolean isMabeyEofLine = false;
            //申请足够大的内存记录读取的数据 (一行)
            ByteBuffer byteBuffer = ByteBuffer.allocate(10 * 1024);
            //标记
            byteBuffer.mark();
            while ((b = (byte) is.read()) != -1) {
                byteBuffer.put(b);
                // 读取到/r则记录，判断下一个字节是否为/n
                if (b == CR) {
                    isMabeyEofLine = true;
                } else if (isMabeyEofLine) {
                    //上一个字节是/r 并且本次读取到/n
                    if (b == LF) {
                        //获得目前读取的所有字节
                        byte[] lineBytes = new byte[byteBuffer.position()];
                        //返回标记位置
                        byteBuffer.reset();
                        byteBuffer.get(lineBytes);
                        //清空所有index并重新标记
                        byteBuffer.clear();
                        byteBuffer.mark();
                        String line = new String(lineBytes);
                        return line;
                    }
                    isMabeyEofLine = false;
                }
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        throw new IOException("Response Read Line.");
    }

     boolean isEmptyLine(String line) {
        return line.equals("\r\n");
    }

     void readHeaders(Response response, InputStream is) throws IOException {
        while (true) {
            String line = readLine(is);
            //读取到空行 则下面的为body
            if (isEmptyLine(line)) {
                return;
            }
            response.message.append(line);
            //解析header行数据
            parseLine(response, line);
        }
    }

     byte[] readBytes(InputStream is, int len) throws IOException {
        byte[] bytes = new byte[len];
        int readNum = 0;
        while (true) {
            readNum += is.read(bytes, readNum, len - readNum);
            //读取完毕
            if (readNum == len) {
                return bytes;
            }
        }
    }

    private  void readBody(Response response, InputStream is) throws IOException {
        if (response.contentLength > 0) {
            byte[] bytes = readBytes(is, response.contentLength);
            response.body.append(new String(bytes));
        } else if (response.isChunked) {
            int len = -1;
            boolean isNoData = false;
            while (true) {
                //解析下一个chunk长度
                if (len < 0) {
                    String line = readLine(is);
                    if (isEmptyLine(line)) {
                        return;
                    }
                    line = line.substring(0, line.length() - 2);
                    len = Integer.valueOf(line, 16);
                    //chunk编码的数据最后一段为 0\r\n\r\n
                    isNoData = len == 0;
                } else {
                    //块长度不包括 \r\n +2
                    byte[] bytes = readBytes(is, len + 2);
                    response.body.append(new String(bytes));
                    len = -1;
                    if (isNoData){
                        break;
                    }
                }
            }
        }
    }


}
