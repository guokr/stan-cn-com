package com.guokr.protocol.xcf;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.List;

public abstract class XcfConnection extends URLConnection {

    private final URL         base;
    private final URL         url;
    private List<InputStream> bottoms;

    public XcfConnection(URL base, URL url) {
        super(url);
        this.base = base;
        this.url = url;
    }

    public abstract List<InputStream> list(URL base, String find, String pattern) throws IOException;

    @Override
    public void connect() throws IOException {
        bottoms = list(base, url.getPath(), url.getRef());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new SequenceInputStream(Collections.enumeration(bottoms));
    }

}
