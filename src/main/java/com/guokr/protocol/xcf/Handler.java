package com.guokr.protocol.xcf;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import org.xeustechnologies.jcl.AbstractClassLoader;
import org.xeustechnologies.jcl.JarClassLoader;

import com.guokr.util.DowngradeClassLoader;

public class Handler extends java.net.URLStreamHandler {

    final ClassLoader        loader;
    private ProtectionDomain domain   = Handler.class.getProtectionDomain();
    private CodeSource       source   = domain.getCodeSource();
    private URL              location = source.getLocation();
    private String           path     = (location != null ? location.getPath() : null);

    public Handler() {
        AbstractClassLoader loader = (path != null ? new JarClassLoader(new String[] { path })
                : new DowngradeClassLoader(getClass().getClassLoader()));
        this.loader = loader;
    }

    public Handler(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        if ("file".equals(location.getProtocol())) {
            return new XcfFileConnection(u, location);
        } else if ("jar".equals(location.getProtocol())) {
            return new XcfJarConnection(u, location);
        } else {
            throw new IOException("base protocol[" + location.getProtocol() + "] isn't supported");
        }
    }
}
