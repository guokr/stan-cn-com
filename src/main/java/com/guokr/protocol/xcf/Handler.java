package com.guokr.protocol.xcf;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class Handler extends java.net.URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        String path = u.getPath().substring(1);
        String refr = u.getRef();

        StringBuilder test = new StringBuilder(path);
        if (refr != null && refr.length() > 0) {
            test.append(".part.");
            for (int i = 0; i < refr.length() - 1; i++) {
                test.append("0");
            }
            test.append("1");
        }

        URL local = Thread.currentThread().getContextClassLoader().getResource(test.toString());
        String base = local.toExternalForm();
        int end = base.length() - test.length();
        base = base.substring(0, end);

        if ("file".equals(local.getProtocol())) {
            return new XcfFileConnection(new URL(base), u);
        } else if ("jar".equals(local.getProtocol())) {
            return new XcfJarConnection(new URL(base), u);
        } else {
            throw new IOException("base protocol[" + local.getProtocol() + "] isn't supported");
        }
    }
}
