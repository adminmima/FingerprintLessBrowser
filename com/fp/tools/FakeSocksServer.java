package com.fp.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class FakeSocksServer {
    private static boolean started = false;

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ServerSocket server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9050));
                    while (true) {
                        Socket c = server.accept();
                        Thread th = new Thread(new Handler(c));
                        th.setDaemon(true);
                        th.start();
                    }
                } catch (Throwable e) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static class Handler implements Runnable {
        private final Socket client;
        Handler(Socket c) { this.client = c; }
        public void run() {
            try {
                client.setSoTimeout(60000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                int ver = in.read();
                if (ver != 5) { close(client); return; }
                int nm = in.read();
                for (int i = 0; i < nm; i++) in.read();
                out.write(new byte[]{5, 0});
                out.flush();

                in.read(); in.read(); in.read();
                int atyp = in.read();
                String host;
                if (atyp == 1) {
                    byte[] b = new byte[4];
                    readFully(in, b);
                    host = (b[0]&255)+"."+(b[1]&255)+"."+(b[2]&255)+"."+(b[3]&255);
                } else if (atyp == 3) {
                    int len = in.read();
                    byte[] b = new byte[len];
                    readFully(in, b);
                    host = new String(b, "UTF-8");
                } else if (atyp == 4) {
                    byte[] b = new byte[16];
                    readFully(in, b);
                    host = InetAddress.getByAddress(b).getHostAddress();
                } else { close(client); return; }
                int port = (in.read() << 8) | in.read();

                final Socket target = new Socket();
                target.connect(new InetSocketAddress(host, port), 20000);

                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();

                Thread t1 = new Thread(new Runnable() { public void run() { copy(client, target); } });
                Thread t2 = new Thread(new Runnable() { public void run() { copy(target, client); } });
                t1.setDaemon(true); t2.setDaemon(true);
                t1.start(); t2.start();
            } catch (Throwable e) {
                close(client);
            }
        }

        static void copy(Socket a, Socket b) {
            try {
                InputStream in = a.getInputStream();
                OutputStream out = b.getOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Throwable e) {}
            close(a); close(b);
        }

        static void readFully(InputStream in, byte[] b) throws Exception {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new Exception("EOF");
                off += r;
            }
        }

        static void close(Socket s) {
            try { if (s != null) s.close(); } catch (Throwable e) {}
        }
    }
}
