package com.guokr.protocol.xcf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class XcfJarConnection extends XcfConnection {

    public XcfJarConnection(URL base, URL url) {
        super(base, url);
    }

    @Override
    public List<InputStream> list(URL base, String find, String pattern) throws IOException {
        String basepath = base.getPath();
        basepath = basepath.substring(5, basepath.lastIndexOf('!'));

        StringBuilder regex = new StringBuilder("^");
        regex.append(find.substring(1));
        if (pattern != null) {
            regex.append("\\.part.");
            for (int i = 0; i < pattern.length(); i++) {
                regex.append("\\d");
            }
        }
        regex.append("$");
        final Pattern pat = Pattern.compile(regex.toString());

        final JarFile jar = new JarFile(basepath);
        final Enumeration<JarEntry> entries = jar.entries();
        List<URL> urls = new ArrayList<URL>();
        while (entries.hasMoreElements()) {
            final String name = entries.nextElement().getName();
            if (pat.matcher(name).find()) {
                String url = String.format("jar:file://%s!/%s", basepath, name);
                urls.add(new URL(url));
            }
        }
        jar.close();

        List<InputStream> inputs = new ArrayList<InputStream>();
        if (urls.size() == 1) {
            inputs.add(urls.get(0).openStream());
        } else {
            for (URL url : urls) {
                inputs.add(new GZIPInputStream(url.openStream()));
            }
        }

        return inputs;
    }

}
