package com.guokr.protocol.xcf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class XcfJarConnection extends XcfConnection {

    public XcfJarConnection(URL base, URL url) {
        super(base, url);
    }

    @Override
    public List<InputStream> list(URL base, String path, String pattern) throws IOException {
        List<InputStream> inputs = new ArrayList<InputStream>();
        return inputs;
    }

}
