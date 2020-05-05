package com.example.sockettest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

public class NormalSocket {

    public static void main(String[] args) throws Exception {

        Thread thread = new Thread() {
            @Override
            public void run() {
                while (true) {
                    System.out.println("runing......");
                }
            }
        };
        thread.setDaemon(true);
        thread.start();

//        URL url1 = new URL("http://www.kuaidi100.com/query?type=yuantong&postid=11111111111");
//        String host = url1.getHost();
//        String file = url1.getFile();
//        file = null == file||file.length()==0 ? "/" : file;
//        String protocol = url1.getProtocol();
//        int port = url1.getPort();
//        port = port == -1 ? url1.getDefaultPort() : port;
//
//        System.out.println("Host:"+host);
//        System.out.println("file:" + file);
//        System.out.println(protocol);
//
//        System.out.println(port);

//        InetAddress[] allByName = InetAddress.getAllByName("www.baidu.com");
//        for (InetAddress inetAddress : allByName) {
//            System.out.println(inetAddress);
//        }
//        doHttp();
//        doHttps();
    }

    static void doHttps() throws Exception {
        Socket socket = SSLSocketFactory.getDefault().createSocket("www.baidu.com", 443);
//        Socket socket = new Socket("www.baidu.com", 443);

        //接受数据的输入流
        final BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        //发送数据 输出流
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        new Thread() {
            @Override
            public void run() {
                while (true) {
                    String line = null;
                    try {
                        while ((line = br.readLine()) != null) {
                            System.out.println("recv :" + line);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();

        bw.write("GET / HTTP/1.1\r\n");
        bw.write("Host: www.baidu.com\r\n\r\n");
        bw.flush();
    }

    static void doHttp() throws Exception {
        Socket socket = new Socket("restapi.amap.com", 80);

        //接受数据的输入流
        final BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        //发送数据 输出流
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        new Thread() {
            @Override
            public void run() {
                while (true) {
                    String line = null;
                    try {
                        while ((line = br.readLine()) != null) {
                            System.out.println("recv :" + line);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();

        bw.write("GET /v3/weather/weatherInfo?city=%E9%95%BF%E6%B2%99&key=13cb58f5884f9749287abbead9c658f2 HTTP/1.1\r\n");
        bw.write("Host: restapi.amap.com\r\n\r\n");
        bw.flush();
    }
}
